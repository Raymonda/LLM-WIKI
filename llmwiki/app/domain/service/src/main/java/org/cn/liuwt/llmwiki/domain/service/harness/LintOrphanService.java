package org.cn.liuwt.llmwiki.domain.service.harness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageKeywordDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageKeywordMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class LintOrphanService {

    private static final Logger log = LoggerFactory.getLogger(LintOrphanService.class);

    private final ExecutorService orphanTriageExecutor = Executors.newFixedThreadPool(4,
        r -> { Thread t = new Thread(r, "lint-orphan-triage"); t.setDaemon(true); return t; });

    @jakarta.annotation.PreDestroy
    public void shutdownExecutor() { orphanTriageExecutor.shutdown(); }

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private GlobalSummaryService globalSummaryService;

    @Autowired
    private LinkWritingService linkWritingService;

    @Autowired
    private LintFindingService lintFindingService;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private LlmConcurrencyBarrier llmConcurrencyBarrier;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageKeywordMapper wikiPageKeywordMapper;

    @Autowired
    private LintFindingMapper lintFindingMapper;

    @Autowired
    private WikiFileServiceImpl wikiFileService;

    @Autowired
    private SearchService searchService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record OrphanDiagnosis(
        String diagnosis,
        String reason,
        String duplicateTarget,
        String thinSuggestion
    ) {
        public static OrphanDiagnosis fallback(String reason) {
            return new OrphanDiagnosis("standalone", reason, null, null);
        }
    }

    public record TriageResult(
        int integrated,
        int duplicated,
        int standalone,
        int thin,
        int failed,
        int skipped
    ) {}

    private static final int THIN_THRESHOLD_CHARS = 200;
    private static final int STANDALONE_CONTENT_THRESHOLD = 1000;
    private static final double DUPLICATE_KEYWORD_OVERLAP = 0.7;

    OrphanDiagnosis deterministicDiagnosis(Long scopeId, WikiPageDO orphanPage) {
        try {
            String content = readPageSummary(scopeId, orphanPage.getFilePath(), 5000);
            int contentLen = content.startsWith("(") ? 0 : content.length();

            if (contentLen > 0 && contentLen < THIN_THRESHOLD_CHARS) {
                return new OrphanDiagnosis("thin", "页面内容仅 " + contentLen + " 字符，低于阈值 " + THIN_THRESHOLD_CHARS,
                    null, "建议补充相关知识内容或通过 Ingest 导入更多资料");
            }

            String category = orphanPage.getCategory();
            if (category != null && !category.isBlank()) {
                long categoryCount = wikiPageMapper.selectCount(
                    new LambdaQueryWrapper<WikiPageDO>()
                        .eq(WikiPageDO::getScopeId, scopeId)
                        .eq(WikiPageDO::getCategory, category)
                );
                if (categoryCount <= 1 && contentLen > STANDALONE_CONTENT_THRESHOLD) {
                    return new OrphanDiagnosis("standalone",
                        "分类「" + category + "」下仅此一个页面，且内容充实（" + contentLen + " 字符）",
                        null, null);
                }
            }

            List<WikiPageKeywordDO> orphanKeywords = wikiPageKeywordMapper.selectList(
                new LambdaQueryWrapper<WikiPageKeywordDO>()
                    .eq(WikiPageKeywordDO::getScopeId, scopeId)
                    .eq(WikiPageKeywordDO::getPageId, orphanPage.getId())
            );
            if (orphanKeywords.size() >= 3) {
                Set<String> orphanKwSet = orphanKeywords.stream()
                    .map(WikiPageKeywordDO::getKeyword)
                    .collect(Collectors.toSet());

                List<WikiPageDO> candidatePages = wikiPageMapper.selectList(
                    new LambdaQueryWrapper<WikiPageDO>()
                        .eq(WikiPageDO::getScopeId, scopeId)
                        .ne(WikiPageDO::getId, orphanPage.getId())
                        .isNotNull(WikiPageDO::getFilePath)
                        .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath)
                        .last("LIMIT 50")
                );
                if (!candidatePages.isEmpty()) {
                    List<Long> candidateIds = candidatePages.stream().map(WikiPageDO::getId).toList();
                    List<WikiPageKeywordDO> candidateKeywords = wikiPageKeywordMapper.selectList(
                        new LambdaQueryWrapper<WikiPageKeywordDO>()
                            .eq(WikiPageKeywordDO::getScopeId, scopeId)
                            .in(WikiPageKeywordDO::getPageId, candidateIds)
                    );
                    Map<Long, Set<String>> kwByCandidate = new HashMap<>();
                    for (WikiPageKeywordDO kw : candidateKeywords) {
                        kwByCandidate.computeIfAbsent(kw.getPageId(), k -> new HashSet<>()).add(kw.getKeyword());
                    }
                    for (WikiPageDO candidate : candidatePages) {
                        Set<String> candKws = kwByCandidate.getOrDefault(candidate.getId(), Set.of());
                        if (candKws.isEmpty()) continue;
                        long overlap = candKws.stream().filter(orphanKwSet::contains).count();
                        double overlapRate = (double) overlap / Math.min(orphanKwSet.size(), candKws.size());
                        if (overlapRate >= DUPLICATE_KEYWORD_OVERLAP && overlap >= 3) {
                            String target = candidate.getTitle() != null ? candidate.getTitle() : candidate.getFilePath();
                            return new OrphanDiagnosis("duplicate",
                                "与「" + target + "」关键词重叠率达 " + String.format("%.0f%%", overlapRate * 100) + "（" + overlap + " 个共同关键词）",
                                target, null);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Deterministic orphan diagnosis failed for pageId={}: {}", orphanPage.getId(), e.getMessage());
        }
        return null;
    }

    public OrphanDiagnosis diagnoseOrphan(Long scopeId, WikiPageDO orphanPage, String globalSummaryCompact) {
        OrphanDiagnosis deterministic = deterministicDiagnosis(scopeId, orphanPage);
        if (deterministic != null) {
            log.info("Orphan deterministic triage: pageId={}, diagnosis={}, reason={}",
                orphanPage.getId(), deterministic.diagnosis(), deterministic.reason());
            return deterministic;
        }

        String title = orphanPage.getTitle() != null ? orphanPage.getTitle() : orphanPage.getFilePath();
        String pageSummary = readPageSummary(scopeId, orphanPage.getFilePath(), 200);

        String prompt = schemaInjector.prependForLint(scopeId,
            PromptRegistry.forLint().diagnoseOrphan(title, pageSummary, globalSummaryCompact));

        boolean acquired = llmConcurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 30_000);
        if (!acquired) {
            log.warn("LlmConcurrencyBarrier LINT bucket timeout for orphan diagnosis, pageId={}", orphanPage.getId());
            return OrphanDiagnosis.fallback("LLM 并发超时，降级为独立知识");
        }
        try {
            String response = chatClient.chat(prompt);
            return parseDiagnosis(response);
        } catch (Exception e) {
            log.warn("Failed to diagnose orphan page {}: {}", orphanPage.getFilePath(), e.getMessage());
            return OrphanDiagnosis.fallback("LLM 调用失败，降级为独立知识");
        } finally {
            llmConcurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
        }
    }

    public TriageResult executeOrphanTriage(Long scopeId, Long executionId, List<LintFindingDO> orphanFindings,
                                             int autoFixLimit, String globalSummaryCompact) {
        if (chatClient == null || !chatClient.isAvailable()) {
            return new TriageResult(0, 0, 0, 0, 0, orphanFindings.size());
        }

        List<LintFindingDO> candidates = orphanFindings.stream()
            .filter(f -> "auto_repair".equals(f.getHandlingMethod()))
            .collect(Collectors.toList());
        int skipped = Math.max(0, candidates.size() - autoFixLimit);
        List<LintFindingDO> workItems = candidates.subList(0, Math.min(candidates.size(), Math.max(0, autoFixLimit)));
        if (workItems.isEmpty()) {
            return new TriageResult(0, 0, 0, 0, 0, skipped);
        }

        Set<Long> assetIds = workItems.stream()
            .map(LintFindingDO::getAssetId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, WikiPageDO> pageById = new HashMap<>();
        if (!assetIds.isEmpty()) {
            for (WikiPageDO page : wikiPageMapper.selectBatchIds(assetIds)) {
                pageById.put(page.getId(), page);
            }
        }

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (LintFindingDO f : workItems) {
            WikiPageDO orphanPage = f.getAssetId() != null ? pageById.get(f.getAssetId()) : null;
            if (orphanPage == null) continue;
            // 复诊跳过：已诊断过且页面内容未变更的孤儿不重复花 LLM
            if (!shouldReTriage(f, orphanPage)) {
                skipped++;
                log.debug("Orphan re-triage skipped: findingId={}, pageId={}, diagnosis={} unchanged",
                    f.getId(), orphanPage.getId(), f.getOrphanDiagnosis());
                continue;
            }
            futures.add(CompletableFuture.supplyAsync(
                () -> triageOne(scopeId, executionId, f, orphanPage, globalSummaryCompact),
                orphanTriageExecutor));
        }
        if (futures.isEmpty()) {
            return new TriageResult(0, 0, 0, 0, 0, skipped);
        }

        int timeoutSeconds = Math.min(180 + futures.size() * 20, 600);
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Orphan triage parallel wait failed after {}s, harvesting completed tasks: {}",
                timeoutSeconds, e.getMessage());
        }

        int integrated = 0, duplicated = 0, standalone = 0, thin = 0, failed = 0;
        for (CompletableFuture<String> future : futures) {
            if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
                switch (future.getNow("failed")) {
                    case "integrated" -> integrated++;
                    case "duplicated" -> duplicated++;
                    case "standalone" -> standalone++;
                    case "thin" -> thin++;
                    default -> failed++;
                }
            } else {
                future.cancel(true);
                failed++;
            }
        }

        log.info("Orphan triage completed: integrated={}, duplicated={}, standalone={}, thin={}, failed={}, skipped={}",
            integrated, duplicated, standalone, thin, failed, skipped);
        return new TriageResult(integrated, duplicated, standalone, thin, failed, skipped);
    }

    /**
     * 判断孤儿 finding 是否需要重新 triage：
     * 未诊断过、诊断时间缺失、或页面内容在上次诊断之后发生变更时需要复诊。
     */
    private boolean shouldReTriage(LintFindingDO finding, WikiPageDO orphanPage) {
        if (finding.getOrphanDiagnosis() == null || finding.getOrphanDiagnosis().isBlank()) {
            return true;
        }
        LocalDateTime diagnosedAt = finding.getUpdatedAt();
        if (diagnosedAt == null) {
            return true;
        }
        LocalDateTime contentUpdatedAt = orphanPage.getContentUpdatedAt() != null
            ? orphanPage.getContentUpdatedAt() : orphanPage.getUpdatedAt();
        return contentUpdatedAt != null && contentUpdatedAt.isAfter(diagnosedAt);
    }

    private String triageOne(Long scopeId, Long executionId, LintFindingDO f,
                              WikiPageDO orphanPage, String globalSummaryCompact) {
        OrphanDiagnosis diagnosis = diagnoseOrphan(scopeId, orphanPage, globalSummaryCompact);
        updateOrphanDiagnosis(f.getId(), diagnosis.diagnosis());

        try {
            switch (diagnosis.diagnosis()) {
                case "integrate" -> {
                    boolean linkSuccess = generateAndApplyLinks(scopeId, orphanPage, globalSummaryCompact);
                    if (!linkSuccess) {
                        updateOrphanDiagnosisExtra(f.getId(), "linkGenerationFailed", "LLM 未生成有效链接建议");
                        lintFindingService.markRepairFailed(f.getId());
                        return "failed";
                    }
                    lintFindingService.autoResolve(f.getId(), "auto_repair");
                    return "integrated";
                }
                case "duplicate" -> {
                    createDuplicateFinding(scopeId, executionId, f, orphanPage, diagnosis);
                    lintFindingService.autoResolve(f.getId(), "auto_repair");
                    return "duplicated";
                }
                case "standalone" -> {
                    lintFindingService.autoResolve(f.getId(), "dismiss");
                    return "standalone";
                }
                case "thin" -> {
                    createThinFinding(scopeId, executionId, f, orphanPage, diagnosis);
                    lintFindingService.autoResolve(f.getId(), "auto_repair");
                    return "thin";
                }
                default -> {
                    lintFindingService.autoResolve(f.getId(), "auto_repair");
                    return "standalone";
                }
            }
        } catch (Exception e) {
            log.warn("Orphan triage failed for findingId={}, diagnosis={}: {}",
                f.getId(), diagnosis.diagnosis(), e.getMessage());
            lintFindingService.markRepairFailed(f.getId());
            return "failed";
        }
    }

    public Map<String, Object> retryOrphanFix(Long scopeId, Long findingId) {
        if (chatClient == null || !chatClient.isAvailable()) {
            throw new RuntimeException("AI 未配置，无法重试孤儿修复");
        }

        LintFindingDO finding = lintFindingService.getFinding(findingId);
        if (finding == null) {
            throw new RuntimeException("Finding not found: id=" + findingId);
        }
        if (!"orphan".equals(finding.getFindingType())) {
            throw new RuntimeException("Only orphan findings can be retried: type=" + finding.getFindingType());
        }

        WikiPageDO orphanPage = finding.getAssetId() != null ? wikiPageMapper.selectById(finding.getAssetId()) : null;
        if (orphanPage == null) {
            throw new RuntimeException("Orphan page not found: assetId=" + finding.getAssetId());
        }

        GlobalSummaryService.GlobalSummary summary = globalSummaryService.build(scopeId);
        String globalSummaryCompact = summary.toCompactPrompt();

        OrphanDiagnosis diagnosis = diagnoseOrphan(scopeId, orphanPage, globalSummaryCompact);
        updateOrphanDiagnosis(findingId, diagnosis.diagnosis());

        Map<String, Object> result = new HashMap<>();
        result.put("findingId", findingId);
        result.put("diagnosis", diagnosis.diagnosis());
        result.put("reason", diagnosis.reason());

        switch (diagnosis.diagnosis()) {
            case "integrate" -> {
                boolean linkSuccess = generateAndApplyLinks(scopeId, orphanPage, globalSummaryCompact);
                if (linkSuccess) {
                    lintFindingService.autoResolve(findingId, "auto_repair");
                    result.put("status", "auto_resolved");
                } else {
                    lintFindingService.markRepairFailed(findingId);
                    result.put("status", "repair_failed");
                }
                result.put("linksApplied", linkSuccess);
            }
            case "duplicate" -> {
                createDuplicateFinding(scopeId, finding.getExecutionId(), finding, orphanPage, diagnosis);
                lintFindingService.autoResolve(findingId, "auto_repair");
                result.put("status", "auto_resolved");
                result.put("duplicateTarget", diagnosis.duplicateTarget());
            }
            case "standalone" -> {
                lintFindingService.autoResolve(findingId, "dismiss");
                result.put("status", "auto_resolved");
            }
            case "thin" -> {
                createThinFinding(scopeId, finding.getExecutionId(), finding, orphanPage, diagnosis);
                lintFindingService.autoResolve(findingId, "auto_repair");
                result.put("status", "auto_resolved");
                result.put("thinSuggestion", diagnosis.thinSuggestion());
            }
            default -> {
                lintFindingService.autoResolve(findingId, "dismiss");
                result.put("status", "auto_resolved");
            }
        }

        return result;
    }

    private boolean generateAndApplyLinks(Long scopeId, WikiPageDO orphanPage, String globalSummaryCompact) {
        String summary = readPageSummary(scopeId, orphanPage.getFilePath(), 500);
        String title = orphanPage.getTitle() != null ? orphanPage.getTitle() : orphanPage.getFilePath();

        String linkPrompt = schemaInjector.prependForLint(scopeId,
            PromptRegistry.forLint().suggestOrphanLinks(orphanPage.getFilePath(), title, summary, globalSummaryCompact));

        boolean acquired = llmConcurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 30_000);
        if (!acquired) {
            log.warn("LlmConcurrencyBarrier timeout for link generation, pageId={}", orphanPage.getId());
            return false;
        }
        try {
            String linkSuggestions = chatClient.chat(linkPrompt);
            int applied = linkWritingService.applyLinkSuggestions(linkSuggestions, scopeId);
            log.info("Applied {} links for orphan page {}", applied, orphanPage.getFilePath());
            return applied > 0;
        } catch (Exception e) {
            log.warn("Link generation failed for orphan {}: {}", orphanPage.getFilePath(), e.getMessage());
            return false;
        } finally {
            llmConcurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
        }
    }

    private void createDuplicateFinding(Long scopeId, Long executionId, LintFindingDO originalFinding,
                                         WikiPageDO orphanPage, OrphanDiagnosis diagnosis) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("handlingMethod", "manual_merge");
        extra.put("orphanDiagnosis", "duplicate");
        extra.put("duplicateTarget", diagnosis.duplicateTarget());
        extra.put("reason", diagnosis.reason());

        String detail = "该页面与「" + (diagnosis.duplicateTarget() != null ? diagnosis.duplicateTarget() : "某页面")
            + "」内容高度重复。建议合并。理由：" + diagnosis.reason();

        lintFindingService.createFinding(scopeId, executionId, "duplicate_orphan",
            "medium", "重复页面：「" + orphanPage.getTitle() + "」", detail,
            orphanPage.getFilePath(), orphanPage.getId(), extra);
    }

    private void createThinFinding(Long scopeId, Long executionId, LintFindingDO originalFinding,
                                    WikiPageDO orphanPage, OrphanDiagnosis diagnosis) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("handlingMethod", "manual_ingest");
        extra.put("orphanDiagnosis", "thin");
        extra.put("thinSuggestion", diagnosis.thinSuggestion());

        String detail = "该页面内容过薄，无实质知识。建议补充相关资料或删除。";
        if (diagnosis.thinSuggestion() != null) {
            detail += " 补充建议：" + diagnosis.thinSuggestion();
        }

        lintFindingService.createFinding(scopeId, executionId, "content_thin",
            "low", "页面内容过薄：「" + orphanPage.getTitle() + "」", detail,
            orphanPage.getFilePath(), orphanPage.getId(), extra);
    }

    private void updateOrphanDiagnosis(Long findingId, String diagnosis) {
        lintFindingMapper.update(null,
            new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getOrphanDiagnosis, diagnosis)
        );
    }

    private void updateOrphanDiagnosisExtra(Long findingId, String key, String value) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null) return;
        try {
            Map<String, Object> extraMap;
            if (finding.getExtra() != null && !finding.getExtra().isBlank()) {
                extraMap = objectMapper.readValue(finding.getExtra(), Map.class);
            } else {
                extraMap = new HashMap<>();
            }
            extraMap.put(key, value);
            lintFindingMapper.update(null,
                new LambdaUpdateWrapper<LintFindingDO>()
                    .eq(LintFindingDO::getId, findingId)
                    .set(LintFindingDO::getExtra, objectMapper.writeValueAsString(extraMap))
            );
        } catch (Exception e) {
            log.warn("Failed to update orphan diagnosis extra for findingId={}: {}", findingId, e.getMessage());
        }
    }

    private String readPageSummary(Long scopeId, String filePath, int maxChars) {
        try {
            String storagePath = filePath.startsWith("pages/") ? "wiki/" + filePath : filePath;
            byte[] contentBytes = storageProvider.read(String.valueOf(scopeId), storagePath);
            if (contentBytes == null) return "(无法读取页面内容)";
            String content = new String(contentBytes, StandardCharsets.UTF_8);
            if (content.length() <= maxChars) return content;
            return content.substring(0, maxChars) + "...";
        } catch (Exception e) {
            log.warn("Failed to read page content for {}: {}", filePath, e.getMessage());
            return "(无法读取页面内容)";
        }
    }

    private OrphanDiagnosis parseDiagnosis(String response) {
        try {
            String cleanJson = extractJsonObject(response);
            JsonNode root = objectMapper.readTree(cleanJson);

            String diagnosis = root.has("diagnosis") ? root.get("diagnosis").asText() : "standalone";
            if (!List.of("integrate", "duplicate", "standalone", "thin").contains(diagnosis)) {
                diagnosis = "standalone";
            }

            String reason = root.has("reason") ? root.get("reason").asText() : "";
            String duplicateTarget = root.has("duplicateTarget") && !root.get("duplicateTarget").isNull()
                ? root.get("duplicateTarget").asText() : null;
            String thinSuggestion = root.has("thinSuggestion") && !root.get("thinSuggestion").isNull()
                ? root.get("thinSuggestion").asText() : null;

            return new OrphanDiagnosis(diagnosis, reason, duplicateTarget, thinSuggestion);
        } catch (Exception e) {
            log.warn("Failed to parse orphan diagnosis JSON: {}", e.getMessage());
            return OrphanDiagnosis.fallback("诊断结果解析失败");
        }
    }

    public Map<String, Object> enrichPage(Long scopeId, Long findingId, String userSupplement) {
        if (chatClient == null || !chatClient.isAvailable()) {
            throw new RuntimeException("AI 未配置，无法执行页面充实");
        }

        LintFindingDO finding = lintFindingService.getFinding(findingId);
        if (finding == null) {
            throw new RuntimeException("Finding not found: id=" + findingId);
        }
        if (!"content_thin".equals(finding.getFindingType())) {
            throw new RuntimeException("Only content_thin findings can be enriched: type=" + finding.getFindingType());
        }

        WikiPageDO page = finding.getAssetId() != null ? wikiPageMapper.selectById(finding.getAssetId()) : null;
        if (page == null) {
            throw new RuntimeException("Page not found: assetId=" + finding.getAssetId());
        }

        String scopeIdStr = String.valueOf(scopeId);
        String pageStoragePath = page.getFilePath().startsWith("pages/") ? "wiki/" + page.getFilePath() : page.getFilePath();

        byte[] existingBytes = storageProvider.read(scopeIdStr, pageStoragePath);
        if (existingBytes == null) {
            throw new RuntimeException("Page file not found in storage: " + pageStoragePath);
        }
        String existingContent = new String(existingBytes, StandardCharsets.UTF_8);
        String pageTitle = page.getTitle() != null ? page.getTitle() : page.getFilePath();

        String prompt = schemaInjector.prependForLint(scopeId,
            PromptRegistry.forLint().enrichThinPage(pageTitle, existingContent, userSupplement));

        boolean acquired = llmConcurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 60_000);
        if (!acquired) {
            throw new RuntimeException("LLM 并发超时，请稍后重试");
        }
        String merged;
        try {
            merged = chatClient.chat(prompt);
        } catch (Exception e) {
            throw new RuntimeException("AI 融合失败: " + e.getMessage(), e);
        } finally {
            llmConcurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
        }

        merged = PromptTemplate.stripConversationalFiller(stripMarkdownFences(merged));
        merged = linkWritingService.sanitizeSourceLinks(merged);
        merged = linkWritingService.sanitizeWikiLinks(merged, scopeId);

        storageProvider.write(scopeIdStr, pageStoragePath, merged.getBytes(StandardCharsets.UTF_8));

        page.setContentUpdatedAt(LocalDateTime.now());
        page.setHealthStatus("healthy");
        wikiPageMapper.updateById(page);

        syncPageToIndex(page, scopeId, scopeIdStr, merged);

        lintFindingService.autoResolve(findingId, "auto_repair");
        wikiFileService.recalcPageHealthStatus(scopeId, page.getId());

        log.info("Thin page enriched: scopeId={}, pageId={}, findingId={}, pagePath={}",
            scopeId, page.getId(), findingId, page.getFilePath());

        Map<String, Object> result = new HashMap<>();
        result.put("findingId", findingId);
        result.put("pageId", page.getId());
        result.put("status", "enriched");
        return result;
    }

    private String stripMarkdownFences(String content) {
        if (content == null) return "";
        content = content.trim();
        if (content.startsWith("```markdown")) content = content.substring("```markdown".length());
        else if (content.startsWith("```md")) content = content.substring("```md".length());
        else if (content.startsWith("```")) content = content.substring(3);
        if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
        return content.trim();
    }

    private void syncPageToIndex(WikiPageDO pageDO, Long scopeId, String scopeIdStr, String content) {
        try {
            searchService.indexPage(
                scopeId, pageDO.getId(), pageDO.getTitle(), pageDO.getFilePath(),
                pageDO.getCategory(), pageDO.getSummary(), content,
                pageDO.getHealthStatus(), pageDO.getVisibility(),
                pageDO.getLifecycleStatus()
            );
        } catch (Exception e) {
            log.warn("syncPageToIndex failed for thin page enrichment: pageId={}, error={}", pageDO.getId(), e.getMessage());
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw.trim();
    }
}
