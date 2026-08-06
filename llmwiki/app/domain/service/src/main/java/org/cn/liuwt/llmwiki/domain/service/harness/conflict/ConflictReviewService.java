package org.cn.liuwt.llmwiki.domain.service.harness.conflict;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ConflictReviewDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ConflictReviewMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.constant.PageLifecycle;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class ConflictReviewService {

    private static final Logger log = LoggerFactory.getLogger(ConflictReviewService.class);

    @Autowired
    private ConflictReviewMapper conflictReviewMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Autowired
    private NotificationService notificationService;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private LlmConcurrencyBarrier llmConcurrencyBarrier;

    @Autowired
    private SearchService searchService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private LintFindingService lintFindingService;

    public Long createReview(Long scopeId, Long executionId, String sourceType,
                             WikiPageDO fromPage, WikiPageDO toPage,
                             String conflictType, String strategyKey,
                             String strategyLabel, String routeReason) {
        ConflictReviewDO existing = findPendingByPagePair(scopeId, fromPage.getId(), toPage.getId());
        if (existing == null) {
            existing = findPendingByPagePair(scopeId, toPage.getId(), fromPage.getId());
        }
        if (existing != null) {
            if (executionId != null) {
                existing.setSourceExecutionId(executionId);
                conflictReviewMapper.updateById(existing);
            }
            log.info("ConflictReview already pending for {}↔{} (id={}, source={}), returning existing",
                fromPage.getTitle(), toPage.getTitle(), existing.getId(), existing.getSourceType());
            return existing.getId();
        }

        ConflictReviewDO review = new ConflictReviewDO();
        review.setScopeId(scopeId);
        review.setSourceType(sourceType != null ? sourceType : "INGEST");
        review.setSourceExecutionId(executionId);
        review.setFromPageId(fromPage.getId());
        review.setToPageId(toPage.getId());
        review.setFromPageTitle(fromPage.getTitle());
        review.setToPageTitle(toPage.getTitle());
        review.setConflictType(conflictType);
        review.setStrategyKey(strategyKey);
        review.setStrategyLabel(strategyLabel);
        review.setRouteReason(routeReason);
        review.setStatus("pending");
        review.setCreatedAt(LocalDateTime.now());
        conflictReviewMapper.insert(review);
        return review.getId();
    }

    public List<ConflictReviewDO> listPending(Long scopeId) {
        return conflictReviewMapper.selectList(
            new LambdaQueryWrapper<ConflictReviewDO>()
                .eq(ConflictReviewDO::getScopeId, scopeId)
                .eq(ConflictReviewDO::getStatus, "pending")
                .orderByDesc(ConflictReviewDO::getCreatedAt)
        );
    }

    public int countPending(Long scopeId) {
        return Math.toIntExact(conflictReviewMapper.selectCount(
            new LambdaQueryWrapper<ConflictReviewDO>()
                .eq(ConflictReviewDO::getScopeId, scopeId)
                .eq(ConflictReviewDO::getStatus, "pending")
        ));
    }

    public ConflictReviewDO executeRuling(Long reviewId, Long userId, String action, String detail) {
        ConflictReviewDO review = conflictReviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(ErrorCode.CONFLICT_REVIEW_NOT_FOUND);
        }
        if (!"pending".equals(review.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT_ALREADY_PROCESSED);
        }

        review.setRulingAction(action);
        review.setRulingDetail(detail);
        review.setDecidedBy(userId);
        review.setDecidedAt(LocalDateTime.now());

        switch (action) {
            case "merge" -> executeMerge(review);
            case "coexist" -> executeCoexist(review);
            case "choose_a" -> executeChoose(review, review.getFromPageId(), review.getToPageId());
            case "choose_b" -> executeChoose(review, review.getToPageId(), review.getFromPageId());
            default -> throw new BusinessException(ErrorCode.CONFLICT_UNSUPPORTED_ACTION, action);
        }

        if (review.getExecutionError() != null && !review.getExecutionError().isBlank()) {
            review.setStatus("failed");
        } else {
            review.setStatus("executed");
        }
        review.setExecutedAt(LocalDateTime.now());
        conflictReviewMapper.updateById(review);

        updateLinkedFinding(review);
        return review;
    }

    public void cancelRuling(Long reviewId) {
        ConflictReviewDO review = conflictReviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(ErrorCode.CONFLICT_REVIEW_NOT_FOUND);
        }
        if (!"pending".equals(review.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT_ALREADY_PROCESSED);
        }
        review.setStatus("cancelled");
        review.setRulingAction("coexist");
        review.setRulingDetail("暂不处理，暂时并存");
        review.setDecidedAt(LocalDateTime.now());
        conflictReviewMapper.updateById(review);

        resolveConflictWarningToCoexist(review);
    }

    private void resolveConflictWarningToCoexist(ConflictReviewDO review) {
        try {
            WikiPageDO fromPage = wikiPageMapper.selectById(review.getFromPageId());
            WikiPageDO toPage = wikiPageMapper.selectById(review.getToPageId());
            if (fromPage != null && "conflict-warning".equals(fromPage.getHealthStatus())) {
                fromPage.setHealthStatus("healthy");
                wikiPageMapper.updateById(fromPage);
            }
            if (toPage != null && "conflict-warning".equals(toPage.getHealthStatus())) {
                toPage.setHealthStatus("healthy");
                wikiPageMapper.updateById(toPage);
            }
        } catch (Exception e) {
            log.warn("resolveConflictWarningToCoexist failed for reviewId={}: {}", review.getId(), e.getMessage());
        }
    }

    public ConflictReviewDO findPendingByPagePair(Long scopeId, Long fromPageId, Long toPageId) {
        return conflictReviewMapper.selectOne(
            new LambdaQueryWrapper<ConflictReviewDO>()
                .eq(ConflictReviewDO::getScopeId, scopeId)
                .eq(ConflictReviewDO::getFromPageId, fromPageId)
                .eq(ConflictReviewDO::getToPageId, toPageId)
                .eq(ConflictReviewDO::getStatus, "pending")
                .last("LIMIT 1"));
    }

    public void findAndCancelByPagePair(Long scopeId, Long fromPageId, Long toPageId) {
        ConflictReviewDO review = findPendingByPagePair(scopeId, fromPageId, toPageId);
        if (review == null) {
            review = findPendingByPagePair(scopeId, toPageId, fromPageId);
        }
        if (review != null) {
            review.setStatus("cancelled");
            review.setRulingAction("coexist");
            review.setRulingDetail("从Lint体检页面驳回，自动取消");
            review.setDecidedAt(LocalDateTime.now());
            conflictReviewMapper.updateById(review);
            resolveConflictWarningToCoexist(review);
            log.info("Cancelled ConflictReview id={} via Lint finding dismissal", review.getId());
        }
    }

    private void updateLinkedFinding(ConflictReviewDO review) {
        if (lintFindingService == null) return;
        try {
            LintFindingDO finding = findLinkedConflictFinding(review);
            if (finding == null) return;

            if ("executed".equals(review.getStatus())) {
                lintFindingService.autoResolve(finding.getId(), "ruling_brief");
            } else if ("failed".equals(review.getStatus())) {
                lintFindingService.markAsFailed(finding.getId(), review.getExecutionError());
            } else if ("cancelled".equals(review.getStatus())) {
                lintFindingService.dismissFinding(finding.getId());
            }
            log.info("Updated linked lint_finding id={} to match review id={} status={}",
                finding.getId(), review.getId(), review.getStatus());
        } catch (Exception e) {
            log.warn("updateLinkedFinding failed for reviewId={}: {}", review.getId(), e.getMessage());
        }
    }

    private LintFindingDO findLinkedConflictFinding(ConflictReviewDO review) {
        List<LintFindingDO> candidates = lintFindingService.listFindings(
            review.getScopeId(), "conflict", null, null);
        for (LintFindingDO f : candidates) {
            if (isResolvable(f) && isPagePairMatch(f, review.getFromPageId(), review.getToPageId())) {
                return f;
            }
        }
        for (LintFindingDO f : candidates) {
            if (isResolvable(f) && isPageIdMatch(f, review.getFromPageId(), review.getToPageId())) {
                return f;
            }
        }
        return null;
    }

    private boolean isResolvable(LintFindingDO f) {
        return f.getStatus() != null
            && java.util.Set.of("open", "awaiting_approval").contains(f.getStatus());
    }

    private boolean isPagePairMatch(LintFindingDO f, Long pageA, Long pageB) {
        if (f.getAssetId() == null) return false;
        if (!f.getAssetId().equals(pageA) && !f.getAssetId().equals(pageB)) return false;
        Long relatedId = extractRelatedPageIdFromExtra(f.getExtra());
        if (relatedId == null) return false;
        return (f.getAssetId().equals(pageA) && relatedId.equals(pageB))
            || (f.getAssetId().equals(pageB) && relatedId.equals(pageA));
    }

    private boolean isPageIdMatch(LintFindingDO f, Long pageA, Long pageB) {
        return f.getAssetId() != null
            && (f.getAssetId().equals(pageA) || f.getAssetId().equals(pageB));
    }

    private Long extractRelatedPageIdFromExtra(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) return null;
        try {
            var extra = new com.fasterxml.jackson.databind.ObjectMapper().readValue(extraJson, java.util.Map.class);
            Object id = extra.get("relatedPageId");
            if (id instanceof Number num) return num.longValue();
        } catch (Exception ignore) {}
        return null;
    }

    private void executeMerge(ConflictReviewDO review) {
        WikiPageDO fromPage = wikiPageMapper.selectById(review.getFromPageId());
        WikiPageDO toPage = wikiPageMapper.selectById(review.getToPageId());
        if (fromPage == null || toPage == null) {
            throw new BusinessException(ErrorCode.CONFLICT_PAGE_NOT_FOUND);
        }

        if (chatClient == null || !chatClient.isAvailable()) {
            throw new BusinessException(ErrorCode.CONFLICT_AI_UNAVAILABLE);
        }

        String fromContent = readPageContent(review.getScopeId(), fromPage.getFilePath());
        String toContent = readPageContent(review.getScopeId(), toPage.getFilePath());

        boolean isDuplication = "content_duplication".equals(review.getConflictType());
        String strategyInstruction = isDuplication
            ? "【冲突解决策略：人工裁决 - 去重整合】两份内容高度相似，请整合为一份完整、精炼的知识。消除重复段落，保留双方的独有补充信息，确保不丢失任何有价值的内容。"
            : "【冲突解决策略：人工裁决 - 合并】将两份冲突内容合并为一份完整、一致的知识。保留双方有效信息，消除矛盾。";

        String rewritePrompt = schemaInjector.prependForLint(review.getScopeId(),
            PromptTemplate.knowledgeNormalizationPrinciple() + "\n\n---\n\n"
                + strategyInstruction + "\n\n"
                + "冲突类型：" + (review.getConflictType() != null ? review.getConflictType() : "未分类") + "\n\n"
                + "【页面A：" + fromPage.getTitle() + "】\n" + truncate(fromContent, 8000) + "\n\n"
                + "【页面B：" + toPage.getTitle() + "】\n" + truncate(toContent, 8000) + "\n\n"
                + "请合并为一份完整内容，以页面B为基础框架整合页面A的独有信息。");

        boolean acquired = llmConcurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 60_000);
        if (!acquired) {
            throw new BusinessException(ErrorCode.CONFLICT_AI_CONCURRENCY);
        }

        try {
            String merged = chatClient.chat(rewritePrompt);
            merged = stripMarkdownFences(merged);

            storageProvider.write(String.valueOf(review.getScopeId()),
                "wiki/" + toPage.getFilePath(),
                merged.getBytes(StandardCharsets.UTF_8));

            toPage.setHealthStatus("healthy");
            toPage.setContentUpdatedAt(LocalDateTime.now());
            wikiPageMapper.updateById(toPage);

            migrateSourceRelations(review.getScopeId(), fromPage.getId(), toPage.getId());

            searchService.indexPage(review.getScopeId(), toPage.getId(), toPage.getTitle(),
                toPage.getFilePath(), toPage.getCategory(), toPage.getSummary(),
                merged, "healthy", toPage.getVisibility(),
                toPage.getLifecycleStatus());

            fromPage.setHealthStatus("merged-into-" + toPage.getId());
            fromPage.setVisibility("private");
            fromPage.setLifecycleStatus(PageLifecycle.MERGED.name());
            fromPage.setMergedIntoPageId(toPage.getId());
            wikiPageMapper.updateById(fromPage);

            searchService.removePage(review.getScopeId(), fromPage.getId());

            removeContradictionLink(review.getScopeId(), fromPage.getId(), toPage.getId());

            log.info("Conflict ruling merge executed: {} → {}", fromPage.getTitle(), toPage.getTitle());
        } catch (Exception e) {
            review.setExecutionError(e.getMessage());
            log.warn("Conflict ruling merge failed: {}", e.getMessage());
        } finally {
            llmConcurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
        }
    }

    private void executeCoexist(ConflictReviewDO review) {
        WikiPageDO fromPage = wikiPageMapper.selectById(review.getFromPageId());
        WikiPageDO toPage = wikiPageMapper.selectById(review.getToPageId());
        if (fromPage == null || toPage == null) {
            throw new BusinessException(ErrorCode.CONFLICT_PAGE_NOT_FOUND);
        }

        fromPage.setHealthStatus("healthy");
        wikiPageMapper.updateById(fromPage);
        toPage.setHealthStatus("healthy");
        wikiPageMapper.updateById(toPage);

        log.info("Conflict ruling coexist: {} ↔ {}", fromPage.getTitle(), toPage.getTitle());
    }

    private void executeChoose(ConflictReviewDO review, Long keptPageId, Long discardedPageId) {
        WikiPageDO keptPage = wikiPageMapper.selectById(keptPageId);
        WikiPageDO discardedPage = wikiPageMapper.selectById(discardedPageId);
        if (keptPage == null || discardedPage == null) {
            throw new BusinessException(ErrorCode.CONFLICT_PAGE_NOT_FOUND);
        }

        keptPage.setHealthStatus("healthy");
        keptPage.setContentUpdatedAt(LocalDateTime.now());
        wikiPageMapper.updateById(keptPage);

        discardedPage.setHealthStatus("archived");
        discardedPage.setVisibility("private");
        wikiPageMapper.updateById(discardedPage);

        searchService.removePage(review.getScopeId(), discardedPageId);
        migrateSourceRelations(review.getScopeId(), discardedPageId, keptPageId);

        removeContradictionLink(review.getScopeId(), keptPageId, discardedPageId);

        log.info("Conflict ruling choose: kept={}, discarded={}", keptPage.getTitle(), discardedPage.getTitle());
    }

    private void migrateSourceRelations(Long scopeId, Long fromPageId, Long toPageId) {
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

    private void removeContradictionLink(Long scopeId, Long pageAId, Long pageBId) {
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

    private String readPageContent(Long scopeId, String filePath) {
        if (filePath == null || filePath.isBlank()) return "";
        try {
            byte[] bytes = storageProvider.read(String.valueOf(scopeId), "wiki/" + filePath);
            if (bytes != null) return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("readPageContent failed: scopeId={}, path={}", scopeId, filePath);
        }
        return "";
    }

    private String stripMarkdownFences(String text) {
        if (text == null) return "";
        text = text.trim();
        if (text.startsWith("```markdown")) text = text.substring(11);
        else if (text.startsWith("```md")) text = text.substring(5);
        else if (text.startsWith("```")) text = text.substring(3);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        return text.trim();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
