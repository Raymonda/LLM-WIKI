package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageTagDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageKeywordDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageTagMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageKeywordMapper;
import org.cn.liuwt.llmwiki.common.dal.helper.ActivePageScope;
import org.cn.liuwt.llmwiki.common.util.constant.PageLifecycle;
import org.cn.liuwt.llmwiki.domain.service.harness.LinkWritingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.DocumentChunker;
import org.cn.liuwt.llmwiki.domain.service.harness.DocumentStructureAnalyzer;
import org.cn.liuwt.llmwiki.domain.service.harness.ExecutionStrategy;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.domain.service.wiki.CategoryNormalizer;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
public class WriterAgent {

    private static final Logger log = LoggerFactory.getLogger(WriterAgent.class);

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private SchemaComplianceChecker schemaComplianceChecker;

    @Autowired
    private CategoryNormalizer categoryNormalizer;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Autowired
    private WikiPageTagMapper wikiPageTagMapper;

    @Autowired
    private WikiPageKeywordMapper wikiPageKeywordMapper;

    @Autowired
    private WikiFileServiceImpl wikiFileService;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private org.cn.liuwt.llmwiki.domain.service.search.SearchService searchService;

    @Autowired
    private org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService lintFindingService;

    @Autowired
    private LlmConcurrencyBarrier llmBarrier;

    @Autowired
    private LinkWritingService linkWritingService;

