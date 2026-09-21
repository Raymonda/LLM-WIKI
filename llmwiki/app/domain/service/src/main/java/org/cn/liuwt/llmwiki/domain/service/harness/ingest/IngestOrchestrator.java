package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.GlobalSummaryService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventTypes;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.harness.baseline.ExecutionBaselineService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.ApprovalService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.ApprovalService.ApprovalLevel;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.RateLimitService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestOrchestrator.class);

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private UploadAgent uploadAgent;

    @Autowired
    private AnalysisAgent analysisAgent;

    @Autowired
    private WritingAgent writingAgent;

    @Autowired
    private CompletionAgent completionAgent;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired(required = false)
    private ExecutionBaselineService baselineService;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private GlobalSummaryService globalSummaryService;

    @Autowired
    private ExecutionEventLogService executionEventLog;

    @Autowired
    private SchemaComplianceChecker schemaComplianceChecker;

    @Autowired
    private ScopeMapper scopeMapper;

    @Autowired
    private IngestBatchMapper ingestBatchMapper;

    @Autowired
    private AutoConfirmPolicy autoConfirmPolicy;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Value("${llmwiki.ingest.auto-confirm.enabled:true}")
    private boolean autoConfirmEnabled;

    public static boolean isBatchContext(ExecutionModel execution) {
        return execution != null && execution.getBatchId() != null;
    }

    public ExecutionModel runIngestPipelineWithExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return runIngestPipelineWithExecution(executionId, scopeId, sourceId, guidance, false);
    }

    public ExecutionModel runIngestPipelineWithExecution(Long executionId, Long scopeId, Long sourceId, String guidance, boolean suppressNotifications) {
        TokenUsageContext.set(scopeId, "ingest");
        try {
            return doRunIngestPipeline(executionId, scopeId, sourceId, guidance, suppressNotifications, false);
        } finally {
            TokenUsageContext.clear();
        }
    }

    private ExecutionModel doRunIngestPipeline(Long executionId, Long scopeId, Long sourceId, String guidance, boolean suppressNotifications, boolean stopAfterPhase1) {
        if (!rateLimitService.tryAcquireConcurrent(scopeId)) {
            throw new RuntimeException("并发执行数量已达上限，请等待当前任务完成后再试。scopeId=" + scopeId);
        }

        try {
            if (!rateLimitService.awaitCallRate(scopeId)) {
                throw new RuntimeException("AI 调用频率过高，请稍后再试。scopeId=" + scopeId);
            }

            executionTracker.updateExecutionStatus(executionId, "running");

            ExecutionModel currentExecution = executionTracker.getExecution(executionId);
            boolean suppressAll = suppressNotifications || isBatchContext(currentExecution);
            Long submittedBy = currentExecution != null ? currentExecution.getSubmittedBy() : null;

            SourceDO sourceDO = sourceMapper.selectOne(
                new LambdaQueryWrapper<SourceDO>()
                    .eq(SourceDO::getId, sourceId)
                    .eq(SourceDO::getScopeId, scopeId)
            );
            if (sourceDO == null) {
                throw new RuntimeException("Source not found: id=" + sourceId + ", scopeId=" + scopeId);
            }

            String sourceName = sourceDO.getName() != null ? sourceDO.getName() : "未知文件";
            if (!suppressAll) {
                notificationService.createPersonalNotification(submittedBy, "ingest_started",
                    "正在处理 — " + sourceName,
                    "AI 正在分析文档内容，完成后可查看生成的知识页面",
                    scopeId, null, executionId);
            }

            IngestContext context = new IngestContext(scopeId, sourceId, executionId, guidance);
            context.setSubmittedBy(submittedBy);
            context.setSuppressNotifications(suppressAll);
            int totalTokens = 0;
            executionEventLog.append(String.valueOf(executionId), ExecutionEventTypes.TURN_START,
                java.util.Map.of("pipeline", "ingest", "scopeId", scopeId, "sourceId", sourceId));

            try {
                ExecutionStepModel uploadStep = createAndRunStep(executionId, IngestStep.UPLOAD, scopeId);
                long uploadStart = System.currentTimeMillis();
                int uploadTokens = uploadAgent.process(context);
                completeStep(uploadStep, scopeId, context.toParseOutputJson(sourceDO), estimateTokens(context.getSourceContent()) + uploadTokens, System.currentTimeMillis() - uploadStart);
                totalTokens += uploadTokens;

                checkStopped(executionId);
                ExecutionStepModel analyzeStep = createAndRunStep(executionId, IngestStep.ANALYZE, scopeId);
                long analyzeStart = System.currentTimeMillis();
                int analyzeTokens = analysisAgent.process(context, executionId, analyzeStep.getId());
                applyTargetTitleToContext(context);
                completeStep(analyzeStep, scopeId, buildAnalysisOutput(context), analyzeTokens, System.currentTimeMillis() - analyzeStart);
                totalTokens += analyzeTokens;

                checkStopped(executionId);
                if (stopAfterPhase1) {
                    return awaitReview(executionId, scopeId, sourceName, totalTokens, suppressAll, context);
                }
                ExecutionStepModel writeStep = createAndRunStep(executionId, IngestStep.WRITE, scopeId);
                long writeStart = System.currentTimeMillis();
                int writeTokens = writingAgent.process(context);
                completeStep(writeStep, scopeId, context.toWriteOutputJson(), writeTokens, System.currentTimeMillis() - writeStart);
                totalTokens += writeTokens;

                checkStopped(executionId);
                ExecutionStepModel completeStep = createAndRunStep(executionId, IngestStep.COMPLETE, scopeId);
                long completeStart = System.currentTimeMillis();
                int completeTokens = completionAgent.process(context);
                completeStep(completeStep, scopeId, buildCompletionOutput(context), completeTokens, System.currentTimeMillis() - completeStart);
                totalTokens += completeTokens;

            } catch (Exception e) {
                boolean isStopped = isStoppedOrPaused(executionId) || Thread.currentThread().isInterrupted();
                if (isStopped) {
                    log.warn("Ingest pipeline stopped/paused: executionId={}, scopeId={}, sourceId={}", executionId, scopeId, sourceId);
                    throw e;
                }
                log.error("Ingest pipeline failed: executionId={}, scopeId={}, sourceId={}", executionId, scopeId, sourceId, e);
                executionEventLog.append(String.valueOf(executionId), ExecutionEventTypes.ERROR,
                    java.util.Map.of("pipeline", "ingest",
                        "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                executionTracker.failExecution(executionId, e.getMessage());
                if (!suppressAll) {
                    notificationService.createPersonalNotification(submittedBy, "ingest_failed",
                        "处理失败 — " + sourceName,
                        e.getMessage() != null ? e.getMessage() : "未知错误，请重试",
                        scopeId, null, executionId);
                }
                throw e;
            }

            ExecutionModel exec = executionTracker.getExecution(executionId);
            if (!"failed".equals(exec.getStatus()) && !"cancelled".equals(exec.getStatus()) && !"paused".equals(exec.getStatus())) {
                executionTracker.completeExecution(executionId, totalTokens);
                globalSummaryService.invalidate(scopeId);
                executionEventLog.append(String.valueOf(executionId), ExecutionEventTypes.TURN_END,
                    java.util.Map.of("pipeline", "ingest", "status", "completed", "totalTokens", totalTokens));
                int conflictCount = context.getConflictAnnotations() != null ? context.getConflictAnnotations().size() : 0;
                String completeContent;
                if (conflictCount > 0) {
                    completeContent = "处理完成，发现 " + conflictCount + " 处知识矛盾，建议查看";
                } else {
                    completeContent = "文档已成功处理，知识页面已更新";
                }
                if (!suppressAll) {
                    notificationService.createPersonalNotification(submittedBy, "ingest_completed",
                        "处理完成 — " + sourceName,
                        completeContent,
                        scopeId, null, executionId);
                }
            } else {
                executionEventLog.append(String.valueOf(executionId), ExecutionEventTypes.TURN_END,
                    java.util.Map.of("pipeline", "ingest", "status", exec.getStatus()));
            }

            return executionTracker.getExecution(executionId);
        } finally {
            rateLimitService.releaseConcurrent(scopeId);
        }
    }

    /**
     * Phase1 分析完成后的用户决策点。先做 Schema 合规预检（宪法规则 3：违规即阻断）：
     * 分类/元数据/写作计划存在 HIGH/MEDIUM 违规 → 进入 awaiting_review 待评审，
     * 否则维持 awaiting_confirmation 常规确认。预检自身异常按 fail-open 处理（不因
     * 检查器故障阻断正常流程），但不吞掉违规结论。
     * 批量导入模式（ingest mode = auto）下改由 AutoConfirmPolicy 判定：放行则置 confirmed
     * 并发布 IngestAutoConfirmedEvent 驱动调度续跑；拦截则置 awaiting_review 走人工评审。
     */
    private ExecutionModel awaitReview(Long executionId, Long scopeId, String sourceName, int totalTokens,
                                       boolean suppressNotifications, IngestContext context) {
        boolean schemaReview = schemaPrecheckRequiresReview(scopeId, context);
        if ("auto".equals(resolveIngestMode(executionId, scopeId))) {
            return awaitAutoDecision(executionId, scopeId, sourceName, totalTokens, suppressNotifications, context, schemaReview);
        }
        String reviewStatus = schemaReview ? "awaiting_review" : "awaiting_confirmation";
        executionTracker.updateExecutionStatus(executionId, reviewStatus);
        executionEventLog.append(String.valueOf(executionId), ExecutionEventTypes.TURN_END,
            java.util.Map.of("pipeline", "ingest", "status", reviewStatus, "totalTokens", totalTokens));
        if (!suppressNotifications) {
            ExecutionModel execution = executionTracker.getExecution(executionId);
            Long submittedBy = execution != null ? execution.getSubmittedBy() : null;
            if (schemaReview) {
                notificationService.createPersonalNotification(submittedBy, "ingest_awaiting_review",
                    "需要评审 — " + sourceName,
                    "分析结果存在 Schema 合规违规，请审阅后确认是否继续写入知识库",
                    scopeId, null, executionId);
            } else {
                notificationService.createPersonalNotification(submittedBy, "ingest_awaiting_confirmation",
                    "分析完成 — " + sourceName,
                    "请审阅分析结果，确认后写入知识库",
                    scopeId, null, executionId);
            }
        }
        return executionTracker.getExecution(executionId);
    }

    private boolean schemaPrecheckRequiresReview(Long scopeId, IngestContext context) {
        if (context == null) return false;
        try {
            ComplianceResult planResult = schemaComplianceChecker.checkPlan(
                scopeId, context.getWritingPlanJson(), context.getMetadataJson());
            if (planResult.requiresReview()) {
                log.warn("Schema 预检发现写作计划违规 scope={} violations={}",
                    scopeId, planResult.violations().size());
                return true;
            }
            ComplianceResult metaResult = schemaComplianceChecker.check(
                scopeId, context.getMetadataJson(), java.util.Map.of());
            if (metaResult.requiresReview()) {
                log.warn("Schema 预检发现元数据违规 scope={} violations={}",
                    scopeId, metaResult.violations().size());
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Schema 预检执行失败，按无违规继续 scope={}: {}", scopeId, e.getMessage());
            return false;
        }
    }

    private String resolveIngestMode(Long executionId, Long scopeId) {
        if (!autoConfirmEnabled) return "review";
        ScopeDO scope = scopeMapper.selectById(scopeId);
        if (scope != null && Boolean.TRUE.equals(scope.getAutoSuspended())) return "review";
        ExecutionModel execution = executionTracker.getExecution(executionId);
        if (execution != null && execution.getBatchId() != null) {
            IngestBatchDO batch = ingestBatchMapper.selectById(execution.getBatchId());
            if (batch != null && batch.getMode() != null) return batch.getMode();
        }
        if (scope != null && scope.getIngestMode() != null) return scope.getIngestMode();
        return "review";
    }

    private ExecutionModel awaitAutoDecision(Long executionId, Long scopeId, String sourceName, int totalTokens,
                                             boolean suppressNotifications, IngestContext context, boolean schemaReview) {
        AutoConfirmPolicy.AutoDecision decision = autoConfirmPolicy.decide(buildAutoConfirmSignals(context, schemaReview));
        java.util.Map<String, Object> autoDecision = java.util.Map.of(
            "autoApprove", decision.autoApprove(),
            "hardBlocked", decision.hardBlocked(),
            "softScore", decision.softScore(),
            "reasons", decision.reasons());
        if (decision.autoApprove()) {
            executionTracker.updateExecutionStatus(executionId, "confirmed");
            executionEventLog.append(String.valueOf(executionId), ExecutionEventTypes.TURN_END,
                java.util.Map.of("pipeline", "ingest", "status", "confirmed", "totalTokens", totalTokens,
                    "autoDecision", autoDecision));
            eventPublisher.publishEvent(new IngestAutoConfirmedEvent(this, scopeId, executionId));
            log.info("Ingest auto-confirmed: executionId={}, scopeId={}", executionId, scopeId);
            return executionTracker.getExecution(executionId);
        }
        executionTracker.updateExecutionStatus(executionId, "awaiting_review");
        executionEventLog.append(String.valueOf(executionId), ExecutionEventTypes.TURN_END,
            java.util.Map.of("pipeline", "ingest", "status", "awaiting_review", "totalTokens", totalTokens,
                "autoDecision", autoDecision));
        if (!suppressNotifications) {
            ExecutionModel execution = executionTracker.getExecution(executionId);
            Long submittedBy = execution != null ? execution.getSubmittedBy() : null;
            String reviewNotice = decision.hardBlocked()
                ? "分析结果存在 Schema 合规违规，请审阅后确认是否继续写入知识库"
                : "自动放行评估未通过（风险评分 " + decision.softScore() + "），请评审后确认是否写入知识库";
            notificationService.createPersonalNotification(submittedBy, "ingest_awaiting_review",
                "需要评审 — " + sourceName,
                reviewNotice,
                scopeId, null, executionId);
        }
        return executionTracker.getExecution(executionId);
    }

    private AutoConfirmPolicy.Signals buildAutoConfirmSignals(IngestContext context, boolean schemaReview) {
        int conflictCount = context != null && context.getConflictAnnotations() != null
            ? context.getConflictAnnotations().size() : 0;
        double completeness = context != null ? context.getCompletenessScore() : 0.0;
        double updateRatio = context != null ? extractUpdateRatio(context.getMetadataJson(), context.getWritingPlanJson()) : 0.0;
        boolean hasGapHints = context != null && context.getSchemaGapHints() != null
            && !context.getSchemaGapHints().isEmpty();
        boolean parseDegraded = isParseDegraded(context != null ? context.getParseValidationReport() : null);
        return new AutoConfirmPolicy.Signals(schemaReview, conflictCount, completeness, updateRatio, hasGapHints, parseDegraded);
    }

    private boolean isParseDegraded(String report) {
        return report != null && report.toLowerCase(java.util.Locale.ROOT).matches(".*(degraded|fallback|降级).*");
    }

    private double extractUpdateRatio(String metadataJson, String writingPlanJson) {
        int[] metadataCounts = countMetadataActions(metadataJson);
        if (metadataCounts != null) {
            int total = metadataCounts[0] + metadataCounts[1];
            return total == 0 ? 0.0 : (double) metadataCounts[0] / total;
        }
        return extractWritingPlanUpdateRatio(writingPlanJson);
    }

    private int[] countMetadataActions(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return null;
        try {
            int start = metadataJson.indexOf("{");
            int end = metadataJson.lastIndexOf("}") + 1;
            if (start < 0 || end <= start) return null;
            JsonNode root = MAPPER.readTree(metadataJson.substring(start, end));
            if (root == null || !root.isObject()) return null;
            int updates = 0;
            int creates = 0;
            JsonNode affected = root.get("affectedPages");
            if (affected != null && affected.isArray()) {
                for (JsonNode page : affected) {
                    String action = page.has("action") ? page.get("action").asText() : "补充";
                    if ("更新".equals(action) || "补充".equals(action)) updates++;
                }
            }
            JsonNode entities = root.get("entities");
            if (entities != null && entities.isArray()) {
                for (JsonNode entity : entities) {
                    String action = entity.has("action") ? entity.get("action").asText() : "";
                    if ("新建".equals(action)) creates++;
                    else if ("补充".equals(action) || "更新".equals(action)) updates++;
                }
            }
            return (updates + creates) > 0 ? new int[]{updates, creates} : null;
        } catch (Exception e) {
            log.warn("countMetadataActions parse failed: {}", e.getMessage());
            return null;
        }
    }

    private double extractWritingPlanUpdateRatio(String writingPlanJson) {
        if (writingPlanJson == null || writingPlanJson.isBlank()) return 0.0;
        try {
            JsonNode root = MAPPER.readTree(writingPlanJson);
            if (root == null || !root.isObject()) return 0.0;
            int updates = planMapSize(root.get("affectedPagePlans"));
            int total = updates + planMapSize(root.get("entityPlans")) + planMapSize(root.get("chapterPlans"));
            return total == 0 ? 0.0 : (double) updates / total;
        } catch (Exception e) {
            log.warn("extractUpdateRatio parse failed: {}", e.getMessage());
            return 0.0;
        }
    }

    private int planMapSize(JsonNode node) {
        return node != null && node.isObject() ? node.size() : 0;
    }

    public ExecutionModel runIngestAnalysisWithExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        TokenUsageContext.set(scopeId, "ingest");
        try {
            return doRunIngestPipeline(executionId, scopeId, sourceId, guidance, false, true);
        } finally {
            TokenUsageContext.clear();
        }
    }

    public ExecutionModel runIngestExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        TokenUsageContext.set(scopeId, "ingest");
        try {
            return doResumeIngestExecution(executionId, scopeId, sourceId, guidance);
        } finally {
            TokenUsageContext.clear();
        }
    }

    public ExecutionModel reanalyzeIngest(Long executionId, Long scopeId, Long sourceId, String guidance) {
        TokenUsageContext.set(scopeId, "ingest");
        try {
            ExecutionModel execution = executionTracker.getExecution(executionId);
            if (execution == null || (!"awaiting_confirmation".equals(execution.getStatus())
                    && !"awaiting_review".equals(execution.getStatus()))) {
                throw new RuntimeException("该执行不在待确认状态，无法重新分析");
            }
            Long analyzeStepId = null;
            for (ExecutionStepModel step : executionTracker.listSteps(executionId)) {
                if (IngestStep.ANALYZE.name().equals(IngestStep.normalizeStepName(step.getStepName()))) {
                    analyzeStepId = step.getId();
                    break;
                }
            }
            if (analyzeStepId == null) {
                throw new RuntimeException("未找到分析步骤，无法重新分析: executionId=" + executionId);
            }
            executionTracker.resetStepForRetry(analyzeStepId);
            return doResumeIngestAnalysis(executionId, scopeId, sourceId, guidance);
        } finally {
            TokenUsageContext.clear();
        }
    }

    public ExecutionModel resumeIngestAnalysis(Long executionId, Long scopeId, Long sourceId, String guidance) {
        TokenUsageContext.set(scopeId, "ingest");
        try {
            return doResumeIngestAnalysis(executionId, scopeId, sourceId, guidance);
        } finally {
            TokenUsageContext.clear();
        }
    }

    private ExecutionModel doResumeIngestAnalysis(Long executionId, Long scopeId, Long sourceId, String guidance) {
        if (chatClient == null || !chatClient.isAvailable()) {
            throw new RuntimeException("AI 服务未配置或不可用");
        }
        if (!rateLimitService.tryAcquireConcurrent(scopeId)) {
            throw new RuntimeException("并发执行数量已达上限");
        }

        try {
            if (!rateLimitService.awaitCallRate(scopeId)) {
                throw new RuntimeException("AI 调用频率过高");
            }

            ExecutionModel execution = executionTracker.getExecution(executionId);
            if (execution == null || (!"failed".equals(execution.getStatus()) && !"paused".equals(execution.getStatus())
                    && !"awaiting_confirmation".equals(execution.getStatus()) && !"awaiting_review".equals(execution.getStatus())
                    && !"running".equals(execution.getStatus()))) {
                throw new RuntimeException("该执行当前状态无法继续分析阶段");
            }

            SourceDO sourceDO = sourceMapper.selectOne(
                new LambdaQueryWrapper<SourceDO>()
                    .eq(SourceDO::getId, sourceId)
                    .eq(SourceDO::getScopeId, scopeId)
            );
            if (sourceDO == null) {
                throw new RuntimeException("Source not found: id=" + sourceId + ", scopeId=" + scopeId);
            }

            List<ExecutionStepModel> steps = executionTracker.listSteps(executionId);
            ExecutionStepModel resumeStep = null;
            for (ExecutionStepModel step : steps) {
                String normalized = IngestStep.normalizeStepName(step.getStepName());
                if ("UPLOAD".equals(normalized) || "ANALYZE".equals(normalized)) {
                    if (!"completed".equals(step.getStatus())) {
                        resumeStep = step;
                        break;
                    }
                }
            }

            if (resumeStep == null) {
                boolean uploadCompleted = steps.stream().anyMatch(s ->
                    IngestStep.UPLOAD.name().equals(IngestStep.normalizeStepName(s.getStepName()))
                        && "completed".equals(s.getStatus()));
                boolean analyzeCompleted = steps.stream().anyMatch(s ->
                    IngestStep.ANALYZE.name().equals(IngestStep.normalizeStepName(s.getStepName()))
                        && "completed".equals(s.getStatus()));
                if (analyzeCompleted || !uploadCompleted) {
                    throw new RuntimeException("Phase1 所有步骤已完成，无法续传分析阶段");
                }
                resumeStep = findOrCreateStep(executionId, IngestStep.ANALYZE, scopeId, steps, steps.size() + 1);
            }

            log.info("Resuming Phase1 from step {} for executionId={}", resumeStep.getStepName(), executionId);

            IngestContext context = IngestContext.reconstructFromSteps(scopeId, sourceId, executionId, guidance, steps, storageProvider, sourceDO);

            executionTracker.resetStepForRetry(resumeStep.getId());
            executionTracker.resetExecutionForRetry(executionId);

            int totalTokens = 0;
            for (ExecutionStepModel step : steps) {
                if ("completed".equals(step.getStatus())) {
                    totalTokens += step.getTokensUsed() != null ? step.getTokensUsed() : 0;
                }
            }

            try {
                String resumeNormalized = IngestStep.normalizeStepName(resumeStep.getStepName());

                if ("UPLOAD".equals(resumeNormalized)) {
                    executionTracker.updateStepStatus(resumeStep.getId(), "running");
                    long start = System.currentTimeMillis();
                    int tokens = uploadAgent.process(context);
                    completeStep(resumeStep, scopeId, context.toParseOutputJson(sourceDO), estimateTokens(context.getSourceContent()) + tokens, System.currentTimeMillis() - start);
                    totalTokens += tokens;

                    checkStopped(executionId);
                    steps = executionTracker.listSteps(executionId);
                    ExecutionStepModel analyzeStep = findOrCreateStep(executionId, IngestStep.ANALYZE, scopeId, steps, steps.size() + 1);
                    long analyzeStart = System.currentTimeMillis();
                    int analyzeTokens = analysisAgent.process(context, executionId, analyzeStep.getId());
                    applyTargetTitleToContext(context);
                    completeStep(analyzeStep, scopeId, buildAnalysisOutput(context), analyzeTokens, System.currentTimeMillis() - analyzeStart);
                    totalTokens += analyzeTokens;

                } else if ("ANALYZE".equals(resumeNormalized)) {
                    executionTracker.updateStepStatus(resumeStep.getId(), "running");
                    long analyzeStart = System.currentTimeMillis();
                    int analyzeTokens = analysisAgent.process(context, executionId, resumeStep.getId());
                    applyTargetTitleToContext(context);
                    completeStep(resumeStep, scopeId, buildAnalysisOutput(context), analyzeTokens, System.currentTimeMillis() - analyzeStart);
                    totalTokens += analyzeTokens;
                }

                checkStopped(executionId);
                boolean suppressAll = isBatchContext(executionTracker.getExecution(executionId));
                return awaitReview(executionId, scopeId, sourceDO.getName() != null ? sourceDO.getName() : "未知文件", totalTokens, suppressAll, context);

            } catch (Exception e) {
                boolean isStopped = isStoppedOrPaused(executionId) || Thread.currentThread().isInterrupted();
                if (isStopped) {
                    log.warn("Phase1 resume stopped/paused: executionId={}", executionId);
                    throw e;
                }
                log.error("Phase1 resume failed: executionId={}", executionId, e);
                executionTracker.failExecution(executionId, e.getMessage());
                throw e;
            }
        } finally {
            rateLimitService.releaseConcurrent(scopeId);
        }
    }

    public ExecutionModel resumeIngestExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        TokenUsageContext.set(scopeId, "ingest");
        try {
            return doResumeIngestExecution(executionId, scopeId, sourceId, guidance);
        } finally {
            TokenUsageContext.clear();
        }
    }

    private ExecutionModel doResumeIngestExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        if (chatClient == null || !chatClient.isAvailable()) {
            throw new RuntimeException("AI 服务未配置或不可用");
        }
        if (!rateLimitService.tryAcquireConcurrent(scopeId)) {
            throw new RuntimeException("并发执行数量已达上限");
        }

        try {
            ExecutionModel execution = executionTracker.getExecution(executionId);
            if (execution == null || (!"failed".equals(execution.getStatus()) && !"paused".equals(execution.getStatus())
                    && !"awaiting_confirmation".equals(execution.getStatus()) && !"awaiting_review".equals(execution.getStatus())
                    && !"running".equals(execution.getStatus()))) {
                throw new RuntimeException("该执行当前状态无法继续执行阶段");
            }

            List<ExecutionStepModel> steps = executionTracker.listSteps(executionId);
            for (ExecutionStepModel step : steps) {
                String normalized = IngestStep.normalizeStepName(step.getStepName());
                if ("UPLOAD".equals(normalized) || "ANALYZE".equals(normalized)) {
                    if (!"completed".equals(step.getStatus())) {
                        throw new RuntimeException("Phase1 存在未完成步骤，请先续传分析阶段");
                    }
                }
            }

            SourceDO sourceDO = sourceMapper.selectOne(
                new LambdaQueryWrapper<SourceDO>()
                    .eq(SourceDO::getId, sourceId)
                    .eq(SourceDO::getScopeId, scopeId)
            );
            if (sourceDO == null) {
                throw new RuntimeException("Source not found: id=" + sourceId + ", scopeId=" + scopeId);
            }

            ExecutionStepModel resumeStep = null;
            for (ExecutionStepModel step : steps) {
                String normalized = IngestStep.normalizeStepName(step.getStepName());
                if ("WRITE".equals(normalized) || "COMPLETE".equals(normalized)) {
                    if (!"completed".equals(step.getStatus())) {
                        resumeStep = step;
                        break;
                    }
                }
            }

            int totalTokens = 0;
            for (ExecutionStepModel step : steps) {
                if ("completed".equals(step.getStatus())) {
                    totalTokens += step.getTokensUsed() != null ? step.getTokensUsed() : 0;
                }
            }

            if (resumeStep == null) {
                boolean writeCompleted = steps.stream().anyMatch(s ->
                    IngestStep.WRITE.name().equals(IngestStep.normalizeStepName(s.getStepName()))
                        && "completed".equals(s.getStatus()));
                if (!writeCompleted) {
                    resumeStep = findOrCreateStep(executionId, IngestStep.WRITE, scopeId, steps, steps.size() + 1);
                } else {
                    boolean completeCompleted = steps.stream().anyMatch(s ->
                        IngestStep.COMPLETE.name().equals(IngestStep.normalizeStepName(s.getStepName()))
                            && "completed".equals(s.getStatus()));
                    if (completeCompleted) {
                        executionTracker.resetExecutionForRetry(executionId);
                        executionTracker.completeExecution(executionId, totalTokens);
                        return executionTracker.getExecution(executionId);
                    }
                    resumeStep = findOrCreateStep(executionId, IngestStep.COMPLETE, scopeId, steps, steps.size() + 1);
                }
            }

            log.info("Resuming Phase2 from step {} for executionId={}", resumeStep.getStepName(), executionId);

            IngestContext context = IngestContext.reconstructFromSteps(scopeId, sourceId, executionId, guidance, steps, storageProvider, sourceDO);
            applyTargetTitleToContext(context);

            executionTracker.resetStepForRetry(resumeStep.getId());
            executionTracker.resetExecutionForRetry(executionId);

            try {
                checkStopped(executionId);
                String resumeNormalized = IngestStep.normalizeStepName(resumeStep.getStepName());

                if ("WRITE".equals(resumeNormalized)) {
                    executionTracker.updateStepStatus(resumeStep.getId(), "running");
                    long writeStart = System.currentTimeMillis();
                    int writeTokens = writingAgent.process(context);
                    completeStep(resumeStep, scopeId, context.toWriteOutputJson(), writeTokens, System.currentTimeMillis() - writeStart);
                    totalTokens += writeTokens;

                    checkStopped(executionId);
                    steps = executionTracker.listSteps(executionId);
                    ExecutionStepModel completeStepObj = findOrCreateStep(executionId, IngestStep.COMPLETE, scopeId, steps, steps.size() + 1);
                    long completeStart = System.currentTimeMillis();
                    int completeTokens = completionAgent.process(context);
                    completeStep(completeStepObj, scopeId, buildCompletionOutput(context), completeTokens, System.currentTimeMillis() - completeStart);
                    totalTokens += completeTokens;

                } else if ("COMPLETE".equals(resumeNormalized)) {
                    executionTracker.updateStepStatus(resumeStep.getId(), "running");
                    long completeStart = System.currentTimeMillis();
                    int completeTokens = completionAgent.process(context);
                    completeStep(resumeStep, scopeId, buildCompletionOutput(context), completeTokens, System.currentTimeMillis() - completeStart);
                    totalTokens += completeTokens;
                }

            } catch (Exception e) {
                boolean isStopped = isStoppedOrPaused(executionId) || Thread.currentThread().isInterrupted();
                if (isStopped) {
                    log.warn("Phase2 resume stopped: executionId={}", executionId);
                    throw e;
                }
                log.error("Phase2 resume failed: executionId={}", executionId, e);
                executionTracker.failExecution(executionId, e.getMessage());
                throw e;
            }

            ExecutionModel exec = executionTracker.getExecution(executionId);
            if (!"failed".equals(exec.getStatus()) && !"cancelled".equals(exec.getStatus()) && !"paused".equals(exec.getStatus())) {
                executionTracker.completeExecution(executionId, totalTokens);
            }
            return executionTracker.getExecution(executionId);
        } finally {
            rateLimitService.releaseConcurrent(scopeId);
        }
    }

    private String buildAnalysisOutput(IngestContext context) {
        try {
            java.util.Map<String, Object> output = new java.util.LinkedHashMap<>();
            String mergedAnalysis = context.getMergedAnalysis();
            if (mergedAnalysis != null && !mergedAnalysis.isEmpty()) {
                output.put("mergedAnalysis", mergedAnalysis);
            }
            String metadata = context.getMetadataJson();
            if (metadata != null && !metadata.isEmpty()) {
                try {
                    output.put("metadata", MAPPER.readTree(metadata));
                } catch (Exception e) {
                    output.put("metadata", metadata);
                }
            }
            if (context.getDocumentType() != null) {
                output.put("documentType", context.getDocumentType().name());
                output.put("chapterCount", context.getTotalChapterCount());
            }
            if (context.getStrategy() != null) {
                output.put("strategyPreset", context.getStrategy().getPreset().name());
                output.put("strategyReasoning", context.getStrategy().getReasoning());
                output.put("skipMerge", context.getStrategy().isSkipMerge());
                output.put("useChapterMode", context.getStrategy().isUseChapterMode());
            }
            output.put("completenessScore", context.getCompletenessScore());
            if (context.getEntityRelationshipSummary() != null) {
                output.put("entityRelationshipSummaryLength", context.getEntityRelationshipSummary().length());
            }
            return MAPPER.writeValueAsString(output);
        } catch (Exception e) {
            log.warn("buildAnalysisOutput failed: {}", e.getMessage());
            return context.getMetadataJson() != null ? context.getMetadataJson() : "{}";
        }
    }

    private String buildCompletionOutput(IngestContext context) {
        try {
            java.util.Map<String, Object> output = new java.util.LinkedHashMap<>();
            output.put("status", "completed");
            int conflictCount = context.getConflictAnnotations() != null ? context.getConflictAnnotations().size() : 0;
            if (conflictCount > 0) {
                output.put("conflictCount", conflictCount);
                java.util.Map<String, Integer> routeSummary = context.getConflictRouteSummary();
                if (routeSummary != null && !routeSummary.isEmpty()) {
                    java.util.Map<String, Integer> summary = new java.util.LinkedHashMap<>();
                    summary.put("auto", routeSummary.getOrDefault("auto", 0));
                    summary.put("review", routeSummary.getOrDefault("review", 0));
                    summary.put("deferred", routeSummary.getOrDefault("defer", 0));
                    output.put("conflictRouteSummary", summary);
                }
            }
            WriterQualityVerifier.VerificationReport report = context.getVerificationReport();
            if (report != null) {
                output.put("qualityCritical", report.criticalCount());
                output.put("qualityWarnings", report.warningCount());
            }
            if (!context.getSchemaGapHints().isEmpty()) {
                output.put("schemaGaps", context.getSchemaGapHints());
            }
            return MAPPER.writeValueAsString(output);
        } catch (Exception e) {
            log.warn("buildCompletionOutput failed: {}", e.getMessage());
            return "{\"status\":\"completed\"}";
        }
    }

    private ExecutionStepModel createAndRunStep(Long executionId, IngestStep step, Long scopeId) {
        return createAndRunStep(executionId, step, scopeId, 0);
    }

    private ExecutionStepModel createAndRunStep(Long executionId, IngestStep step, Long scopeId, int order) {
        ApprovalLevel level = approvalService.getApprovalLevel(step.name(), "ingest");
        int stepOrder = order > 0 ? order : step.ordinal() + 1;
        ExecutionStepModel stepModel = executionTracker.createStep(executionId, step.name(), stepOrder, level.name());
        executionTracker.updateStepStatus(stepModel.getId(), "running");
        executionEventLog.append(String.valueOf(executionId), ExecutionEventTypes.STEP_START,
            java.util.Map.of("step", step.name(), "scopeId", scopeId == null ? "" : scopeId));
        return stepModel;
    }

    private ExecutionStepModel findOrCreateStep(Long executionId, IngestStep step, Long scopeId, List<ExecutionStepModel> existingSteps, int order) {
        for (ExecutionStepModel existing : existingSteps) {
            if (step.name().equals(existing.getStepName())) {
                executionTracker.resetStepForRetry(existing.getId());
                executionTracker.updateStepStatus(existing.getId(), "running");
                return existing;
            }
        }
        return createAndRunStep(executionId, step, scopeId, order);
    }

    private void completeStep(ExecutionStepModel step, Long scopeId, String output, int tokens, long durationMs) {
        executionTracker.completeStep(step.getId(), output, tokens, (int) Math.min(durationMs, Integer.MAX_VALUE));
        if (baselineService != null) {
            baselineService.recordSample(scopeId, "unknown", step.getStepName(), durationMs);
        }
        executionEventLog.append(String.valueOf(step.getExecutionId()), ExecutionEventTypes.STEP_END,
            java.util.Map.of("step", step.getStepName(),
                "tokens", tokens,
                "durationMs", durationMs));
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

    private boolean isStoppedOrPaused(Long executionId) {
        ExecutionModel current = executionTracker.getExecution(executionId);
        return current != null && ("cancelled".equals(current.getStatus()) || "paused".equals(current.getStatus()));
    }

    private void checkStopped(Long executionId) {
        if (isStoppedOrPaused(executionId) || Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("Execution stopped or paused: executionId=" + executionId);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private void applyTargetTitleToContext(IngestContext context) {
        String guidance = context.getGuidance();
        if (guidance == null || guidance.isBlank()) return;

        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("目标页面标题:\\s*(.+?)(?:\\n|$)")
            .matcher(guidance);
        if (!matcher.find()) return;

        String targetTitle = matcher.group(1).trim();
        if (targetTitle.isEmpty()) return;

        context.setTargetTitle(targetTitle);
        String metadataJson = context.getMetadataJson();
        if (metadataJson == null || metadataJson.isBlank()) return;

        try {
            ObjectNode node = (ObjectNode) MAPPER.readTree(metadataJson);
            node.put("title", targetTitle);
            context.setMetadataJson(MAPPER.writeValueAsString(node));
            log.info("Merge: overrode LLM-generated title with user targetTitle='{}'", targetTitle);
        } catch (Exception e) {
            log.warn("Merge: failed to override metadataJson title with '{}': {}", targetTitle, e.getMessage());
        }
    }
}
