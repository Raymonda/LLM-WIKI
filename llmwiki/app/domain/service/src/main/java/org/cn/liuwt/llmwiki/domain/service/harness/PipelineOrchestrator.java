package org.cn.liuwt.llmwiki.domain.service.harness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.UserDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageKeywordDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageTagDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionHistoryService;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.UserMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageKeywordMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageTagMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SchemaConfigMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.common.util.FileFormatValidator;
import org.cn.liuwt.llmwiki.common.util.constant.PageLifecycle;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.cn.liuwt.llmwiki.domain.service.harness.query.WikiReferencePathParser;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.harness.baseline.ExecutionBaselineService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.ApprovalService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.ApprovalService.ApprovalLevel;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.RateLimitService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaPatchProposer;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaPatchModel;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictDomainService;
import org.cn.liuwt.llmwiki.domain.service.harness.crossref.CrossRefDomainService;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.cn.liuwt.llmwiki.domain.service.wiki.CategoryNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

@Service
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private ApprovalService approvalService;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private WikiPageTagMapper wikiPageTagMapper;

    @Autowired
    private WikiPageKeywordMapper wikiPageKeywordMapper;

    @Autowired
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Autowired
    private ScopeMapper scopeMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private CategoryNormalizer categoryNormalizer;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private SchemaPatchProposer schemaPatchProposer;

    @Autowired
    private org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager schemaManager;

    @Autowired(required = false)
    private ExecutionBaselineService baselineService;

    @Autowired
    private org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestOrchestrator ingestOrchestrator;

    @Autowired
    private LinkWritingService linkWritingService;

    @Autowired
    private LintFindingService lintFindingService;

    @Autowired
    private SchemaConfigMapper schemaConfigMapper;

    @Autowired
    private org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaSection6Parser schemaSection6Parser;

    @Autowired
    private LlmConcurrencyBarrier llmConcurrencyBarrier;

    @Autowired
    private LintProbeService lintProbeService;

    @Autowired
    private ExecutionHistoryService executionHistoryService;

    @Autowired
    private GlobalSummaryService globalSummaryService;

    @Autowired
    private LintOrphanService lintOrphanService;

    @Autowired
    private CrossRefDomainService crossRefDomainService;

    @Autowired
    private ConflictDomainService conflictDomainService;

    @Autowired
    private LintFindingMapper lintFindingMapper;

    @Autowired(required = false)
    private KeywordBackfillService keywordBackfillService;

    @org.springframework.beans.factory.annotation.Value("${llmwiki.lint.auto-fix-max-per-run:50}")
    private int autoFixMaxPerRun;

    private static final int LINT_PER_EXECUTION_SOFT_LIMIT = 200000;
    private static final int LINT_STALE_BATCH_SIZE = 30;
    private static final int LINT_CROSSREF_MAX_CANDIDATES = 50;
    private static final int LINT_CROSSREF_MAX_LLM_CHECKS = 15;
    private static final int LINT_PARTITION_SIZE = 150;
    private static final int LINT_LARGE_SCALE_THRESHOLD = 150;
    private static final int LINT_SAMPLE_MAX = 30;
    private static final String LINT_WATERMARK_KEY = "lint_watermark";
    private static final String LINT_WATERMARK_GROUP = "lint";

    /**
     * Phase 1 新增：读取页面内容（用于 AI 判断交叉引用）
     */
    private String readPageContent(Long scopeId, String filePath) {
        try {
            String storagePath = filePath.startsWith("pages/") ? "wiki/" + filePath : filePath;
            byte[] contentBytes = storageProvider.read(String.valueOf(scopeId), storagePath);
            if (contentBytes != null) {
                return new String(contentBytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to read page content for scopeId={}, filePath={}: {}", scopeId, filePath, e.getMessage());
        }
        return "";
    }

    private static final String[] QUERY_SAVE_STEPS = {
        "FORMAT_ANSWER", "WRITE_SAVED_PAGE", "SAVE_LINKS"
    };

    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(4);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down parallelExecutor");
        parallelExecutor.shutdown();
        try {
            if (!parallelExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                parallelExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            parallelExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public ExecutionModel runIngestPipeline(Long scopeId, Long sourceId) {
        ExecutionModel execution = executionTracker.createExecution("ingest", scopeId, sourceId, null);
        return ingestOrchestrator.runIngestPipelineWithExecution(execution.getId(), scopeId, sourceId, null);
    }

    public ExecutionModel runIngestPipelineWithExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return ingestOrchestrator.runIngestPipelineWithExecution(executionId, scopeId, sourceId, guidance);
    }

    public ExecutionModel runIngestAnalysisWithExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return ingestOrchestrator.runIngestAnalysisWithExecution(executionId, scopeId, sourceId, guidance);
    }

    public ExecutionModel runIngestExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return ingestOrchestrator.runIngestExecution(executionId, scopeId, sourceId, guidance);
    }

    public ExecutionModel resumeIngestAnalysis(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return ingestOrchestrator.resumeIngestAnalysis(executionId, scopeId, sourceId, guidance);
    }

    public ExecutionModel resumeIngestExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return ingestOrchestrator.resumeIngestExecution(executionId, scopeId, sourceId, guidance);
    }

    public WikiPageDO runSaveQueryResultPipeline(Long scopeId, String question, String answer, String sessionId) {
        TokenUsageContext.set(scopeId, "query");
        try {
            return doRunSaveQueryResultPipeline(scopeId, question, answer, sessionId);
        } finally {
            TokenUsageContext.clear();
        }
    }

    private WikiPageDO doRunSaveQueryResultPipeline(Long scopeId, String question, String answer, String sessionId) {
        if (chatClient == null || !chatClient.isAvailable()) {
            throw new RuntimeException("AI 服务未配置或不可用");
        }

        ExecutionModel execution = executionTracker.createExecution("query_save", scopeId, null, null);
        executionTracker.updateExecutionStatus(execution.getId(), "running");

        int totalTokens = 0;

        ExecutionStepModel formatStep = executionTracker.createStep(execution.getId(), "FORMAT_ANSWER", 1, "AUTO");
        executionTracker.updateStepStatus(formatStep.getId(), "running");
        long formatStart = System.currentTimeMillis();
        String formattedContent;
        try {
            String formatPrompt = schemaInjector.prependForQuery(scopeId,
                PromptRegistry.forQuery().formatSavePrompt());
            formattedContent = chatClient.chat(formatPrompt,
                "问题：" + question + "\n\n回答：" + answer);
        } catch (Exception e) {
            log.error("FORMAT_ANSWER failed", e);
            executionTracker.updateStepStatus(formatStep.getId(), "failed");
            executionTracker.failExecution(execution.getId(), "FORMAT_ANSWER: " + e.getMessage());
            throw new RuntimeException("FORMAT_ANSWER 失败: " + e.getMessage());
        }
        int formatTokens = estimateTokens(formattedContent);
        totalTokens += formatTokens;
        long formatDuration = System.currentTimeMillis() - formatStart;
        executionTracker.completeStep(formatStep.getId(), formattedContent, formatTokens, (int) Math.min(formatDuration, Integer.MAX_VALUE));

        String title;
        String summary;
        String category;
        String markdownContent;
        try {
            String jsonStr = formattedContent;
            int start = jsonStr.indexOf("{");
            int end = jsonStr.lastIndexOf("}") + 1;
            if (start >= 0 && end > start) {
                jsonStr = jsonStr.substring(start, end);
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(jsonStr);
            title = node.has("title") ? node.get("title").asText() : question;
            summary = node.has("summary") ? node.get("summary").asText() : "";
            category = node.has("category") ? node.get("category").asText() : "问答沉淀";
            markdownContent = node.has("content") ? node.get("content").asText() : answer;
        } catch (Exception e) {
            log.warn("JSON parsing failed for formatted content, using fallback", e);
            title = question;
            summary = "";
            category = "问答沉淀";
            markdownContent = "# " + question + "\n\n" + answer + "\n\n---\n*来源：知识问答沉淀*";
        }

        WikiPageDO existingByTitle = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .eq(WikiPageDO::getTitle, title)
                .orderByDesc(WikiPageDO::getCreatedAt)
                .last("LIMIT 1")
        );
        if (existingByTitle != null) {
            log.info("Save idempotent hit: title '{}' already exists as page id={}, returning existing page",
                title, existingByTitle.getId());
            executionTracker.completeStep(formatStep.getId(), "idempotent-skip", formatTokens,
                (int) Math.min(formatDuration, Integer.MAX_VALUE));
            executionTracker.completeExecution(execution.getId(), totalTokens);
            return existingByTitle;
        }

        String normalized = title.toLowerCase();
        String sanitized = normalized.replaceAll("[^a-z0-9\\u4e00-\\u9fff_-]", "-");
        sanitized = sanitized.replaceAll("-+", "-");
        if (sanitized.startsWith("-")) sanitized = sanitized.substring(1);
        if (sanitized.endsWith("-")) sanitized = sanitized.substring(0, sanitized.length() - 1);
        if (sanitized.isEmpty()) sanitized = "untitled";
        String filePath = "pages/" + sanitized + ".md";
        String scopeIdStr = String.valueOf(scopeId);

        java.util.List<String> sourcePagePaths = WikiReferencePathParser.extractPaths(answer);
        java.util.List<WikiPageDO> resolvedSources = new ArrayList<>();
        for (String sourcePath : sourcePagePaths) {
            WikiPageDO sourcePage = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getFilePath, sourcePath)
            );
            if (sourcePage != null) {
                resolvedSources.add(sourcePage);
            }
        }

        ExecutionStepModel writeStep = executionTracker.createStep(execution.getId(), "WRITE_SAVED_PAGE", 2, "AUTO");
        executionTracker.updateStepStatus(writeStep.getId(), "running");
        long writeStart = System.currentTimeMillis();
        try {
            storageProvider.write(scopeIdStr, "wiki/" + filePath,
                markdownContent.getBytes(StandardCharsets.UTF_8));

            WikiPageDO pageDO = new WikiPageDO();
            pageDO.setTitle(title);
            pageDO.setFilePath(filePath);
            pageDO.setCategory(category);
            pageDO.setSummary(summary);
            pageDO.setScopeId(scopeId);
            pageDO.setSourceCount(resolvedSources.size());
            pageDO.setHealthStatus("healthy");
            pageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
            pageDO.setContentUpdatedAt(java.time.LocalDateTime.now());
            wikiPageMapper.insert(pageDO);
            lintFindingService.resolvePageFindingsOnIngest(scopeId, pageDO.getId());

            for (WikiPageDO sourcePage : resolvedSources) {
                WikiPageSourceDO existingSource = wikiPageSourceMapper.selectOne(
                    new LambdaQueryWrapper<WikiPageSourceDO>()
                        .eq(WikiPageSourceDO::getScopeId, scopeId)
                        .eq(WikiPageSourceDO::getPageId, pageDO.getId())
                        .eq(WikiPageSourceDO::getSourceId, sourcePage.getId())
                );
                if (existingSource == null) {
                    WikiPageSourceDO psRel = new WikiPageSourceDO();
                    psRel.setScopeId(scopeId);
                    psRel.setPageId(pageDO.getId());
                    psRel.setSourceId(sourcePage.getId());
                    wikiPageSourceMapper.insert(psRel);
                }
            }

            try {
                searchService.indexPage(scopeId, pageDO.getId(), pageDO.getTitle(), pageDO.getFilePath(),
                    pageDO.getCategory(), pageDO.getSummary(), markdownContent,
                    pageDO.getHealthStatus(), pageDO.getVisibility(),
                    pageDO.getLifecycleStatus());
            } catch (Exception e) {
                log.warn("Failed to sync saved page to search index: {}", e.getMessage());
            }

            executionTracker.completeStep(writeStep.getId(), filePath, 0,
                (int) Math.min(System.currentTimeMillis() - writeStart, Integer.MAX_VALUE));

            ExecutionStepModel linksStep = executionTracker.createStep(execution.getId(), "SAVE_LINKS", 3, "AUTO");
            executionTracker.updateStepStatus(linksStep.getId(), "running");
            long linksStart = System.currentTimeMillis();
            int linkCount = 0;
            for (WikiPageDO sourcePage : resolvedSources) {
                WikiPageLinkDO existingLink = wikiPageLinkMapper.selectOne(
                    new LambdaQueryWrapper<WikiPageLinkDO>()
                        .eq(WikiPageLinkDO::getScopeId, scopeId)
                        .eq(WikiPageLinkDO::getFromPageId, pageDO.getId())
                        .eq(WikiPageLinkDO::getToPageId, sourcePage.getId())
                );
                if (existingLink == null) {
                    WikiPageLinkDO newLink = new WikiPageLinkDO();
                    newLink.setScopeId(scopeId);
                    newLink.setFromPageId(pageDO.getId());
                    newLink.setToPageId(sourcePage.getId());
                    newLink.setLinkType("query-save");
                    wikiPageLinkMapper.insert(newLink);
                    linkCount++;
                }
            }
            long linksDuration = System.currentTimeMillis() - linksStart;
            executionTracker.completeStep(linksStep.getId(), "linked " + linkCount + " pages", 0,
                (int) Math.min(linksDuration, Integer.MAX_VALUE));

            try {
                Map<Long, String> contentLinkMap = Map.of(pageDO.getId(), markdownContent);
                linkWritingService.syncContentLinks(scopeId, contentLinkMap, execution.getId());
            } catch (Exception e) {
                log.warn("Failed to sync content links for query-save page: {}", e.getMessage());
            }

            ExecutionStepModel conflictStep = executionTracker.createStep(execution.getId(), "DETECT_SAVE_CONFLICTS", 4, "AUTO");
            executionTracker.updateStepStatus(conflictStep.getId(), "running");
            long conflictStart = System.currentTimeMillis();
            try {
                List<WikiPageDO> allPages = wikiPageMapper.selectList(
                    new LambdaQueryWrapper<WikiPageDO>()
                        .eq(WikiPageDO::getScopeId, scopeId)
                        .ne(WikiPageDO::getId, pageDO.getId())
                        .orderByDesc(WikiPageDO::getCreatedAt)
                        .last("LIMIT 20")
                );
                int conflictCount = 0;
                for (WikiPageDO existingPage : allPages) {
                    byte[] existingBytes = storageProvider.read(scopeIdStr, "wiki/" + existingPage.getFilePath());
                    if (existingBytes == null) continue;
                    String existingContent = new String(existingBytes, StandardCharsets.UTF_8);
                    String conflictPrompt = schemaInjector.prependForQuery(scopeId,
                        PromptRegistry.forQuery().detectSaveConflictsPrompt(
                            pageDO.getTitle(), markdownContent,
                            existingPage.getTitle(), existingContent));
                    boolean acquired = llmConcurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 30_000);
                    if (!acquired) {
                        log.warn("LlmConcurrencyBarrier timeout for conflict detection with page {}", existingPage.getId());
                        continue;
                    }
                    try {
                        String conflictResult = chatClient.chat(conflictPrompt);
                        totalTokens += estimateTokens(conflictResult);
                        boolean hasConflict = false;
                        String conflictType = "";
                        try {
                            com.fasterxml.jackson.databind.JsonNode conflictNode = objectMapper.readTree(conflictResult);
                            hasConflict = conflictNode.has("hasConflict") && conflictNode.get("hasConflict").asBoolean();
                            conflictType = conflictNode.has("conflictType") ? conflictNode.get("conflictType").asText() : "";
                        } catch (Exception parseEx) {
                            hasConflict = conflictResult.toLowerCase().contains("true");
                        }
                        if (hasConflict) {
                            conflictDomainService.markContradiction(scopeId, pageDO, existingPage, conflictType);
                            conflictCount++;
                            log.info("Detected conflict between saved page {} and existing page {}",
                                pageDO.getId(), existingPage.getId());
                        }
                    } finally {
                        llmConcurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
                    }
                }
                long conflictDuration = System.currentTimeMillis() - conflictStart;
                executionTracker.completeStep(conflictStep.getId(), "checked " + allPages.size() + " pages, found " + conflictCount + " conflicts", totalTokens,
                    (int) Math.min(conflictDuration, Integer.MAX_VALUE));
            } catch (Exception e) {
                log.warn("DETECT_SAVE_CONFLICTS failed: {}", e.getMessage());
                executionTracker.completeStep(conflictStep.getId(), "conflict detection skipped: " + e.getMessage(), 0,
                    (int) Math.min(System.currentTimeMillis() - conflictStart, Integer.MAX_VALUE));
            }

            executionTracker.completeExecution(execution.getId(), totalTokens);
            globalSummaryService.invalidate(scopeId);
            log.info("Saved query answer to wiki: title={}, filePath={}, scopeId={}, executionId={}, links={}",
                title, filePath, scopeId, execution.getId(), linkCount);
            return pageDO;
        } catch (Exception e) {
            log.error("WRITE_SAVED_PAGE failed", e);
            executionTracker.updateStepStatus(writeStep.getId(), "failed");
            executionTracker.failExecution(execution.getId(), "WRITE_SAVED_PAGE: " + e.getMessage());
            throw new RuntimeException("WRITE_SAVED_PAGE 失败: " + e.getMessage());
        }
    }

    private int runSteps(Long executionId, Long scopeId, Long sourceId, String guidance, String[] steps, int startOrder) {
        int totalTokens = 0;
        String docFormat = resolveDocFormat(sourceId);

        for (int i = 0; i < steps.length; i++) {
            String stepName = steps[i];

            ExecutionModel currentExec = executionTracker.getExecution(executionId);
            if (currentExec != null && ("cancelled".equals(currentExec.getStatus()) || "paused".equals(currentExec.getStatus()) || "failed".equals(currentExec.getStatus()))) {
                log.info("Pipeline interrupted at step {} due to execution status {}", stepName, currentExec.getStatus());
                return totalTokens;
            }

            ApprovalLevel level = approvalService.getApprovalLevel(stepName, "ingest");

            ExecutionStepModel step = executionTracker.createStep(
                executionId, stepName, startOrder + i + 1, level.name()
            );

            executionTracker.updateStepStatus(step.getId(), "running");

            long stepStartMs = System.currentTimeMillis();
            try {
                ExecutionModel execution = executionTracker.getExecution(executionId);
                Map<String, Object> result = executeIngestStep(scopeId, sourceId, stepName, execution, guidance, step.getId());
                int stepTokens = result.get("tokensUsed") != null ? (Integer) result.get("tokensUsed") : 0;
                long elapsedMs = System.currentTimeMillis() - stepStartMs;
                int durationMs = result.get("durationMs") != null ? (Integer) result.get("durationMs") : (int) Math.min(elapsedMs, Integer.MAX_VALUE);
                executionTracker.completeStep(
                    step.getId(),
                    result.get("output") != null ? result.get("output").toString() : null,
                    stepTokens,
                    durationMs
                );
                if (stepTokens > 0) {
                    totalTokens += stepTokens;
                }
                if (baselineService != null) {
                    baselineService.recordSample(scopeId, docFormat, stepName, elapsedMs);
                }
            } catch (Exception e) {
                log.error("Ingest step {} failed", stepName, e);
                ExecutionModel latestExec = executionTracker.getExecution(executionId);
                if (latestExec != null && ("cancelled".equals(latestExec.getStatus()) || "paused".equals(latestExec.getStatus()))) {
                    return totalTokens;
                }
                executionTracker.updateStepStatus(step.getId(), "failed");
                String errMsg = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
                executionTracker.failExecution(executionId, "[" + stepName + "] " + errMsg);
                return totalTokens;
            }
        }

        return totalTokens;
    }

    private String resolveDocFormat(Long sourceId) {
        if (sourceId == null) return "";
        try {
            SourceDO sourceDO = sourceMapper.selectById(sourceId);
            if (sourceDO != null && sourceDO.getFormat() != null) {
                return sourceDO.getFormat();
            }
        } catch (Exception e) {
            log.warn("resolveDocFormat failed: sourceId={}, err={}", sourceId, e.getMessage());
        }
        return "";
    }

    private boolean needsDocumentParsing(String format) {
        if (format == null) return false;
        return switch (format.toLowerCase()) {
            case "pdf", "docx", "doc", "xlsx" -> true;
            default -> false;
        };
    }

    public ExecutionModel runLintPipeline(Long scopeId, boolean fullScan) {
        TokenUsageContext.set(scopeId, "lint");
        try {
            return doRunLintPipeline(scopeId, fullScan);
        } finally {
            TokenUsageContext.clear();
        }
    }

    private ExecutionModel doRunLintPipeline(Long scopeId, boolean fullScan) {
        if (!rateLimitService.tryAcquireConcurrent(scopeId)) {
            throw new RuntimeException("并发执行数量已达上限，请等待当前任务完成后再试。scopeId=" + scopeId);
        }

        ExecutionModel execution = executionTracker.createExecution("lint", scopeId, null, null);
        executionTracker.updateExecutionStatus(execution.getId(), "running");
        return runLintPipelineWithExecution(execution.getId(), scopeId, fullScan);
    }

    public ExecutionModel runLintPipelineWithExecution(Long executionId, Long scopeId, boolean fullScan) {
        TokenUsageContext.set(scopeId, "lint");
        try {
            return doRunLintPipelineWithExecution(executionId, scopeId, fullScan);
        } finally {
            TokenUsageContext.clear();
        }
    }

    private ExecutionModel doRunLintPipelineWithExecution(Long executionId, Long scopeId, boolean fullScan) {
        ExecutionModel execution = executionTracker.getExecution(executionId);
        DeltaLintContext lintCtx = buildDeltaLintContext(scopeId, fullScan);
        
        // 加载 Schema Section 6 驱动的 Lint 规则配置（兆底为硬编码默认值）
        LintRulesConfig rulesConfig = schemaSection6Parser.parse(scopeId);
        
        Map<String, Object> watermark = new java.util.HashMap<>(readLintWatermark(scopeId));
        java.util.Set<Long> checkedPageIds = new java.util.HashSet<>();
        long totalPages = lintCtx.totalPageCount();
        log.info("Lint pipeline start: scopeId={}, fullScan={}, incremental={}, changedPages={}, newPages={}, watermark={}, totalPages={}",
            scopeId, fullScan, lintCtx.isIncremental(),
            lintCtx.changedPages().size(), lintCtx.newPages().size(), watermark, totalPages);

        // 前置步骤：keyword 按需补全（为交叉引用/矛盾检测提供元数据基础）
        if (keywordBackfillService != null) {
            Set<Long> backfillFocusIds = lintCtx.isIncremental()
                ? lintCtx.focusPages().stream().map(WikiPageDO::getId).collect(java.util.stream.Collectors.toSet())
                : null;
            int backfilled = keywordBackfillService.backfill(scopeId, backfillFocusIds);
            if (backfilled > 0) {
                log.info("Lint pre-step: backfilled keywords for {} pages in scopeId={}", backfilled, scopeId);
            }
        }

        String[] lintSteps = {
            "PROBE_AND_VALIDATE",
            "AUTO_FIX_ORPHANS",
            "AUTO_FIX_CROSSREFS",
            "GENERATE_RULING_BRIEFS",
            "WRITE_HEALTH_STATUS",
            "GENERATE_REPORT",
            "SUGGEST_ACTIONS",
            "PROPOSE_SCHEMA_PATCH"
        };

        int totalTokens = 0;
        boolean hasFailures = false;
        Boolean lintReportShortCircuit = null;

        for (int i = 0; i < lintSteps.length; i++) {
            String stepName = lintSteps[i];

            ExecutionModel currentExec = executionTracker.getExecution(execution.getId());
            if (currentExec != null && ("cancelled".equals(currentExec.getStatus()) || "paused".equals(currentExec.getStatus()) || "failed".equals(currentExec.getStatus()))) {
                log.info("Lint pipeline interrupted at step {} due to execution status {}", stepName, currentExec.getStatus());
                rateLimitService.releaseConcurrent(scopeId);
                return executionTracker.getExecution(execution.getId());
            }

            int tokenSoftLimit = rulesConfig.getScheduleConfig().getPerExecutionTokenSoftLimit();
            if (totalTokens > tokenSoftLimit && isAiLintStep(stepName)) {
                log.warn("Lint token budget exceeded ({} > {}), skipping AI step {} for scopeId={}",
                    totalTokens, tokenSoftLimit, stepName, scopeId);
                ExecutionStepModel skipStep = executionTracker.createStep(
                    execution.getId(), stepName, i + 1, "skipped"
                );
                executionTracker.completeStep(skipStep.getId(), "Token budget exceeded, skipped", 0, 0);
                continue;
            }

            // AUTO_FIX_ORPHANS + AUTO_FIX_CROSSREFS 并行执行
            if ("AUTO_FIX_ORPHANS".equals(stepName) && i + 1 < lintSteps.length && "AUTO_FIX_CROSSREFS".equals(lintSteps[i + 1])) {
                ApprovalLevel orphanLevel = approvalService.getApprovalLevel("AUTO_FIX_ORPHANS", "lint");
                ApprovalLevel crossrefLevel = approvalService.getApprovalLevel("AUTO_FIX_CROSSREFS", "lint");
                ExecutionStepModel orphanStep = executionTracker.createStep(execution.getId(), "AUTO_FIX_ORPHANS", i + 1, orphanLevel.name());
                ExecutionStepModel crossrefStep = executionTracker.createStep(execution.getId(), "AUTO_FIX_CROSSREFS", i + 2, crossrefLevel.name());
                executionTracker.updateStepStatus(orphanStep.getId(), "running");
                executionTracker.updateStepStatus(crossrefStep.getId(), "running");

                final int stepIdx = i;
                java.util.concurrent.CompletableFuture<Map<String, Object>> orphanFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> executeLintStep(scopeId, "AUTO_FIX_ORPHANS", execution, lintCtx, watermark, fullScan, checkedPageIds, rulesConfig),
                    parallelExecutor);
                java.util.concurrent.CompletableFuture<Map<String, Object>> crossrefFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> executeLintStep(scopeId, "AUTO_FIX_CROSSREFS", execution, lintCtx, watermark, fullScan, checkedPageIds, rulesConfig),
                    parallelExecutor);

                // 处理 AUTO_FIX_ORPHANS 结果
                try {
                    Map<String, Object> orphanResult = orphanFuture.get(300, java.util.concurrent.TimeUnit.SECONDS);
                    int orphanTokens = orphanResult.get("tokensUsed") != null ? (Integer) orphanResult.get("tokensUsed") : 0;
                    executionTracker.completeStep(orphanStep.getId(), orphanResult.get("output") != null ? orphanResult.get("output").toString() : null, orphanTokens,
                        orphanResult.get("durationMs") != null ? (Integer) orphanResult.get("durationMs") : 0);
                    if (orphanTokens > 0) { totalTokens += orphanTokens; }
                } catch (Exception e) {
                    log.error("Lint step AUTO_FIX_ORPHANS failed (non-blocking)", e);
                    executionTracker.updateStepStatus(orphanStep.getId(), "failed");
                    hasFailures = true;
                }

                // 处理 AUTO_FIX_CROSSREFS 结果
                try {
                    Map<String, Object> crossrefResult = crossrefFuture.get(300, java.util.concurrent.TimeUnit.SECONDS);
                    int crossrefTokens = crossrefResult.get("tokensUsed") != null ? (Integer) crossrefResult.get("tokensUsed") : 0;
                    executionTracker.completeStep(crossrefStep.getId(), crossrefResult.get("output") != null ? crossrefResult.get("output").toString() : null, crossrefTokens,
                        crossrefResult.get("durationMs") != null ? (Integer) crossrefResult.get("durationMs") : 0);
                    if (crossrefTokens > 0) { totalTokens += crossrefTokens; }
                } catch (Exception e) {
                    log.error("Lint step AUTO_FIX_CROSSREFS failed (non-blocking)", e);
                    executionTracker.updateStepStatus(crossrefStep.getId(), "failed");
                    hasFailures = true;
                }

                i++; // 跳过 AUTO_FIX_CROSSREFS（已并行处理）
                continue;
            }

            // 报告短路：活跃 findings 签名与上次 Lint 完全一致时，
            // 跳过 SUGGEST_ACTIONS / PROPOSE_SCHEMA_PATCH 两个 LLM 步骤
            if ("SUGGEST_ACTIONS".equals(stepName) || "PROPOSE_SCHEMA_PATCH".equals(stepName)) {
                if (lintReportShortCircuit == null) {
                    lintReportShortCircuit = shouldShortCircuitLintReport(scopeId, watermark);
                }
                if (lintReportShortCircuit) {
                    ExecutionStepModel skipStep = executionTracker.createStep(
                        execution.getId(), stepName, i + 1, "skipped");
                    executionTracker.completeStep(skipStep.getId(),
                        "诊断项集合与上次体检一致，复用上次结果，跳过 LLM 调用", 0, 0);
                    log.info("Lint report short-circuit: skipping {} for scopeId={}", stepName, scopeId);
                    continue;
                }
            }

            ApprovalLevel level = approvalService.getApprovalLevel(stepName, "lint");

            ExecutionStepModel step = executionTracker.createStep(
                execution.getId(), stepName, i + 1, level.name()
            );

            executionTracker.updateStepStatus(step.getId(), "running");

            try {
                Map<String, Object> result = executeLintStep(scopeId, stepName, execution, lintCtx, watermark, fullScan, checkedPageIds, rulesConfig);
                int stepTokens = result.get("tokensUsed") != null ? (Integer) result.get("tokensUsed") : 0;
                executionTracker.completeStep(
                    step.getId(),
                    result.get("output") != null ? result.get("output").toString() : null,
                    stepTokens,
                    result.get("durationMs") != null ? (Integer) result.get("durationMs") : 0
                );
                if (stepTokens > 0) {
                    totalTokens += stepTokens;
                }
            } catch (Exception e) {
                ExecutionModel latestExec = executionTracker.getExecution(executionId);
                if (latestExec != null && ("cancelled".equals(latestExec.getStatus()) || "paused".equals(latestExec.getStatus()))) {
                    log.info("Lint step {} interrupted due to execution cancelled/paused", stepName);
                    executionTracker.updateStepStatus(step.getId(), latestExec.getStatus());
                    rateLimitService.releaseConcurrent(scopeId);
                    return latestExec;
                }
                log.error("Lint step {} failed (non-blocking, continuing pipeline)", stepName, e);
                executionTracker.updateStepStatus(step.getId(), "failed");
                hasFailures = true;
            }
        }

        int partitionSize = rulesConfig.getPartitionConfig().getPartitionSize();
        int crossrefMaxLlmChecks = rulesConfig.getCrossrefConfig().getMaxLlmChecks();
        int crossrefMaxCandidates = rulesConfig.getCrossrefConfig().getMaxCandidates();
        int staleBatchSize = rulesConfig.getStaleThresholds().getBatchSize();

        int conflictPartition = ((Number) watermark.getOrDefault("conflictPartition", 0)).intValue() + 1;
        int gapPartition = ((Number) watermark.getOrDefault("gapPartition", 0)).intValue() + 1;
        int crossrefCursor = ((Number) watermark.getOrDefault("crossrefCursor", 0)).intValue() + crossrefMaxLlmChecks;
        int totalPartitions = Math.max(1, (int) Math.ceil((double) lintCtx.healthDistribution().values().stream().mapToLong(Long::longValue).sum() / partitionSize));
        conflictPartition = conflictPartition % Math.max(1, totalPartitions);
        gapPartition = gapPartition % Math.max(1, totalPartitions);
        int maxCrossrefCursor = Math.max(1, crossrefMaxCandidates);
        crossrefCursor = crossrefCursor % maxCrossrefCursor;
        watermark.put("conflictPartition", conflictPartition);
        watermark.put("gapPartition", gapPartition);
        watermark.put("crossrefCursor", crossrefCursor);
        int staleCursor = ((Number) watermark.getOrDefault("staleCursor", 0)).intValue() + staleBatchSize;
        watermark.put("staleCursor", staleCursor);
        watermark.put("findingsSignature", computeFindingsSignature(scopeId));
        writeLintWatermark(scopeId, watermark);

        ExecutionModel latestExec = executionTracker.getExecution(executionId);
        if (latestExec != null && ("cancelled".equals(latestExec.getStatus()) || "paused".equals(latestExec.getStatus()))) {
            log.info("Lint pipeline already cancelled/paused, skip completeExecution for scopeId={}", scopeId);
            return latestExec;
        }

        executionTracker.completeExecution(execution.getId(), totalTokens);
        rateLimitService.releaseConcurrent(scopeId);
        int tokenSoftLimit = rulesConfig.getScheduleConfig().getPerExecutionTokenSoftLimit();
        if (totalTokens > tokenSoftLimit) {
            log.warn("Lint per-execution token usage exceeded soft limit: scopeId={}, tokens={}, limit={}", scopeId, totalTokens, tokenSoftLimit);
        }
        if (hasFailures) {
            log.warn("Lint pipeline completed with failures for scopeId={}, totalTokens={}", scopeId, totalTokens);
        }

        List<LintFindingDO> findings = lintFindingService.listFindingsByExecution(execution.getId());
        long highCount = findings.stream().filter(f -> "high".equals(f.getPriority())).count();
        long mediumCount = findings.stream().filter(f -> "medium".equals(f.getPriority())).count();
        if (!findings.isEmpty()) {
            String title = String.format("知识体检完成，发现 %d 个诊断项", findings.size());
            String content = String.format("其中高优先级 %d 项、中优先级 %d 项。点击查看详情。", highCount, mediumCount);
            notificationService.createNotification(scopeId, "lint_completed", title, content, scopeId, null);
        }

        return executionTracker.getExecution(execution.getId());
    }

    private DeltaLintContext buildDeltaLintContext(Long scopeId, boolean fullScan) {
        GlobalSummaryService.GlobalSummary globalSummary = globalSummaryService.build(scopeId);

        List<LintFindingDO> previousFindings = lintFindingService.listFindingsByScope(scopeId,
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getScopeId, scopeId)
                .in(LintFindingDO::getStatus, "open", "repairing", "awaiting_approval", "auto_resolved")
                .isNull(LintFindingDO::getArchivedAt)
                .orderByDesc(LintFindingDO::getUpdatedAt)
                .last("LIMIT 100"));

        Map<String, Long> healthDistribution = wikiPageMapper.selectMaps(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WikiPageDO>()
                .eq("scope_id", scopeId)
                .groupBy("health_status")
                .select("health_status", "COUNT(*) AS cnt")
        ).stream().collect(java.util.stream.Collectors.toMap(
            row -> {
                String hs = (String) ((Map<String, Object>) row).get("health_status");
                return hs != null ? hs : "healthy";
            },
            row -> ((Number) ((Map<String, Object>) row).get("cnt")).longValue(),
            (a, b) -> a
        ));

        LocalDateTime lastLintCompletedAt = findLastLintCompletedAt(scopeId);

        List<WikiPageDO> changedPages = List.of();
        List<WikiPageDO> newPages = List.of();
        Set<Long> ingestAffectedPageIds = Set.of();

        if (!fullScan && lastLintCompletedAt != null) {
            changedPages = findChangedPages(scopeId, lastLintCompletedAt);
            newPages = findNewPages(scopeId, lastLintCompletedAt);
            ingestAffectedPageIds = findIngestAffectedPageIds(scopeId, lastLintCompletedAt);
            log.info("DeltaLintContext: scopeId={}, changedPages={}, newPages={}, ingestAffected={}",
                scopeId, changedPages.size(), newPages.size(), ingestAffectedPageIds.size());
        }

        return new DeltaLintContext(globalSummary, previousFindings, healthDistribution,
            changedPages, newPages, ingestAffectedPageIds, lastLintCompletedAt, fullScan);
    }

    private LocalDateTime findLastLintCompletedAt(Long scopeId) {
        return executionHistoryService.findLastCompletedAt(scopeId, "lint");
    }

    private List<WikiPageDO> findChangedPages(Long scopeId, LocalDateTime since) {
        try {
            return wikiPageMapper.selectList(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .gt(WikiPageDO::getContentUpdatedAt, since)
                    .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath,
                            WikiPageDO::getCategory, WikiPageDO::getHealthStatus)
            );
        } catch (Exception e) {
            log.debug("Failed to find changed pages for scopeId={}: {}", scopeId, e.getMessage());
            return List.of();
        }
    }

    private List<WikiPageDO> findNewPages(Long scopeId, LocalDateTime since) {
        try {
            return wikiPageMapper.selectList(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .gt(WikiPageDO::getCreatedAt, since)
                    .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath,
                            WikiPageDO::getCategory, WikiPageDO::getHealthStatus)
            );
        } catch (Exception e) {
            log.debug("Failed to find new pages for scopeId={}: {}", scopeId, e.getMessage());
            return List.of();
        }
    }

    private Set<Long> findIngestAffectedPageIds(Long scopeId, LocalDateTime since) {
        try {
            List<WikiPageSourceDO> sources = wikiPageSourceMapper.selectList(
                new LambdaQueryWrapper<WikiPageSourceDO>()
                    .eq(WikiPageSourceDO::getScopeId, scopeId)
                    .gt(WikiPageSourceDO::getCreatedAt, since)
                    .select(WikiPageSourceDO::getPageId)
            );
            return sources.stream()
                .map(WikiPageSourceDO::getPageId)
                .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            log.debug("Failed to find ingest affected page IDs for scopeId={}: {}", scopeId, e.getMessage());
            return Set.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeIngestStep(Long scopeId, Long sourceId, String stepName, ExecutionModel execution, String guidance) {
        return executeIngestStep(scopeId, sourceId, stepName, execution, guidance, null);
    }

    private Map<String, Object> executeIngestStep(Long scopeId, Long sourceId, String stepName, ExecutionModel execution, String guidance, Long stepId) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        int tokensUsed = 0;
        String scopeIdStr = String.valueOf(scopeId);

        switch (stepName) {
            case "PARSE_DOCUMENT": {
                SourceDO sourceDO = sourceMapper.selectById(sourceId);
                if (sourceDO == null) {
                    throw new RuntimeException("Source not found: id=" + sourceId);
                }

                byte[] fileBytes = storageProvider.read(scopeIdStr, sourceDO.getFilePath());
                if (fileBytes == null) {
                    throw new RuntimeException("Source file not found in storage: " + sourceDO.getFilePath());
                }

                if (!FileFormatValidator.isValidFormat(sourceDO.getFormat(), fileBytes)) {
                    throw new RuntimeException("文件格式校验失败：上传文件的实际格式与声明格式不匹配");
                }

                PythonProcessRunner runner = new PythonProcessRunner(120);
                String jsonOutput = runner.run(
                    getStorageAbsolutePath(scopeIdStr, sourceDO.getFilePath()),
                    sourceDO.getFormat()
                );

                try {
                    Map<String, Object> parsed = objectMapper.readValue(jsonOutput, Map.class);
                    if (parsed.containsKey("error")) {
                        throw new RuntimeException(parsed.get("error").toString());
                    }
                    String parsedContent = (String) parsed.get("content");
                    String parsedPath = "parsed/" + sourceId + ".parsed.md";
                    storageProvider.write(scopeIdStr, parsedPath, parsedContent.getBytes(StandardCharsets.UTF_8));

                    result.put("output", parsedContent);
                    if (parsed.containsKey("warning")) {
                        result.put("output", "{\"warning\":\"" + parsed.get("warning") + "\",\"warningMessage\":\"" + parsed.get("warningMessage") + "\"}");
                    }
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("解析 Python 输出失败: " + e.getMessage());
                }

                sourceDO.setStatus("processing");
                sourceMapper.updateById(sourceDO);
                break;
            }

            case "READ_SOURCE": {
                SourceDO sourceDO = sourceMapper.selectById(sourceId);
                if (sourceDO == null) {
                    throw new RuntimeException("Source not found: id=" + sourceId);
                }

                String readPath = "parsed/" + sourceId + ".parsed.md";
                byte[] parsedContent = storageProvider.read(scopeIdStr, readPath);

                if (parsedContent != null) {
                    result.put("output", new String(parsedContent, StandardCharsets.UTF_8));
                } else {
                    byte[] sourceContent = storageProvider.read(scopeIdStr, sourceDO.getFilePath());
                    if (sourceContent == null) {
                        throw new RuntimeException("Source file not found in storage: " + sourceDO.getFilePath());
                    }
                    result.put("output", new String(sourceContent, StandardCharsets.UTF_8));
                    sourceDO.setStatus("processing");
                    sourceMapper.updateById(sourceDO);
                }
                break;
            }

            case "SPLIT_CHUNKS": {
                String sourceText = getPreviousStepOutput(execution.getId(), "READ_SOURCE");
                DocumentChunker chunker = new DocumentChunker();
                List<DocumentChunker.Chunk> chunks = chunker.chunk(sourceText);

                try {
                    result.put("output", objectMapper.writeValueAsString(Map.of(
                        "chunkCount", chunks.size(),
                        "hasLargeDocument", chunks.size() > 1
                    )));
                } catch (Exception e) {
                    result.put("output", "{\"chunkCount\":" + chunks.size() + "}");
                }
                break;
            }

            case "ANALYZE_CHUNKS": {
                String sourceText = getPreviousStepOutput(execution.getId(), "READ_SOURCE");
                DocumentChunker chunker = new DocumentChunker();
                List<DocumentChunker.Chunk> chunks = chunker.chunk(sourceText);

                if (chatClient != null && chatClient.isAvailable()) {
                    if (chunks.size() <= 1) {
                        String prompt = schemaInjector.prependForAnalyzer(execution.getScopeId(), PromptRegistry.forIngest().analyzeChunkWithGuidance(guidance));
                        String analysis = chatClient.chat(prompt, sourceText);
                        try {
                            result.put("output", objectMapper.writeValueAsString(Map.of(
                                "chunkResults", List.of(Map.of(
                                    "index", 0,
                                    "content", analysis,
                                    "hasError", false
                                ))
                            )));
                        } catch (Exception e) {
                            result.put("output", analysis);
                        }
                        tokensUsed = estimateTokens(analysis);
                    } else {
                        final Long fStepId = stepId;
                        final Long fExecId = execution.getId();
                        ParallelAnalysisExecutor.ProgressListener listener = (current, total, avgMs, chunkIndex, chunkContent) -> {
                            if (fStepId != null) {
                                executionTracker.publishStepProgress(
                                    fExecId, fStepId, "ANALYZE_CHUNKS", current, total, avgMs, chunkIndex, chunkContent
                                );
                            }
                        };
                        ParallelAnalysisExecutor analysisExecutor = new ParallelAnalysisExecutor(chatClient, parallelExecutor, 60, guidance, schemaInjector.buildSchemaHeader(execution.getScopeId()), listener);
                        List<ParallelAnalysisExecutor.ChunkAnalysisResult> chunkResults = analysisExecutor.analyze(chunks);

                        try {
                            result.put("output", objectMapper.writeValueAsString(Map.of(
                                "chunkResults", chunkResults.stream()
                                    .map(r -> Map.of(
                                        "index", r.chunkIndex(),
                                        "content", r.content(),
                                        "hasError", r.hasError()
                                    ))
                                    .toList()
                            )));
                        } catch (Exception e) {
                            result.put("output", chunkResults.stream()
                                .map(r -> r.chunkIndex() + ": " + r.content())
                                .reduce("", (a, b) -> a + "\n---\n" + b));
                        }

                        for (ParallelAnalysisExecutor.ChunkAnalysisResult cr : chunkResults) {
                            tokensUsed += estimateTokens(cr.content());
                        }
                    }
                } else {
                    try {
                        result.put("output", objectMapper.writeValueAsString(Map.of(
                            "chunkResults", List.of(Map.of(
                                "index", 0,
                                "content", "AI not available, skipping analysis",
                                "hasError", true
                            ))
                        )));
                    } catch (Exception e) {
                        result.put("output", "AI not available, skipping analysis");
                    }
                }
                break;
            }

            case "MERGE_RESULTS": {
                String splitStepOutput = getPreviousStepOutput(execution.getId(), "SPLIT_CHUNKS");
                int chunkCount;
                try {
                    Map<String, Object> splitData = objectMapper.readValue(splitStepOutput, Map.class);
                    chunkCount = ((Number) splitData.get("chunkCount")).intValue();
                } catch (Exception e) {
                    chunkCount = 1;
                }

                String chunkResultsJson = getPreviousStepOutput(execution.getId(), "ANALYZE_CHUNKS");
                List<ParallelAnalysisExecutor.ChunkAnalysisResult> chunkResults;
                try {
                    Map<String, Object> parsed = objectMapper.readValue(chunkResultsJson, Map.class);
                    List<Map<String, Object>> crList = (List<Map<String, Object>>) parsed.get("chunkResults");
                    chunkResults = crList.stream()
                        .map(m -> new ParallelAnalysisExecutor.ChunkAnalysisResult(
                            ((Number) m.get("index")).intValue(),
                            (String) m.get("content"),
                            (Boolean) m.get("hasError")
                        ))
                        .toList();
                } catch (Exception e) {
                    chunkResults = List.of(new ParallelAnalysisExecutor.ChunkAnalysisResult(0, chunkResultsJson, false));
                }

                if (chunkCount <= 1 || chunkResults.size() <= 1) {
                    String singleResult = chunkResults.isEmpty()
                        ? chunkResultsJson
                        : chunkResults.get(0).content();
                    result.put("output", singleResult);
                    tokensUsed = estimateTokens(singleResult);
                    break;
                }

                ChunkMergeCoordinator merger = new ChunkMergeCoordinator(chatClient, schemaInjector.buildSchemaHeader(execution.getScopeId()));
                String merged = merger.merge(chunkResults);
                result.put("output", merged);
                tokensUsed = estimateTokens(merged);
                break;
            }

            case "EXTRACT_METADATA": {
                String analysisResult = getPreviousStepOutput(execution.getId(), "MERGE_RESULTS");
                if (chatClient != null && chatClient.isAvailable()) {
                    List<WikiPageDO> existingPages = wikiPageMapper.selectList(
                        new LambdaQueryWrapper<WikiPageDO>()
                            .eq(WikiPageDO::getScopeId, scopeId)
                    );
                    String pagesContext = "";
                    if (!existingPages.isEmpty()) {
                        List<PromptTemplate.PageSummary> pageSummaries = existingPages.stream()
                            .map(p -> new PromptTemplate.PageSummary(p.getTitle(), p.getCategory(), p.getSummary(), p.getFilePath()))
                            .toList();
                        pagesContext = PromptTemplate.estimateAndTruncatePageList(pageSummaries, "现有知识库页面列表（用于判断受影响页面）：");
                    }
                    String prompt = schemaInjector.prependForAnalyzer(execution.getScopeId(), PromptRegistry.forIngest().extractMetadata(pagesContext));
                    String metadata = chatClient.chat(prompt, analysisResult);
                    result.put("output", metadata);
                    tokensUsed = estimateTokens(metadata);
                } else {
                    result.put("output", "{\"title\":\"未命名\",\"summary\":\"AI不可用\",\"category\":\"未分类\",\"tags\":[],\"keywords\":[],\"affectedPages\":[]}");
                }
                break;
            }

            case "WRITE_SUMMARY": {
                String analysisResult = getPreviousStepOutput(execution.getId(), "MERGE_RESULTS");
                String metadataJson = getPreviousStepOutput(execution.getId(), "EXTRACT_METADATA");
                if (chatClient != null && chatClient.isAvailable()) {
                    String prompt = schemaInjector.prependForWriter(execution.getScopeId(), PromptRegistry.forIngest().writeSummary(metadataJson));
                    String summary = chatClient.chat(prompt, analysisResult);
                    summary = PromptTemplate.stripConversationalFiller(stripMarkdownFences(summary));
                    result.put("output", summary);
                    tokensUsed = estimateTokens(summary);

                    String pagePath = generatePagePath(metadataJson);
                    storageProvider.write(scopeIdStr, "wiki/" + pagePath, summary.getBytes(StandardCharsets.UTF_8));

                    WikiPageDO pageDO = wikiPageMapper.selectOne(
                        new LambdaQueryWrapper<WikiPageDO>()
                            .eq(WikiPageDO::getScopeId, scopeId)
                            .eq(WikiPageDO::getFilePath, pagePath)
                    );
                    String title = extractJsonField(metadataJson, "title");
                    String summaryText = extractJsonField(metadataJson, "summary");
                    String category = categoryNormalizer.normalize(scopeId, extractJsonField(metadataJson, "category"));

                    if (pageDO != null) {
                        pageDO.setTitle(title);
                        pageDO.setSummary(summaryText);
                        pageDO.setCategory(category);
                        pageDO.setSourceCount(pageDO.getSourceCount() + 1);
                        pageDO.setContentUpdatedAt(java.time.LocalDateTime.now());
                        wikiPageMapper.updateById(pageDO);
                        lintFindingService.resolvePageFindingsOnIngest(scopeId, pageDO.getId());
                        syncPageToIndex(pageDO, scopeId);
                    } else {
                        pageDO = new WikiPageDO();
                        pageDO.setTitle(title);
                        pageDO.setFilePath(pagePath);
                        pageDO.setSummary(summaryText);
                        pageDO.setCategory(category);
                        pageDO.setScopeId(scopeId);
                        pageDO.setSourceCount(1);
                        pageDO.setHealthStatus("healthy");
                        pageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                        pageDO.setContentUpdatedAt(java.time.LocalDateTime.now());
                        pageDO.setSchemaVersion(schemaManager.getCurrentVersionId(scopeId,
                            org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator.WIKI_SCHEMA_KEY));
                        wikiPageMapper.insert(pageDO);
                        lintFindingService.resolvePageFindingsOnIngest(scopeId, pageDO.getId());
                        syncPageToIndex(pageDO, scopeId);
                    }

                    persistSourceRelation(scopeId, pageDO.getId(), sourceId);
                    persistTagsAndKeywords(scopeId, pageDO.getId(), metadataJson);
                } else {
                    result.put("output", "AI not available, skipping summary generation");
                }
                break;
            }

            case "WRITE_ENTITY_PAGES": {
                String analysisResult = getPreviousStepOutput(execution.getId(), "MERGE_RESULTS");
                String metadataJson = getPreviousStepOutput(execution.getId(), "EXTRACT_METADATA");
                String sourceContent = getPreviousStepOutput(execution.getId(), "READ_SOURCE");
                int entityPageCount = 0;
                StringBuilder entityReport = new StringBuilder();

                if (chatClient != null && chatClient.isAvailable()) {
                    List<Map<String, String>> entities = parseEntities(metadataJson);
                    for (Map<String, String> entity : entities) {
                        String entityName = entity.get("name");
                        String entityType = entity.getOrDefault("type", "concept");
                        if (entityName == null || entityName.isBlank()) continue;

                        String entityPagePath = "pages/" + entityName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_-]", "-") + ".md";
                        WikiPageDO existingEntityPage = wikiPageMapper.selectOne(
                            new LambdaQueryWrapper<WikiPageDO>()
                                .eq(WikiPageDO::getScopeId, scopeId)
                                .eq(WikiPageDO::getFilePath, entityPagePath)
                        );

                        if (existingEntityPage != null) {
                            byte[] existingBytes = storageProvider.read(scopeIdStr, "wiki/" + entityPagePath);
                            if (existingBytes != null) {
                                String existingContent = new String(existingBytes, StandardCharsets.UTF_8);
                                String mergePrompt = schemaInjector.prependForWriter(execution.getScopeId(), PromptRegistry.forIngest().mergeIntoExistingPage(
                                    existingContent, sourceContent, analysisResult, metadataJson, "补充"
                                ));
                                try {
                                    String merged = chatClient.chat(mergePrompt);
                                    merged = PromptTemplate.stripConversationalFiller(stripMarkdownFences(merged));
                                    merged = linkWritingService.sanitizeSourceLinks(merged);
                                    merged = linkWritingService.sanitizeWikiLinks(merged, scopeId);
                                    tokensUsed += estimateTokens(merged);
                                    storageProvider.write(scopeIdStr, "wiki/" + entityPagePath,
                                        merged.getBytes(StandardCharsets.UTF_8));
                                    existingEntityPage.setSourceCount(existingEntityPage.getSourceCount() + 1);
                                    existingEntityPage.setContentUpdatedAt(java.time.LocalDateTime.now());
                                    wikiPageMapper.updateById(existingEntityPage);
                                    lintFindingService.resolvePageFindingsOnIngest(scopeId, existingEntityPage.getId());
                                    syncPageToIndex(existingEntityPage, scopeId);
                                    persistSourceRelation(scopeId, existingEntityPage.getId(), sourceId);
                                    entityPageCount++;
                                    entityReport.append("- 更新实体页：").append(entityName).append("\n");
                                } catch (Exception e) {
                                    log.warn("Merge into entity page failed: entity={}, error={}", entityName, e.getMessage());
                                }
                            }
                        } else {
                            String entityPrompt = schemaInjector.prependForWriter(execution.getScopeId(), PromptRegistry.forIngest().writeEntityPage(
                                entityName, entityType, analysisResult, metadataJson
                            ));
                            try {
                                String entityContent = chatClient.chat(entityPrompt, analysisResult);
                                entityContent = PromptTemplate.stripConversationalFiller(stripMarkdownFences(entityContent));
                                entityContent = linkWritingService.sanitizeSourceLinks(entityContent);
                                entityContent = linkWritingService.sanitizeWikiLinks(entityContent, scopeId);
                                tokensUsed += estimateTokens(entityContent);
                                storageProvider.write(scopeIdStr, "wiki/" + entityPagePath,
                                    entityContent.getBytes(StandardCharsets.UTF_8));

                                String entityCategory = deriveEntityCategory(entityType, metadataJson);
                                WikiPageDO entityPageDO = new WikiPageDO();
                                entityPageDO.setTitle(entityName);
                                entityPageDO.setFilePath(entityPagePath);
                                entityPageDO.setSummary("关于「" + entityName + "」的知识页面");
                                entityPageDO.setCategory(entityCategory);
                                entityPageDO.setScopeId(scopeId);
                                entityPageDO.setSourceCount(1);
                                entityPageDO.setHealthStatus("healthy");
                                entityPageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                                entityPageDO.setContentUpdatedAt(java.time.LocalDateTime.now());
                                entityPageDO.setSchemaVersion(schemaManager.getCurrentVersionId(scopeId,
                                    org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator.WIKI_SCHEMA_KEY));
                                wikiPageMapper.insert(entityPageDO);
                                lintFindingService.resolvePageFindingsOnIngest(scopeId, entityPageDO.getId());
                                syncPageToIndex(entityPageDO, scopeId);
                                persistSourceRelation(scopeId, entityPageDO.getId(), sourceId);
                                entityPageCount++;
                                entityReport.append("- 新建实体页：").append(entityName).append("\n");
                            } catch (Exception e) {
                                log.warn("Entity page generation failed: entity={}, error={}", entityName, e.getMessage());
                            }
                        }
                    }
                }
                result.put("output", "实体页面生成完成，共处理 " + entityPageCount + " 个实体页面\n" + entityReport);
                break;
            }

            case "UPDATE_RELATED": {
                String analysisResult = getPreviousStepOutput(execution.getId(), "MERGE_RESULTS");
                String metadataJson = getPreviousStepOutput(execution.getId(), "EXTRACT_METADATA");
                String sourceContent = getPreviousStepOutput(execution.getId(), "READ_SOURCE");
                if (chatClient != null && chatClient.isAvailable()) {
                    List<Map<String, String>> affectedPages = parseAffectedPages(metadataJson);
                    Set<String> sourceRelatedPaths = findSourceRelatedPagePaths(scopeId, sourceId);
                    affectedPages = mergeAffectedPagesWithSourceRelations(affectedPages, sourceRelatedPaths);
                    int updatedCount = 0;
                    StringBuilder report = new StringBuilder();
                    for (Map<String, String> affected : affectedPages) {
                        String affectedPath = affected.get("path");
                        String action = affected.get("action");
                        if (affectedPath == null || affectedPath.isBlank()) continue;
                        if (!"更新".equals(action) && !"补充".equals(action)) continue;

                        WikiPageDO existing = wikiPageMapper.selectOne(
                            new LambdaQueryWrapper<WikiPageDO>()
                                .eq(WikiPageDO::getScopeId, scopeId)
                                .eq(WikiPageDO::getFilePath, affectedPath)
                        );
                        if (existing == null) continue;

                        byte[] existingBytes = storageProvider.read(scopeIdStr, "wiki/" + affectedPath);
                        if (existingBytes == null) continue;
                        String existingContent = new String(existingBytes, StandardCharsets.UTF_8);

                        String mergePrompt = schemaInjector.prependForWriter(execution.getScopeId(), PromptRegistry.forIngest().mergeIntoExistingPage(
                            existingContent, sourceContent, analysisResult, metadataJson, action
                        ));
                        String merged;
                        try {
                            merged = chatClient.chat(mergePrompt);
                        } catch (Exception e) {
                            log.warn("Merge into existing page failed: path={}, error={}", affectedPath, e.getMessage());
                            continue;
                        }
                        merged = PromptTemplate.stripConversationalFiller(stripMarkdownFences(merged));
                        merged = linkWritingService.sanitizeSourceLinks(merged);
                        merged = linkWritingService.sanitizeWikiLinks(merged, scopeId);
                        tokensUsed += estimateTokens(merged);

                        storageProvider.write(scopeIdStr, "wiki/" + affectedPath,
                            merged.getBytes(StandardCharsets.UTF_8));

                        existing.setHealthStatus("healthy");
                        existing.setSourceCount(existing.getSourceCount() + 1);
                        existing.setContentUpdatedAt(java.time.LocalDateTime.now());
                        wikiPageMapper.updateById(existing);
                        lintFindingService.resolvePageFindingsOnIngest(scopeId, existing.getId());
                        syncPageToIndex(existing, scopeId);

                        persistSourceRelation(scopeId, existing.getId(), sourceId);
                        updatedCount++;
                        report.append("- ").append(action).append(": ").append(affectedPath).append("\n");
                    }
                    String output = "增量编译完成，更新 " + updatedCount + " 个关联页面\n" + report;
                    result.put("output", output);
                } else {
                    result.put("output", "[]");
                }
                break;
            }

            case "UPDATE_LINKS": {
                String metadataJson = getPreviousStepOutput(execution.getId(), "EXTRACT_METADATA");
                String relatedAnalysis = getPreviousStepOutput(execution.getId(), "UPDATE_RELATED");
                String newPagePath = generatePagePath(metadataJson);

                if (chatClient != null && chatClient.isAvailable()) {
                    String prompt = schemaInjector.prependForWriter(execution.getScopeId(), PromptRegistry.forIngest().updateLinks(newPagePath, metadataJson, relatedAnalysis));
                    String linkSuggestions = chatClient.chat(prompt);
                    result.put("output", linkSuggestions);
                    tokensUsed = estimateTokens(linkSuggestions);
                    linkWritingService.applyLinkSuggestions(linkSuggestions, execution.getScopeId());
                } else {
                    result.put("output", "[]");
                }
                break;
            }

            case "PROPOSE_SCHEMA_PATCH": {
                String metadataJson = getPreviousStepOutput(execution.getId(), "EXTRACT_METADATA");
                String summaryOutput = getPreviousStepOutput(execution.getId(), "WRITE_SUMMARY");
                String relatedOutput = getPreviousStepOutput(execution.getId(), "UPDATE_RELATED");
                StringBuilder summary = new StringBuilder();
                summary.append("【新建主摘要】\n").append(metadataJson == null ? "(无)" : metadataJson).append("\n\n");
                if (summaryOutput != null && !summaryOutput.isBlank()) {
                    summary.append("【主摘要正文片段】\n")
                        .append(summaryOutput.length() > 1500 ? summaryOutput.substring(0, 1500) + "..." : summaryOutput)
                        .append("\n\n");
                }
                if (relatedOutput != null && !relatedOutput.isBlank()) {
                    summary.append("【关联页面更新摘要】\n")
                        .append(relatedOutput.length() > 800 ? relatedOutput.substring(0, 800) + "..." : relatedOutput)
                        .append("\n");
                }
                int n = schemaPatchProposer.propose(
                    execution.getScopeId(),
                    execution.getId(),
                    SchemaPatchModel.SourceType.INGEST,
                    summary.toString()
                );
                result.put("output", "Schema 补丁候选提案：" + n + " 条");
                break;
            }

            default:
                result.put("output", "Unknown step: " + stepName);
        }

        result.put("tokensUsed", tokensUsed);
        result.put("durationMs", (int) (System.currentTimeMillis() - startTime));
        return result;
    }

    private String getStorageAbsolutePath(String scopeId, String filePath) {
        return Path.of("wiki-data", scopeId, filePath).toAbsolutePath().toString();
    }



    private Map<String, Object> executeLintStep(Long scopeId, String stepName, ExecutionModel execution, DeltaLintContext lintCtx, Map<String, Object> watermark, boolean fullScan, java.util.Set<Long> checkedPageIds, LintRulesConfig rulesConfig) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        int tokensUsed = 0;
        String scopeIdStr = String.valueOf(scopeId);

        switch (stepName) {
            case "PROBE_AND_VALIDATE": {
                List<WikiPageDO> focusPages = lintCtx.isIncremental() ? lintCtx.focusPages() : null;
                LintProbeService.ProbeOutcome outcome = lintProbeService.probeAndValidate(
                    scopeId, execution.getId(),
                    lintCtx.summary(),
                    lintCtx.previousFindings(), lintCtx.healthDistribution(),
                    rulesConfig,
                    focusPages, null);

                int aiCount = outcome.getAiResult() != null ? outcome.getAiResult().getFindings().size() : 0;
                int sqlOrphanCount = 0;
                int sqlStaleCount = 0;
                int totalCount = outcome.getMergedFindings().size();
                for (LintProbeService.MergedFinding mf : outcome.getMergedFindings()) {
                    if (!mf.fromAi) {
                        if ("orphan".equals(mf.type)) sqlOrphanCount++;
                        if ("stale".equals(mf.type)) sqlStaleCount++;
                    }
                }
                String aiStatus = outcome.getAiResult() != null ? outcome.getAiResult().getStatus() : "unknown";
                String probeSummary = String.format(
                    "诊断探查完成（AI探查状态：%s）：AI探查 %d 个，SQL安全网孤儿 %d 个/过时 %d 个，合并后总计 %d 个诊断项",
                    aiStatus, aiCount, sqlOrphanCount, sqlStaleCount, totalCount);
                result.put("output", probeSummary);
                if (outcome.getAiRawOutput() != null) {
                    tokensUsed += estimateTokens(outcome.getAiRawOutput());
                }
                if (outcome.getAiResult() != null && outcome.getAiResult().getInputPromptLength() > 0) {
                    tokensUsed += estimateTokensFromLength(outcome.getAiResult().getInputPromptLength());
                }

                int supersededCount = lintFindingService.autoArchiveSupersededFindings(
                    scopeId, execution.getId(), outcome.getTouchedFindingIds());
                if (supersededCount > 0) {
                    log.info("Lint PROBE_AND_VALIDATE auto-archived {} superseded findings from previous executions", supersededCount);
                }
                break;
            }

            case "AUTO_FIX_ORPHANS": {
                if (chatClient == null || !chatClient.isAvailable()) {
                    result.put("output", "AI 未配置，跳过孤儿页面自动修复");
                    break;
                }
                int autoFixLimit = rulesConfig.getDiagnosticStandard().getAutoFixLimit();
                List<LintFindingDO> orphanFindings = lintFindingService.listFindings(scopeId, "orphan", "open", null);
                if (lintCtx.isIncremental()) {
                    java.util.Set<Long> focusIds = lintCtx.focusPages().stream().map(WikiPageDO::getId).collect(java.util.stream.Collectors.toSet());
                    int before = orphanFindings.size();
                    orphanFindings = orphanFindings.stream()
                        .filter(f -> f.getAssetId() != null && focusIds.contains(f.getAssetId()))
                        .collect(java.util.stream.Collectors.toList());
                    if (before > orphanFindings.size()) {
                        log.info("AUTO_FIX_ORPHANS incremental filter: {} -> {} findings", before, orphanFindings.size());
                    }
                }
                String globalSummaryCompact = lintCtx.summary().toCompactPrompt();

                LintOrphanService.TriageResult triage = lintOrphanService.executeOrphanTriage(
                    scopeId, execution.getId(), orphanFindings, autoFixLimit, globalSummaryCompact);

                result.put("output", String.format(
                    "孤儿页面四分类诊断：integrate=%d, duplicate=%d, standalone=%d, thin=%d, failed=%d, skipped=%d",
                    triage.integrated(), triage.duplicated(), triage.standalone(),
                    triage.thin(), triage.failed(), triage.skipped()));
                break;
            }

            case "AUTO_FIX_CROSSREFS": {
                List<LintFindingDO> crossrefFindings = lintFindingService.listFindings(scopeId, "missing_crossref", "open", null);
                List<LintFindingDO> conflictFindings = lintFindingService.listFindings(scopeId, "conflict", "open", null);
                if (lintCtx.isIncremental()) {
                    java.util.Set<Long> focusIds = lintCtx.focusPages().stream().map(WikiPageDO::getId).collect(java.util.stream.Collectors.toSet());
                    int beforeCross = crossrefFindings.size();
                    crossrefFindings = crossrefFindings.stream()
                        .filter(f -> f.getAssetId() != null && focusIds.contains(f.getAssetId()))
                        .collect(java.util.stream.Collectors.toList());
                    int beforeConflict = conflictFindings.size();
                    conflictFindings = conflictFindings.stream()
                        .filter(f -> f.getAssetId() != null && focusIds.contains(f.getAssetId()))
                        .collect(java.util.stream.Collectors.toList());
                    if (beforeCross > crossrefFindings.size() || beforeConflict > conflictFindings.size()) {
                        log.info("AUTO_FIX_CROSSREFS incremental filter: crossref {} -> {}, conflict {} -> {}",
                            beforeCross, crossrefFindings.size(), beforeConflict, conflictFindings.size());
                    }
                }
                var crossRefOutcome = crossRefDomainService.resolveFindings(
                    scopeId, crossrefFindings, conflictFindings, execution.getId(), rulesConfig);

                var crossCatOutcome = crossRefDomainService.detectAndFixCrossCategoryLinks(
                    scopeId, execution.getId(), rulesConfig);

                result.put("output", crossRefOutcome.summary() + "\n\n" + crossCatOutcome.summary());
                break;
            }

            case "GENERATE_RULING_BRIEFS": {
                var rulingOutcome = conflictDomainService.generatePendingRulings(scopeId, execution.getId(), rulesConfig);
                result.put("output", rulingOutcome.summary());
                break;
            }

            case "WRITE_HEALTH_STATUS": {
                java.util.Set<Long> problemPageIds = new java.util.HashSet<>();
                java.util.Set<Long> warnPageIds = new java.util.HashSet<>();
                List<Map<String, Object>> openFindingDistribution = lintFindingService.getOpenFindingAssetDistribution(scopeId);
                for (Map<String, Object> row : openFindingDistribution) {
                    Long assetId = ((Number) row.get("asset_id")).longValue();
                    if (assetId == null || assetId == 0) continue;
                    String findingType = (String) row.get("finding_type");
                    if ("conflict".equals(findingType) || "stale".equals(findingType)) {
                        problemPageIds.add(assetId);
                    } else {
                        warnPageIds.add(assetId);
                    }
                }

                java.time.LocalDateTime now = java.time.LocalDateTime.now();

                java.util.List<Long> problemIds = new java.util.ArrayList<>();
                java.util.List<Long> warnIds = new java.util.ArrayList<>();
                java.util.List<Long> healthyIds = new java.util.ArrayList<>();

                java.util.Set<Long> affectedPageIds = new java.util.HashSet<>();
                affectedPageIds.addAll(problemPageIds);
                affectedPageIds.addAll(warnPageIds);
                if (!affectedPageIds.isEmpty()) {
                    List<Map<String, Object>> currentStatuses = wikiPageMapper.selectHealthDistributionByScope(scopeId);
                    Map<Long, String> pageIdToStatus = new java.util.HashMap<>();
                    for (WikiPageDO page : wikiPageMapper.selectList(
                        new LambdaQueryWrapper<WikiPageDO>()
                            .eq(WikiPageDO::getScopeId, scopeId)
                            .in(WikiPageDO::getId, affectedPageIds)
                            .select(WikiPageDO::getId, WikiPageDO::getHealthStatus))) {
                        pageIdToStatus.put(page.getId(), page.getHealthStatus());
                    }
                    for (Long pageId : affectedPageIds) {
                        String newStatus = problemPageIds.contains(pageId) ? "has-problems" : "needs-update";
                        String currentStatus = pageIdToStatus.getOrDefault(pageId, "healthy");
                        if (!newStatus.equals(currentStatus)) {
                            if ("has-problems".equals(newStatus)) {
                                problemIds.add(pageId);
                            } else {
                                warnIds.add(pageId);
                            }
                        }
                    }
                }

                List<Long> previouslyUnhealthyIds = wikiPageMapper.selectUnhealthyPageIds(scopeId);
                for (Long pageId : previouslyUnhealthyIds) {
                    if (!problemPageIds.contains(pageId) && !warnPageIds.contains(pageId)) {
                        healthyIds.add(pageId);
                    }
                }

                if (!problemIds.isEmpty()) {
                    wikiPageMapper.update(null, new LambdaUpdateWrapper<WikiPageDO>()
                        .set(WikiPageDO::getHealthStatus, "has-problems")
                        .set(WikiPageDO::getLastCheckedAt, now)
                        .in(WikiPageDO::getId, problemIds));
                }
                if (!warnIds.isEmpty()) {
                    wikiPageMapper.update(null, new LambdaUpdateWrapper<WikiPageDO>()
                        .set(WikiPageDO::getHealthStatus, "needs-update")
                        .set(WikiPageDO::getLastCheckedAt, now)
                        .in(WikiPageDO::getId, warnIds));
                }
                if (!healthyIds.isEmpty()) {
                    wikiPageMapper.update(null, new LambdaUpdateWrapper<WikiPageDO>()
                        .set(WikiPageDO::getHealthStatus, "healthy")
                        .set(WikiPageDO::getLastCheckedAt, now)
                        .in(WikiPageDO::getId, healthyIds));
                }

                java.util.List<Long> changedIds = new java.util.ArrayList<>();
                changedIds.addAll(problemIds);
                changedIds.addAll(warnIds);
                changedIds.addAll(healthyIds);
                if (!changedIds.isEmpty()) {
                    List<WikiPageDO> changedPages = wikiPageMapper.selectBatchIds(changedIds);
                    searchService.bulkIndexPages(scopeId, changedPages);
                }

                long totalProblem = problemPageIds.size();
                long totalWarn = warnPageIds.size();
                String summary = String.format(
                    "健康状态回写完成：更新 %d 个页面状态。has-problems=%d, needs-update=%d, 恢复healthy=%d",
                    changedIds.size(), totalProblem, totalWarn, healthyIds.size()
                );
                result.put("output", summary);
                log.info("WRITE_HEALTH_STATUS: scopeId={}, {}", scopeId, summary);
                break;
            }

            case "GENERATE_REPORT": {
                String probeResult = getPreviousStepOutput(execution.getId(), "PROBE_AND_VALIDATE");
                String healthResult = getPreviousStepOutput(execution.getId(), "WRITE_HEALTH_STATUS");

                String report = String.format(
                    "# 知识库健康报告\n\n## 健康状态汇总\n%s\n\n## 诊断探查结果\n%s",
                    healthResult, probeResult
                );
                result.put("output", report);
                break;
            }

            case "SUGGEST_ACTIONS": {
                String report = getPreviousStepOutput(execution.getId(), "GENERATE_REPORT");
                if (chatClient != null && chatClient.isAvailable()) {
                    String prompt = schemaInjector.prependForLint(execution.getScopeId(),
                        PromptRegistry.forLint().suggestActions(truncate(report, 4000)));
                    String suggestions = chatClient.chat(prompt);
                    result.put("output", suggestions);
                    tokensUsed = estimateTokens(suggestions);
                } else {
                    result.put("output", "[]");
                }
                break;
            }

            case "PROPOSE_SCHEMA_PATCH": {
                String healthSummary = getPreviousStepOutput(execution.getId(), "WRITE_HEALTH_STATUS");
                String probeOutput = getPreviousStepOutput(execution.getId(), "PROBE_AND_VALIDATE");
                String actionOutput = getPreviousStepOutput(execution.getId(), "SUGGEST_ACTIONS");

                StringBuilder summary = new StringBuilder();
                summary.append("【本次 Lint 全库健康汇总】\n")
                    .append(healthSummary != null ? healthSummary : "(无)")
                    .append("\n\n【诊断探查摘录（截断 800）】\n")
                    .append(truncate(probeOutput, 800))
                    .append("\n\n【AI 改进建议摘录（截断 800）】\n")
                    .append(truncate(actionOutput, 800));

                int proposed = schemaPatchProposer.propose(
                    execution.getScopeId(),
                    execution.getId(),
                    SchemaPatchModel.SourceType.LINT,
                    summary.toString()
                );
                result.put("output", "Lint 维度 Schema 补丁候选提案：" + proposed + " 条");
                break;
            }

            default:
                result.put("output", "Unknown step: " + stepName);
        }

        result.put("tokensUsed", tokensUsed);
        result.put("durationMs", (int) (System.currentTimeMillis() - startTime));
        return result;
    }

    private java.util.Set<String> parseConflictTextPaths(String conflictOutput, List<WikiPageDO> allPages) {
        java.util.Set<String> paths = new java.util.HashSet<>();
        if (conflictOutput == null || conflictOutput.isBlank()) return paths;
        java.util.Set<String> validPaths = allPages.stream()
            .map(WikiPageDO::getFilePath)
            .filter(p -> p != null)
            .collect(java.util.stream.Collectors.toSet());
        try {
            String cleanJson = conflictOutput;
            int start = cleanJson.indexOf('[');
            int end = cleanJson.lastIndexOf(']');
            if (start >= 0 && end > start) {
                cleanJson = cleanJson.substring(start, end + 1);
                var list = objectMapper.readValue(cleanJson, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Map<String, Object>>>() {});
                for (var item : list) {
                    Object path = item.get("pagePath");
                    if (path != null && validPaths.contains(path.toString())) {
                        paths.add(path.toString());
                    }
                }
            }
        } catch (Exception e) {
            for (String path : validPaths) {
                if (conflictOutput.contains(path)) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    private String getPreviousStepOutput(Long executionId, String previousStepName) {
        List<ExecutionStepModel> steps = executionTracker.listSteps(executionId);
        for (ExecutionStepModel step : steps) {
            if (step.getStepName().equals(previousStepName)) {
                return step.getOutputData() != null ? step.getOutputData() : "";
            }
        }
        return "";
    }

    private List<WikiPageDO> selectLintPartition(List<WikiPageDO> allPages, int partitionIndex) {
        if (allPages.size() <= LINT_PARTITION_SIZE) {
            return allPages;
        }
        List<WikiPageDO> priorityPages = allPages.stream()
            .filter(p -> "has-problems".equals(p.getHealthStatus()) || "needs-update".equals(p.getHealthStatus()))
            .toList();
        List<WikiPageDO> otherPages = allPages.stream()
            .filter(p -> !"has-problems".equals(p.getHealthStatus()) && !"needs-update".equals(p.getHealthStatus()))
            .sorted(java.util.Comparator.comparing(WikiPageDO::getId))
            .toList();
        int nonPrioritySlots = Math.max(LINT_PARTITION_SIZE - priorityPages.size(), LINT_PARTITION_SIZE / 2);
        int totalPartitions = otherPages.isEmpty() ? 1 : (int) Math.ceil((double) otherPages.size() / nonPrioritySlots);
        int effectiveIndex = partitionIndex % totalPartitions;
        int start = effectiveIndex * nonPrioritySlots;
        int end = Math.min(start + nonPrioritySlots, otherPages.size());
        List<WikiPageDO> partition = new java.util.ArrayList<>(priorityPages);
        partition.addAll(otherPages.subList(start, end));
        log.info("Lint partition selection: {} total pages, partition {} of {}, {} pages ({} priority + {} other {}-{})",
            allPages.size(), effectiveIndex, totalPartitions, partition.size(), priorityPages.size(), end - start, start, end - 1);
        return partition;
    }

    private List<WikiPageDO> samplePagesForLint(List<WikiPageDO> allPages) {
        if (allPages.size() <= LINT_LARGE_SCALE_THRESHOLD) {
            return allPages;
        }
        List<WikiPageDO> priorityPages = new java.util.ArrayList<>();
        List<WikiPageDO> otherPages = new java.util.ArrayList<>();
        for (WikiPageDO page : allPages) {
            if ("has-problems".equals(page.getHealthStatus()) || "needs-update".equals(page.getHealthStatus())) {
                priorityPages.add(page);
            } else {
                otherPages.add(page);
            }
        }
        List<WikiPageDO> sampled = new java.util.ArrayList<>(priorityPages);
        int remaining = LINT_SAMPLE_MAX - sampled.size();
        if (remaining > 0 && !otherPages.isEmpty()) {
            java.util.Collections.shuffle(otherPages);
            for (int i = 0; i < otherPages.size() && sampled.size() < LINT_SAMPLE_MAX; i++) {
                sampled.add(otherPages.get(i));
            }
        }
        log.info("Lint page sampling: {} total, {} sampled ({} priority + {} random uniform",
            allPages.size(), sampled.size(), priorityPages.size(), sampled.size() - priorityPages.size());
        return sampled;
    }

    private boolean shouldShortCircuitLintReport(Long scopeId, Map<String, Object> watermark) {
        Object previousSignature = watermark.get("findingsSignature");
        if (previousSignature == null) {
            return false;
        }
        String currentSignature = computeFindingsSignature(scopeId);
        boolean same = previousSignature.toString().equals(currentSignature);
        if (same) {
            log.info("Lint report short-circuit check: findings signature unchanged ({})", currentSignature);
        }
        return same;
    }

    private String computeFindingsSignature(Long scopeId) {
        List<LintFindingDO> activeFindings = lintFindingService.listFindingsByScope(scopeId,
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getScopeId, scopeId)
                .in(LintFindingDO::getStatus, "open", "repairing", "awaiting_approval")
                .isNull(LintFindingDO::getArchivedAt)
                .ne(LintFindingDO::getFindingType, "schema_violation"));
        List<String> parts = activeFindings.stream()
            .map(f -> f.getFindingType() + ":" + f.getAssetId() + ":" + f.getPriority())
            .sorted()
            .toList();
        return Integer.toHexString(parts.hashCode()) + "-" + parts.size();
    }

    private Map<String, Object> readLintWatermark(Long scopeId) {
        SchemaConfigDO config = schemaConfigMapper.selectOne(
            new LambdaQueryWrapper<SchemaConfigDO>()
                .eq(SchemaConfigDO::getScopeId, scopeId)
                .eq(SchemaConfigDO::getConfigKey, LINT_WATERMARK_KEY)
        );
        if (config != null && config.getConfigValue() != null) {
            try {
                return objectMapper.readValue(config.getConfigValue(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("Failed to parse lint watermark for scopeId={}, reseting: {}", scopeId, e.getMessage());
            }
        }
        return new java.util.HashMap<>(Map.of("conflictPartition", 0, "gapPartition", 0, "crossrefCursor", 0, "staleCursor", 0));
    }

    private void writeLintWatermark(Long scopeId, Map<String, Object> watermark) {
        try {
            String json = objectMapper.writeValueAsString(watermark);
            SchemaConfigDO existing = schemaConfigMapper.selectOne(
                new LambdaQueryWrapper<SchemaConfigDO>()
                    .eq(SchemaConfigDO::getScopeId, scopeId)
                    .eq(SchemaConfigDO::getConfigKey, LINT_WATERMARK_KEY)
            );
            if (existing != null) {
                existing.setConfigValue(json);
                schemaConfigMapper.updateById(existing);
            } else {
                SchemaConfigDO newConfig = new SchemaConfigDO();
                newConfig.setScopeId(scopeId);
                newConfig.setConfigKey(LINT_WATERMARK_KEY);
                newConfig.setConfigValue(json);
                newConfig.setConfigGroup(LINT_WATERMARK_GROUP);
                newConfig.setDescription("Lint batch rotation watermark");
                schemaConfigMapper.insert(newConfig);
            }
        } catch (Exception e) {
            log.warn("Failed to write lint watermark for scopeId={}: {}", scopeId, e.getMessage());
        }
    }

    private String truncate(String content, int maxLen) {
        if (content == null) return "(无)";
        if (content.length() <= maxLen) return content;
        return content.substring(0, maxLen) + "...";
    }

    private String sanitizePathSegment(String segment) {
        if (segment == null || segment.isBlank()) return "untitled";
        return segment.replaceAll("[\\\\/:*?\"<>|]", "-")
            .replaceAll("\\s+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
    }

    private String sanitizePathSegmentUnique(String segment, Long scopeId) {
        String base = sanitizePathSegment(segment);
        String candidate = base;
        int suffix = 1;
        while (wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .eq(WikiPageDO::getFilePath, "pages/" + candidate + ".md")
        ) != null) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String stripMarkdownFences(String content) {
        if (content == null) return null;
        String trimmed = content.trim();
        if (trimmed.startsWith("```markdown") || trimmed.startsWith("```md")) {
            int end = trimmed.indexOf("\n") + 1;
            trimmed = trimmed.substring(end);
        } else if (trimmed.startsWith("```")) {
            int end = trimmed.indexOf("\n") + 1;
            trimmed = trimmed.substring(end);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }

    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String trimmed = stripMarkdownFences(raw);
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        int cjkCount = 0, otherCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u4e00' && c <= '\u9fff') || (c >= '\u3400' && c <= '\u4dbf') || (c >= '\u3000' && c <= '\u303f')) {
                cjkCount++;
            } else {
                otherCount++;
            }
        }
        return cjkCount * 2 + otherCount / 4;
    }

    private int estimateTokensFromLength(int charLength) {
        return Math.max(1, charLength / 2);
    }

    private boolean isAiLintStep(String stepName) {
        return Set.of(
            "PROBE_AND_VALIDATE",
            "AUTO_FIX_ORPHANS",
            "AUTO_FIX_CROSSREFS",
            "GENERATE_RULING_BRIEFS",
            "SUGGEST_ACTIONS",
            "PROPOSE_SCHEMA_PATCH"
        ).contains(stepName);
    }

    private String generatePagePath(String metadataJson) {
        String title = extractJsonField(metadataJson, "title");
        if (title == null || title.isEmpty() || title.equals("未命名")) {
            return "pages/untitled-" + System.currentTimeMillis() + ".md";
        }
        String normalized = title.toLowerCase();
        String sanitized = normalized.replaceAll("[^a-z0-9\\u4e00-\\u9fff_-]", "-");
        sanitized = sanitized.replaceAll("-+", "-");
        if (sanitized.startsWith("-")) sanitized = sanitized.substring(1);
        if (sanitized.endsWith("-")) sanitized = sanitized.substring(0, sanitized.length() - 1);
        if (sanitized.isEmpty()) sanitized = "untitled-" + System.currentTimeMillis();
        return "pages/" + sanitized + ".md";
    }

    private String extractJsonField(String json, String field) {
        if (json == null || json.isEmpty()) return "";
        String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private void syncPageToIndex(WikiPageDO pageDO, Long scopeId) {
        try {
            String scopeIdStr = String.valueOf(scopeId);
            String content = "";
            String storagePath = "wiki/" + pageDO.getFilePath();
            if (storageProvider.exists(scopeIdStr, storagePath)) {
                byte[] contentBytes = storageProvider.read(scopeIdStr, storagePath);
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
            log.error("Failed to sync page {} to search index: {}", pageDO.getId(), e.getMessage(), e);
        }
    }

    private void persistSourceRelation(Long scopeId, Long pageId, Long sourceId) {
        if (pageId == null || sourceId == null) return;
        try {
            Long existing = wikiPageSourceMapper.selectCount(
                new LambdaQueryWrapper<WikiPageSourceDO>()
                    .eq(WikiPageSourceDO::getScopeId, scopeId)
                    .eq(WikiPageSourceDO::getPageId, pageId)
                    .eq(WikiPageSourceDO::getSourceId, sourceId)
            );
            if (existing != null && existing > 0) return;
            WikiPageSourceDO rel = new WikiPageSourceDO();
            rel.setScopeId(scopeId);
            rel.setPageId(pageId);
            rel.setSourceId(sourceId);
            wikiPageSourceMapper.insert(rel);
        } catch (Exception e) {
            log.warn("persistSourceRelation failed: scopeId={}, pageId={}, sourceId={}, error={}",
                scopeId, pageId, sourceId, e.getMessage());
        }
    }

    private void persistTagsAndKeywords(Long scopeId, Long pageId, String metadataJson) {
        if (pageId == null || metadataJson == null || metadataJson.isBlank()) return;
        try {
            List<String> tags = extractJsonArray(metadataJson, "tags");
            for (String tag : tags) {
                String trimmed = tag.trim();
                if (trimmed.isEmpty()) continue;
                Long existing = wikiPageTagMapper.selectCount(
                    new LambdaQueryWrapper<WikiPageTagDO>()
                        .eq(WikiPageTagDO::getScopeId, scopeId)
                        .eq(WikiPageTagDO::getPageId, pageId)
                        .eq(WikiPageTagDO::getTag, trimmed)
                );
                if (existing != null && existing > 0) continue;
                WikiPageTagDO tagDO = new WikiPageTagDO();
                tagDO.setScopeId(scopeId);
                tagDO.setPageId(pageId);
                tagDO.setTag(trimmed);
                wikiPageTagMapper.insert(tagDO);
            }

            List<String> keywords = extractJsonArray(metadataJson, "keywords");
            for (String keyword : keywords) {
                String trimmed = keyword.trim();
                if (trimmed.isEmpty()) continue;
                Long existing = wikiPageKeywordMapper.selectCount(
                    new LambdaQueryWrapper<WikiPageKeywordDO>()
                        .eq(WikiPageKeywordDO::getScopeId, scopeId)
                        .eq(WikiPageKeywordDO::getPageId, pageId)
                        .eq(WikiPageKeywordDO::getKeyword, trimmed)
                );
                if (existing != null && existing > 0) continue;
                WikiPageKeywordDO kwDO = new WikiPageKeywordDO();
                kwDO.setScopeId(scopeId);
                kwDO.setPageId(pageId);
                kwDO.setKeyword(trimmed);
                wikiPageKeywordMapper.insert(kwDO);
            }
        } catch (Exception e) {
            log.warn("persistTagsAndKeywords failed: scopeId={}, pageId={}, error={}",
                scopeId, pageId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseAffectedPages(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return List.of();
        try {
            Map<String, Object> root = objectMapper.readValue(metadataJson, Map.class);
            Object affected = root.get("affectedPages");
            if (!(affected instanceof List<?> list)) return List.of();
            List<Map<String, String>> results = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, String> entry = new HashMap<>();
                    Object path = m.get("path");
                    Object action = m.get("action");
                    Object title = m.get("title");
                    if (path != null) entry.put("path", path.toString());
                    if (action != null) entry.put("action", action.toString());
                    if (title != null) entry.put("title", title.toString());
                    results.add(entry);
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("parseAffectedPages failed: {}", e.getMessage());
            return List.of();
        }
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
                if (page != null && page.getFilePath() != null) {
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
        List<Map<String, String>> merged = new ArrayList<>(llmAffectedPages);
        for (Map<String, String> page : llmAffectedPages) {
            String path = page.get("path");
            if (path != null && !path.isBlank()) {
                coveredPaths.add(path);
            }
        }
        for (String path : sourceRelatedPaths) {
            if (!coveredPaths.contains(path)) {
                log.info("Programmatically adding source-related page to UPDATE_RELATED: {}", path);
                Map<String, String> extra = new HashMap<>();
                extra.put("path", path);
                extra.put("action", "更新");
                merged.add(extra);
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseEntities(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return List.of();
        try {
            Map<String, Object> root = objectMapper.readValue(metadataJson, Map.class);
            Object entitiesObj = root.get("entities");
            if (!(entitiesObj instanceof List<?> list)) return List.of();
            List<Map<String, String>> results = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, String> entry = new HashMap<>();
                    Object name = m.get("name");
                    Object type = m.get("type");
                    if (name != null) entry.put("name", name.toString());
                    if (type != null) entry.put("type", type.toString());
                    if (entry.get("name") != null) results.add(entry);
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("parseEntities failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String deriveEntityCategory(String entityType, String metadataJson) {
        String parentCategory = extractJsonField(metadataJson, "category");
        if (parentCategory == null || parentCategory.isBlank()) {
            parentCategory = "其他";
        }
        return switch (entityType) {
            case "person", "人物" -> "人物";
            case "organization", "组织" -> "组织机构";
            case "system", "系统" -> "系统/平台";
            case "document", "文档" -> "文档/制度";
            case "event", "事件" -> "事件";
            default -> parentCategory;
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> extractJsonArray(String json, String field) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            Object val = root.get(field);
            if (val instanceof List<?> list) {
                List<String> result = new java.util.ArrayList<>();
                for (Object item : list) {
                    if (item != null) result.add(item.toString());
                }
                return result;
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

}
