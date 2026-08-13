package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaPatchModel;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictRoutingService;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ContentDuplicateDetector;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.AsyncSchemaPatchService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaSection6Parser;
import org.cn.liuwt.llmwiki.domain.service.harness.LinkWritingService;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class CompletionAgent {

    private static final Logger log = LoggerFactory.getLogger(CompletionAgent.class);

    @Autowired
    private IndexerAgent indexerAgent;

    @Autowired
    private WriterAgent writerAgent;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private ConflictRoutingService conflictRoutingService;

    @Autowired
    private ContentDuplicateDetector contentDuplicateDetector;

    @Autowired
    private SchemaSection6Parser schemaSection6Parser;

    @Autowired
    private AsyncSchemaPatchService asyncSchemaPatchService;

    @Autowired
    private LinkWritingService linkWritingService;

    @Autowired(required = false)
    private ConsistencyReconciler consistencyReconciler;

    @Autowired
    private SearchService searchService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public int process(IngestContext context) {
        Long scopeId = context.getScopeId();
        int tokensUsed = 0;

        CompletableFuture<Integer> linksFuture = ensureLinksGenerationStarted(context);

        if (context.isWriterPostChecksDone()) {
            joinReconcilerFuture(context);
        } else {
            runComplianceCheck(context);
            tokensUsed += runConsistencyReconciliation(context);
            runQualityVerification(context);
        }
        if (!context.isBulkIndexed()) {
            reSyncIndex(context);
        }

        tokensUsed += waitForLinks(linksFuture);

        syncContentLinks(context);

        List<CompletableFuture<Void>> duplicateFutures = detectContentDuplicates(context);
        List<CompletableFuture<Void>> conflictFutures = dispatchConflictRouting(scopeId, context);

        proposeSchemaPatch(context);
        proposeComplianceSchemaPatches(context);

        updateSourceStatus(context.getSourceId());

        for (CompletableFuture<Void> cf : conflictFutures) {
            try {
                cf.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("Conflict routing future timeout: {}", e.getMessage());
            }
        }
        for (CompletableFuture<Void> cf : duplicateFutures) {
            try {
                cf.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("Duplicate detection future timeout: {}", e.getMessage());
            }
        }

        return tokensUsed;
    }

    private CompletableFuture<Integer> ensureLinksGenerationStarted(IngestContext context) {
        if (context.getLinksFuture() != null) {
            return context.getLinksFuture();
        }
        CompletableFuture<Integer> future = indexerAgent.startLinksGeneration(context);
        context.setLinksFuture(future);
        return future;
    }

    private void joinReconcilerFuture(IngestContext context) {
        CompletableFuture<Void> future = context.getReconcilerFuture();
        if (future == null) {
            return;
        }
        try {
            future.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("CompletionAgent: waiting for async reconciliation failed: {}", e.getMessage());
        }
    }

    private void runComplianceCheck(IngestContext context) {
        try {
            if (context.getWritingPlanJson() == null || context.getWritingPlanJson().isBlank()) {
                return;
            }
            WritingPlanComplianceChecker checker = new WritingPlanComplianceChecker(storageProvider);
            WritingPlanComplianceChecker.ComplianceReport report = checker.check(context);
            if (report.hasIssues()) {
                log.info("CompletionAgent: compliance check: {}. scopeId={}", report.summarize(), context.getScopeId());
            }
        } catch (Exception e) {
            log.warn("CompletionAgent: compliance check failed (non-blocking): {}", e.getMessage());
        }
    }

    private int runConsistencyReconciliation(IngestContext context) {
        try {
            if (consistencyReconciler == null) {
                return 0;
            }
            int totalPages = countTotalPages(context);
            if (totalPages < 2) {
                return 0;
            }
            ConsistencyReconciler.ConsistencyReport report = consistencyReconciler.reconcile(context);
            if (report.hasIssues()) {
                log.info("CompletionAgent: consistency reconciliation: {}. scopeId={}", report.summarize(), context.getScopeId());
            }
            return 0;
        } catch (Exception e) {
            log.warn("CompletionAgent: consistency reconciliation failed (non-blocking): {}", e.getMessage());
            return 0;
        }
    }

    private void runQualityVerification(IngestContext context) {
        try {
            if (context.getEntityDossiers() == null || context.getEntityDossiers().isEmpty()) {
                return;
            }
            WriterQualityVerifier verifier = new WriterQualityVerifier(storageProvider);
            WriterQualityVerifier.VerificationReport report = verifier.verify(context);
            context.setVerificationReport(report);
            if (report.hasCriticalIssues()) {
                log.warn("CompletionAgent: quality verification found {} critical issues. scopeId={}",
                    report.criticalCount(), context.getScopeId());
            }
        } catch (Exception e) {
            log.warn("CompletionAgent: quality verification failed (non-blocking): {}", e.getMessage());
        }
    }

    private void reSyncIndex(IngestContext context) {
        try {
            writerAgent.reSyncToIndex(context);
        } catch (Exception e) {
            log.warn("CompletionAgent: re-index failed (non-blocking): {}", e.getMessage());
        }
    }

    private int waitForLinks(CompletableFuture<Integer> linksFuture) {
        try {
            return linksFuture.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("CompletionAgent: link generation wait failed: {}", e.getMessage());
            return 0;
        }
    }

    private void syncContentLinks(IngestContext context) {
        try {
            Map<Long, String> pageIdToContent = new HashMap<>();
            Map<String, String> pageContents = context.getPageContents();
            if (context.getSummaryPage() != null && context.getSummaryPage().getId() != null) {
                String path = context.getSummaryPage().getFilePath();
                if (pageContents.containsKey(path)) {
                    pageIdToContent.put(context.getSummaryPage().getId(), pageContents.get(path));
                }
            }
            if (context.getEntityPages() != null) {
                for (var entry : context.getEntityPages().entrySet()) {
                    WikiPageDO page = entry.getValue();
                    if (page != null && page.getId() != null && pageContents.containsKey(entry.getKey())) {
                        pageIdToContent.put(page.getId(), pageContents.get(entry.getKey()));
                    }
                }
            }
            if (context.getUpdatedPages() != null) {
                for (var entry : context.getUpdatedPages().entrySet()) {
                    WikiPageDO page = entry.getValue();
                    if (page != null && page.getId() != null && pageContents.containsKey(entry.getKey())) {
                        pageIdToContent.put(page.getId(), pageContents.get(entry.getKey()));
                    }
                }
            }
            if (context.getChapterPages() != null) {
                for (var entry : context.getChapterPages().entrySet()) {
                    WikiPageDO page = entry.getValue();
                    if (page != null && page.getId() != null && pageContents.containsKey(entry.getKey())) {
                        pageIdToContent.put(page.getId(), pageContents.get(entry.getKey()));
                    }
                }
            }
            if (!pageIdToContent.isEmpty()) {
                linkWritingService.syncContentLinks(context.getScopeId(), pageIdToContent, context.getExecutionId());
            }
        } catch (Exception e) {
            log.warn("CompletionAgent: syncContentLinks failed (non-blocking): {}", e.getMessage());
        }
    }

    private List<CompletableFuture<Void>> detectContentDuplicates(IngestContext context) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        try {
            List<ContentDuplicateDetector.DuplicatePair> duplicates =
                contentDuplicateDetector.detect(context.getScopeId(), context);
            for (ContentDuplicateDetector.DuplicatePair dup : duplicates) {
                CompletableFuture<Void> future = conflictRoutingService.dispatchDuplicateConflict(
                    context.getScopeId(), context.getExecutionId(),
                    dup.pageA(), dup.pageB(), dup.similarity());
                futures.add(future);
                context.incrementConflictRoute("review");
            }
            if (!duplicates.isEmpty()) {
                log.info("CompletionAgent: detected {} content duplicates via deterministic check. scopeId={}",
                    duplicates.size(), context.getScopeId());
            }
        } catch (Exception e) {
            log.warn("CompletionAgent: content duplicate detection failed (non-blocking): {}", e.getMessage());
        }
        return futures;
    }

    private List<CompletableFuture<Void>> dispatchConflictRouting(Long scopeId, IngestContext context) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<IngestContext.ConflictAnnotation> annotations = context.getConflictAnnotations();
        if (annotations == null || annotations.isEmpty()) {
            return futures;
        }

        LintRulesConfig rulesConfig = schemaSection6Parser.parse(scopeId);

        for (IngestContext.ConflictAnnotation ann : annotations) {
            String pagePath = ann.pagePath();
            if (pagePath == null || pagePath.isBlank()) continue;

            WikiPageDO existingPage = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getFilePath, pagePath)
            );
            if (existingPage == null) {
                log.debug("dispatchConflictRouting: page not found for path={}", pagePath);
                continue;
            }

            WikiPageDO newPageDO = context.getSummaryPage();
            if (newPageDO == null) {
                String summaryPath = generatePagePath(context.getMetadataJson());
                newPageDO = wikiPageMapper.selectOne(
                    new LambdaQueryWrapper<WikiPageDO>()
                        .eq(WikiPageDO::getScopeId, scopeId)
                        .eq(WikiPageDO::getFilePath, summaryPath)
                );
            }
            if (newPageDO == null) continue;

            String category = existingPage.getCategory();
            String aiHint = ann.resolution();
            ConflictRoutingService.ConflictRoute route = conflictRoutingService.route(
                category, aiHint, rulesConfig);

            CompletableFuture<Void> future = conflictRoutingService.dispatchIngestConflict(
                scopeId, context.getExecutionId(), newPageDO, existingPage,
                ann.conflictType(), route);
            futures.add(future);

            context.incrementConflictRoute(route.autoLevel().name().toLowerCase());

            log.info("Ingest conflict routed: {} <-> {}, strategy={}, autoLevel={}",
                newPageDO.getTitle(), existingPage.getTitle(),
                route.strategy().getKey(), route.autoLevel());
        }

        return futures;
    }

    private void proposeSchemaPatch(IngestContext context) {
        try {
            String patchSummary = buildPatchSummary(context.getMetadataJson(), context);
            asyncSchemaPatchService.proposeAsync(
                context.getScopeId(), context.getExecutionId(),
                SchemaPatchModel.SourceType.INGEST, patchSummary
            );
        } catch (Exception e) {
            log.warn("CompletionAgent: schema patch proposal failed: {}", e.getMessage());
        }
    }

    private void proposeComplianceSchemaPatches(IngestContext context) {
        ComplianceResult planResult = context.getPlanComplianceResult();
        if (planResult != null && planResult.requiresReview()) {
            asyncSchemaPatchService.proposeAsync(context.getScopeId(), context.getExecutionId(),
                SchemaPatchModel.SourceType.INGEST, planResult.summarize());
        }
        ComplianceResult productResult = context.getProductComplianceResult();
        if (productResult != null && productResult.requiresReview()) {
            asyncSchemaPatchService.proposeAsync(context.getScopeId(), context.getExecutionId(),
                SchemaPatchModel.SourceType.INGEST, productResult.summarize());
        }
    }

    private void updateSourceStatus(Long sourceId) {
        SourceDO sourceDO = sourceMapper.selectById(sourceId);
        if (sourceDO != null) {
            sourceDO.setStatus("processed");
            sourceMapper.updateById(sourceDO);
        }
    }

    private String buildPatchSummary(String metadataJson, IngestContext context) {
        StringBuilder summary = new StringBuilder();
        summary.append("【新建主摘要】\n").append(metadataJson == null ? "(无)" : metadataJson).append("\n\n");

        Map<String, String> pageContents = context.getPageContents();
        if (!pageContents.isEmpty()) {
            String summaryPath = generatePagePath(metadataJson);
            String summaryContent = pageContents.get(summaryPath);
            if (summaryContent != null && !summaryContent.isBlank()) {
                summary.append("【主摘要正文片段】\n")
                    .append(summaryContent.length() > 1500 ? summaryContent.substring(0, 1500) + "..." : summaryContent)
                    .append("\n\n");
            }

            List<String> otherKeys = pageContents.keySet().stream()
                .filter(k -> !k.equals(summaryPath))
                .toList();
            if (!otherKeys.isEmpty()) {
                summary.append("【关联页面更新摘要】（").append(otherKeys.size()).append(" 个页面）\n");
                for (String key : otherKeys) {
                    String content = pageContents.get(key);
                    if (content != null && !content.isBlank()) {
                        summary.append("--- ").append(key).append(" ---\n")
                            .append(content.length() > 800 ? content.substring(0, 800) + "..." : content)
                            .append("\n");
                    }
                }
            }
        }
        return summary.toString();
    }

    private String generatePagePath(String metadataJson) {
        String title = extractJsonField(metadataJson, "title");
        if (title == null || title.isEmpty()) title = "untitled";
        String normalized = title.toLowerCase();
        String sanitized = normalized.replaceAll("[^a-z0-9\\u4e00-\\u9fff_-]", "-");
        sanitized = sanitized.replaceAll("-+", "-");
        if (sanitized.startsWith("-")) sanitized = sanitized.substring(1);
        if (sanitized.endsWith("-")) sanitized = sanitized.substring(0, sanitized.length() - 1);
        if (sanitized.isEmpty()) sanitized = "untitled";
        return "pages/" + sanitized + ".md";
    }

    private String extractJsonField(String json, String field) {
        if (json == null || json.isEmpty()) return "";
        try {
            int start = json.indexOf("{");
            int end = json.lastIndexOf("}") + 1;
            String cleanJson = (start >= 0 && end > start) ? json.substring(start, end) : json;
            JsonNode node = objectMapper.readTree(cleanJson);
            if (node.has(field)) return node.get(field).asText();
        } catch (Exception e) {
            log.debug("extractJsonField failed for '{}': {}", field, e.getMessage());
        }
        return "";
    }

    private int countTotalPages(IngestContext context) {
        int count = 0;
        if (context.getSummaryPage() != null) count++;
        if (context.getEntityPages() != null) count += context.getEntityPages().size();
        if (context.getChapterPages() != null) count += context.getChapterPages().size();
        if (context.getUpdatedPages() != null) count += context.getUpdatedPages().size();
        return count;
    }
}
