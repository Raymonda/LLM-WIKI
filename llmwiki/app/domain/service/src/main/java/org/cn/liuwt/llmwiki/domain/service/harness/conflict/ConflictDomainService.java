package org.cn.liuwt.llmwiki.domain.service.harness.conflict;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.common.util.constant.PageLifecycle;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictResolutionStrategy.AutoLevel;

@Component
public class ConflictDomainService {

    private static final Logger log = LoggerFactory.getLogger(ConflictDomainService.class);

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private LintFindingService lintFindingService;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private LlmConcurrencyBarrier llmConcurrencyBarrier;

    @Autowired
    private SearchService searchService;

    @Autowired
    private ConflictRoutingService conflictRoutingService;

    @Autowired
    private ConflictReviewService conflictReviewService;

    @Autowired
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record RulingOutcome(int generated, int deferred, int skipped, String summary) {}
    public record ExecutionOutcome(int executed, int reviewHeld, int skipped, String summary) {}

    public RulingOutcome generatePendingRulings(Long scopeId, Long executionId, LintRulesConfig rulesConfig) {
        if (chatClient == null || !chatClient.isAvailable()) {
            return new RulingOutcome(0, 0, 0, "AI 未配置，跳过裁决简报生成");
        }
        int autoFixLimit = rulesConfig.getDiagnosticStandard().getAutoFixLimit();
        List<LintFindingDO> allConflictFindings = lintFindingService.listFindings(scopeId, "conflict", "open", null);

        int generated = 0, deferred = 0, skipped = 0;
        StringBuilder briefReport = new StringBuilder();

        for (LintFindingDO f : allConflictFindings) {
            if (generated >= autoFixLimit) {
                skipped++;
                continue;
            }

            LintFindingDO current = lintFindingService.getFinding(f.getId());
            if (current == null || !"open".equals(current.getStatus())) {
                log.debug("Skipping conflict finding id={}: status={}, idempotency guard in generatePendingRulings",
                    f.getId(), current != null ? current.getStatus() : "deleted");
                skipped++;
                continue;
            }

            WikiPageDO pageDO = resolvePageByPath(scopeId, f.getPagePath());
            String category = pageDO != null ? pageDO.getCategory() : null;
            String aiHint = f.getHandlingMethod();
            ConflictRoutingService.ConflictRoute route = conflictRoutingService.route(category, aiHint, rulesConfig);

            if (route.autoLevel() == AutoLevel.DEFER) {
                lintFindingService.annotateDeferred(f.getId());
                deferred++;
                briefReport.append("- 暂缓处理（DEFER）：findingId=").append(f.getId())
                    .append("，reason=").append(route.reason()).append("\n");
                continue;
            }

            if (f.getRulingBriefJson() != null && !f.getRulingBriefJson().isBlank()) {
                continue;
            }

            String pageContent = readPageContent(scopeId, f.getPagePath());
            String relatedContent = readRelatedContent(scopeId, f.getExtra());
            String sourceEvidence = traceSourceEvidence(scopeId, f);

            String briefPrompt = schemaInjector.prependForLint(scopeId,
                PromptRegistry.forLint().generateRulingBrief(
                    f.getTitle(), f.getDetail(), pageContent, relatedContent));
            if (sourceEvidence != null && !sourceEvidence.isBlank()) {
                briefPrompt += "\n\n【来源追溯证据】\n" + sourceEvidence;
            }
            briefPrompt += "\n\n【冲突路由策略】\n" + route.reason()
                + "\n请根据此策略方向生成裁决方案。";

            boolean acquired = llmConcurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 30_000);
            if (!acquired) {
                log.warn("LlmConcurrencyBarrier LINT bucket timeout, skipping ruling brief for findingId={}", f.getId());
                continue;
            }
            try {
                String briefRaw = chatClient.chat(briefPrompt);
                String briefJson = extractJsonObject(briefRaw);
                if (briefJson != null && !briefJson.isBlank()) {
                    lintFindingService.setRulingBrief(f.getId(), briefJson);
                    lintFindingService.updateStatus(f.getId(), "awaiting_approval");
                    if (route.autoLevel() == AutoLevel.REVIEW) {
                        createConflictReviewFromLintFinding(scopeId, executionId, f, pageDO, route);
                    }
                    generated++;
                    briefReport.append("- 已生成裁决简报[").append(route.autoLevel()).append("]：findingId=")
                        .append(f.getId()).append("\n");
                } else {
                    log.warn("Failed to extract JSON from ruling brief for findingId={}", f.getId());
                    briefReport.append("- 裁决简报 JSON 提取失败：findingId=").append(f.getId()).append("\n");
                }
            } catch (Exception e) {
                log.warn("GENERATE_RULING_BRIEFS failed for findingId={}: {}", f.getId(), e.getMessage());
            } finally {
                llmConcurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
            }
        }
        String summary = String.format("裁决简报生成：成功 %d 个，暂缓 %d 个，超限跳过 %d 个\n%s",
            generated, deferred, skipped, briefReport);
        return new RulingOutcome(generated, deferred, skipped, summary);
    }

    public ExecutionOutcome executePendingRulings(Long scopeId, Long executionId, LintRulesConfig rulesConfig) {
        if (chatClient == null || !chatClient.isAvailable()) {
            return new ExecutionOutcome(0, 0, 0, "AI 未配置，跳过裁决方案执行");
        }
        int autoFixLimit = rulesConfig.getDiagnosticStandard().getAutoFixLimit();
        List<LintFindingDO> resolvedFindings = lintFindingService.listFindings(scopeId, "conflict", null, null)
            .stream().filter(f -> List.of("open", "awaiting_approval").contains(f.getStatus()))
            .filter(f -> f.getRulingBriefJson() != null && !f.getRulingBriefJson().isBlank())
            .toList();

        int executed = 0, reviewHeld = 0, skipped = 0;
        StringBuilder rulingReport = new StringBuilder();

        for (LintFindingDO f : resolvedFindings) {
            if (executed >= autoFixLimit) {
                skipped++;
                continue;
            }

            LintFindingDO current = lintFindingService.getFinding(f.getId());
            if (current == null || !List.of("open", "awaiting_approval").contains(current.getStatus())) {
                log.debug("Skipping conflict finding id={}: already resolved (status={}), idempotency guard",
                    f.getId(), current != null ? current.getStatus() : "deleted");
                skipped++;
                continue;
            }

            WikiPageDO pageDO = resolvePageByPath(scopeId, f.getPagePath());
            String category = pageDO != null ? pageDO.getCategory() : null;
            ConflictRoutingService.ConflictRoute route = conflictRoutingService.route(
                category, f.getHandlingMethod(), rulesConfig);

            if (route.autoLevel() == AutoLevel.REVIEW && "awaiting_approval".equals(f.getStatus())) {
                reviewHeld++;
                rulingReport.append("- 待人工确认（REVIEW）：findingId=").append(f.getId()).append("\n");
                sendReviewReminderNotification(scopeId, executionId, f, route);
                continue;
            }
            if (route.autoLevel() == AutoLevel.DEFER) {
                reviewHeld++;
                rulingReport.append("- 暂缓（DEFER）：findingId=").append(f.getId()).append("\n");
                continue;
            }

            Map<String, Object> rulingBrief;
            try {
                rulingBrief = objectMapper.readValue(f.getRulingBriefJson(), Map.class);
            } catch (Exception e) {
                log.warn("Failed to parse rulingBriefJson for findingId={}, skipping", f.getId());
                continue;
            }

            String action = rulingBrief.get("action") != null ? rulingBrief.get("action").toString() : null;
            boolean needsLLM = "merge".equals(action) || "rewrite".equals(action);
            String targetPath = needsLLM && rulingBrief.get("targetPagePath") != null
                ? rulingBrief.get("targetPagePath").toString() : null;

            byte[] contentBytes = null;
            String content = null;
            if (needsLLM && targetPath != null) {
                contentBytes = storageProvider.read(String.valueOf(scopeId), "wiki/" + targetPath);
                if (contentBytes != null) {
                    content = new String(contentBytes, StandardCharsets.UTF_8);
                }
            }

            boolean acquired = false;
            if (needsLLM && content != null) {
                acquired = llmConcurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 30_000);
                if (!acquired) {
                    log.warn("LlmConcurrencyBarrier LINT bucket timeout, skipping ruling findingId={}", f.getId());
                    continue;
                }
            }
            try {
                if (needsLLM && content != null) {
                    String strategyInstruction = ConflictResolutionStrategy.buildStrategyInstruction(
                        route.strategy().getKey());
                    String rewritePrompt = schemaInjector.prependForLint(scopeId,
                        PromptTemplate.knowledgeNormalizationPrinciple() + "\n\n---\n\n根据裁决方案改写此页面内容：\n"
                            + strategyInstruction + "\n路由决策：" + route.reason()
                            + "\n裁决方案：" + f.getRulingBriefJson() + "\n\n原内容：\n" + content);
                    String rewritten = chatClient.chat(rewritePrompt);
                    rewritten = PromptTemplate.stripConversationalFiller(stripMarkdownFences(rewritten));

                    applyConflictRewrite(scopeId, targetPath, rewritten, f);
                    archiveRelatedPageAfterMerge(scopeId, targetPath, f);
                    sendAutoExecutedNotification(scopeId, executionId, f, route);
                }
                if ("discard".equals(action)) {
                    String discardPath = rulingBrief.get("targetPagePath") != null
                        ? rulingBrief.get("targetPagePath").toString() : null;
                    if (discardPath != null) {
                        WikiPageDO discardPage = resolvePageByPath(scopeId, discardPath);
                        if (discardPage != null) {
                            discardPage.setHealthStatus("archived");
                            discardPage.setVisibility("private");
                            discardPage.setLifecycleStatus(PageLifecycle.MERGED.name());
                            discardPage.setContentUpdatedAt(java.time.LocalDateTime.now());
                            wikiPageMapper.updateById(discardPage);
                            searchService.removePage(scopeId, discardPage.getId());
                            migrateSourceRelations(scopeId, discardPage.getId(), f.getAssetId());
                            removeContradictionLink(scopeId, discardPage.getId(), f.getAssetId());
                            rulingReport.append("- 已归档废弃页面：").append(discardPath).append("\n");
                        }
                    }
                }
                lintFindingService.autoResolve(f.getId(), "ruling_brief");
                executed++;
                rulingReport.append("- 已执行裁决方案[AUTO]：findingId=").append(f.getId()).append("\n");
            } catch (Exception e) {
                log.warn("EXECUTE_RULINGS failed for findingId={}: {}", f.getId(), e.getMessage());
                lintFindingService.markAsFailed(f.getId(), e.getMessage());
                rulingReport.append("- 裁决执行失败：findingId=").append(f.getId()).append("，原因：").append(e.getMessage()).append("\n");
            } finally {
                if (acquired) {
                    llmConcurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
                }
            }
        }
        String summary = String.format("裁决方案执行：自动执行 %d 条，待人工确认 %d 条，超限跳过 %d 条\n%s",
            executed, reviewHeld, skipped, rulingReport);
        return new ExecutionOutcome(executed, reviewHeld, skipped, summary);
    }

    private void sendReviewReminderNotification(Long scopeId, Long executionId,
            LintFindingDO finding, ConflictRoutingService.ConflictRoute route) {
        String title = "冲突裁决待确认";
        String content = String.format("Lint 发现「%s」存在冲突，裁决方案已生成，路由策略：%s，请人工确认后执行。",
            finding.getTitle(), route.reason());
        notificationService.createNotification(scopeId, "conflict_review_reminder",
            title, content, scopeId, finding.getAssetId(), executionId);
    }

    private void sendAutoExecutedNotification(Long scopeId, Long executionId,
            LintFindingDO finding, ConflictRoutingService.ConflictRoute route) {
        String title = "冲突已自动执行";
        String content = String.format("Lint 冲突「%s」已按策略【%s】自动执行裁决方案。路由：%s。如有异议请回滚。",
            finding.getTitle(), route.strategy().getLabel(), route.reason());
        notificationService.createNotification(scopeId, "conflict_auto_executed",
            title, content, scopeId, finding.getAssetId(), executionId);
    }

    private void applyConflictRewrite(Long scopeId, String targetPath, String rewritten, LintFindingDO finding) {
        storageProvider.write(String.valueOf(scopeId), "wiki/" + targetPath,
            rewritten.getBytes(StandardCharsets.UTF_8));

        WikiPageDO targetPage = resolvePageByPath(scopeId, targetPath);
        if (targetPage != null) {
            targetPage.setHealthStatus("healthy");
            targetPage.setContentUpdatedAt(java.time.LocalDateTime.now());
            wikiPageMapper.updateById(targetPage);
            syncPageToIndex(scopeId, targetPage);

            persistSourceRelationsForConflict(scopeId, targetPage.getId(), finding);
        }
    }

    private void persistSourceRelationsForConflict(Long scopeId, Long pageId, LintFindingDO finding) {
        if (pageId == null) return;
        try {
            List<Long> sourceIds = lintFindingService.findSourceIdsForPage(scopeId, finding.getAssetId());
            for (Long sourceId : sourceIds) {
                Long existing = wikiPageSourceMapper.selectCount(
                    new LambdaQueryWrapper<WikiPageSourceDO>()
                        .eq(WikiPageSourceDO::getScopeId, scopeId)
                        .eq(WikiPageSourceDO::getPageId, pageId)
                        .eq(WikiPageSourceDO::getSourceId, sourceId)
                );
                if (existing != null && existing > 0) continue;
                WikiPageSourceDO rel = new WikiPageSourceDO();
                rel.setScopeId(scopeId);
                rel.setPageId(pageId);
                rel.setSourceId(sourceId);
                wikiPageSourceMapper.insert(rel);
            }
        } catch (Exception e) {
            log.warn("persistSourceRelationsForConflict failed: scopeId={}, pageId={}, findingId={}",
                scopeId, pageId, finding.getId());
        }
    }

    private String traceSourceEvidence(Long scopeId, LintFindingDO finding) {
        if (finding.getAssetId() == null) return null;
        try {
            List<Long> sourceIds = lintFindingService.findSourceIdsForPage(scopeId, finding.getAssetId());
            if (sourceIds.isEmpty()) return null;
            StringBuilder evidence = new StringBuilder();
            for (Long sourceId : sourceIds) {
                evidence.append("- 来源ID: ").append(sourceId).append("\n");
            }
            return evidence.toString();
        } catch (Exception e) {
            log.debug("traceSourceEvidence failed for findingId={}: {}", finding.getId(), e.getMessage());
            return null;
        }
    }

    private String readPageContent(Long scopeId, String pagePath) {
        if (pagePath == null || pagePath.isBlank()) return "";
        try {
            byte[] bytes = storageProvider.read(String.valueOf(scopeId), "wiki/" + pagePath);
            if (bytes != null) return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("readPageContent failed: scopeId={}, path={}", scopeId, pagePath);
        }
        return "";
    }

    private String readRelatedContent(Long scopeId, String extraJson) {
        if (extraJson == null || extraJson.isBlank()) return "";
        try {
            var extra = objectMapper.readValue(extraJson, Map.class);
            Object relatedPath = extra.get("relatedPagePath");
            if (relatedPath instanceof String rp && !rp.isBlank()) {
                byte[] bytes = storageProvider.read(String.valueOf(scopeId), "wiki/" + rp);
                if (bytes != null) return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception ignore) {}
        return "";
    }

    private WikiPageDO resolvePageByPath(Long scopeId, String filePath) {
        return wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .eq(WikiPageDO::getFilePath, filePath)
                .last("LIMIT 1"));
    }

    private String stripMarkdownFences(String text) {
        if (text == null) return "";
        text = text.trim();
        if (text.startsWith("```markdown")) {
            text = text.substring(11);
        } else if (text.startsWith("```md")) {
            text = text.substring(5);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return null;
    }

    private void createConflictReviewFromLintFinding(Long scopeId, Long executionId,
            LintFindingDO f, WikiPageDO fromPage, ConflictRoutingService.ConflictRoute route) {
        if (fromPage == null) return;
        try {
            WikiPageDO toPage = resolveRelatedPage(scopeId, f.getExtra());
            if (toPage == null) {
                log.warn("Cannot resolve related page for lint finding id={}, skipping conflict_review creation", f.getId());
                return;
            }
            String conflictType = extractExtraField(f.getExtra(), "conflictType");
            conflictReviewService.createReview(
                scopeId, executionId, "LINT",
                fromPage, toPage, conflictType,
                route.strategy().getKey(), route.strategy().getLabel(), route.reason());
        } catch (Exception e) {
            log.warn("createConflictReviewFromLintFinding failed for findingId={}: {}", f.getId(), e.getMessage());
        }
    }

    private WikiPageDO resolveRelatedPage(Long scopeId, String extraJson) {
        if (extraJson == null || extraJson.isBlank()) return null;
        try {
            var extra = objectMapper.readValue(extraJson, Map.class);
            if (extra.get("relatedPageId") instanceof Number num) {
                WikiPageDO page = wikiPageMapper.selectById(num.longValue());
                if (page != null) return page;
            }
            Object relatedPath = extra.get("relatedPagePath");
            if (relatedPath instanceof String rp && !rp.isBlank()) {
                return resolvePageByPath(scopeId, rp);
            }
        } catch (Exception e) {
            log.debug("resolveRelatedPage failed: {}", e.getMessage());
        }
        return null;
    }

    private String extractExtraField(String extraJson, String field) {
        if (extraJson == null || extraJson.isBlank()) return null;
        try {
            var extra = objectMapper.readValue(extraJson, Map.class);
            Object val = extra.get(field);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void syncPageToIndex(Long scopeId, WikiPageDO pageDO) {
        try {
            String content = "";
            String storagePath = "wiki/" + pageDO.getFilePath();
            byte[] contentBytes = storageProvider.read(String.valueOf(scopeId), storagePath);
            if (contentBytes != null) {
                content = new String(contentBytes, StandardCharsets.UTF_8);
            }
            searchService.indexPage(
                scopeId, pageDO.getId(), pageDO.getTitle(), pageDO.getFilePath(),
                pageDO.getCategory(), pageDO.getSummary(), content,
                pageDO.getHealthStatus(), pageDO.getVisibility(),
                pageDO.getLifecycleStatus()
            );
        } catch (Exception e) {
            log.warn("syncPageToIndex failed: scopeId={}, pageId={}", scopeId, pageDO.getId());
        }
    }

    private void archiveRelatedPageAfterMerge(Long scopeId, String targetPath, LintFindingDO finding) {
        try {
            String relatedPath = extractExtraField(finding.getExtra(), "relatedPagePath");
            if (relatedPath == null || relatedPath.equals(targetPath)) return;

            WikiPageDO relatedPage = resolvePageByPath(scopeId, relatedPath);
            if (relatedPage == null) return;

            WikiPageDO targetPage = resolvePageByPath(scopeId, targetPath);
            if (targetPage == null) return;

            relatedPage.setHealthStatus("merged-into-" + targetPage.getId());
            relatedPage.setVisibility("private");
            relatedPage.setLifecycleStatus(PageLifecycle.MERGED.name());
            relatedPage.setMergedIntoPageId(targetPage.getId());
            wikiPageMapper.updateById(relatedPage);
            searchService.removePage(scopeId, relatedPage.getId());
            migrateSourceRelations(scopeId, relatedPage.getId(), targetPage.getId());
            removeContradictionLink(scopeId, relatedPage.getId(), targetPage.getId());
            log.info("Archived related page after merge: '{}' → '{}'", relatedPage.getTitle(), targetPage.getTitle());
        } catch (Exception e) {
            log.warn("archiveRelatedPageAfterMerge failed: scopeId={}, targetPath={}: {}",
                scopeId, targetPath, e.getMessage());
        }
    }

    public void migrateSourceRelations(Long scopeId, Long fromPageId, Long toPageId) {
        try {
            WikiPageDO toPage = wikiPageMapper.selectById(toPageId);
            if (toPage == null) return;

            List<WikiPageSourceDO> fromSources = wikiPageSourceMapper.selectList(
                new LambdaQueryWrapper<WikiPageSourceDO>()
                    .eq(WikiPageSourceDO::getScopeId, scopeId)
                    .eq(WikiPageSourceDO::getPageId, fromPageId)
            );
            List<Long> existingSourceIds = wikiPageSourceMapper.selectList(
                new LambdaQueryWrapper<WikiPageSourceDO>()
                    .eq(WikiPageSourceDO::getScopeId, scopeId)
                    .eq(WikiPageSourceDO::getPageId, toPageId)
                    .select(WikiPageSourceDO::getSourceId)
            ).stream().map(WikiPageSourceDO::getSourceId).toList();

            int migrated = 0;
            for (WikiPageSourceDO source : fromSources) {
                if (!existingSourceIds.contains(source.getSourceId())) {
                    source.setPageId(toPageId);
                    wikiPageSourceMapper.updateById(source);
                    migrated++;
                }
            }
            if (migrated > 0) {
                toPage.setSourceCount((toPage.getSourceCount() != null ? toPage.getSourceCount() : 0) + migrated);
                wikiPageMapper.updateById(toPage);
            }
            log.info("Migrated {} source relations from page {} to page {}", migrated, fromPageId, toPageId);
        } catch (Exception e) {
            log.warn("migrateSourceRelations failed: {}", e.getMessage());
        }
    }

    public void removeContradictionLink(Long scopeId, Long pageAId, Long pageBId) {
        wikiPageLinkMapper.delete(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, pageAId)
                .eq(WikiPageLinkDO::getToPageId, pageBId)
                .eq(WikiPageLinkDO::getLinkType, "contradiction")
        );
        wikiPageLinkMapper.delete(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, pageBId)
                .eq(WikiPageLinkDO::getToPageId, pageAId)
                .eq(WikiPageLinkDO::getLinkType, "contradiction")
        );
    }

    // ===== 合并相关领域 API =====

    /**
     * 将 fromPageId 的 inbound links 重定向到 toPageId（去重）
     */
    public void migrateInboundLinks(Long scopeId, Long fromPageId, Long toPageId) {
        try {
            List<WikiPageLinkDO> inboundLinks = wikiPageLinkMapper.selectList(
                new LambdaQueryWrapper<WikiPageLinkDO>()
                    .eq(WikiPageLinkDO::getScopeId, scopeId)
                    .eq(WikiPageLinkDO::getToPageId, fromPageId)
            );
            int migrated = 0;
            for (WikiPageLinkDO link : inboundLinks) {
                if (link.getFromPageId().equals(toPageId)) continue;
                Long existing = wikiPageLinkMapper.selectCount(
                    new LambdaQueryWrapper<WikiPageLinkDO>()
                        .eq(WikiPageLinkDO::getScopeId, scopeId)
                        .eq(WikiPageLinkDO::getFromPageId, link.getFromPageId())
                        .eq(WikiPageLinkDO::getToPageId, toPageId)
                );
                if (existing != null && existing > 0) continue;
                link.setToPageId(toPageId);
                wikiPageLinkMapper.insert(link);
                migrated++;
            }
            log.info("Migrated {} inbound links from page {} to page {}", migrated, fromPageId, toPageId);
        } catch (Exception e) {
            log.warn("migrateInboundLinks failed: scopeId={}, from={}, to={}", scopeId, fromPageId, toPageId, e);
        }
    }

    /**
     * 归档单个页面（合并场景）：设置 visibility=private + deletedAt + mergedIntoPageId，
     * 从 ES 移除，迁移 source 关系、inbound links 和 contradiction links
     */
    public void archivePageForMerge(Long scopeId, Long oldPageId, Long targetPageId) {
        WikiPageDO oldPage = wikiPageMapper.selectById(oldPageId);
        if (oldPage == null) return;

        String status = oldPage.getLifecycleStatus();
        if (PageLifecycle.MERGED.name().equals(status) || PageLifecycle.DELETED.name().equals(status)) {
            log.debug("Skipping already archived page: pageId={}, status={}", oldPageId, status);
            return;
        }

        oldPage.setVisibility("private");
        oldPage.setHealthStatus("archived");
        oldPage.setDeletedAt(java.time.LocalDateTime.now());
        oldPage.setMergedIntoPageId(targetPageId);
        oldPage.setLifecycleStatus(PageLifecycle.MERGED.name());
        wikiPageMapper.updateById(oldPage);

        try {
            searchService.removePage(scopeId, oldPageId);
        } catch (Exception e) {
            log.warn("Failed to remove merged page from index: pageId={}", oldPageId);
        }

        migrateSourceRelations(scopeId, oldPageId, targetPageId);
        migrateInboundLinks(scopeId, oldPageId, targetPageId);
        removeContradictionLink(scopeId, oldPageId, targetPageId);

        log.info("Archived page {} for merge into page {}", oldPageId, targetPageId);
    }

    /**
     * 批量归档页面（合并场景）：归档所有 oldPageIds，最后更新目标页面的 sourceCount
     */
    public void batchArchivePagesForMerge(Long scopeId, List<Long> oldPageIds, Long targetPageId) {
        for (Long oldPageId : oldPageIds) {
            if (oldPageId.equals(targetPageId)) continue;
            archivePageForMerge(scopeId, oldPageId, targetPageId);
        }

        WikiPageDO targetDO = wikiPageMapper.selectById(targetPageId);
        if (targetDO != null) {
            targetDO.setSourceCount((int) wikiPageSourceMapper.selectCount(
                new LambdaQueryWrapper<WikiPageSourceDO>()
                    .eq(WikiPageSourceDO::getScopeId, scopeId)
                    .eq(WikiPageSourceDO::getPageId, targetPageId)
            ).longValue());
            wikiPageMapper.updateById(targetDO);
        }
    }

    /**
     * 标记两个页面之间存在矛盾（创建 contradiction link + 设置 healthStatus）
     */
    public void markContradiction(Long scopeId, WikiPageDO pageA, WikiPageDO pageB, String conflictType) {
        conflictRoutingService.createContradictionLink(scopeId, pageA, pageB, conflictType, null);
        conflictRoutingService.markConflictWarning(scopeId, pageA, pageB);
    }
}
