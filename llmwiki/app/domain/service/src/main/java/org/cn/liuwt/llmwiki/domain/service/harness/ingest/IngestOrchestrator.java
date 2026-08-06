package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.GlobalSummaryService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.harness.baseline.ExecutionBaselineService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.ApprovalService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.ApprovalService.ApprovalLevel;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.RateLimitService;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    public ExecutionModel runIngestPipelineWithExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return runIngestPipelineWithExecution(executionId, scopeId, sourceId, guidance, false);
    }

    public ExecutionModel runIngestPipelineWithExecution(Long executionId, Long scopeId, Long sourceId, String guidance, boolean suppressNotifications) {
        TokenUsageContext.set(scopeId, "ingest");
        try {
            return doRunIngestPipeline(executionId, scopeId, sourceId, guidance, suppressNotifications);
        } finally {
            TokenUsageContext.clear();
        }
    }

    private ExecutionModel doRunIngestPipeline(Long executionId, Long scopeId, Long sourceId, String guidance, boolean suppressNotifications) {
        if (!rateLimitService.tryAcquireConcurrent(scopeId)) {
            throw new RuntimeException("并发执行数量已达上限，请等待当前任务完成后再试。scopeId=" + scopeId);
        }
        if (!rateLimitService.checkCallRate(scopeId)) {
            rateLimitService.releaseConcurrent(scopeId);
            throw new RuntimeException("AI 调用频率过高，请稍后再试。scopeId=" + scopeId);
        }

        executionTracker.updateExecutionStatus(executionId, "running");

        SourceDO sourceDO = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getId, sourceId)
                .eq(SourceDO::getScopeId, scopeId)
        );
        if (sourceDO == null) {
            rateLimitService.releaseConcurrent(scopeId);
            throw new RuntimeException("Source not found: id=" + sourceId + ", scopeId=" + scopeId);
        }

        String sourceName = sourceDO.getName() != null ? sourceDO.getName() : "未知文件";
        if (!suppressNotifications) {
            notificationService.createNotification(scopeId, "ingest_started",
                "正在处理 — " + sourceName,
                "AI 正在分析文档内容，完成后可查看生成的知识页面",
                scopeId, null, executionId);
        }

        IngestContext context = new IngestContext(scopeId, sourceId, executionId, guidance);
        context.setSuppressNotifications(suppressNotifications);
        int totalTokens = 0;

        try {
            ExecutionStepModel uploadStep = createAndRunStep(executionId, IngestStep.UPLOAD, scopeId);
            long uploadStart = System.currentTimeMillis();
            int uploadTokens = uploadAgent.process(context);
            completeStep(uploadStep, scopeId, context.toParseOutputJson(sourceDO), estimateTokens(context.getSourceContent()) + uploadTokens, System.currentTimeMillis() - uploadStart);
            totalTokens += uploadTokens;

            ExecutionStepModel analyzeStep = createAndRunStep(executionId, IngestStep.ANALYZE, scopeId);
            long analyzeStart = System.currentTimeMillis();
            int analyzeTokens = analysisAgent.process(context, executionId, analyzeStep.getId());
            applyTargetTitleToContext(context);
            completeStep(analyzeStep, scopeId, buildAnalysisOutput(context), analyzeTokens, System.currentTimeMillis() - analyzeStart);
            totalTokens += analyzeTokens;

            ExecutionStepModel writeStep = createAndRunStep(executionId, IngestStep.WRITE, scopeId);
            long writeStart = System.currentTimeMillis();
            int writeTokens = writingAgent.process(context);
            completeStep(writeStep, scopeId, context.toWriteOutputJson(), writeTokens, System.currentTimeMillis() - writeStart);
            totalTokens += writeTokens;

            ExecutionStepModel completeStep = createAndRunStep(executionId, IngestStep.COMPLETE, scopeId);
            long completeStart = System.currentTimeMillis();
            int completeTokens = completionAgent.process(context);
            completeStep(completeStep, scopeId, buildCompletionOutput(context), completeTokens, System.currentTimeMillis() - completeStart);
            totalTokens += completeTokens;

        } catch (Exception e) {
            boolean isStopped = isStoppedOrPaused(executionId) || Thread.currentThread().isInterrupted();
            if (isStopped) {
                log.warn("Ingest pipeline stopped/paused: executionId={}, scopeId={}, sourceId={}", executionId, scopeId, sourceId);
                rateLimitService.releaseConcurrent(scopeId);
                throw e;
            }
            log.error("Ingest pipeline failed: executionId={}, scopeId={}, sourceId={}", executionId, scopeId, sourceId, e);
            executionTracker.failExecution(executionId, e.getMessage());
            rateLimitService.releaseConcurrent(scopeId);
            if (!suppressNotifications) {
                notificationService.createNotification(scopeId, "ingest_failed",
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
        }
        rateLimitService.releaseConcurrent(scopeId);

        int conflictCount = context.getConflictAnnotations() != null ? context.getConflictAnnotations().size() : 0;
        String completeContent;
        if (conflictCount > 0) {
            completeContent = "处理完成，发现 " + conflictCount + " 处知识矛盾，建议查看";
        } else {
            completeContent = "文档已成功处理，知识页面已更新";
        }
        if (!suppressNotifications) {
            notificationService.createNotification(scopeId, "ingest_completed",
                "处理完成 — " + sourceName,
                completeContent,
                scopeId, null, executionId);
        }

        return executionTracker.getExecution(executionId);
    }

    public ExecutionModel runIngestAnalysisWithExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return runIngestPipelineWithExecution(executionId, scopeId, sourceId, guidance);
    }

    public ExecutionModel runIngestExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return runIngestPipelineWithExecution(executionId, scopeId, sourceId, guidance);
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
        if (!rateLimitService.checkCallRate(scopeId)) {
            rateLimitService.releaseConcurrent(scopeId);
            throw new RuntimeException("AI 调用频率过高");
        }

        ExecutionModel execution = executionTracker.getExecution(executionId);
        if (execution == null || (!"failed".equals(execution.getStatus()) && !"paused".equals(execution.getStatus()))) {
            rateLimitService.releaseConcurrent(scopeId);
            throw new RuntimeException("该执行不在失败或暂停状态，无法续传");
        }

        SourceDO sourceDO = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getId, sourceId)
                .eq(SourceDO::getScopeId, scopeId)
        );
        if (sourceDO == null) {
            rateLimitService.releaseConcurrent(scopeId);
            throw new RuntimeException("Source not found: id=" + sourceId + ", scopeId=" + scopeId);
        }

        List<ExecutionStepModel> steps = executionTracker.listSteps(executionId);
        ExecutionStepModel resumeStep = null;
        for (ExecutionStepModel step : steps) {
            String normalized = IngestStep.normalizeStepName(step.getStepName());
            if ("UPLOAD".equals(normalized) || "ANALYZE".equals(normalized)) {
                if (!"completed".equals(step.getStatus()) && !"paused".equals(step.getStatus())) {
                    resumeStep = step;
                    break;
                }
            }
        }

        if (resumeStep == null) {
            rateLimitService.releaseConcurrent(scopeId);
            throw new RuntimeException("Phase1 所有步骤已完成，无法续传分析阶段");
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

            steps = executionTracker.listSteps(executionId);
            ExecutionStepModel writeStep = findOrCreateStep(executionId, IngestStep.WRITE, scopeId, steps, steps.size() + 1);
            long writeStart = System.currentTimeMillis();
            int writeTokens = writingAgent.process(context);
            completeStep(writeStep, scopeId, context.toWriteOutputJson(), writeTokens, System.currentTimeMillis() - writeStart);
            totalTokens += writeTokens;

            steps = executionTracker.listSteps(executionId);
            ExecutionStepModel completeStepObj = findOrCreateStep(executionId, IngestStep.COMPLETE, scopeId, steps, steps.size() + 1);
            long completeStart = System.currentTimeMillis();
            int completeTokens = completionAgent.process(context);
            completeStep(completeStepObj, scopeId, buildCompletionOutput(context), completeTokens, System.currentTimeMillis() - completeStart);
            totalTokens += completeTokens;

        } catch (Exception e) {
            boolean isStopped = isStoppedOrPaused(executionId) || Thread.currentThread().isInterrupted();
            if (isStopped) {
                log.warn("Phase1 resume stopped/paused: executionId={}", executionId);
                rateLimitService.releaseConcurrent(scopeId);
                throw e;
            }
            log.error("Phase1 resume failed: executionId={}", executionId, e);
            executionTracker.failExecution(executionId, e.getMessage());
            rateLimitService.releaseConcurrent(scopeId);
            throw e;
        }

        rateLimitService.releaseConcurrent(scopeId);

        ExecutionModel exec = executionTracker.getExecution(executionId);
        if (!"failed".equals(exec.getStatus()) && !"cancelled".equals(exec.getStatus())) {
            executionTracker.completeExecution(executionId, totalTokens);
        }
        return executionTracker.getExecution(executionId);
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

        ExecutionModel execution = executionTracker.getExecution(executionId);
        if (execution == null || (!"failed".equals(execution.getStatus()) && !"paused".equals(execution.getStatus()))) {
            rateLimitService.releaseConcurrent(scopeId);
            throw new RuntimeException("该执行不在失败或暂停状态，无法续传");
        }

        List<ExecutionStepModel> steps = executionTracker.listSteps(executionId);
        for (ExecutionStepModel step : steps) {
            String normalized = IngestStep.normalizeStepName(step.getStepName());
            if ("UPLOAD".equals(normalized) || "ANALYZE".equals(normalized)) {
                if (!"completed".equals(step.getStatus()) && !"paused".equals(step.getStatus())) {
                    rateLimitService.releaseConcurrent(scopeId);
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
            rateLimitService.releaseConcurrent(scopeId);
            throw new RuntimeException("Source not found: id=" + sourceId + ", scopeId=" + scopeId);
        }

        ExecutionStepModel resumeStep = null;
        for (ExecutionStepModel step : steps) {
            String normalized = IngestStep.normalizeStepName(step.getStepName());
            if ("WRITE".equals(normalized) || "COMPLETE".equals(normalized)) {
                if (!"completed".equals(step.getStatus()) && !"paused".equals(step.getStatus())) {
                    resumeStep = step;
                    break;
                }
            }
        }

        if (resumeStep == null) {
            rateLimitService.releaseConcurrent(scopeId);
            throw new RuntimeException("Phase2 所有步骤已完成，无法续传执行阶段");
        }

        log.info("Resuming Phase2 from step {} for executionId={}", resumeStep.getStepName(), executionId);

        IngestContext context = IngestContext.reconstructFromSteps(scopeId, sourceId, executionId, guidance, steps, storageProvider, sourceDO);
        applyTargetTitleToContext(context);

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

            if ("WRITE".equals(resumeNormalized)) {
                executionTracker.updateStepStatus(resumeStep.getId(), "running");
                long writeStart = System.currentTimeMillis();
                int writeTokens = writingAgent.process(context);
                completeStep(resumeStep, scopeId, context.toWriteOutputJson(), writeTokens, System.currentTimeMillis() - writeStart);
                totalTokens += writeTokens;

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
                rateLimitService.releaseConcurrent(scopeId);
                throw e;
            }
            log.error("Phase2 resume failed: executionId={}", executionId, e);
            executionTracker.failExecution(executionId, e.getMessage());
            rateLimitService.releaseConcurrent(scopeId);
            throw e;
        }

        ExecutionModel exec = executionTracker.getExecution(executionId);
        if (!"failed".equals(exec.getStatus()) && !"cancelled".equals(exec.getStatus()) && !"paused".equals(exec.getStatus())) {
            executionTracker.completeExecution(executionId, totalTokens);
        }
        rateLimitService.releaseConcurrent(scopeId);
        return executionTracker.getExecution(executionId);
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
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    output.put("metadata", mapper.readTree(metadata));
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
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(output);
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
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(output);
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