    @Autowired
    private IndexerAgent indexerAgent;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llmwiki.writer.pool.core-size:6}")
    private int writerPoolCoreSize;

    @Value("${llmwiki.writer.pool.max-size:16}")
    private int writerPoolMaxSize;

    @Value("${llmwiki.writer.parallel.base-timeout-seconds:180}")
    private int parallelBaseTimeoutSeconds;

    @Value("${llmwiki.writer.parallel.per-task-timeout-seconds:150}")
    private int parallelPerTaskTimeoutSeconds;

    @Value("${llmwiki.llm.barrier.acquire-timeout-ms:120000}")
    private long barrierAcquireTimeoutMs;

    private final ThreadPoolExecutor writerExecutor = new ThreadPoolExecutor(
        6, 16, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(200),
        r -> new Thread(r, "writer-" + r.hashCode())
    );

    @PostConstruct
    public void initExecutor() {
        if (writerPoolCoreSize != 6) {
            writerExecutor.setCorePoolSize(writerPoolCoreSize);
        }
        if (writerPoolMaxSize != 16) {
            writerExecutor.setMaximumPoolSize(writerPoolMaxSize);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down writerExecutor");
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                writerExecutor.shutdownNow();
                if (!writerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("writerExecutor did not reach quiescence after shutdownNow");
                }
            }
        } catch (InterruptedException e) {
            writerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public int write(IngestContext context) {
        if (chatClient == null || !chatClient.isAvailable()) {
            return 0;
        }
        checkInterrupted();

        context.setOriginalSourceContent(context.getSourceContent());

        String imageManifest = context.buildImageManifest();
        if (!imageManifest.isEmpty()) {
            String enriched = context.getSourceContent() + "\n\n---\n\n" + imageManifest;
            context.setSourceContent(enriched);
            log.info("Image manifest injected into sourceContent: {} charts + {} extracted images, {} chars",
                context.getChartInfos() != null ? context.getChartInfos().size() : 0,
                context.getExtractedImages() != null ? context.getExtractedImages().size() : 0,
                imageManifest.length());
        }

        ExecutionStrategy strategy = context.getStrategy();
        boolean useChapterMode = false;
        if (strategy != null) {
            useChapterMode = strategy.isUseChapterMode()
                && context.getChapters() != null && context.getChapters().size() >= 3;
        } else {
            useChapterMode = context.getDocumentType() == DocumentStructureAnalyzer.DocumentType.STRUCTURED
                && context.getChapters() != null && context.getChapters().size() >= 3;
        }

        if (useChapterMode) {
            log.info("Using chapter-based compilation mode: preset={}, {} chapters for scopeId={}",
                strategy != null ? strategy.getPreset().name() : "auto",
                context.getChapters().size(), context.getScopeId());
            return writeChapterBased(context);
        }

        return writeEntityBased(context);
    }

    private int writeEntityBased(IngestContext context) {
        Long scopeId = context.getScopeId();
        Long sourceId = context.getSourceId();
        String scopeIdStr = String.valueOf(scopeId);
        int tokensUsed = 0;
        TokenUsageContext.Context parentCtx = TokenUsageContext.get();

        Map<String, String> contentCollector = new ConcurrentHashMap<>();
        String analysisResult = context.getMergedAnalysis();
        String metadataJson = categoryNormalizer.normalizeInMetadataJson(scopeId, context.getMetadataJson());
        context.setMetadataJson(metadataJson);
        String schemaPageTemplate = schemaComplianceChecker.hasSchemaPageTemplate(scopeId) ? "has_template" : null;
        String sourceContent = context.getSourceContent();
        ExecutionStrategy strategy = context.getStrategy();
        int maxSummaryChars = strategy != null ? strategy.getMaxSourceCharsSummary() : PromptTemplate.MAX_SOURCE_CHARS_SUMMARY;
        int maxRelatedChars = strategy != null ? strategy.getMaxSourceCharsRelated() : PromptTemplate.MAX_SOURCE_CHARS_RELATED;

        CompletableFuture<Void> chunkMapFuture = CompletableFuture.runAsync(
            () -> buildEntityChunkMap(context), writerExecutor);

        CompletableFuture<Set<String>> relatedPreloadFuture = CompletableFuture.supplyAsync(
            () -> findSourceRelatedPagePaths(scopeId, sourceId), writerExecutor);

        List<Map<String, String>> entities = parseEntities(metadataJson);
        try { chunkMapFuture.get(30, TimeUnit.SECONDS); } catch (Exception e) {
            log.warn("buildEntityChunkMap async failed, falling back to synchronous: {}", e.getMessage());
            buildEntityChunkMap(context);
        }
        ClassificationResult classified = classifyEntities(entities, context);
        List<String> plannedEntityNames = classified.coreAndImportant().stream()
            .map(e -> e.get("name")).filter(n -> n != null && !n.isBlank()).toList();

        String writingPlanJson = generateWritingPlan(scopeId, sourceContent, analysisResult, metadataJson, context.getPagesContext(), null, plannedEntityNames, context);
        if (writingPlanJson == null || writingPlanJson.isEmpty()) {
            log.warn("WritingPlan generation failed, falling back to serial execution");
            return writeSerial(context);
        }
        context.setWritingPlanJson(writingPlanJson);
        tokensUsed += estimateTokens(writingPlanJson);

        List<IngestContext.ConflictAnnotation> conflictAnnotations = extractConflictMeta(writingPlanJson);
        processConflictAnnotations(scopeId, context, conflictAnnotations);

        ComplianceResult planResult = schemaComplianceChecker.checkPlan(scopeId, writingPlanJson, metadataJson);
        context.setPlanComplianceResult(planResult);
        if (!context.isSkipComplianceCheck() && planResult != null && planResult.requiresReview()) {
            log.warn("WritingPlan Schema compliance violations detected (will still proceed with writing) for scopeId={}: {}", scopeId, planResult.summarize());
        }

        Map<String, String> subPlans = parseSubPlans(writingPlanJson);

        List<String> entityNames = classified.coreAndImportant().stream().map(e -> e.get("name")).filter(n -> n != null).toList();
        String entitiesSummary = buildEntitiesSummaryForSummary(entityNames, classified.contextuals(), context);
        String summaryPlan = buildSummarySubPlan(subPlans, writingPlanJson);
        String summarySource = enrichSummarySourceWithCharts(
            PromptTemplate.sampleSourceContent(sourceContent, maxSummaryChars), context);
        String summaryPagePath = generatePagePath(metadataJson);
        String summaryAnalysisWithEntities = analysisResult + entitiesSummary;

        Set<String> entityPagePaths = new HashSet<>();
        for (Map<String, String> entity : classified.coreAndImportant()) {
            String name = entity.get("name");
            if (name != null && !name.isBlank()) {
                entityPagePaths.add(generatePagePath(name));
            }
        }
        entityPagePaths.add(summaryPagePath);

        try {
            List<CompletableFuture<Map.Entry<String, WikiPageDO>>> entityFutures = new ArrayList<>();
            for (Map<String, String> entity : classified.coreAndImportant()) {
                checkInterrupted();
                String entityName = entity.get("name");
                String entityType = entity.getOrDefault("type", "concept");
                if (entityName == null || entityName.isBlank()) continue;
                String entityPlan = buildEntitySubPlan(subPlans, entityName, writingPlanJson);
                String entitySource = buildEntitySourceFromChunks(entityName, entity, context);
                String entityPagePath = generatePagePath(entityName);
                entityFutures.add(CompletableFuture.supplyAsync(
                    () -> {
                        if (parentCtx != null) TokenUsageContext.set(parentCtx.scopeId(), parentCtx.operationType());
                        try {
                            return new AbstractMap.SimpleEntry<>(entityName,
                                writeEntityPage(scopeId, sourceId, scopeIdStr, entityName, entityType, entitySource, analysisResult, metadataJson, entityPlan, contentCollector, schemaPageTemplate, conflictAnnotations, entityPagePath, context));
                        } finally {
                            TokenUsageContext.clear();
                        }
                    },
                    writerExecutor
                ));
            }

            CompletableFuture<WikiPageDO> summaryFuture = CompletableFuture.supplyAsync(
                () -> {
                    if (parentCtx != null) TokenUsageContext.set(parentCtx.scopeId(), parentCtx.operationType());
                    try {
                        return writeSummaryPage(scopeId, sourceId, scopeIdStr, summarySource, summaryAnalysisWithEntities, metadataJson, summaryPlan, contentCollector, schemaPageTemplate, conflictAnnotations, summaryPagePath, context);
                    } finally {
                        TokenUsageContext.clear();
                    }
                },
                writerExecutor
            );

            Set<String> sourceRelatedPaths;
            try {
                sourceRelatedPaths = relatedPreloadFuture.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Preload related pages failed, fetching synchronously: {}", e.getMessage());
                sourceRelatedPaths = findSourceRelatedPagePaths(scopeId, sourceId);
            }
            List<Map<String, String>> affectedPages = mergeAffectedPagesWithSourceRelations(
                parseAffectedPages(metadataJson), sourceRelatedPaths);
            List<CompletableFuture<Map.Entry<String, WikiPageDO>>> relatedFutures = new ArrayList<>();
            for (Map<String, String> affected : affectedPages) {
                String affectedPath = affected.get("path");
                String action = affected.get("action");
                if (affectedPath == null || affectedPath.isBlank()) continue;
                if (entityPagePaths.contains(affectedPath)) {
                    log.info("Skipping affected page '{}' - already processed as entity/summary page", affectedPath);
                    continue;
                }
                if (!"更新".equals(action) && !"补充".equals(action)) continue;
                String relatedPlan = buildRelatedSubPlan(subPlans, affectedPath, writingPlanJson);
                String relatedSource = filterSourceContentForRelated(sourceContent, affectedPath, maxRelatedChars);
                relatedFutures.add(CompletableFuture.supplyAsync(
                    () -> {
                        if (parentCtx != null) TokenUsageContext.set(parentCtx.scopeId(), parentCtx.operationType());
                        try {
                            return new AbstractMap.SimpleEntry<>(affectedPath,
                                updateRelatedPage(scopeId, sourceId, scopeIdStr, affectedPath, action, relatedSource, analysisResult, metadataJson, relatedPlan, contentCollector, conflictAnnotations, context));
                        } finally {
                            TokenUsageContext.clear();
                        }
                    },
                    writerExecutor
                ));
            }

            List<CompletableFuture<?>> allFutures = new ArrayList<>();
            allFutures.add(summaryFuture);
            allFutures.addAll(entityFutures);
            allFutures.addAll(relatedFutures);
            long batchTimeoutSeconds = parallelBaseTimeoutSeconds + (long) parallelPerTaskTimeoutSeconds * allFutures.size();
            boolean batchCompleted = true;
            try {
                CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0]))
                    .get(batchTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                batchCompleted = false;
                log.error("Writer parallel batch exceeded adaptive timeout ({}s, {} tasks), harvesting completed pages",
                    batchTimeoutSeconds, allFutures.size());
            }

            for (CompletableFuture<Map.Entry<String, WikiPageDO>> f : entityFutures) {
                Map.Entry<String, WikiPageDO> entry = harvestFuture(f, batchCompleted);
                if (entry != null && entry.getValue() != null) {
                    context.getEntityPages().put(entry.getKey(), entry.getValue());
                    tokensUsed += estimateTokens(entry.getValue().getSummary());
                }
            }

            WikiPageDO summaryPage = harvestFuture(summaryFuture, batchCompleted);
            if (summaryPage != null) {
                context.setSummaryPage(summaryPage);
                tokensUsed += estimateTokens(summaryPage.getSummary());
            }

            for (CompletableFuture<Map.Entry<String, WikiPageDO>> f : relatedFutures) {
                Map.Entry<String, WikiPageDO> entry = harvestFuture(f, batchCompleted);
                if (entry != null && entry.getValue() != null) {
                    context.getUpdatedPages().put(entry.getKey(), entry.getValue());
                    tokensUsed += estimateTokens(entry.getValue().getSummary());
                }
            }

        } catch (Exception e) {
            log.error("Writer parallel execution failed, some pages may be incomplete", e);
        }

        verifySourceRelations(scopeId, sourceId, context);
        batchPersistTagsAndKeywords(scopeId, context, metadataJson);
        context.getPageContents().putAll(contentCollector);
        bulkSyncToIndex(scopeId, context);

        context.setLinksFuture(indexerAgent.startLinksGeneration(context, buildPageInventory(context)));

        ComplianceResult productResult = schemaComplianceChecker.check(
            scopeId, context.getMetadataJson(), contentCollector);
        context.setProductComplianceResult(productResult);
        if (productResult.hasViolations()) {
            log.warn("WriterAgent Schema compliance violations for scopeId={}: {}", scopeId, productResult.summarize());
        }

        return tokensUsed;
    }

    private <T> T harvestFuture(CompletableFuture<T> future, boolean batchCompleted) {
        try {
            if (batchCompleted) {
                return future.get();
            }
            return future.get(parallelPerTaskTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Writer task still incomplete after grace period ({}s), skipping collection", parallelPerTaskTimeoutSeconds);
            return null;
        } catch (Exception e) {
            log.warn("Writer task failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean acquireBarrier(LlmConcurrencyBarrier.Bucket bucket, String taskLabel) {
        if (llmBarrier.tryAcquire(bucket, barrierAcquireTimeoutMs)) {
            return true;
        }
        log.error("Barrier acquire timeout ({}ms) for bucket {} on '{}', retrying once", barrierAcquireTimeoutMs, bucket, taskLabel);
        if (llmBarrier.tryAcquire(bucket, barrierAcquireTimeoutMs)) {
            return true;
        }
        log.error("Barrier acquire failed after retry for bucket {} on '{}', page write skipped", bucket, taskLabel);
        return false;
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("Writer interrupted by cancellation");
        }
    }

    private int writeSerial(IngestContext context) {
        Long scopeId = context.getScopeId();
        Long sourceId = context.getSourceId();
        String scopeIdStr = String.valueOf(scopeId);
        int tokensUsed = 0;

        String analysisResult = context.getMergedAnalysis();
        String metadataJson = context.getMetadataJson();
        String schemaPageTemplate = schemaComplianceChecker.hasSchemaPageTemplate(context.getScopeId()) ? "has_template" : null;
        String sourceContent = context.getSourceContent();
        List<IngestContext.ConflictAnnotation> conflictAnnotations = context.getConflictAnnotations();
        ExecutionStrategy strategy = context.getStrategy();
        int maxSummaryChars = strategy != null ? strategy.getMaxSourceCharsSummary() : PromptTemplate.MAX_SOURCE_CHARS_SUMMARY;
        int maxRelatedChars = strategy != null ? strategy.getMaxSourceCharsRelated() : PromptTemplate.MAX_SOURCE_CHARS_RELATED;

        buildEntityChunkMap(context);

        Map<String, String> serialCollector = new ConcurrentHashMap<>();

        List<Map<String, String>> entities = parseEntities(metadataJson);
        ClassificationResult classified = classifyEntities(entities, context);
        for (Map<String, String> entity : classified.coreAndImportant()) {
            checkInterrupted();
            String entityName = entity.get("name");
            String entityType = entity.getOrDefault("type", "concept");
            if (entityName == null || entityName.isBlank()) continue;
            String entitySource = buildEntitySourceFromChunks(entityName, entity, context);
            String entityPagePath = generatePagePath(entityName);
            WikiPageDO entityPage = writeEntityPage(scopeId, sourceId, scopeIdStr, entityName, entityType, entitySource, analysisResult, metadataJson, null, serialCollector, schemaPageTemplate, conflictAnnotations, entityPagePath, context);
            if (entityPage != null) {
                context.getEntityPages().put(entityName, entityPage);
                tokensUsed += estimateTokens(entityPage.getSummary());
            }
        }

        List<String> entityNames = classified.coreAndImportant().stream().map(e -> e.get("name")).filter(n -> n != null).toList();
        String entitiesSummary = buildEntitiesSummaryForSummary(entityNames, classified.contextuals(), context);

        String summarySource = enrichSummarySourceWithCharts(
            PromptTemplate.sampleSourceContent(sourceContent, maxSummaryChars), context);
        String summaryPagePath = generatePagePath(metadataJson);
        String summaryAnalysisWithEntities = analysisResult + entitiesSummary;
        WikiPageDO summaryPage = writeSummaryPage(scopeId, sourceId, scopeIdStr, summarySource, summaryAnalysisWithEntities, metadataJson, null, serialCollector, schemaPageTemplate, conflictAnnotations, summaryPagePath, context);
        context.setSummaryPage(summaryPage);
        tokensUsed += estimateTokens(summaryPage != null ? summaryPage.getSummary() : "");

        Set<String> entityPagePaths = new HashSet<>();
        for (Map<String, String> entity : classified.coreAndImportant()) {
            String name = entity.get("name");
            if (name != null && !name.isBlank()) {
                entityPagePaths.add(generatePagePath(name));
            }
        }
        entityPagePaths.add(summaryPagePath);

        List<Map<String, String>> affectedPages = mergeAffectedPagesWithSourceRelations(
            parseAffectedPages(metadataJson),
            findSourceRelatedPagePaths(scopeId, sourceId)
        );
        for (Map<String, String> affected : affectedPages) {
            checkInterrupted();
            String affectedPath = affected.get("path");
            String action = affected.get("action");
            if (affectedPath == null || affectedPath.isBlank()) continue;
            if (entityPagePaths.contains(affectedPath)) {
                log.info("Skipping affected page '{}' (serial) - already processed as entity/summary page", affectedPath);
                continue;
            }
            if (!"更新".equals(action) && !"补充".equals(action)) continue;
            String relatedSource = filterSourceContentForRelated(sourceContent, affectedPath, maxRelatedChars);
            WikiPageDO updatedPage = updateRelatedPage(scopeId, sourceId, scopeIdStr, affectedPath, action, relatedSource, analysisResult, metadataJson, null, serialCollector, conflictAnnotations, context);
            if (updatedPage != null) {
                context.getUpdatedPages().put(affectedPath, updatedPage);
                tokensUsed += estimateTokens(updatedPage.getSummary());
            }
        }

        verifySourceRelations(scopeId, sourceId, context);
        batchPersistTagsAndKeywords(scopeId, context, metadataJson);
        context.getPageContents().putAll(serialCollector);
        bulkSyncToIndex(scopeId, context);

        ComplianceResult productResult = schemaComplianceChecker.check(
            scopeId, context.getMetadataJson(), serialCollector);
        context.setProductComplianceResult(productResult);
        if (productResult.hasViolations()) {
            log.warn("WriterAgent serial Schema compliance violations for scopeId={}: {}", scopeId, productResult.summarize());
        }

        return tokensUsed;
    }

    private int writeChapterBased(IngestContext context) {
        Long scopeId = context.getScopeId();
        Long sourceId = context.getSourceId();
        String scopeIdStr = String.valueOf(scopeId);
        int tokensUsed = 0;
        TokenUsageContext.Context parentCtx = TokenUsageContext.get();

        Map<String, String> contentCollector = new ConcurrentHashMap<>();
        String analysisResult = context.getMergedAnalysis();
        String metadataJson = categoryNormalizer.normalizeInMetadataJson(scopeId, context.getMetadataJson());
        context.setMetadataJson(metadataJson);
        String schemaPageTemplate = schemaComplianceChecker.hasSchemaPageTemplate(scopeId) ? "has_template" : null;
        String sourceContent = context.getSourceContent();
        int cmIdx = sourceContent.indexOf("\n\n---\n\n## 源文档图表清单");
        if (cmIdx >= 0) sourceContent = sourceContent.substring(0, cmIdx);
        List<DocumentStructureAnalyzer.Chapter> chapters = context.getChapters();
        ExecutionStrategy strategy = context.getStrategy();
        int maxSummaryChars = strategy != null ? strategy.getMaxSourceCharsSummary() : PromptTemplate.MAX_SOURCE_CHARS_SUMMARY;
        int maxRelatedChars = strategy != null ? strategy.getMaxSourceCharsRelated() : PromptTemplate.MAX_SOURCE_CHARS_RELATED;

        CompletableFuture<Void> chunkMapFuture = CompletableFuture.runAsync(
            () -> buildEntityChunkMap(context), writerExecutor);

        CompletableFuture<Set<String>> relatedPreloadFuture = CompletableFuture.supplyAsync(
            () -> findSourceRelatedPagePaths(scopeId, sourceId), writerExecutor);

        final List<DocumentStructureAnalyzer.Chapter> chaptersForBatch = chapters;
        CompletableFuture<Map<Integer, String>> batchSummariesFuture = CompletableFuture.supplyAsync(
            () -> batchGenerateReferenceSummaries(scopeId, chaptersForBatch), writerExecutor);

        String writingPlanJson = generateWritingPlan(scopeId, sourceContent, analysisResult, metadataJson, context.getPagesContext(), chapters, context);
        if (writingPlanJson == null || writingPlanJson.isEmpty()) {
            log.warn("WritingPlan generation failed for chapter-based mode, falling back to entity-based");
            return writeEntityBased(context);
        }
        context.setWritingPlanJson(writingPlanJson);
        tokensUsed += estimateTokens(writingPlanJson);

        List<IngestContext.ConflictAnnotation> conflictAnnotations = extractConflictMeta(writingPlanJson);
        processConflictAnnotations(scopeId, context, conflictAnnotations);

        ComplianceResult planResult = schemaComplianceChecker.checkPlan(scopeId, writingPlanJson, metadataJson);
        context.setPlanComplianceResult(planResult);

        Map<String, String> subPlans = parseSubPlans(writingPlanJson);

        List<Map<String, String>> entities = parseEntities(metadataJson);
        try { chunkMapFuture.get(30, TimeUnit.SECONDS); } catch (Exception e) {
            log.warn("buildEntityChunkMap async failed, falling back to synchronous: {}", e.getMessage());
            buildEntityChunkMap(context);
        }
        ClassificationResult classified = classifyEntities(entities, context);

        List<String> entityNames = classified.coreAndImportant().stream().map(e -> e.get("name")).filter(n -> n != null).toList();
        String entitiesSummary = buildEntitiesSummaryForSummary(entityNames, classified.contextuals(), context);
        String summaryPlan = buildSummarySubPlan(subPlans, writingPlanJson);
        String summarySource = enrichSummarySourceWithCharts(
            PromptTemplate.sampleSourceContent(sourceContent, maxSummaryChars), context);
        String summaryPagePath = generatePagePath(metadataJson);
        String summaryAnalysisWithEntities = analysisResult + entitiesSummary;

        int totalChapters = chapters.size();
        String docTitle = resolveDocTitle(extractJsonField(metadataJson, "title"), context);
        String docCategory = extractJsonField(metadataJson, "category");

        try {
            Map<Integer, String> batchSummaries;
            try {
                batchSummaries = batchSummariesFuture.get(120, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Reference summaries future failed, generating synchronously: {}", e.getMessage());
                batchSummaries = batchGenerateReferenceSummaries(scopeId, chapters);
            }
            if (!batchSummaries.isEmpty()) {
                tokensUsed += estimateTokens(batchSummaries.values().stream().collect(java.util.stream.Collectors.joining()));
            }

            List<CompletableFuture<Map.Entry<String, WikiPageDO>>> chapterFutures = new ArrayList<>();
            for (int ci = 0; ci < chapters.size(); ci++) {
                DocumentStructureAnalyzer.Chapter chapter = chapters.get(ci);
                int chapterIndex = ci;
                String chapterTitle = chapter.title();
                String chapterPlan = buildChapterSubPlan(subPlans, chapterTitle, writingPlanJson);
                String rawChapterSource = chapter.sourceContent() != null ? chapter.sourceContent() : "";
                String chapterSource = formatReferenceSourceContent(rawChapterSource, chapterTitle, context.getChartInfos());
                String chapterCategory = buildChapterCategory(docCategory, chapterTitle);
                String chapterPagePath = generateChapterPagePath(docTitle, chapterTitle, chapterIndex);
                String precomputedSummary = batchSummaries.get(chapterIndex);

                chapterFutures.add(CompletableFuture.supplyAsync(
                    () -> {
                        if (parentCtx != null) TokenUsageContext.set(parentCtx.scopeId(), parentCtx.operationType());
                        try {
                            return new AbstractMap.SimpleEntry<>(chapterTitle,
                                writeReferencePage(scopeId, sourceId, scopeIdStr, chapterTitle, chapterIndex,
                                    chapterSource, analysisResult, metadataJson, contentCollector,
                                    conflictAnnotations, chapterPagePath, chapterCategory, precomputedSummary, context));
                        } finally {
                            TokenUsageContext.clear();
                        }
                    },
                    writerExecutor
                ));
            }

            List<CompletableFuture<Map.Entry<String, WikiPageDO>>> entityFutures = new ArrayList<>();
            int maxEntities = strategy != null ? strategy.getMaxEntityPages() : 5;
            int maxEntityPagesInChapterMode = Math.min(classified.coreAndImportant().size(), maxEntities);
            for (int ei = 0; ei < maxEntityPagesInChapterMode; ei++) {
                Map<String, String> entity = classified.coreAndImportant().get(ei);
                String entityName = entity.get("name");
                String entityType = entity.getOrDefault("type", "concept");
                if (entityName == null || entityName.isBlank()) continue;
                String entityPlan = buildEntitySubPlan(subPlans, entityName, writingPlanJson);
                String entitySource = buildEntitySourceFromChunks(entityName, entity, context);
                String entityPagePath = generatePagePath(entityName);
                entityFutures.add(CompletableFuture.supplyAsync(
                    () -> {
                        if (parentCtx != null) TokenUsageContext.set(parentCtx.scopeId(), parentCtx.operationType());
                        try {
                            return new AbstractMap.SimpleEntry<>(entityName,
                                writeEntityPage(scopeId, sourceId, scopeIdStr, entityName, entityType, entitySource, analysisResult, metadataJson, entityPlan, contentCollector, schemaPageTemplate, conflictAnnotations, entityPagePath, context));
                        } finally {
                            TokenUsageContext.clear();
                        }
                    },
                    writerExecutor
                ));
            }

            CompletableFuture<WikiPageDO> summaryFuture = CompletableFuture.supplyAsync(
                () -> {
                    if (parentCtx != null) TokenUsageContext.set(parentCtx.scopeId(), parentCtx.operationType());
                    try {
                        return writeSummaryPage(scopeId, sourceId, scopeIdStr, summarySource, summaryAnalysisWithEntities, metadataJson, summaryPlan, contentCollector, schemaPageTemplate, conflictAnnotations, summaryPagePath, context);
                    } finally {
                        TokenUsageContext.clear();
                    }
                },
                writerExecutor
            );

            Set<String> selfManagedPaths = new HashSet<>();
            for (Map<String, String> entity : classified.coreAndImportant()) {
                String name = entity.get("name");
                if (name != null && !name.isBlank()) {
                    selfManagedPaths.add(generatePagePath(name));
                }
            }
            selfManagedPaths.add(summaryPagePath);
            for (int ci = 0; ci < chapters.size(); ci++) {
                selfManagedPaths.add(generateChapterPagePath(docTitle, chapters.get(ci).title(), ci));
            }

            Set<String> sourceRelatedPaths;
            try {
                sourceRelatedPaths = relatedPreloadFuture.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Preload related pages failed, fetching synchronously: {}", e.getMessage());
                sourceRelatedPaths = findSourceRelatedPagePaths(scopeId, sourceId);
            }
            List<Map<String, String>> affectedPages = mergeAffectedPagesWithSourceRelations(
                parseAffectedPages(metadataJson), sourceRelatedPaths);
            List<CompletableFuture<Map.Entry<String, WikiPageDO>>> relatedFutures = new ArrayList<>();
            for (Map<String, String> affected : affectedPages) {
                String affectedPath = affected.get("path");
                String action = affected.get("action");
                if (affectedPath == null || affectedPath.isBlank()) continue;
                if (selfManagedPaths.contains(affectedPath)) {
                    log.info("Skipping affected page '{}' (chapter-mode) - already processed as entity/chapter/summary page", affectedPath);
                    continue;
                }
                if (!"更新".equals(action) && !"补充".equals(action)) continue;
                String relatedPlan = buildRelatedSubPlan(subPlans, affectedPath, writingPlanJson);
                String relatedSource = filterSourceContentForRelated(sourceContent, affectedPath, maxRelatedChars);
                relatedFutures.add(CompletableFuture.supplyAsync(
                    () -> {
                        if (parentCtx != null) TokenUsageContext.set(parentCtx.scopeId(), parentCtx.operationType());
                        try {
                            return new AbstractMap.SimpleEntry<>(affectedPath,
                                updateRelatedPage(scopeId, sourceId, scopeIdStr, affectedPath, action, relatedSource, analysisResult, metadataJson, relatedPlan, contentCollector, conflictAnnotations, context));
                        } finally {
                            TokenUsageContext.clear();
                        }
                    },
                    writerExecutor
                ));
            }

            List<CompletableFuture<?>> allFutures = new ArrayList<>();
            allFutures.add(summaryFuture);
            allFutures.addAll(entityFutures);
            allFutures.addAll(chapterFutures);
            allFutures.addAll(relatedFutures);
            long batchTimeoutSeconds = parallelBaseTimeoutSeconds + (long) parallelPerTaskTimeoutSeconds * allFutures.size();
            boolean batchCompleted = true;
            try {
                CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0]))
                    .get(batchTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                batchCompleted = false;
                log.error("Writer chapter-based parallel batch exceeded adaptive timeout ({}s, {} tasks), harvesting completed pages",
                    batchTimeoutSeconds, allFutures.size());
            }

            for (CompletableFuture<Map.Entry<String, WikiPageDO>> f : chapterFutures) {
                Map.Entry<String, WikiPageDO> entry = harvestFuture(f, batchCompleted);
                if (entry != null && entry.getValue() != null) {
                    context.getChapterPages().put(entry.getKey(), entry.getValue());
                    tokensUsed += estimateTokens(entry.getValue().getSummary());
                }
            }

            for (CompletableFuture<Map.Entry<String, WikiPageDO>> f : entityFutures) {
                Map.Entry<String, WikiPageDO> entry = harvestFuture(f, batchCompleted);
                if (entry != null && entry.getValue() != null) {
                    context.getEntityPages().put(entry.getKey(), entry.getValue());
                    tokensUsed += estimateTokens(entry.getValue().getSummary());
                }
            }

            WikiPageDO summaryPage = harvestFuture(summaryFuture, batchCompleted);
            if (summaryPage != null) {
                context.setSummaryPage(summaryPage);
                tokensUsed += estimateTokens(summaryPage.getSummary());
            }

            for (CompletableFuture<Map.Entry<String, WikiPageDO>> f : relatedFutures) {
                Map.Entry<String, WikiPageDO> entry = harvestFuture(f, batchCompleted);
                if (entry != null && entry.getValue() != null) {
                    context.getUpdatedPages().put(entry.getKey(), entry.getValue());
                    tokensUsed += estimateTokens(entry.getValue().getSummary());
                }
            }

        } catch (Exception e) {
            log.error("Writer chapter-based parallel execution failed", e);
        }

        verifySourceRelations(scopeId, sourceId, context);
        batchPersistTagsAndKeywords(scopeId, context, metadataJson);
        context.getPageContents().putAll(contentCollector);
        bulkSyncToIndex(scopeId, context);

        context.setLinksFuture(indexerAgent.startLinksGeneration(context, buildPageInventory(context)));

        ComplianceResult productResult = schemaComplianceChecker.check(
            scopeId, context.getMetadataJson(), contentCollector);
        context.setProductComplianceResult(productResult);
        if (productResult.hasViolations()) {
            log.warn("WriterAgent chapter-mode Schema compliance violations for scopeId={}: {}", scopeId, productResult.summarize());
        }

        log.info("Chapter-based compilation complete: {} chapter pages, {} entity pages, {} updated pages for scopeId={}",
            context.getChapterPages().size(), context.getEntityPages().size(), context.getUpdatedPages().size(), scopeId);

        return tokensUsed;
    }

    private String buildChapterSubPlan(Map<String, String> subPlans, String chapterTitle, String fullPlanJson) {
        String key = "chapter_" + chapterTitle;
        if (subPlans.containsKey(key)) {
            try {
                JsonNode root = parseToJsonNode(fullPlanJson);
                if (root != null && root.has("consistencyRules")) {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.set("chapterPlan", objectMapper.readTree(subPlans.get(key)));
                    node.set("consistencyRules", root.get("consistencyRules"));
                    return objectMapper.writeValueAsString(node);
                }
                return subPlans.get(key);
            } catch (Exception e) {
                log.debug("buildChapterSubPlan merge failed for '{}': {}", chapterTitle, e.getMessage());
            }
            return subPlans.get(key);
        }
        return fullPlanJson;
    }

    private static final int MAX_CATEGORY_LENGTH = 60;

    private String buildChapterCategory(String docCategory, String chapterTitle) {
        String result;
        if (docCategory != null && !docCategory.isBlank()) {
            result = docCategory + "/" + chapterTitle;
        } else {
            result = chapterTitle;
        }
        if (result.length() > MAX_CATEGORY_LENGTH) {
            result = result.substring(0, MAX_CATEGORY_LENGTH);
        }
        return result;
    }

    private String generateChapterPagePath(String docTitle, String chapterTitle, int chapterIndex) {
        String sanitizedDoc = (docTitle != null && !docTitle.isEmpty())
            ? docTitle.replaceAll("[^\\w\\u4e00-\\u9fff]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "") : "doc";
        if (sanitizedDoc.isEmpty()) sanitizedDoc = "doc";
        String sanitizedChapter = chapterTitle.replaceAll("[^\\w\\u4e00-\\u9fff]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (sanitizedChapter.isEmpty()) sanitizedChapter = "chapter-" + (chapterIndex + 1);
        return "pages/" + sanitizedDoc + "/" + sanitizedChapter + ".md";
    }

    private String resolveDocTitle(String metadataTitle, IngestContext context) {
        if (metadataTitle != null && !metadataTitle.isEmpty()
            && !"未命名".equals(metadataTitle)
            && !"untitled".equalsIgnoreCase(metadataTitle)) {
            return metadataTitle;
        }
        String sourceName = context.getSourceName();
        if (sourceName != null && !sourceName.isEmpty()) {
            String cleanName = sourceName.replaceAll("\\.[^.]+$", "");
            if (!cleanName.isEmpty()) return cleanName;
        }
        return (metadataTitle != null && !metadataTitle.isEmpty()) ? metadataTitle : "未命名文档";
    }

    private String formatReferenceSourceContent(String content, String chapterTitle, List<IngestContext.ChartInfo> chartInfos) {
        if (content == null || content.isEmpty()) return content;
        int cmIdx = content.indexOf("\n\n---\n\n## 源文档图表清单");
        if (cmIdx >= 0) content = content.substring(0, cmIdx);
        content = cleanImageReferences(content);
        content = normalizeReferenceHeadings(content, chapterTitle);
        content = content.replaceAll("\\n{3,}", "\n\n").trim();
        return content;
    }

    private String cleanImageReferences(String content) {
        return content;
    }

    private String normalizeReferenceHeadings(String content, String chapterTitle) {
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("# ") && !line.startsWith("## ")) {
                String h1Text = line.substring(2).trim();
                if (i < 3 && (h1Text.equals(chapterTitle) || chapterTitle.contains(h1Text) || h1Text.contains(chapterTitle))) {
                    continue;
                }
                sb.append("##").append(line.substring(1)).append("\n");
            } else if (line.startsWith("## ")) {
                sb.append("###").append(line.substring(2)).append("\n");
            } else if (line.startsWith("### ")) {
                sb.append("####").append(line.substring(3)).append("\n");
            } else if (line.startsWith("#### ")) {
                sb.append("#####").append(line.substring(4)).append("\n");
            } else if (line.startsWith("##### ")) {
                sb.append("######").append(line.substring(5)).append("\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private WikiPageDO writeReferencePage(Long scopeId, Long sourceId, String scopeIdStr,
        String chapterTitle, int chapterIndex,
        String sourceContent, String analysisResult, String metadataJson,
        Map<String, String> contentCollector,
        List<IngestContext.ConflictAnnotation> conflictAnnotations, String precomputedPagePath, String chapterCategory,
        String precomputedSummary, IngestContext context) {
        try {
            String docTitle = resolveDocTitle(extractJsonField(metadataJson, "title"), context);
            String summary = precomputedSummary != null ? precomputedSummary : generateReferenceSummary(scopeId, sourceContent);
            String sanitizedContent = linkWritingService.sanitizeSourceLinks(sourceContent);
            sanitizedContent = linkWritingService.sanitizeWikiLinks(sanitizedContent, scopeId);

            StringBuilder navContent = new StringBuilder();
            navContent.append("# ").append(chapterTitle).append("\n\n");
            navContent.append("> 参考页 | 来源：").append(docTitle).append(" — ").append(chapterTitle).append("\n\n");
            navContent.append("## 参考摘要\n\n");
            navContent.append(summary).append("\n\n");
            navContent.append("## 原文内容\n\n");
            navContent.append(sanitizedContent).append("\n\n");

            String pagePath = precomputedPagePath != null ? precomputedPagePath : generateChapterPagePath(null, chapterTitle, chapterIndex);
            String finalContent = navContent.toString();
            contentCollector.put(pagePath, finalContent);
            storageProvider.write(scopeIdStr, "wiki/" + pagePath, finalContent.getBytes(StandardCharsets.UTF_8));

            WikiPageDO pageDO = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getFilePath, pagePath)
            );

            boolean hasConflict = hasConflictAnnotationsForPath(conflictAnnotations, pagePath);
            String healthStatus = hasConflict ? "conflict-warning" : "healthy";

            if (pageDO != null) {
                pageDO.setTitle(chapterTitle);
                pageDO.setSummary(summary);
                pageDO.setCategory(chapterCategory);
                pageDO.setPageType("reference");
                pageDO.setSourceCount(pageDO.getSourceCount() + 1);
                pageDO.setHealthStatus(healthStatus);
                pageDO.setSchemaVersion(schemaManager.getCurrentVersionId(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY));
                pageDO.setContentUpdatedAt(java.time.LocalDateTime.now());
                wikiPageMapper.updateById(pageDO);
                persistSourceRelation(scopeId, pageDO.getId(), sourceId);
                persistTagsAndKeywords(scopeId, pageDO.getId(), metadataJson);
                lintFindingService.resolvePageFindingsOnIngest(scopeId, pageDO.getId());
            } else {
                pageDO = new WikiPageDO();
                pageDO.setTitle(chapterTitle);
                pageDO.setFilePath(pagePath);
                pageDO.setSummary(summary);
                pageDO.setCategory(chapterCategory);
                pageDO.setPageType("reference");
                pageDO.setScopeId(scopeId);
                pageDO.setSourceCount(1);
                pageDO.setHealthStatus(healthStatus);
                pageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                pageDO.setSchemaVersion(schemaManager.getCurrentVersionId(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY));
                pageDO.setContentUpdatedAt(java.time.LocalDateTime.now());
                wikiPageMapper.insert(pageDO);
                persistSourceRelation(scopeId, pageDO.getId(), sourceId);
                persistTagsAndKeywords(scopeId, pageDO.getId(), metadataJson);
                lintFindingService.resolvePageFindingsOnIngest(scopeId, pageDO.getId());
            }

            return pageDO;
        } catch (Exception e) {
            log.error("writeReferencePage failed for '{}': {}", chapterTitle, e.getMessage());
            return null;
        }
    }

    private Map<Integer, String> batchGenerateReferenceSummaries(Long scopeId, List<DocumentStructureAnalyzer.Chapter> chapters) {
        Map<Integer, String> result = new java.util.concurrent.ConcurrentHashMap<>();
        if (chatClient == null || chapters == null || chapters.isEmpty()) {
            return result;
        }
        try {
            if (!llmBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.CHAPTER, 60_000)) {
                return result;
            }
            try {
                StringBuilder batchInput = new StringBuilder();
                for (int i = 0; i < chapters.size(); i++) {
                    DocumentStructureAnalyzer.Chapter ch = chapters.get(i);
                    String content = ch.sourceContent() != null ? ch.sourceContent() : "";
                    String excerpt = PromptTemplate.sampleSourceContent(content, 4000);
                    batchInput.append("### 章节 ").append(i).append(": ").append(ch.title()).append("\n");
                    batchInput.append(excerpt).append("\n\n");
                }

                String prompt = schemaInjector.prependForAnalyzer(scopeId, PromptRegistry.forIngest().batchReferenceSummaries());
                String response = chatClient.chat(prompt, batchInput.toString());

                int arrStart = response.indexOf('[');
                int arrEnd = response.lastIndexOf(']');
                String jsonStr = (arrStart >= 0 && arrEnd > arrStart) ? response.substring(arrStart, arrEnd + 1) : null;
                if (jsonStr != null && !jsonStr.isBlank()) {
                    List<Map<String, Object>> items = objectMapper.readValue(jsonStr, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    for (Map<String, Object> item : items) {
                        int idx = ((Number) item.getOrDefault("chapterIndex", -1)).intValue();
                        if (idx >= 0 && idx < chapters.size()) {
                            result.put(idx, formatBatchSummary(item));
                        }
                    }
                }
                log.info("Batch reference summaries: generated {}/{} in 1 LLM call", result.size(), chapters.size());
            } finally {
                llmBarrier.release(LlmConcurrencyBarrier.Bucket.CHAPTER);
            }

            if (result.size() < chapters.size()) {
                int missingCount = chapters.size() - result.size();
                log.info("Batch summaries incomplete: {} chapters missing, falling back to individual generation", missingCount);
                generateMissingSummariesInParallel(scopeId, chapters, result);
                log.info("Fallback complete: all {}/{} chapters have summaries", result.size(), chapters.size());
            }
        } catch (Exception e) {
            log.warn("Batch reference summaries failed, falling back to individual: {}", e.getMessage());
            generateMissingSummariesInParallel(scopeId, chapters, result);
        }
        return result;
    }

    private void generateMissingSummariesInParallel(Long scopeId, List<DocumentStructureAnalyzer.Chapter> chapters, Map<Integer, String> result) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            if (result.containsKey(i)) continue;
            DocumentStructureAnalyzer.Chapter ch = chapters.get(i);
            final int idx = i;
            futures.add(CompletableFuture.runAsync(() -> {
                String content = ch.sourceContent() != null ? ch.sourceContent() : "";
                result.put(idx, generateReferenceSummary(scopeId, content));
            }, writerExecutor));
        }
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    @SuppressWarnings("unchecked")
    private String formatBatchSummary(Map<String, Object> item) {
        StringBuilder sb = new StringBuilder();
        Object corePoints = item.get("corePoints");
        if (corePoints instanceof List<?> list && !list.isEmpty()) {
            sb.append("## 核心要点\n");
            for (Object p : list) sb.append("- ").append(p).append("\n");
        }
        Object keyTerms = item.get("keyTerms");
        if (keyTerms instanceof List<?> list && !list.isEmpty()) {
            sb.append("## 关键术语\n");
            for (Object t : list) sb.append("- ").append(t).append("\n");
        }
        Object crossRefs = item.get("crossRefs");
        if (crossRefs instanceof List<?> list && !list.isEmpty()) {
            sb.append("## 与其他章节的关联\n");
            for (Object r : list) sb.append("- ").append(r).append("\n");
        }
        return sb.length() > 0 ? sb.toString() : "章节内容摘要";
    }

    private String generateReferenceSummary(Long scopeId, String chapterContent) {
        if (chatClient == null) {
            return "章节：" + chapterContent.substring(0, Math.min(chapterContent.length(), 100));
        }
        try {
            if (!llmBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.CHAPTER, 30_000)) {
                return "章节内容摘要";
            }
            try {
                String prompt = PromptRegistry.forIngest().referenceSummary();
                String sampledContent = sampleChapterForSummary(chapterContent, 20000);
                return chatClient.chat(prompt, sampledContent);
            } finally {
                llmBarrier.release(LlmConcurrencyBarrier.Bucket.CHAPTER);
            }
        } catch (Exception e) {
            log.warn("Reference summary generation failed: {}", e.getMessage());
            return "章节内容摘要";
        }
    }

    private String sampleChapterForSummary(String content, int maxChars) {
        if (content.length() <= maxChars) return content;

        int headSize = (int) (maxChars * 0.4);
        int tailSize = (int) (maxChars * 0.3);
        int midSize = maxChars - headSize - tailSize;

        String head = content.substring(0, headSize);
        int midStart = (content.length() - midSize) / 2;
        int midEnd = midStart + midSize;
        String mid = content.substring(midStart, midEnd);
        String tail = content.substring(content.length() - tailSize);

        return head + "\n\n...(中间部分节选)...\n\n" + mid + "\n\n...(后段节选)...\n\n" + tail;
    }

    private String buildPageInventory(IngestContext context) {
        StringBuilder sb = new StringBuilder();
        WikiPageDO summaryPage = context.getSummaryPage();
        if (summaryPage != null && summaryPage.getTitle() != null) {
            sb.append("- ").append(summaryPage.getTitle()).append(" (filePath: ").append(summaryPage.getFilePath()).append(", type: summary)\n");
        }
        for (WikiPageDO page : context.getEntityPages().values()) {
            if (page != null && page.getTitle() != null) {
                sb.append("- ").append(page.getTitle()).append(" (filePath: ").append(page.getFilePath()).append(", type: entity)\n");
            }
        }
        for (WikiPageDO page : context.getChapterPages().values()) {
            if (page != null && page.getTitle() != null) {
                sb.append("- ").append(page.getTitle()).append(" (filePath: ").append(page.getFilePath()).append(", type: reference)\n");
            }
        }
        for (WikiPageDO page : context.getUpdatedPages().values()) {
            if (page != null && page.getTitle() != null) {
                sb.append("- ").append(page.getTitle()).append(" (filePath: ").append(page.getFilePath()).append(", type: updated)\n");
            }
        }
        return sb.toString();
    }

    public void reSyncToIndex(IngestContext context) {
        bulkSyncToIndex(context.getScopeId(), context);
    }

    public void reSyncSpecificPages(IngestContext context, java.util.Set<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return;
        Long scopeId = context.getScopeId();
        java.util.Map<String, WikiPageDO> pathToPage = new java.util.HashMap<>();
        if (context.getSummaryPage() != null && context.getSummaryPage().getFilePath() != null) {
            pathToPage.put(context.getSummaryPage().getFilePath(), context.getSummaryPage());
        }
        for (WikiPageDO p : context.getEntityPages().values()) {
            if (p != null && p.getFilePath() != null) pathToPage.put(p.getFilePath(), p);
        }
        for (WikiPageDO p : context.getUpdatedPages().values()) {
            if (p != null && p.getFilePath() != null) pathToPage.put(p.getFilePath(), p);
        }
        for (WikiPageDO p : context.getChapterPages().values()) {
            if (p != null && p.getFilePath() != null) pathToPage.put(p.getFilePath(), p);
        }
        int synced = 0;
        for (String path : filePaths) {
            WikiPageDO page = pathToPage.get(path);
            if (page == null || page.getId() == null) continue;
            syncPageToIndex(page, scopeId);
            synced++;
        }
        if (synced > 0) {
            log.info("reSyncSpecificPages: synced {} pages to index for scopeId={}", synced, scopeId);
        }
    }

    private void bulkSyncToIndex(Long scopeId, IngestContext context) {
        List<WikiPageDO> allPages = new ArrayList<>();
        if (context.getSummaryPage() != null) allPages.add(context.getSummaryPage());
        allPages.addAll(context.getEntityPages().values());
        allPages.addAll(context.getUpdatedPages().values());
        allPages.addAll(context.getChapterPages().values());
        allPages = new ArrayList<>(allPages.stream().filter(p -> p != null && p.getId() != null).toList());

        try {
            List<WikiPageDO> dbPages = wikiPageMapper.selectList(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
            );
            Set<Long> contextPageIds = allPages.stream().map(WikiPageDO::getId).collect(java.util.stream.Collectors.toSet());
            for (WikiPageDO dbPage : dbPages) {
                if (!contextPageIds.contains(dbPage.getId())) {
                    allPages.add(dbPage);
                }
            }
        } catch (Exception e) {
            log.warn("bulkSyncToIndex: failed to query all DB pages for scopeId={}, indexing context pages only: {}", scopeId, e.getMessage());
        }

        if (!allPages.isEmpty()) {
            try {
                searchService.bulkIndexPages(scopeId, allPages);
            } catch (Exception e) {
                log.error("bulkSyncToIndex failed, falling back to per-page sync: {}", e.getMessage());
                for (WikiPageDO p : allPages) {
                    syncPageToIndex(p, scopeId);
                }
            }
        }
        context.setBulkIndexed(true);
    }

    private void batchPersistTagsAndKeywords(Long scopeId, IngestContext context, String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return;

        List<Long> allPageIds = new ArrayList<>();
        if (context.getSummaryPage() != null && context.getSummaryPage().getId() != null) {
            allPageIds.add(context.getSummaryPage().getId());
        }
        for (WikiPageDO p : context.getEntityPages().values()) {
            if (p != null && p.getId() != null) allPageIds.add(p.getId());
        }
        for (WikiPageDO p : context.getUpdatedPages().values()) {
            if (p != null && p.getId() != null) allPageIds.add(p.getId());
        }
        for (WikiPageDO p : context.getChapterPages().values()) {
            if (p != null && p.getId() != null) allPageIds.add(p.getId());
        }
        if (allPageIds.isEmpty()) return;

        try {
            List<String> newTags = extractJsonArray(metadataJson, "tags").stream()
                .map(String::trim).filter(t -> !t.isEmpty()).toList();
            if (!newTags.isEmpty()) {
                Map<Long, Set<String>> existingTagsByPage = wikiPageTagMapper.selectList(
                    new LambdaQueryWrapper<WikiPageTagDO>()
                        .eq(WikiPageTagDO::getScopeId, scopeId)
                        .in(WikiPageTagDO::getPageId, allPageIds)
                ).stream().collect(Collectors.groupingBy(
                    WikiPageTagDO::getPageId,
                    Collectors.mapping(WikiPageTagDO::getTag, Collectors.toSet())
                ));
                for (Long pageId : allPageIds) {
                    Set<String> existing = existingTagsByPage.getOrDefault(pageId, Collections.emptySet());
                    for (String t : newTags) {
                        if (!existing.contains(t)) {
                            WikiPageTagDO tagDO = new WikiPageTagDO();
                            tagDO.setScopeId(scopeId);
                            tagDO.setPageId(pageId);
                            tagDO.setTag(t);
                            wikiPageTagMapper.insert(tagDO);
                        }
                    }
                }
            }

            List<String> newKeywords = extractJsonArray(metadataJson, "keywords").stream()
                .map(String::trim).filter(k -> !k.isEmpty()).toList();
            if (!newKeywords.isEmpty()) {
                Map<Long, Set<String>> existingKwByPage = wikiPageKeywordMapper.selectList(
                    new LambdaQueryWrapper<WikiPageKeywordDO>()
                        .eq(WikiPageKeywordDO::getScopeId, scopeId)
                        .in(WikiPageKeywordDO::getPageId, allPageIds)
                ).stream().collect(Collectors.groupingBy(
                    WikiPageKeywordDO::getPageId,
                    Collectors.mapping(WikiPageKeywordDO::getKeyword, Collectors.toSet())
                ));
                for (Long pageId : allPageIds) {
                    Set<String> existing = existingKwByPage.getOrDefault(pageId, Collections.emptySet());
                    for (String k : newKeywords) {
                        if (!existing.contains(k)) {
                            WikiPageKeywordDO kwDO = new WikiPageKeywordDO();
                            kwDO.setScopeId(scopeId);
                            kwDO.setPageId(pageId);
                            kwDO.setKeyword(k);
                            wikiPageKeywordMapper.insert(kwDO);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("batchPersistTagsAndKeywords failed for scopeId={}: {}", scopeId, e.getMessage());
        }
    }

    private void verifySourceRelations(Long scopeId, Long sourceId, IngestContext context) {
        List<Long> allPageIds = new ArrayList<>();
        if (context.getSummaryPage() != null && context.getSummaryPage().getId() != null) {
            allPageIds.add(context.getSummaryPage().getId());
        }
        for (WikiPageDO p : context.getEntityPages().values()) {
            if (p != null && p.getId() != null) allPageIds.add(p.getId());
        }
        for (WikiPageDO p : context.getUpdatedPages().values()) {
            if (p != null && p.getId() != null) allPageIds.add(p.getId());
        }
        for (WikiPageDO p : context.getChapterPages().values()) {
            if (p != null && p.getId() != null) allPageIds.add(p.getId());
        }
        if (allPageIds.isEmpty()) return;

        Set<Long> existingPageIds;
        try {
            existingPageIds = wikiPageSourceMapper.selectList(
                new LambdaQueryWrapper<WikiPageSourceDO>()
                    .eq(WikiPageSourceDO::getScopeId, scopeId)
                    .eq(WikiPageSourceDO::getSourceId, sourceId)
                    .in(WikiPageSourceDO::getPageId, allPageIds)
            ).stream().map(WikiPageSourceDO::getPageId).collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Batch query source relations failed, falling back to per-page: {}", e.getMessage());
            existingPageIds = java.util.Collections.emptySet();
        }

        for (Long pageId : allPageIds) {
            if (!existingPageIds.contains(pageId)) {
                WikiPageSourceDO relation = new WikiPageSourceDO();
                relation.setScopeId(scopeId);
                relation.setPageId(pageId);
                relation.setSourceId(sourceId);
                wikiPageSourceMapper.insert(relation);
                log.info("Compensating missing source relation: scopeId={}, pageId={}, sourceId={}", scopeId, pageId, sourceId);
            }
        }
    }

    private String generateWritingPlan(Long scopeId, String sourceContent, String analysisResult, String metadataJson, String pagesContext, List<DocumentStructureAnalyzer.Chapter> chapters, IngestContext context) {
        return generateWritingPlan(scopeId, sourceContent, analysisResult, metadataJson, pagesContext, chapters, null, context);
    }

    private String generateWritingPlan(Long scopeId, String sourceContent, String analysisResult, String metadataJson, String pagesContext, List<DocumentStructureAnalyzer.Chapter> chapters, List<String> plannedEntityNames, IngestContext context) {
        try {
            String contextToUse = pagesContext;
            if (contextToUse == null || contextToUse.isEmpty()) {
                List<WikiPageDO> existingPages = wikiPageMapper.selectList(
                    ActivePageScope.active(scopeId)
                );
                if (!existingPages.isEmpty()) {
                    List<PromptTemplate.PageSummary> pageSummaries = existingPages.stream()
                        .map(p -> new PromptTemplate.PageSummary(p.getTitle(), p.getCategory(), p.getSummary(), p.getFilePath()))
                        .toList();
                    contextToUse = PromptTemplate.estimateAndTruncatePageList(pageSummaries, "现有知识库页面列表：");
                }
            }

            String docStructure = extractDocStructure(sourceContent);

            String chapterList = null;
            if (chapters != null && !chapters.isEmpty()) {
                StringBuilder clb = new StringBuilder();
                for (int i = 0; i < chapters.size(); i++) {
                    DocumentStructureAnalyzer.Chapter ch = chapters.get(i);
                    clb.append((i + 1)).append(". ").append(ch.title())
                        .append(" (").append(ch.sourceContent().length()).append(" 字符)");
                    if (!ch.subChapters().isEmpty()) {
                        clb.append(" — 子章节: ");
                        clb.append(ch.subChapters().stream().map(DocumentStructureAnalyzer.Chapter::title).collect(Collectors.joining(", ")));
                    }
                    clb.append("\n");
                }
                chapterList = clb.toString();
            }

            String entityRelSummary = context.getEntityRelationshipSummary();
            String completenessHint = buildCompletenessHint(context);

            String prompt = schemaInjector.prependForWriter(scopeId, PromptRegistry.forIngest().writingPlan(metadataJson, contextToUse, chapterList, entityRelSummary, completenessHint));
            StringBuilder userMessage = new StringBuilder();
            userMessage.append("【结构化导航索引】（已覆盖文档所有片段的完整分析结果）\n");
            userMessage.append(analysisResult);
            if (docStructure != null && !docStructure.isEmpty()) {
                userMessage.append("\n\n【文档章节结构】\n");
                userMessage.append(docStructure);
            }
            if (plannedEntityNames != null && !plannedEntityNames.isEmpty()) {
                userMessage.append("\n\n【已确认建页实体清单】（系统已基于信息密度确定性评估，仅以下实体将建立独立实体页）\n");
                userMessage.append(String.join("、", plannedEntityNames));
                userMessage.append("\n要求：entityPlans 只需为上述实体制定写作计划，不要为其他实体规划 entityPlans；");
                userMessage.append("清单之外的实体信息请通过 summaryOutline 的 keyEntitiesToCover 融入摘要页。");
            }
            if (!llmBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.PLAN, 120_000)) {
                log.error("WritingPlan barrier acquire timeout");
                return null;
            }
            try {
                return chatClient.chat(prompt, userMessage.toString());
            } finally {
                llmBarrier.release(LlmConcurrencyBarrier.Bucket.PLAN);
            }
        } catch (Exception e) {
            log.error("WritingPlan generation failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildCompletenessHint(IngestContext context) {
        double completeness = context.getCompletenessScore();
        if (completeness <= 0) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("【分析阶段完整性评估】");
        if (completeness >= 0.8) {
            sb.append("实体分解完整性评分: ").append(String.format("%.0f%%", completeness * 100))
                .append("（高完整性，分析结果可信度高，可放心制定实体页写作计划）");
        } else if (completeness >= 0.5) {
            sb.append("实体分解完整性评分: ").append(String.format("%.0f%%", completeness * 100))
                .append("（中等完整性，部分实体信息较薄。建议：1.摘要页应加强对薄实体的覆盖，确保文档整体内容不遗漏；")
                .append("2.信息量不足的实体考虑降级为摘要页中的一个section而非独立建页）");
        } else {
            sb.append("实体分解完整性评分: ").append(String.format("%.0f%%", completeness * 100))
                .append("（低完整性警告：大量实体信息不足以支撑独立页面。")
                .append("建议：1.减少独立实体页数量，将内容融入摘要页；")
                .append("2.摘要页需要更全面地覆盖原文档的各个方面，弥补实体分解的不足）");
        }
        List<String> schemaGaps = context.getSchemaGapHints();
        if (schemaGaps != null && !schemaGaps.isEmpty()) {
            sb.append("\nSchema差异提示：");
            for (String gap : schemaGaps) {
                sb.append("\n- ").append(gap);
            }
        }
        return sb.toString();
    }

    private String extractDocStructure(String sourceContent) {
        if (sourceContent == null || sourceContent.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        String[] lines = sourceContent.split("\n");
        for (String line : lines) {
            if (line.matches("^#{1,6}\\s+.*")) {
                sb.append(line).append("\n");
            }
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    private Map<String, String> parseSubPlans(String writingPlanJson) {
        Map<String, String> result = new HashMap<>();
        try {
            JsonNode root = parseToJsonNode(writingPlanJson);
            if (root == null) return result;

            if (root.has("entityPlans") && root.get("entityPlans").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = root.get("entityPlans").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    result.put("entity_" + entry.getKey(), objectMapper.writeValueAsString(entry.getValue()));
                }
            }

            if (root.has("affectedPagePlans") && root.get("affectedPagePlans").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = root.get("affectedPagePlans").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    result.put("related_" + entry.getKey(), objectMapper.writeValueAsString(entry.getValue()));
                }
            }

            if (root.has("chapterPlans") && root.get("chapterPlans").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = root.get("chapterPlans").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    result.put("chapter_" + entry.getKey(), objectMapper.writeValueAsString(entry.getValue()));
                }
            }
        } catch (Exception e) {
            log.debug("parseSubPlans failed, will use full WritingPlan: {}", e.getMessage());
        }
        return result;
    }

    private String buildEntitiesSummaryForSummary(List<String> entityNames, List<EntityContextualRef> contextuals, IngestContext context) {
        StringBuilder sb = new StringBuilder();
        if (entityNames != null && !entityNames.isEmpty()) {
            sb.append("\n\n【已生成的实体页面】\n");
            sb.append("以下实体页面已生成，摘要页应综合引用这些页面（使用 [[实体名]] 链接格式）：\n");
            for (String name : entityNames) {
                sb.append("- [[").append(name).append("]]");
                WikiPageDO entityPage = context.getEntityPages().get(name);
                if (entityPage != null && entityPage.getSummary() != null && !entityPage.getSummary().isBlank()) {
                    sb.append(" — ").append(entityPage.getSummary());
                }
                sb.append("\n");
            }
        }
        if (contextuals != null && !contextuals.isEmpty()) {
            sb.append("\n【上下文参考实体】（无独立页面，摘要中适当引用即可）\n");
            sb.append("以下实体在文档中被提及但信息密度不足以独立建页，可在摘要中简要引用（不加 [[链接]]，用普通文字标注即可）：\n");
            for (EntityContextualRef ctx : contextuals) {
                sb.append("- ").append(ctx.name()).append(" (").append(ctx.type()).append(")");
                if (ctx.chunkCount() > 0) {
                    sb.append(" — 出现在 ").append(ctx.chunkCount()).append(" 个片段");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String buildSummarySubPlan(Map<String, String> subPlans, String fullPlanJson) {
        try {
            JsonNode root = parseToJsonNode(fullPlanJson);
            if (root == null) return fullPlanJson;

            ObjectNode summaryNode = objectMapper.createObjectNode();
            if (root.has("coreThesis")) summaryNode.set("coreThesis", root.get("coreThesis"));
            if (root.has("summaryOutline")) summaryNode.set("summaryOutline", root.get("summaryOutline"));
            if (root.has("consistencyRules")) summaryNode.set("consistencyRules", root.get("consistencyRules"));

            String compact = objectMapper.writeValueAsString(summaryNode);
            if (!compact.isEmpty()) return compact;
        } catch (Exception e) {
            log.debug("buildSummarySubPlan failed: {}", e.getMessage());
        }
        return fullPlanJson;
    }

    private String buildEntitySubPlan(Map<String, String> subPlans, String entityName, String fullPlanJson) {
        String key = "entity_" + entityName;
        if (subPlans.containsKey(key)) {
            try {
                JsonNode root = parseToJsonNode(fullPlanJson);
                if (root != null && root.has("consistencyRules")) {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.set("entityPlan", objectMapper.readTree(subPlans.get(key)));
                    node.set("consistencyRules", root.get("consistencyRules"));
                    return objectMapper.writeValueAsString(node);
                }
                ObjectNode node = objectMapper.createObjectNode();
                node.set("entityPlan", objectMapper.readTree(subPlans.get(key)));
                return objectMapper.writeValueAsString(node);
            } catch (Exception e) {
                log.debug("buildEntitySubPlan merge failed for '{}': {}", entityName, e.getMessage());
            }
            return subPlans.get(key);
        }
        return fullPlanJson;
    }

    private String buildRelatedSubPlan(Map<String, String> subPlans, String affectedPath, String fullPlanJson) {
        String key = "related_" + affectedPath;
        if (subPlans.containsKey(key)) {
            try {
                JsonNode root = parseToJsonNode(fullPlanJson);
                if (root != null && root.has("consistencyRules")) {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.set("affectedPagePlan", objectMapper.readTree(subPlans.get(key)));
                    node.set("consistencyRules", root.get("consistencyRules"));
                    return objectMapper.writeValueAsString(node);
                }
                ObjectNode node = objectMapper.createObjectNode();
                node.set("affectedPagePlan", objectMapper.readTree(subPlans.get(key)));
                return objectMapper.writeValueAsString(node);
            } catch (Exception e) {
                log.debug("buildRelatedSubPlan merge failed for '{}': {}", affectedPath, e.getMessage());
            }
            return subPlans.get(key);
        }
        return fullPlanJson;
    }

    private JsonNode parseToJsonNode(String json) {
        try {
            int start = json.indexOf("{");
            int end = json.lastIndexOf("}") + 1;
            String cleanJson = (start >= 0 && end > start) ? json.substring(start, end) : json;
            return objectMapper.readTree(cleanJson);
        } catch (Exception e) {
            log.debug("parseToJsonNode failed: {}", e.getMessage());
            return null;
        }
    }

    private WikiPageDO writeSummaryPage(Long scopeId, Long sourceId, String scopeIdStr, String sourceContent, String analysisResult, String metadataJson, String subPlanJson, Map<String, String> contentCollector, String schemaPageTemplate, List<IngestContext.ConflictAnnotation> conflictAnnotations, String precomputedPagePath, IngestContext context) {
        try {
            if (!acquireBarrier(LlmConcurrencyBarrier.Bucket.SUMMARY, "writeSummaryPage")) {
                return null;
            }
            boolean structuredSource = context != null && context.getDocumentType() == DocumentStructureAnalyzer.DocumentType.STRUCTURED;
            String prompt;
            if (subPlanJson != null) {
                prompt = schemaInjector.prependForWriter(scopeId, PromptRegistry.forIngest().writeSummaryWithPlan(metadataJson, subPlanJson, schemaPageTemplate, structuredSource));
            } else {
                prompt = schemaInjector.prependForWriter(scopeId, PromptRegistry.forIngest().writeSummary(metadataJson, schemaPageTemplate));
            }
            String userMessage = PromptTemplate.buildSourceAndAnalysisUserMessage(sourceContent, analysisResult);
            String summary;
            try {
                summary = chatClient.chat(prompt, userMessage);
            } finally {
                llmBarrier.release(LlmConcurrencyBarrier.Bucket.SUMMARY);
            }
            summary = PromptTemplate.stripConversationalFiller(stripMarkdownFences(summary));
            summary = linkWritingService.sanitizeSourceLinks(summary);
            summary = linkWritingService.sanitizeWikiLinks(summary, scopeId);

            String resolvedTitle = resolveDocTitle(extractJsonField(metadataJson, "title"), context);
            if (!summary.startsWith("# ")) {
                summary = "# " + resolvedTitle + "\n\n" + summary;
            }

            String pagePath = precomputedPagePath != null ? precomputedPagePath : generatePagePath(metadataJson);
            contentCollector.put(pagePath, summary);
            storageProvider.write(scopeIdStr, "wiki/" + pagePath, summary.getBytes(StandardCharsets.UTF_8));

            WikiPageDO pageDO = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getFilePath, pagePath)
            );

            if (pageDO == null) {
                String titleFromMeta = extractJsonField(metadataJson, "title");
                if (titleFromMeta != null && !titleFromMeta.isEmpty()) {
                    pageDO = findPageByTitleIgnoreCase(scopeId, titleFromMeta);
                    if (pageDO != null) {
                        log.info("writeSummaryPage: found existing page by title case-insensitive match (title='{}', oldPath='{}'), migrating filePath to '{}'",
                            pageDO.getTitle(), pageDO.getFilePath(), pagePath);
                        String oldPath = pageDO.getFilePath();
                        byte[] oldContent = storageProvider.read(scopeIdStr, "wiki/" + oldPath);
                        if (oldContent != null) {
                            storageProvider.write(scopeIdStr, "wiki/" + pagePath, oldContent);
                        }
                        pageDO.setFilePath(pagePath);
                        wikiPageMapper.updateById(pageDO);
                    }
                }
            }

            String title = extractJsonField(metadataJson, "title");
            if (title == null || title.isEmpty() || "未命名".equals(title) || "untitled".equalsIgnoreCase(title)) {
                title = resolveDocTitle(title, context);
            }
            String summaryText = extractJsonField(metadataJson, "summary");
            String category = extractJsonField(metadataJson, "category");

            boolean hasConflict = hasConflictAnnotationsForPath(conflictAnnotations, pagePath);
            String healthStatus = hasConflict ? "conflict-warning" : "healthy";

            if (pageDO != null) {
                String summaryLifecycle = pageDO.getLifecycleStatus();
                if (PageLifecycle.DEPRECATED.name().equals(summaryLifecycle)) {
                    log.info("writeSummaryPage: reactivating deprecated page '{}' (id={})", pageDO.getTitle(), pageDO.getId());
                    pageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                    pageDO.setDeprecatedAt(null);
                    pageDO.setDeprecatedReason(null);
                } else if (PageLifecycle.MERGED.name().equals(summaryLifecycle) && pageDO.getMergedIntoPageId() != null) {
                    WikiPageDO mergeTarget = wikiPageMapper.selectById(pageDO.getMergedIntoPageId());
                    if (mergeTarget != null && PageLifecycle.ACTIVE.name().equals(mergeTarget.getLifecycleStatus())) {
                        log.info("writeSummaryPage: redirected MERGED page '{}' to merge target '{}' (id={})", pageDO.getTitle(), mergeTarget.getTitle(), mergeTarget.getId());
                        pageDO = mergeTarget;
                        pagePath = mergeTarget.getFilePath();
                        contentCollector.put(pagePath, summary);
                        storageProvider.write(scopeIdStr, "wiki/" + pagePath, summary.getBytes(StandardCharsets.UTF_8));
                    }
                } else if (PageLifecycle.MERGING.name().equals(summaryLifecycle)) {
                    log.info("writeSummaryPage: reactivating MERGING page '{}' (id={}) — merge target detected", pageDO.getTitle(), pageDO.getId());
                    pageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                }
                pageDO.setTitle(title);
                pageDO.setSummary(summaryText);
                pageDO.setCategory(category);
                pageDO.setPageType("summary");
                pageDO.setSourceCount(pageDO.getSourceCount() + 1);
                pageDO.setHealthStatus(healthStatus);
                pageDO.setSchemaVersion(schemaManager.getCurrentVersionId(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY));
                pageDO.setContentUpdatedAt(java.time.LocalDateTime.now());
                wikiPageMapper.updateById(pageDO);
                lintFindingService.resolvePageFindingsOnIngest(scopeId, pageDO.getId());
            } else {
                pageDO = new WikiPageDO();
                pageDO.setTitle(title);
                pageDO.setFilePath(pagePath);
                pageDO.setSummary(summaryText);
                pageDO.setCategory(category);
                pageDO.setPageType("summary");
                pageDO.setScopeId(scopeId);
                pageDO.setSourceCount(1);
                pageDO.setHealthStatus(healthStatus);
                pageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                pageDO.setSchemaVersion(schemaManager.getCurrentVersionId(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY));
                pageDO.setContentUpdatedAt(java.time.LocalDateTime.now());
                wikiPageMapper.insert(pageDO);
                lintFindingService.resolvePageFindingsOnIngest(scopeId, pageDO.getId());
            }

            persistSourceRelation(scopeId, pageDO.getId(), sourceId);
            persistTagsAndKeywords(scopeId, pageDO.getId(), metadataJson);

            return pageDO;
        } catch (Exception e) {
            log.error("writeSummaryPage failed: {}", e.getMessage());
            return null;
        }
    }

    private WikiPageDO writeEntityPage(Long scopeId, Long sourceId, String scopeIdStr, String entityName, String entityType, String sourceContent, String analysisResult, String metadataJson, String subPlanJson, Map<String, String> contentCollector, String schemaPageTemplate, List<IngestContext.ConflictAnnotation> conflictAnnotations, String precomputedPagePath, IngestContext context) {
        try {
            int maxAnalysisChars = context != null && context.getStrategy() != null
                ? context.getStrategy().getMaxAnalysisCharsEntity() : 8000;
            String filteredAnalysis = filterAnalysisForEntity(analysisResult, entityName, maxAnalysisChars, context);
            String entityPagePath = precomputedPagePath != null ? precomputedPagePath : generatePagePath(entityName);

            WikiPageDO existing = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getFilePath, entityPagePath)
            );

            if (existing == null) {
                existing = findPageByTitleIgnoreCase(scopeId, entityName);
                if (existing != null) {
                    log.info("writeEntityPage: found existing page by title case-insensitive match (title='{}', oldPath='{}'), migrating filePath to '{}'",
                        existing.getTitle(), existing.getFilePath(), entityPagePath);
                    String oldPath = existing.getFilePath();
                    byte[] oldContent = storageProvider.read(scopeIdStr, "wiki/" + oldPath);
                    if (oldContent != null) {
                        storageProvider.write(scopeIdStr, "wiki/" + entityPagePath, oldContent);
                        contentCollector.put(entityPagePath, new String(oldContent, StandardCharsets.UTF_8));
                    }
                    existing.setFilePath(entityPagePath);
                    wikiPageMapper.updateById(existing);
                }
            }

            boolean hasConflict = hasConflictAnnotationsForPath(conflictAnnotations, entityPagePath);
            String healthStatus = hasConflict ? "conflict-warning" : "healthy";

            if (existing == null && context != null) {
                IngestContext.DeprecatedPageInfo deprecatedMatch = context.findDeprecatedPage(entityName);
                if (deprecatedMatch != null) {
                    existing = wikiPageMapper.selectById(deprecatedMatch.pageId());
                    if (existing != null) {
                        entityPagePath = existing.getFilePath();
                        log.info("writeEntityPage: matched deprecated page '{}' (id={}, path='{}') via index", entityName, existing.getId(), entityPagePath);
                    }
                }
            }

            if (existing != null) {
                String lifecycleStatus = existing.getLifecycleStatus();

                if (PageLifecycle.DELETED.name().equals(lifecycleStatus)) {
                    log.info("Skipping writeEntityPage for deleted page '{}' - lifecycle_status=DELETED", entityName);
                    return null;
                }

                if (PageLifecycle.MERGED.name().equals(lifecycleStatus) && existing.getMergedIntoPageId() != null) {
                    WikiPageDO mergeTarget = wikiPageMapper.selectById(existing.getMergedIntoPageId());
                    if (mergeTarget != null && PageLifecycle.ACTIVE.name().equals(mergeTarget.getLifecycleStatus())) {
                        log.info("writeEntityPage: redirected MERGED page '{}' to merge target '{}' (id={})", entityName, mergeTarget.getTitle(), mergeTarget.getId());
                        existing = mergeTarget;
                        entityPagePath = mergeTarget.getFilePath();
                    } else {
                        log.info("Skipping writeEntityPage for merged page '{}' - merge target not active", entityName);
                        return null;
                    }
                } else if (PageLifecycle.DEPRECATED.name().equals(lifecycleStatus)) {
                    log.info("writeEntityPage: reactivating deprecated page '{}' (id={})", entityName, existing.getId());
                    existing.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                    existing.setDeprecatedAt(null);
                    existing.setDeprecatedReason(null);
                    wikiPageMapper.updateById(existing);
                } else if (PageLifecycle.MERGING.name().equals(lifecycleStatus)) {
                    log.info("writeEntityPage: reactivating MERGING page '{}' (id={}) — merge target detected", entityName, existing.getId());
                    existing.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                    wikiPageMapper.updateById(existing);
                } else if (!PageLifecycle.ACTIVE.name().equals(lifecycleStatus)) {
                    log.info("Skipping writeEntityPage merge for non-active page '{}' - lifecycle_status={}", entityName, lifecycleStatus);
                    return null;
                }
                byte[] existingBytes = storageProvider.read(scopeIdStr, "wiki/" + entityPagePath);
                if (existingBytes != null) {
                    String existingContent = new String(existingBytes, StandardCharsets.UTF_8);
                    if (!acquireBarrier(LlmConcurrencyBarrier.Bucket.ENTITY, "writeEntityPage(merge):" + entityName)) {
                        return null;
                    }
                    String mergePrompt;
                    try {
                        if (subPlanJson != null) {
                            mergePrompt = schemaInjector.prependForWriter(scopeId, PromptRegistry.forIngest().mergeIntoExistingPageWithPlan(existingContent, sourceContent, filteredAnalysis, metadataJson, "补充", subPlanJson));
                        } else {
                            mergePrompt = schemaInjector.prependForWriter(scopeId, PromptRegistry.forIngest().mergeIntoExistingPage(existingContent, sourceContent, filteredAnalysis, metadataJson, "补充"));
                        }
                        String merged = chatClient.chat(mergePrompt);
                        merged = PromptTemplate.stripConversationalFiller(stripMarkdownFences(merged));
                        merged = linkWritingService.sanitizeSourceLinks(merged);
                        merged = linkWritingService.sanitizeWikiLinks(merged, scopeId);
                        contentCollector.put(entityPagePath, merged);
                        storageProvider.write(scopeIdStr, "wiki/" + entityPagePath, merged.getBytes(StandardCharsets.UTF_8));
                    } finally {
                        llmBarrier.release(LlmConcurrencyBarrier.Bucket.ENTITY);
                    }
                    existing.setSourceCount(existing.getSourceCount() + 1);
                    existing.setHealthStatus(healthStatus);
                    existing.setSchemaVersion(schemaManager.getCurrentVersionId(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY));
                    existing.setContentUpdatedAt(java.time.LocalDateTime.now());
                    existing.setPageType("entity");
                    wikiPageMapper.updateById(existing);
                    persistSourceRelation(scopeId, existing.getId(), sourceId);
                    persistTagsAndKeywords(scopeId, existing.getId(), metadataJson);
                    lintFindingService.resolvePageFindingsOnIngest(scopeId, existing.getId());
                    return existing;
                }
            } else {
                if (!acquireBarrier(LlmConcurrencyBarrier.Bucket.ENTITY, "writeEntityPage(create):" + entityName)) {
                    return null;
                }
                boolean structuredSource = context != null && context.getDocumentType() == DocumentStructureAnalyzer.DocumentType.STRUCTURED;
                String entityPrompt;
                try {
                    if (subPlanJson != null) {
                        entityPrompt = schemaInjector.prependForWriter(scopeId, PromptRegistry.forIngest().writeEntityPageWithPlan(entityName, entityType, subPlanJson, metadataJson, schemaPageTemplate, structuredSource));
                    } else {
                        entityPrompt = schemaInjector.prependForWriter(scopeId, PromptRegistry.forIngest().writeEntityPage(entityName, entityType, filteredAnalysis, metadataJson, schemaPageTemplate, structuredSource));
                    }
                    String entityUserMsg = PromptTemplate.buildSourceAndAnalysisUserMessage(sourceContent, filteredAnalysis);
                    String entityContent = chatClient.chat(entityPrompt, entityUserMsg);
                    entityContent = PromptTemplate.stripConversationalFiller(stripMarkdownFences(entityContent));
                    entityContent = linkWritingService.sanitizeSourceLinks(entityContent);
                    entityContent = linkWritingService.sanitizeWikiLinks(entityContent, scopeId);
                    contentCollector.put(entityPagePath, entityContent);
                    storageProvider.write(scopeIdStr, "wiki/" + entityPagePath, entityContent.getBytes(StandardCharsets.UTF_8));
                } finally {
                    llmBarrier.release(LlmConcurrencyBarrier.Bucket.ENTITY);
                }

                String entityCategory = deriveEntityCategory(entityType, metadataJson);
                WikiPageDO entityPageDO = new WikiPageDO();
                entityPageDO.setTitle(entityName);
                entityPageDO.setFilePath(entityPagePath);
                entityPageDO.setSummary("关于「" + entityName + "」的知识页面");
                entityPageDO.setCategory(entityCategory);
                entityPageDO.setPageType("entity");
                entityPageDO.setScopeId(scopeId);
                entityPageDO.setSourceCount(1);
                entityPageDO.setHealthStatus(healthStatus);
                entityPageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                entityPageDO.setSchemaVersion(schemaManager.getCurrentVersionId(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY));
                entityPageDO.setContentUpdatedAt(java.time.LocalDateTime.now());
                wikiPageMapper.insert(entityPageDO);
                persistSourceRelation(scopeId, entityPageDO.getId(), sourceId);
                persistTagsAndKeywords(scopeId, entityPageDO.getId(), metadataJson);
                lintFindingService.resolvePageFindingsOnIngest(scopeId, entityPageDO.getId());
                return entityPageDO;
            }
            return null;
        } catch (Exception e) {
            log.error("writeEntityPage failed for '{}': {}", entityName, e.getMessage());
            return null;
        }
    }

    private WikiPageDO updateRelatedPage(Long scopeId, Long sourceId, String scopeIdStr, String affectedPath, String action, String sourceContent, String analysisResult, String metadataJson, String subPlanJson, Map<String, String> contentCollector, List<IngestContext.ConflictAnnotation> conflictAnnotations, IngestContext context) {
        try {
            WikiPageDO existing = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getFilePath, affectedPath)
            );
            if (existing == null) {
                String affectedTitle = deriveTitleFromPath(affectedPath);
                if (affectedTitle != null) {
                    existing = findPageByTitleIgnoreCase(scopeId, affectedTitle);
                    if (existing != null) {
                        String realPath = existing.getFilePath();
                        log.info("updateRelatedPage: found existing page by title case-insensitive match (title='{}', queriedPath='{}', realPath='{}')",
                            existing.getTitle(), affectedPath, realPath);
                        affectedPath = realPath;
                    }
                }
            }
            if (existing == null && context != null) {
                String affectedTitleForMatch = deriveTitleFromPath(affectedPath);
                if (affectedTitleForMatch != null) {
                    IngestContext.DeprecatedPageInfo deprecatedMatch = context.findDeprecatedPage(affectedTitleForMatch);
                    if (deprecatedMatch != null) {
                        existing = wikiPageMapper.selectById(deprecatedMatch.pageId());
                        if (existing != null) {
                            affectedPath = existing.getFilePath();
                            log.info("updateRelatedPage: matched deprecated page '{}' (id={}, path='{}') via index", affectedTitleForMatch, existing.getId(), affectedPath);
                        }
                    }
                }
            }
            if (existing == null) return null;

            if ("reference".equals(existing.getPageType())) {
                log.warn("Skipping updateRelatedPage for reference page '{}' - reference pages are read-only", affectedPath);
                return null;
            }

            String lifecycleStatus = existing.getLifecycleStatus();

            if (PageLifecycle.DELETED.name().equals(lifecycleStatus)) {
                log.info("Skipping updateRelatedPage for deleted page '{}' - lifecycle_status=DELETED", affectedPath);
                return null;
            }

            if (PageLifecycle.MERGED.name().equals(lifecycleStatus) && existing.getMergedIntoPageId() != null) {
                WikiPageDO mergeTarget = wikiPageMapper.selectById(existing.getMergedIntoPageId());
                if (mergeTarget != null && PageLifecycle.ACTIVE.name().equals(mergeTarget.getLifecycleStatus())) {
                    log.info("updateRelatedPage: redirected MERGED page '{}' to merge target '{}' (id={})", affectedPath, mergeTarget.getTitle(), mergeTarget.getId());
                    affectedPath = mergeTarget.getFilePath();
                    existing = mergeTarget;
                } else {
                    log.info("Skipping updateRelatedPage for merged page '{}' - merge target not active", affectedPath);
                    return null;
                }
            } else if (PageLifecycle.DEPRECATED.name().equals(lifecycleStatus)) {
                log.info("updateRelatedPage: reactivating deprecated page '{}' (id={})", affectedPath, existing.getId());
                existing.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                existing.setDeprecatedAt(null);
                existing.setDeprecatedReason(null);
                wikiPageMapper.updateById(existing);
            } else if (!PageLifecycle.ACTIVE.name().equals(lifecycleStatus)) {
                log.info("Skipping updateRelatedPage for non-active page '{}' - lifecycle_status={}", affectedPath, lifecycleStatus);
                return null;
            }

            byte[] existingBytes = storageProvider.read(scopeIdStr, "wiki/" + affectedPath);
            if (existingBytes == null) return null;
            String existingContent = new String(existingBytes, StandardCharsets.UTF_8);
            int maxAnalysisCharsRelated = context != null && context.getStrategy() != null
                ? context.getStrategy().getMaxAnalysisCharsRelated() : 6000;
            String filteredAnalysis = filterAnalysisForRelated(analysisResult, affectedPath, existingContent, maxAnalysisCharsRelated, context);

            boolean hasConflict = hasConflictAnnotationsForPath(conflictAnnotations, affectedPath);
            String healthStatus = hasConflict ? "conflict-warning" : "healthy";

            if (!acquireBarrier(LlmConcurrencyBarrier.Bucket.ENTITY, "updateRelatedPage:" + affectedPath)) {
                return null;
            }
            String mergePrompt;
            try {
                if (subPlanJson != null) {
                    mergePrompt = schemaInjector.prependForWriter(scopeId, PromptRegistry.forIngest().mergeIntoExistingPageWithPlan(existingContent, sourceContent, filteredAnalysis, metadataJson, action, subPlanJson));
                } else {
                    mergePrompt = schemaInjector.prependForWriter(scopeId, PromptRegistry.forIngest().mergeIntoExistingPage(existingContent, sourceContent, filteredAnalysis, metadataJson, action));
                }
                String merged = chatClient.chat(mergePrompt);
                merged = PromptTemplate.stripConversationalFiller(stripMarkdownFences(merged));
                merged = linkWritingService.sanitizeSourceLinks(merged);
                merged = linkWritingService.sanitizeWikiLinks(merged, scopeId);
                contentCollector.put(affectedPath, merged);
                storageProvider.write(scopeIdStr, "wiki/" + affectedPath, merged.getBytes(StandardCharsets.UTF_8));
            } finally {
                llmBarrier.release(LlmConcurrencyBarrier.Bucket.ENTITY);
            }

            existing.setHealthStatus(healthStatus);
            existing.setSourceCount(existing.getSourceCount() + 1);
            existing.setSchemaVersion(schemaManager.getCurrentVersionId(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY));
            existing.setContentUpdatedAt(java.time.LocalDateTime.now());
            wikiPageMapper.updateById(existing);
            persistSourceRelation(scopeId, existing.getId(), sourceId);
            persistTagsAndKeywords(scopeId, existing.getId(), metadataJson);
            lintFindingService.resolvePageFindingsOnIngest(scopeId, existing.getId());

            return existing;
        } catch (Exception e) {
            log.error("updateRelatedPage failed for '{}': {}", affectedPath, e.getMessage());
            return null;
        }
    }

    public WikiPageDO modifyExistingPage(Long scopeId, String pagePath, String instruction,
                                          String analysisContext, String metadataJson, Long sourceId) {
        try {
            WikiPageDO existing = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getFilePath, pagePath)
            );
            if (existing == null) {
                log.warn("modifyExistingPage: page not found for path='{}' scopeId={}", pagePath, scopeId);
                return null;
            }

            String scopeIdStr = String.valueOf(scopeId);
            byte[] existingBytes = storageProvider.read(scopeIdStr, "wiki/" + pagePath);
            if (existingBytes == null) {
                log.warn("modifyExistingPage: content not found for path='{}'", pagePath);
                return null;
            }
            String existingContent = new String(existingBytes, StandardCharsets.UTF_8);

            if (!acquireBarrier(LlmConcurrencyBarrier.Bucket.ENTITY, "modifyExistingPage:" + pagePath)) {
                return null;
            }
            try {
                String modifyPrompt = schemaInjector.prependForWriter(scopeId,
                    PromptRegistry.forPageModify().modifyPage(existingContent, instruction, analysisContext));
                String merged = chatClient.chat(modifyPrompt);
                merged = PromptTemplate.stripConversationalFiller(stripMarkdownFences(merged));
                merged = linkWritingService.sanitizeSourceLinks(merged);
                merged = linkWritingService.sanitizeWikiLinks(merged, scopeId);
                storageProvider.write(scopeIdStr, "wiki/" + pagePath, merged.getBytes(StandardCharsets.UTF_8));
            } finally {
                llmBarrier.release(LlmConcurrencyBarrier.Bucket.ENTITY);
            }

            existing.setHealthStatus("healthy");
            existing.setSourceCount(existing.getSourceCount() == null ? 1 : existing.getSourceCount() + 1);
            existing.setSchemaVersion(schemaManager.getCurrentVersionId(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY));
            existing.setContentUpdatedAt(java.time.LocalDateTime.now());
            wikiPageMapper.updateById(existing);
            persistSourceRelation(scopeId, existing.getId(), sourceId);
            persistTagsAndKeywords(scopeId, existing.getId(), metadataJson);
            lintFindingService.resolvePageFindingsOnIngest(scopeId, existing.getId());

            return existing;
        } catch (Exception e) {
            log.error("modifyExistingPage failed for '{}': {}", pagePath, e.getMessage());
            return null;
        }
    }

    private void persistSourceRelation(Long scopeId, Long pageId, Long sourceId) {
        WikiPageSourceDO existing = wikiPageSourceMapper.selectOne(
            new LambdaQueryWrapper<WikiPageSourceDO>()
                .eq(WikiPageSourceDO::getScopeId, scopeId)
                .eq(WikiPageSourceDO::getPageId, pageId)
                .eq(WikiPageSourceDO::getSourceId, sourceId)
        );
        if (existing == null) {
            WikiPageSourceDO relation = new WikiPageSourceDO();
            relation.setScopeId(scopeId);
            relation.setPageId(pageId);
            relation.setSourceId(sourceId);
            wikiPageSourceMapper.insert(relation);
        }
    }

    private void persistTagsAndKeywords(Long scopeId, Long pageId, String metadataJson) {
        if (pageId == null || metadataJson == null || metadataJson.isBlank()) return;
        try {
            List<String> tags = extractJsonArray(metadataJson, "tags");
            List<String> newTags = tags.stream().map(String::trim).filter(t -> !t.isEmpty()).toList();
            if (!newTags.isEmpty()) {
                Set<String> existingTags = wikiPageTagMapper.selectList(
                    new LambdaQueryWrapper<WikiPageTagDO>()
                        .eq(WikiPageTagDO::getScopeId, scopeId)
                        .eq(WikiPageTagDO::getPageId, pageId)
                ).stream().map(WikiPageTagDO::getTag).collect(Collectors.toSet());

                for (String t : newTags) {
                    if (!existingTags.contains(t)) {
                        WikiPageTagDO tagDO = new WikiPageTagDO();
                        tagDO.setScopeId(scopeId);
                        tagDO.setPageId(pageId);
                        tagDO.setTag(t);
                        wikiPageTagMapper.insert(tagDO);
                    }
                }
            }

            List<String> keywords = extractJsonArray(metadataJson, "keywords");
            List<String> newKeywords = keywords.stream().map(String::trim).filter(k -> !k.isEmpty()).toList();
            if (!newKeywords.isEmpty()) {
                Set<String> existingKeywords = wikiPageKeywordMapper.selectList(
                    new LambdaQueryWrapper<WikiPageKeywordDO>()
                        .eq(WikiPageKeywordDO::getScopeId, scopeId)
                        .eq(WikiPageKeywordDO::getPageId, pageId)
                ).stream().map(WikiPageKeywordDO::getKeyword).collect(Collectors.toSet());

                for (String k : newKeywords) {
                    if (!existingKeywords.contains(k)) {
                        WikiPageKeywordDO kwDO = new WikiPageKeywordDO();
                        kwDO.setScopeId(scopeId);
                        kwDO.setPageId(pageId);
                        kwDO.setKeyword(k);
                        wikiPageKeywordMapper.insert(kwDO);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("persistTagsAndKeywords failed: scopeId={}, pageId={}", scopeId, pageId, e);
        }
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

    private String generatePagePath(String metadataJson) {
        String title;
        if (metadataJson != null && metadataJson.contains("title")) {
            title = extractJsonField(metadataJson, "title");
        } else {
            title = metadataJson != null ? metadataJson : "untitled";
        }
        if (title == null || title.isEmpty()) title = "untitled";
        String normalized = title.toLowerCase();
        String sanitized = normalized.replaceAll("[^a-z0-9\\u4e00-\\u9fff_-]", "-");
        sanitized = sanitized.replaceAll("-+", "-");
        if (sanitized.startsWith("-")) sanitized = sanitized.substring(1);
        if (sanitized.endsWith("-")) sanitized = sanitized.substring(0, sanitized.length() - 1);
        if (sanitized.isEmpty()) sanitized = "untitled";
        return "pages/" + sanitized + ".md";
    }

    private WikiPageDO findPageByTitleIgnoreCase(Long scopeId, String title) {
        if (title == null || title.isEmpty()) return null;
        return wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .eq(WikiPageDO::getLifecycleStatus, PageLifecycle.ACTIVE.name())
                .apply("LOWER(title) = {0}", title.toLowerCase())
                .last("LIMIT 1")
        );
    }

    private String deriveTitleFromPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        String name = filePath;
        if (name.startsWith("pages/")) name = name.substring("pages/".length());
        if (name.endsWith(".md")) name = name.substring(0, name.length() - ".md".length());
        if (name.isEmpty()) return null;
        return name.replace("-", " ");
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

    private List<String> extractJsonArray(String json, String field) {
        try {
            int start = json.indexOf("{");
            int end = json.lastIndexOf("}") + 1;
            String cleanJson = (start >= 0 && end > start) ? json.substring(start, end) : json;
            JsonNode node = objectMapper.readTree(cleanJson);
            if (node.has(field) && node.get(field).isArray()) {
                List<String> result = new ArrayList<>();
                for (JsonNode item : node.get(field)) result.add(item.asText());
                return result;
            }
        } catch (Exception e) {
            log.debug("extractJsonArray failed for '{}': {}", field, e.getMessage());
        }
        return List.of();
    }

    private List<Map<String, String>> parseEntities(String metadataJson) {
        try {
            int start = metadataJson.indexOf("{");
            int end = metadataJson.lastIndexOf("}") + 1;
            String cleanJson = (start >= 0 && end > start) ? metadataJson.substring(start, end) : metadataJson;
            JsonNode node = objectMapper.readTree(cleanJson);
            if (node.has("entities") && node.get("entities").isArray()) {
                List<Map<String, String>> result = new ArrayList<>();
                for (JsonNode entity : node.get("entities")) {
                    Map<String, String> map = new HashMap<>();
                    map.put("name", entity.has("name") ? entity.get("name").asText() : "");
                    map.put("type", entity.has("type") ? entity.get("type").asText() : "concept");
                    if (entity.has("aliases") && entity.get("aliases").isArray()) {
                        List<String> aliases = new ArrayList<>();
                        for (JsonNode alias : entity.get("aliases")) {
                            aliases.add(alias.asText());
                        }
                        map.put("aliases", String.join(",", aliases));
                    }
                    map.put("significance", entity.has("significance") ? entity.get("significance").asText() : "important");
                    result.add(map);
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("parseEntities failed: {}", e.getMessage());
        }
        return List.of();
    }

    private List<Map<String, String>> parseAffectedPages(String metadataJson) {
        try {
            int start = metadataJson.indexOf("{");
            int end = metadataJson.lastIndexOf("}") + 1;
            String cleanJson = (start >= 0 && end > start) ? metadataJson.substring(start, end) : metadataJson;
            JsonNode node = objectMapper.readTree(cleanJson);
            if (node.has("affectedPages") && node.get("affectedPages").isArray()) {
                List<Map<String, String>> result = new ArrayList<>();
                for (JsonNode page : node.get("affectedPages")) {
                    Map<String, String> map = new HashMap<>();
                    map.put("path", page.has("path") ? page.get("path").asText() : "");
                    map.put("action", page.has("action") ? page.get("action").asText() : "补充");
                    result.add(map);
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("parseAffectedPages failed: {}", e.getMessage());
        }
        return List.of();
    }

    private Set<String> findSourceRelatedPagePaths(Long scopeId, Long sourceId) {
        try {
            List<WikiPageSourceDO> relations = wikiPageSourceMapper.selectList(
                new LambdaQueryWrapper<WikiPageSourceDO>()
                    .eq(WikiPageSourceDO::getScopeId, scopeId)
                    .eq(WikiPageSourceDO::getSourceId, sourceId)
            );
            Set<String> paths = new HashSet<>();
            for (WikiPageSourceDO rel : relations) {
                WikiPageDO page = wikiPageMapper.selectById(rel.getPageId());
                if (page != null && page.getFilePath() != null && PageLifecycle.ACTIVE.name().equals(page.getLifecycleStatus())) {
                    paths.add(page.getFilePath());
                }
            }
            return paths;
        } catch (Exception e) {
            log.warn("findSourceRelatedPagePaths failed: {}", e.getMessage());
            return Set.of();
        }
    }

    private List<Map<String, String>> mergeAffectedPagesWithSourceRelations(
            List<Map<String, String>> llmAffectedPages, Set<String> sourceRelatedPaths) {
        Set<String> coveredPaths = new HashSet<>();
        List<Map<String, String>> merged = new ArrayList<>();
        for (Map<String, String> page : llmAffectedPages) {
            String path = page.get("path");
            if (path != null && !path.isBlank()) {
                coveredPaths.add(path);
            }
            merged.add(page);
        }
        for (String path : sourceRelatedPaths) {
            if (!coveredPaths.contains(path)) {
                log.info("Programmatically adding source-related page to affectedPages: {}", path);
                Map<String, String> extra = new HashMap<>();
                extra.put("path", path);
                extra.put("action", "更新");
                merged.add(extra);
            }
        }
        return merged;
    }

    private String deriveEntityCategory(String entityType, String metadataJson) {
        String mainCategory = extractJsonField(metadataJson, "category");
        if (mainCategory != null && !mainCategory.isEmpty()) {
            String topLevel = mainCategory.contains("/") ? mainCategory.substring(0, mainCategory.indexOf("/")) : mainCategory;
            return topLevel + "/" + translateEntityType(entityType);
        }
        return translateEntityType(entityType);
    }

    private String translateEntityType(String entityType) {
        if (entityType == null) return "概念";
        return switch (entityType.toLowerCase()) {
            case "person", "人物" -> "人物";
            case "organization", "组织" -> "组织";
            case "system", "系统" -> "系统";
            case "concept", "概念" -> "概念";
            case "document", "文档" -> "文档";
            case "event", "事件" -> "事件";
            default -> entityType;
        };
    }

    private void syncPageToIndex(WikiPageDO pageDO, Long scopeId) {
        try {
            String content = "";
            String storagePath = "wiki/" + pageDO.getFilePath();
            if (storageProvider.exists(String.valueOf(scopeId), storagePath)) {
                byte[] contentBytes = storageProvider.read(String.valueOf(scopeId), storagePath);
                if (contentBytes != null) {
                    content = new String(contentBytes, StandardCharsets.UTF_8);
                } else {
                    log.warn("syncPageToIndex: file read returned null for storagePath={}", storagePath);
                }
            } else {
                log.warn("syncPageToIndex: file not found at storagePath={}", storagePath);
            }
            if (content.isEmpty()) {
                log.warn("syncPageToIndex: content is empty for pageId={}, title={}", pageDO.getId(), pageDO.getTitle());
            }
            searchService.indexPage(
                scopeId, pageDO.getId(), pageDO.getTitle(), pageDO.getFilePath(),
                pageDO.getCategory(), pageDO.getSummary(), content,
                pageDO.getHealthStatus(), pageDO.getVisibility(),
                pageDO.getLifecycleStatus()
            );
        } catch (Exception e) {
            log.error("syncPageToIndex failed: pageId={}, title={}, error={}", pageDO.getId(), pageDO.getTitle(), e.getMessage(), e);
        }
    }

    private void buildEntityChunkMap(IngestContext context) {
        Map<String, List<Integer>> map = new HashMap<>();
        List<DocumentChunker.Chunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) return;

        String metadataJson = context.getMetadataJson();
        List<Map<String, String>> entities = parseEntities(metadataJson);
        for (Map<String, String> entity : entities) {
            String name = entity.get("name");
            if (name == null || name.isBlank()) continue;
            List<String> searchTerms = new ArrayList<>();
            searchTerms.add(name);
            String aliasesStr = entity.get("aliases");
            if (aliasesStr != null && !aliasesStr.isEmpty()) {
                for (String alias : aliasesStr.split(",")) {
                    String trimmed = alias.trim();
                    if (!trimmed.isEmpty() && !trimmed.equals(name)) {
                        searchTerms.add(trimmed);
                    }
                }
            }
            List<Integer> matchingChunks = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunkContent = chunks.get(i).content();
                for (String term : searchTerms) {
                    if (chunkContent.contains(term)) {
                        matchingChunks.add(i);
                        break;
                    }
                }
            }
            if (!matchingChunks.isEmpty()) {
                map.put(name, matchingChunks);
            }
        }
        context.setEntityChunkMap(map);
        log.info("buildEntityChunkMap: mapped {} entities to chunks for scopeId={}", map.size(), context.getScopeId());
    }

    public record EntityContextualRef(String name, String type, String significance, int chunkCount, int mentionLines) {}

    private double computeProgrammaticScore(String entityName, Map<String, String> entityMeta, IngestContext context, int maxMentionsAcrossAll) {
        Map<String, List<Integer>> chunkMap = context.getEntityChunkMap();
        List<DocumentChunker.Chunk> chunks = context.getChunks();
        if (chunkMap == null || chunks == null || chunks.isEmpty()) return 0.0;
        if (!chunkMap.containsKey(entityName)) return 0.0;
        List<Integer> matchingChunks = chunkMap.get(entityName);
        int totalChunks = chunks.size();
        int chunkCount = matchingChunks.size();

        double chunkDistScore = (double) chunkCount / totalChunks;

        double headingScore = 0.0;
        String sourceContent = context.getSourceContent();
        if (sourceContent != null) {
            String[] lines = sourceContent.split("\n");
            for (String line : lines) {
                if (line.matches("^#{1,6}\\s+.*") && line.contains(entityName)) {
                    headingScore = 1.0;
                    break;
                }
            }
        }

        double firstSectionScore = 0.0;
        int firstQuarterChunks = Math.max(1, totalChunks / 4);
        for (int idx : matchingChunks) {
            if (idx < firstQuarterChunks) {
                firstSectionScore = 1.0;
                break;
            }
        }

        int mentionLines = 0;
        List<String> searchTerms = new ArrayList<>();
        searchTerms.add(entityName);
        if (entityMeta != null && entityMeta.containsKey("aliases")) {
            String aliasesStr = entityMeta.get("aliases");
            if (aliasesStr != null && !aliasesStr.isEmpty()) {
                for (String alias : aliasesStr.split(",")) {
                    String trimmed = alias.trim();
                    if (!trimmed.isEmpty() && !trimmed.equals(entityName)) {
                        searchTerms.add(trimmed);
                    }
                }
            }
        }
        for (int idx : matchingChunks) {
            String[] chunkLines = chunks.get(idx).content().split("\n");
            for (String line : chunkLines) {
                for (String term : searchTerms) {
                    if (line.contains(term)) {
                        mentionLines++;
                        break;
                    }
                }
            }
        }
        double densityScore = maxMentionsAcrossAll > 0 ? Math.min((double) mentionLines / maxMentionsAcrossAll, 1.0) : 0.0;

        return chunkDistScore * 0.35 + headingScore * 0.30 + firstSectionScore * 0.20 + densityScore * 0.15;
    }

    private static final int MAX_ENTITY_PAGES_PER_SOURCE = 20;

    private ClassificationResult classifyEntities(List<Map<String, String>> entities, IngestContext context) {
        List<Map<String, String>> coreAndImportant = new ArrayList<>();
        List<EntityContextualRef> contextuals = new ArrayList<>();
        if (entities.isEmpty()) return new ClassificationResult(coreAndImportant, contextuals);

        Map<String, List<Integer>> chunkMap = context.getEntityChunkMap();
        int totalChunks = context.getChunks() != null ? context.getChunks().size() : 1;

        int maxMentions = 0;
        for (Map<String, String> entity : entities) {
            String name = entity.get("name");
            if (name == null || name.isBlank()) continue;
            maxMentions = Math.max(maxMentions, countMentionLines(name, entity, chunkMap, context.getChunks()));
        }

        for (Map<String, String> entity : entities) {
            String name = entity.get("name");
            String llmSig = entity.getOrDefault("significance", "important");
            if (name == null || name.isBlank()) continue;

            double progScore = computeProgrammaticScore(name, entity, context, maxMentions);
            int chunkCount = chunkMap != null && chunkMap.containsKey(name) ? chunkMap.get(name).size() : 0;
            int mentionLines = countMentionLines(name, entity, chunkMap, context.getChunks());

            if ("core".equals(llmSig)) {
                if (mentionLines < 2 && totalChunks > 2) {
                    contextuals.add(new EntityContextualRef(name, entity.getOrDefault("type", "concept"), llmSig, chunkCount, mentionLines));
                    log.info("Entity '{}' (core) downgraded to contextual: mentionLines={}, progScore={}", name, mentionLines, String.format("%.3f", progScore));
                } else {
                    coreAndImportant.add(entity);
                }
            } else if ("important".equals(llmSig)) {
                boolean shouldDowngrade;
                if (totalChunks <= 2) {
                    shouldDowngrade = mentionLines < 2;
                } else {
                    shouldDowngrade = progScore < 0.10 || mentionLines < 2;
                }
                if (shouldDowngrade) {
                    contextuals.add(new EntityContextualRef(name, entity.getOrDefault("type", "concept"), llmSig, chunkCount, mentionLines));
                    log.info("Entity '{}' downgraded to contextual: progScore={}, mentionLines={}, chunks={}", name, String.format("%.3f", progScore), mentionLines, chunkCount);
                } else {
                    coreAndImportant.add(entity);
                }
            } else {
                if (progScore > 0.35 && mentionLines >= 4) {
                    coreAndImportant.add(entity);
                    log.info("Entity '{}' upgraded to important: progScore={}, mentionLines={}, chunks={}", name, String.format("%.3f", progScore), mentionLines, chunkCount);
                } else {
                    contextuals.add(new EntityContextualRef(name, entity.getOrDefault("type", "concept"), llmSig, chunkCount, mentionLines));
                }
            }
        }

        if (coreAndImportant.size() > MAX_ENTITY_PAGES_PER_SOURCE) {
            final IngestContext finalContext = context;
            final int finalMaxMentions = maxMentions;
            coreAndImportant.sort((a, b) -> {
                String nameA = a.get("name");
                String nameB = b.get("name");
                double scoreA = computeProgrammaticScore(nameA, a, finalContext, finalMaxMentions);
                double scoreB = computeProgrammaticScore(nameB, b, finalContext, finalMaxMentions);
                return Double.compare(scoreB, scoreA);
            });
            List<Map<String, String>> overflow = new ArrayList<>(coreAndImportant.subList(MAX_ENTITY_PAGES_PER_SOURCE, coreAndImportant.size()));
            coreAndImportant = new ArrayList<>(coreAndImportant.subList(0, MAX_ENTITY_PAGES_PER_SOURCE));
            for (Map<String, String> entity : overflow) {
                String name = entity.get("name");
                int chunkCount = chunkMap != null && chunkMap.containsKey(name) ? chunkMap.get(name).size() : 0;
                int mentionLines = countMentionLines(name, entity, chunkMap, context.getChunks());
                contextuals.add(new EntityContextualRef(name, entity.getOrDefault("type", "concept"), "overflow", chunkCount, mentionLines));
                log.info("Entity '{}' overflow: exceeded MAX_ENTITY_PAGES_PER_SOURCE={}, demoted to contextual", name, MAX_ENTITY_PAGES_PER_SOURCE);
            }
        }

        log.info("classifyEntities: {} core+important, {} contextual for scopeId={}",
            coreAndImportant.size(), contextuals.size(), context.getScopeId());
        return new ClassificationResult(coreAndImportant, contextuals);
    }

    private int countMentionLines(String entityName, Map<String, String> entityMeta, Map<String, List<Integer>> chunkMap, List<DocumentChunker.Chunk> chunks) {
        if (chunkMap == null || !chunkMap.containsKey(entityName) || chunks == null) return 0;
        List<String> searchTerms = new ArrayList<>();
        searchTerms.add(entityName);
        if (entityMeta != null && entityMeta.containsKey("aliases")) {
            String aliasesStr = entityMeta.get("aliases");
            if (aliasesStr != null && !aliasesStr.isEmpty()) {
                for (String alias : aliasesStr.split(",")) {
                    String trimmed = alias.trim();
                    if (!trimmed.isEmpty() && !trimmed.equals(entityName)) {
                        searchTerms.add(trimmed);
                    }
                }
            }
        }
        int count = 0;
        for (int idx : chunkMap.get(entityName)) {
            String[] lines = chunks.get(idx).content().split("\n");
            for (String line : lines) {
                for (String term : searchTerms) {
                    if (line.contains(term)) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }

    private record ClassificationResult(List<Map<String, String>> coreAndImportant, List<EntityContextualRef> contextuals) {}

    private String buildEntitySourceFromChunks(String entityName, Map<String, String> entityMeta, IngestContext context) {
        ExecutionStrategy strategy = context.getStrategy();
        int maxEntityChars = strategy != null ? strategy.getMaxSourceCharsEntity() : PromptTemplate.MAX_SOURCE_CHARS_ENTITY;
    
        String baseSource = null;
    
        EntityDossier dossier = context.getEntityDossier(entityName);
        if (dossier != null) {
            String formatted = dossier.formatForPrompt(maxEntityChars);
            if (formatted != null && !formatted.isBlank()) {
                baseSource = formatted;
            }
        }
    
        if (baseSource == null) {
            Map<String, String> subDocSourceMap = context.getSubDocumentSourceMap();
            if (subDocSourceMap != null && subDocSourceMap.containsKey(entityName)) {
                String subDocSource = subDocSourceMap.get(entityName);
                if (subDocSource != null && !subDocSource.isBlank()) {
                    baseSource = subDocSource;
                }
            }
        }
    
        if (baseSource == null) {
            Map<String, List<Integer>> entityChunkMap = context.getEntityChunkMap();
            if (entityChunkMap != null) {
                List<DocumentChunker.Chunk> chunks = context.getChunks();
                if (entityChunkMap.containsKey(entityName) && chunks != null) {
                    List<Integer> chunkIndices = entityChunkMap.get(entityName);
                    StringBuilder sb = new StringBuilder();
                    for (int idx : chunkIndices) {
                        if (idx < chunks.size()) {
                            sb.append("## \u6765\u6e90\u7247\u6bb5 ").append(idx + 1).append("\n");
                            sb.append(chunks.get(idx).content()).append("\n\n");
                        }
                    }
                    baseSource = sb.toString();
                } else {
                    List<String> aliases = new ArrayList<>();
                    if (entityMeta != null && entityMeta.containsKey("aliases")) {
                        String aliasesStr = entityMeta.get("aliases");
                        for (String alias : aliasesStr.split(",")) {
                            String trimmed = alias.trim();
                            if (!trimmed.isEmpty()) {
                                aliases.add(trimmed);
                            }
                        }
                    }
                    if (!aliases.isEmpty() && chunks != null) {
                        for (String alias : aliases) {
                            for (int i = 0; i < chunks.size(); i++) {
                                if (chunks.get(i).content().contains(alias)) {
                                    StringBuilder sb = new StringBuilder();
                                    sb.append("## \u6765\u6e90\u7247\u6bb5 ").append(i + 1).append(" (\u901a\u8fc7\u522b\u540d\u5339\u914d)\n");
                                    sb.append(chunks.get(i).content()).append("\n\n");
                                    baseSource = sb.toString();
                                    break;
                                }
                            }
                            if (baseSource != null) break;
                        }
                    }
                }
            }
        }
    
        if (baseSource == null) {
            String sourceContent = context.getSourceContent();
            baseSource = filterSourceContentForEntity(sourceContent, entityName, maxEntityChars);
        }
    
        // Enrich entity source with relevant chart description blocks
        // Charts contain structured quantitative data (tables, key points, trends)
        // that are critical for entity pages but often missed by name-based chunk matching
        return appendChartBlocksToSource(baseSource, entityName, context, maxEntityChars);
    }
    
    /**
     * Append relevant chart description blocks to entity source content.
     * Charts are matched by: (1) entity name/alias mention, (2) page proximity,
     * or (3) included wholesale if few charts exist (high-value quantitative data).
     */
    private String appendChartBlocksToSource(String baseSource, String entityName, IngestContext context, int maxEntityChars) {
        List<String> chartBlocks = context.getChartDescriptionBlocks();
        if (chartBlocks == null || chartBlocks.isEmpty()) {
            return PromptTemplate.sampleSourceContent(baseSource, maxEntityChars);
        }
    
        // Find relevant chart blocks: mention entity name or contain it
        List<String> relevantCharts = new ArrayList<>();
        for (String block : chartBlocks) {
            if (baseSource.contains(block)) continue; // already in source
            if (block.contains(entityName)) {
                relevantCharts.add(block);
            }
        }
    
        // If no name-matched charts found but few total charts, include all
        // (financial reports typically have charts relevant to most entities)
        if (relevantCharts.isEmpty() && chartBlocks.size() <= 6) {
            for (String block : chartBlocks) {
                if (!baseSource.contains(block)) {
                    relevantCharts.add(block);
                }
            }
        }
    
        if (relevantCharts.isEmpty()) {
            return PromptTemplate.sampleSourceContent(baseSource, maxEntityChars);
        }
    
        // Combine: base source + chart data section
        StringBuilder combined = new StringBuilder(baseSource);
        combined.append("\n\n## \u6e90\u6587\u6863\u56fe\u8868\u6570\u636e\n\n");
        combined.append("\u4ee5\u4e0b\u6570\u636e\u6765\u81ea\u6e90\u6587\u6863\u4e2d\u7684\u56fe\u8868\u5206\u6790\uff0c\u5305\u542b\u7ed3\u6784\u5316\u6570\u636e\u8868\u683c\u548c\u5173\u952e\u6307\u6807\uff0c\u8bf7\u5728\u5199\u4f5c\u65f6\u4f18\u5148\u91c7\u7eb3\u3002\n\n");
        for (String block : relevantCharts) {
            combined.append(block).append("\n\n");
        }
    
        return PromptTemplate.sampleSourceContent(combined.toString(), maxEntityChars);
    }

    /**
     * Enrich summary page source content with all chart description blocks.
     * Summary pages cover the entire document, so all chart data is relevant.
     */
    private String enrichSummarySourceWithCharts(String baseSource, IngestContext context) {
        List<String> chartBlocks = context.getChartDescriptionBlocks();
        if (chartBlocks == null || chartBlocks.isEmpty()) {
            return baseSource;
        }
        StringBuilder combined = new StringBuilder(baseSource);
        combined.append("\n\n## \u6e90\u6587\u6863\u56fe\u8868\u6570\u636e\n\n");
        combined.append("\u4ee5\u4e0b\u6570\u636e\u6765\u81ea\u6e90\u6587\u6863\u4e2d\u7684\u56fe\u8868\u5206\u6790\uff0c\u5305\u542b\u7ed3\u6784\u5316\u6570\u636e\u8868\u683c\u548c\u5173\u952e\u6307\u6807\uff0c\u8bf7\u5728\u5199\u4f5c\u65f6\u4f18\u5148\u91c7\u7eb3\u3002\n\n");
        for (String block : chartBlocks) {
            if (!baseSource.contains(block)) {
                combined.append(block).append("\n\n");
            }
        }
        return combined.toString();
    }

    private String filterSourceContentForEntity(String sourceContent, String entityName, int maxEntityChars) {
        if (sourceContent == null || sourceContent.isEmpty() || entityName == null || entityName.isEmpty()) {
            return PromptTemplate.sampleSourceContent(sourceContent, maxEntityChars);
        }
        Set<Integer> includedIndices = new HashSet<>();
        String[] lines = sourceContent.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(entityName)) {
                for (int j = Math.max(0, i - 2); j <= Math.min(lines.length - 1, i + 2); j++) {
                    includedIndices.add(j);
                }
            }
        }
        if (includedIndices.isEmpty()) {
            return PromptTemplate.sampleSourceContent(sourceContent, maxEntityChars);
        }
        List<Integer> sortedIndices = new ArrayList<>(includedIndices);
        sortedIndices.sort(Integer::compareTo);
        StringBuilder sb = new StringBuilder();
        for (int idx : sortedIndices) {
            sb.append(lines[idx]).append("\n");
        }
        return PromptTemplate.sampleSourceContent(sb.toString(), maxEntityChars);
    }

    private String filterSourceContentForRelated(String sourceContent, String affectedPath, int maxRelatedChars) {
        if (sourceContent == null || sourceContent.isEmpty()) {
            return PromptTemplate.sampleSourceContent(sourceContent, maxRelatedChars);
        }
        String titleFromPath = extractTitleFromPath(affectedPath);
        if (titleFromPath == null || titleFromPath.isEmpty()) {
            return PromptTemplate.sampleSourceContent(sourceContent, maxRelatedChars);
        }
        List<String> relevantLines = new ArrayList<>();
        String[] lines = sourceContent.split("\n");
        for (String line : lines) {
            if (line.contains(titleFromPath)) {
                relevantLines.add(line);
            }
        }
        if (relevantLines.isEmpty()) {
            return PromptTemplate.sampleSourceContent(sourceContent, maxRelatedChars);
        }
        String filtered = relevantLines.stream().collect(Collectors.joining("\n"));
        return PromptTemplate.sampleSourceContent(filtered, maxRelatedChars);
    }

    private String filterAnalysisForEntity(String analysisResult, String entityName, int maxAnalysisChars, IngestContext context) {
        if (analysisResult == null || analysisResult.isEmpty() || entityName == null || entityName.isEmpty()) {
            return analysisResult;
        }

        EntityDossier dossier = context != null ? context.getEntityDossier(entityName) : null;
        if (dossier != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("【实体导航】\n");

            String entitySection = extractSectionByHeader(analysisResult, "主要实体");
            if (entitySection != null && !entitySection.isEmpty()) {
                sb.append(entitySection).append("\n\n");
            }

            if (dossier.containingSections() != null && !dossier.containingSections().isEmpty()) {
                sb.append("【出现章节】").append(String.join("、", dossier.containingSections())).append("\n\n");
            }

            if (dossier.relatedEntities() != null && !dossier.relatedEntities().isEmpty()) {
                sb.append("【关联实体】\n");
                for (String related : dossier.relatedEntities()) {
                    String hint = dossier.relationshipHints() != null ? dossier.relationshipHints().get(related) : null;
                    if (hint != null) {
                        sb.append("- ").append(related).append("（").append(hint).append("）\n");
                    } else {
                        sb.append("- ").append(related).append("\n");
                    }
                }
                sb.append("\n");
            }

            String relationSection = extractSectionByHeader(analysisResult, "关系");
            if (relationSection != null && !relationSection.isEmpty() && relationSection.contains(entityName)) {
                sb.append("【实体间关系】\n").append(relationSection).append("\n\n");
            }

            String factSection = extractSectionByHeader(analysisResult, "关键事实");
            if (factSection != null && !factSection.isEmpty()) {
                String[] factLines = factSection.split("\n");
                List<String> relevantFacts = new ArrayList<>();
                for (String line : factLines) {
                    if (line.contains(entityName) || dossier.aliases().stream().anyMatch(line::contains)) {
                        relevantFacts.add(line);
                    }
                }
                if (!relevantFacts.isEmpty()) {
                    sb.append("【相关关键事实】\n").append(String.join("\n", relevantFacts)).append("\n");
                }
            }

            String result = sb.toString();
            if (result.length() <= maxAnalysisChars) return result;
            return result.substring(0, maxAnalysisChars) + "\n...(已裁剪)";
        }

        List<String> relevantLines = new ArrayList<>();
        String[] lines = analysisResult.split("\n");
        boolean inRelevantSection = false;
        for (String line : lines) {
            if (line.contains(entityName)) {
                inRelevantSection = true;
            }
            if (inRelevantSection) {
                relevantLines.add(line);
                if (line.trim().isEmpty() && relevantLines.size() > 3) {
                    inRelevantSection = false;
                }
            }
        }

        String entitySection = extractSectionByHeader(analysisResult, "主要实体");
        if (entitySection != null && !entitySection.isEmpty()) {
            relevantLines.add(0, "【实体导航】");
            relevantLines.add(1, entitySection);
        }

        String relationSection = extractSectionByHeader(analysisResult, "关系");
        if (relationSection != null && !relationSection.isEmpty() && relationSection.contains(entityName)) {
            relevantLines.add("【实体间关系（含" + entityName + "）】");
            relevantLines.add(relationSection);
        }

        if (relevantLines.isEmpty()) {
            int fallbackThreshold = Math.min(maxAnalysisChars, 4000);
            if (analysisResult.length() <= fallbackThreshold) return analysisResult;
            return analysisResult.substring(0, fallbackThreshold) + "\n...(已裁剪，完整分析请参考摘要页)";
        }

        String filtered = relevantLines.stream().collect(Collectors.joining("\n"));
        if (filtered.length() > maxAnalysisChars) {
            filtered = filtered.substring(0, maxAnalysisChars) + "\n...(已裁剪)";
        }
        return filtered;
    }

    private String filterAnalysisForRelated(String analysisResult, String affectedPath, String existingContent, int maxAnalysisChars, IngestContext context) {
        if (analysisResult == null || analysisResult.isEmpty()) {
            return analysisResult;
        }

        String titleFromPath = extractTitleFromPath(affectedPath);
        List<String> relevantLines = new ArrayList<>();
        String[] lines = analysisResult.split("\n");
        boolean inRelevantSection = false;
        for (String line : lines) {
            if (titleFromPath != null && line.contains(titleFromPath)) {
                inRelevantSection = true;
            }
            if (inRelevantSection) {
                relevantLines.add(line);
                if (line.trim().isEmpty() && relevantLines.size() > 3) {
                    inRelevantSection = false;
                }
            }
        }

        String factSection = extractSectionByHeader(analysisResult, "关键事实");
        if (factSection != null && !factSection.isEmpty()) {
            relevantLines.add("【关键事实（用于差异识别）】");
            relevantLines.add(factSection);
        }

        if (context != null && context.getInformationCatalog() != null && titleFromPath != null) {
            InformationCatalog catalog = context.getInformationCatalog();
            InformationCatalog.EntityRecord pageEntity = catalog.getEntity(titleFromPath);
            if (pageEntity != null && pageEntity.coOccurringEntities() != null && !pageEntity.coOccurringEntities().isEmpty()) {
                relevantLines.add("【关联实体】");
                for (String coEntity : pageEntity.coOccurringEntities()) {
                    relevantLines.add("- " + coEntity);
                }
            }
        }

        if (relevantLines.isEmpty()) {
            int fallbackThreshold = Math.min(maxAnalysisChars, 3000);
            if (analysisResult.length() <= fallbackThreshold) return analysisResult;
            return analysisResult.substring(0, fallbackThreshold) + "\n...(已裁剪)";
        }

        String filtered = relevantLines.stream().collect(Collectors.joining("\n"));
        if (filtered.length() > maxAnalysisChars) {
            filtered = filtered.substring(0, maxAnalysisChars) + "\n...(已裁剪)";
        }
        return filtered;
    }

    private String extractSectionByHeader(String text, String headerKeyword) {
        if (text == null) return null;
        String[] lines = text.split("\n");
        StringBuilder section = new StringBuilder();
        boolean found = false;
        for (String line : lines) {
            if (!found && (line.startsWith("#") || line.startsWith("###") || line.startsWith("**"))
                && line.toLowerCase().contains(headerKeyword.toLowerCase())) {
                found = true;
                section.append(line).append("\n");
                continue;
            }
            if (found) {
                if (line.startsWith("#") || line.startsWith("###") || line.startsWith("**")) {
                    break;
                }
                section.append(line).append("\n");
            }
        }
        String result = section.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private String extractTitleFromPath(String path) {
        if (path == null || path.isEmpty()) return null;
        String filename = path;
        if (path.contains("/")) filename = path.substring(path.lastIndexOf("/") + 1);
        if (filename.endsWith(".md")) filename = filename.substring(0, filename.length() - 3);
        return filename;
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        int chineseCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') chineseCount++;
        }
        int nonChinese = text.length() - chineseCount;
        return chineseCount / 2 + nonChinese / 4;
    }

    private List<IngestContext.ConflictAnnotation> extractConflictMeta(String writingPlanJson) {
        if (writingPlanJson == null || writingPlanJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = parseToJsonNode(writingPlanJson);
            if (root == null || !root.has("conflictAnnotations")) {
                return List.of();
            }
            JsonNode annotations = root.get("conflictAnnotations");
            if (!annotations.isArray()) {
                return List.of();
            }
            List<IngestContext.ConflictAnnotation> result = new ArrayList<>();
            for (JsonNode ann : annotations) {
                String pagePath = ann.has("pagePath") ? ann.get("pagePath").asText() : "";
                String conflictType = ann.has("conflictType") ? ann.get("conflictType").asText() : "";
                String existingClaim = ann.has("existingClaim") ? ann.get("existingClaim").asText() : "";
                String newClaim = ann.has("newClaim") ? ann.get("newClaim").asText() : "";
                String resolution = ann.has("resolution") ? ann.get("resolution").asText() : "annotate_both";
                String sourceRef = ann.has("sourceRef") ? ann.get("sourceRef").asText() : "";
                if (!pagePath.isEmpty() && !conflictType.isEmpty()) {
                    result.add(new IngestContext.ConflictAnnotation(pagePath, conflictType, existingClaim, newClaim, resolution, sourceRef));
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("extractConflictMeta failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void processConflictAnnotations(Long scopeId, IngestContext context, List<IngestContext.ConflictAnnotation> annotations) {
        if (annotations.isEmpty()) {
            return;
        }
        context.setConflictAnnotations(annotations);
        for (IngestContext.ConflictAnnotation ann : annotations) {
            if ("annotate_and_patch".equals(ann.resolution()) && ann.conflictType().contains("definition")) {
                String patchHint = String.format("建议为「%s」的定义冲突制定明确裁决规则：现有定义「%s」 vs 新定义「%s」",
                    deriveTitleFromPath(ann.pagePath()), ann.existingClaim(), ann.newClaim());
                context.addSchemaPatchHint(patchHint);
                log.info("Schema patch hint generated for scopeId={}: {}", scopeId, patchHint);
            }
        }
    }

    private boolean hasConflictAnnotationsForPath(List<IngestContext.ConflictAnnotation> annotations, String pagePath) {
        if (annotations == null || annotations.isEmpty() || pagePath == null) {
            return false;
        }
        return annotations.stream().anyMatch(ann -> ann.pagePath().equals(pagePath));
    }
}