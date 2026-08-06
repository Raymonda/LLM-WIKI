package org.cn.liuwt.llmwiki.domain.service.harness.modify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaPatchModel;
import org.cn.liuwt.llmwiki.domain.service.harness.GlobalSummaryService;
import org.cn.liuwt.llmwiki.domain.service.harness.LinkWritingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.baseline.ExecutionBaselineService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.ApprovalService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.ApprovalService.ApprovalLevel;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.AsyncSchemaPatchService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.RateLimitService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.TokenUsageMonitor;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.WriterAgent;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class PageModifyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PageModifyOrchestrator.class);

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private TokenUsageMonitor tokenUsageMonitor;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private SchemaComplianceChecker schemaComplianceChecker;

    @Autowired
    private WriterAgent writerAgent;

    @Autowired
    private GlobalSummaryService globalSummaryService;

    @Autowired
    private ExecutionBaselineService baselineService;

    @Autowired
    private LinkWritingService linkWritingService;

    @Autowired
    private AsyncSchemaPatchService asyncSchemaPatchService;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private org.cn.liuwt.llmwiki.domain.service.search.SearchService searchService;

    @Autowired
    private LlmConcurrencyBarrier llmBarrier;

    private final ThreadPoolExecutor writerExecutor = new ThreadPoolExecutor(
        4, 8, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(100),
        r -> new Thread(r, "page-modify-writer-" + r.hashCode())
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExecutionModel runPipeline(Long executionId, Long scopeId, Long pageId,
                                       String instruction, Long sourceId, boolean skipComplianceCheck) {
        TokenUsageContext.set(scopeId, "modify");
        try {
            return doRunPipeline(executionId, scopeId, pageId, instruction, sourceId, skipComplianceCheck);
        } finally {
            TokenUsageContext.clear();
        }
    }

    private ExecutionModel doRunPipeline(Long executionId, Long scopeId, Long pageId,
                                         String instruction, Long sourceId, boolean skipComplianceCheck) {
        if (!rateLimitService.tryAcquireConcurrent(scopeId)) {
            throw new RuntimeException("并发执行数量已达上限，请等待当前任务完成后再试。scopeId=" + scopeId);
        }

        executionTracker.updateExecutionStatus(executionId, "running");

        WikiPageDO pageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getId, pageId)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (pageDO == null) {
            rateLimitService.releaseConcurrent(scopeId);
            throw new RuntimeException("Page not found: id=" + pageId + ", scopeId=" + scopeId);
        }

        PageModifyContext context = new PageModifyContext(scopeId, pageId, executionId, sourceId,
            instruction, skipComplianceCheck);
        context.setPagePath(pageDO.getFilePath());
        context.setPageTitle(pageDO.getTitle());
        int totalTokens = 0;

        try {
            ExecutionStepModel analyzeStep = createAndRunStep(executionId, ModifyStep.ANALYZE_FEEDBACK, scopeId);
            long analyzeStart = System.currentTimeMillis();
            totalTokens += analyzeFeedback(context);
            completeStep(analyzeStep, scopeId, context.toAnalyzeOutputJson(), totalTokens,
                System.currentTimeMillis() - analyzeStart);

            ExecutionStepModel writeStep = createAndRunStep(executionId, ModifyStep.WRITE_PAGES, scopeId);
            long writeStart = System.currentTimeMillis();
            int writeTokens = writePages(context);
            completeStep(writeStep, scopeId, context.toWriteOutputJson(), writeTokens,
                System.currentTimeMillis() - writeStart);
            totalTokens += writeTokens;

            ExecutionStepModel linksStep = createAndRunStep(executionId, ModifyStep.UPDATE_LINKS, scopeId);
            long linksStart = System.currentTimeMillis();
            int linksTokens = updateLinks(context);
            completeStep(linksStep, scopeId, "链接更新完成", linksTokens,
                System.currentTimeMillis() - linksStart);
            totalTokens += linksTokens;

        } catch (Exception e) {
            log.error("Page modify pipeline failed: executionId={}, scopeId={}, pageId={}",
                executionId, scopeId, pageId, e);
            executionTracker.failExecution(executionId, e.getMessage());
            rateLimitService.releaseConcurrent(scopeId);
            throw new RuntimeException("Page modify pipeline failed", e);
        }

        executionTracker.completeExecution(executionId, totalTokens);
        globalSummaryService.invalidate(scopeId);
        rateLimitService.releaseConcurrent(scopeId);
        return executionTracker.getExecution(executionId);
    }

    private int analyzeFeedback(PageModifyContext context) {
        Long scopeId = context.getScopeId();
        String pagePath = context.getPagePath();

        byte[] contentBytes = storageProvider.read(String.valueOf(scopeId), "wiki/" + pagePath);
        if (contentBytes == null) {
            throw new RuntimeException("Page content not found: " + pagePath);
        }
        String currentContent = new String(contentBytes, StandardCharsets.UTF_8);
        context.setCurrentContent(currentContent);

        String globalSummary = globalSummaryService.build(scopeId).toCompactPrompt();
        context.setGlobalSummary(globalSummary);

        String prompt = schemaInjector.prependForWriter(scopeId,
            PromptRegistry.forPageModify().analyzeFeedback(currentContent, context.getInstruction(), globalSummary));

        if (!llmBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.ANALYZE, 60_000)) {
            log.warn("analyzeFeedback: barrier acquire timeout, falling back to simple mode for pageId={}", context.getPageId());
            return createSimpleModifyPlan(context);
        }
        try {
            String response = chatClient.chat(prompt);
            String cleaned = cleanJson(response);
            try {
                ObjectMapper mapper = new ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(cleaned);
                PageModifyContext.ModifyPlan plan = new PageModifyContext.ModifyPlan();

                if (root.has("targetChanges") && root.get("targetChanges").isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode node : root.get("targetChanges")) {
                        PageModifyContext.ModifyPlan.TargetChange tc = new PageModifyContext.ModifyPlan.TargetChange();
                        tc.setSection(node.has("section") ? node.get("section").asText() : "");
                        tc.setChange(node.has("change") ? node.get("change").asText() : "");
                        tc.setRationale(node.has("rationale") ? node.get("rationale").asText() : "");
                        plan.getTargetChanges().add(tc);
                    }
                }
                if (root.has("affectedPages") && root.get("affectedPages").isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode node : root.get("affectedPages")) {
                        PageModifyContext.ModifyPlan.AffectedPage ap = new PageModifyContext.ModifyPlan.AffectedPage();
                        ap.setPagePath(node.has("pagePath") ? node.get("pagePath").asText() : "");
                        ap.setRelevantChange(node.has("relevantChange") ? node.get("relevantChange").asText() : "");
                        ap.setWhyAffected(node.has("whyAffected") ? node.get("whyAffected").asText() : "");
                        plan.getAffectedPages().add(ap);
                    }
                }
                context.setModifyPlan(plan);
                context.setModifyPlanJson(cleaned);
                return estimateTokens(prompt + response);
            } catch (Exception parseEx) {
                log.warn("analyzeFeedback: failed to parse ModifyPlan JSON, using simple mode. error={}", parseEx.getMessage());
                return createSimpleModifyPlan(context);
            }
        } finally {
            llmBarrier.release(LlmConcurrencyBarrier.Bucket.ANALYZE);
        }
    }

    private int createSimpleModifyPlan(PageModifyContext context) {
        PageModifyContext.ModifyPlan plan = new PageModifyContext.ModifyPlan();
        PageModifyContext.ModifyPlan.TargetChange tc = new PageModifyContext.ModifyPlan.TargetChange();
        tc.setSection("全文");
        tc.setChange(context.getInstruction());
        tc.setRationale("用户反馈驱动修改（降级模式）");
        plan.getTargetChanges().add(tc);
        context.setModifyPlan(plan);
        context.setModifyPlanJson("{\"fallback\":true,\"mode\":\"simple\"}");
        return 0;
    }

    private int writePages(PageModifyContext context) {
        Long scopeId = context.getScopeId();
        String pagePath = context.getPagePath();
        Long sourceId = context.getSourceId();
        PageModifyContext.ModifyPlan plan = context.getModifyPlan();
        int totalTokens = 0;

        String analysisContext = buildAnalysisContext(plan);
        String metadataJson = context.getMetadataJson() != null ? context.getMetadataJson() : "{}";

        WikiPageDO modifiedTarget = writerAgent.modifyExistingPage(
            scopeId, pagePath, context.getInstruction(), analysisContext, metadataJson, sourceId);
        if (modifiedTarget != null) {
            context.addModifiedPage(pagePath, modifiedTarget);
            byte[] writtenBytes = storageProvider.read(String.valueOf(scopeId), "wiki/" + pagePath);
            if (writtenBytes != null) {
                context.addModifiedContent(pagePath, new String(writtenBytes, StandardCharsets.UTF_8));
            }
        }

        List<PageModifyContext.ModifyPlan.AffectedPage> affectedPages = plan.getAffectedPages();
        if (affectedPages != null && !affectedPages.isEmpty()) {
            TokenUsageContext.Context parentCtx = TokenUsageContext.get();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (PageModifyContext.ModifyPlan.AffectedPage ap : affectedPages) {
                if (ap.getPagePath() == null || ap.getPagePath().isBlank()) continue;
                if (ap.getPagePath().equals(pagePath)) continue;

                futures.add(CompletableFuture.runAsync(() -> {
                    if (parentCtx != null) TokenUsageContext.set(parentCtx.scopeId(), parentCtx.operationType());
                    try {
                        String affectedInstruction = "根据主页面修改同步更新: " + ap.getRelevantChange();
                        WikiPageDO result = writerAgent.modifyExistingPage(
                            scopeId, ap.getPagePath(), affectedInstruction, ap.getRelevantChange(), metadataJson, sourceId);
                        if (result != null) {
                            context.addModifiedPage(ap.getPagePath(), result);
                            byte[] bytes = storageProvider.read(String.valueOf(scopeId), "wiki/" + ap.getPagePath());
                            if (bytes != null) {
                                context.addModifiedContent(ap.getPagePath(), new String(bytes, StandardCharsets.UTF_8));
                            }
                        }
                    } finally {
                        TokenUsageContext.clear();
                    }
                }, writerExecutor));
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(180, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("writePages: some affected page updates timed out or failed: {}", e.getMessage());
            }
        }

        totalTokens += estimateTokens(context.getInstruction());

        if (!context.isSkipComplianceCheck()) {
            ComplianceResult productResult = schemaComplianceChecker.check(scopeId, metadataJson, context.getAllModifiedContent());
            if (productResult.requiresReview()) {
                log.warn("Page modify product compliance violations: executionId={}, scopeId={}, summary={}",
                    context.getExecutionId(), scopeId, productResult.summarize());
                asyncSchemaPatchService.proposeAsync(scopeId, context.getExecutionId(),
                    SchemaPatchModel.SourceType.INGEST, productResult.summarize());
            }
        }

        sanitizeAllContent(context);
        syncContentLinks(context);
        verifySourceRelations(context);
        bulkSyncToIndex(context);

        return totalTokens;
    }

    private int updateLinks(PageModifyContext context) {
        log.info("updateLinks: page modify links update for executionId={}", context.getExecutionId());
        asyncSchemaPatchService.proposeAsync(context.getScopeId(), context.getExecutionId(),
            SchemaPatchModel.SourceType.INGEST, "Page modify completed for pageId=" + context.getPageId());
        return 0;
    }

    private void sanitizeAllContent(PageModifyContext context) {
        for (Map.Entry<String, String> entry : context.getAllModifiedContent().entrySet()) {
            String sanitized = linkWritingService.sanitizeSourceLinks(entry.getValue());
            sanitized = linkWritingService.sanitizeWikiLinks(sanitized, context.getScopeId());
            if (!sanitized.equals(entry.getValue())) {
                context.getAllModifiedContent().put(entry.getKey(), sanitized);
            }
        }
    }

    private void syncContentLinks(PageModifyContext context) {
        try {
            Map<Long, String> pageIdToContent = new HashMap<>();
            for (Map.Entry<String, WikiPageDO> entry : context.getModifiedPages().entrySet()) {
                WikiPageDO page = entry.getValue();
                if (page != null && page.getId() != null) {
                    String content = context.getAllModifiedContent().get(entry.getKey());
                    if (content != null && !content.isBlank()) {
                        pageIdToContent.put(page.getId(), content);
                    }
                }
            }
            if (!pageIdToContent.isEmpty()) {
                linkWritingService.syncContentLinks(context.getScopeId(), pageIdToContent, context.getExecutionId());
            }
        } catch (Exception e) {
            log.warn("PageModifyOrchestrator: syncContentLinks failed (non-blocking): {}", e.getMessage());
        }
    }

    private void verifySourceRelations(PageModifyContext context) {
        log.debug("verifySourceRelations: WriterAgent.modifyExistingPage already handles source relations internally");
    }

    @SuppressWarnings("unchecked")
    private void bulkSyncToIndex(PageModifyContext context) {
        try {
            List<WikiPageDO> pages = new ArrayList<>(context.getModifiedPages().values());
            pages.removeIf(Objects::isNull);
            if (!pages.isEmpty()) {
                searchService.bulkIndexPages(context.getScopeId(), pages);
            }
        } catch (Exception e) {
            log.warn("bulkSyncToIndex failed for page modify executionId={}: {}",
                context.getExecutionId(), e.getMessage());
        }
    }

    private String buildAnalysisContext(PageModifyContext.ModifyPlan plan) {
        if (plan == null) return "";
        StringBuilder sb = new StringBuilder();
        List<PageModifyContext.ModifyPlan.TargetChange> targetChanges = plan.getTargetChanges();
        if (targetChanges != null) {
            sb.append("目标页面修改点：\n");
            for (PageModifyContext.ModifyPlan.TargetChange tc : targetChanges) {
                sb.append("- 段落: ").append(tc.getSection()).append("\n");
                sb.append("  修改: ").append(tc.getChange()).append("\n");
            }
        }
        return sb.toString();
    }

    private ExecutionStepModel createAndRunStep(Long executionId, ModifyStep step, Long scopeId) {
        ApprovalLevel level = approvalService.getApprovalLevel(step.name(), "page_modify");
        ExecutionStepModel stepModel = executionTracker.createStep(executionId, step.name(), step.ordinal() + 1, level.name());
        executionTracker.updateStepStatus(stepModel.getId(), "running");
        return stepModel;
    }

    private void completeStep(ExecutionStepModel step, Long scopeId, String output, int tokens, long durationMs) {
        executionTracker.completeStep(step.getId(), output, tokens, (int) Math.min(durationMs, Integer.MAX_VALUE));
        if (baselineService != null) {
            baselineService.recordSample(scopeId, "page_modify", step.getStepName(), durationMs);
        }
    }

    private String cleanJson(String content) {
        if (content == null || content.isEmpty()) return content;
        int start = content.indexOf("{");
        int end = content.lastIndexOf("}") + 1;
        return (start >= 0 && end > start) ? content.substring(start, end) : content;
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
}
