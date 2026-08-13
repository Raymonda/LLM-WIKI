package org.cn.liuwt.llmwiki.web.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.HarnessEngine;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionStatusEvent;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.StepStatusEvent;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.StepProgressEvent;
import org.cn.liuwt.llmwiki.facade.model.ExecutionInfo;
import org.cn.liuwt.llmwiki.facade.model.HealthOverview;
import org.cn.liuwt.llmwiki.facade.model.LintFindingInfo;
import org.cn.liuwt.llmwiki.facade.model.PageResult;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.harness.mq.MqHealthService;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.cn.liuwt.llmwiki.service.lint.LintService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/lint")
public class LintController {

    private static final Logger log = LoggerFactory.getLogger(LintController.class);

    @Autowired
    private LintService lintService;

    @Autowired
    private HarnessEngine harnessEngine;

    @Autowired
    private LintFindingService lintFindingService;

    @Autowired
    private ExecutionNodeRegistry registry;

    @Autowired
    private ExecutionMapper executionMapper;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired(required = false)
    private org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    @Autowired
    private MqHealthService mqHealthService;

    @Value("${llmwiki.rocketmq.enabled:false}")
    private boolean mqEnabled;

    private boolean isMqAvailable() {
        return rocketMQTemplate != null && mqEnabled;
    }

    @GetMapping("/active-execution")
    public Result<ExecutionInfo> getActiveExecution(@RequestParam Long scopeId) {
        ExecutionDO activeDO = lintService.getActiveLintExecution(scopeId);
        if (activeDO == null) {
            return Result.success(null);
        }
        ExecutionModel execution = harnessEngine.getExecution(activeDO.getId());
        if (execution == null) {
            return Result.success(null);
        }
        return Result.success(toExecutionInfo(execution));
    }

    @PostMapping("/start")
    public Result<ExecutionInfo> startLint(@RequestParam Long scopeId, @RequestParam(defaultValue = "true") boolean fullScan) {
        ExecutionModel execution = lintService.createLintExecution(scopeId);
        setNodeOwnership(execution.getId());
        ExecutionInfo info = toExecutionInfo(execution);

        if (isMqAvailable() && mqHealthService.shouldAttempt()) {
            try {
                sendPipelineTask(execution.getId(), scopeId, fullScan);
                mqHealthService.markSendSuccess();
            } catch (Exception e) {
                mqHealthService.markSendFailed();
                log.error("Failed to send lint pipeline task to RocketMQ, executionId={}, falling back to local execution", execution.getId(), e);
                submitLocalTask(execution.getId(), () -> runLintPipelineLocally(execution.getId(), scopeId, fullScan));
            }
        } else {
            submitLocalTask(execution.getId(), () -> runLintPipelineLocally(execution.getId(), scopeId, fullScan));
        }

        return Result.success(info);
    }

    private void runLintPipelineLocally(Long executionId, Long scopeId, boolean fullScan) {
        try {
            harnessEngine.executeLintWithExecution(executionId, scopeId, fullScan);
        } catch (Exception e) {
            log.error("Lint pipeline failed for executionId={}", executionId, e);
            failExecutionIfNotStarted(executionId, e.getMessage());
        }
    }

    private void failExecutionIfNotStarted(Long executionId, String message) {
        ExecutionModel current = harnessEngine.getExecution(executionId);
        if (current != null && (current.getSteps() == null || current.getSteps().isEmpty())) {
            executionTracker.failExecution(executionId, message);
        } else {
            log.warn("Execution {} already has steps, skip failExecution to avoid clobbering an active run", executionId);
        }
    }

    @GetMapping("/{id}/progress")
    public Result<ExecutionInfo> getProgress(@PathVariable Long id) {
        ExecutionModel execution = harnessEngine.getExecution(id);
        if (execution == null) {
            return Result.success(new ExecutionInfo());
        }
        return Result.success(toExecutionInfo(execution));
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@PathVariable Long id) {
        SseEmitter emitter = registry.createEmitter(id);

        ExecutionModel current = harnessEngine.getExecution(id);
        if (current != null) {
            try {
                emitter.send(SseEmitter.event()
                    .name("init")
                    .data(toExecutionInfo(current)));

                if (current.getSteps() != null && !current.getSteps().isEmpty()) {
                    for (ExecutionModel.ExecutionStepModel step : current.getSteps()) {
                        Map<String, Object> stepData = Map.of(
                            "stepId", step.getId(),
                            "stepName", step.getStepName(),
                            "status", step.getStatus(),
                            "outputData", step.getOutputData() != null ? step.getOutputData() : ""
                        );
                        emitter.send(SseEmitter.event()
                            .name("step")
                            .data(stepData));
                    }
                }

                String status = current.getStatus();
                if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
                    emitter.send(SseEmitter.event()
                        .name("done")
                        .data(toExecutionInfo(current)));
                    emitter.complete();
                    registry.removeEmitter(id);
                    return emitter;
                }
            } catch (Exception e) {
                log.error("Failed to send init event for lint SSE", e);
            }
        }

        startPeriodicSync(id, emitter);
        return emitter;
    }

    private void startPeriodicSync(Long id, SseEmitter emitter) {
        final boolean[] closed = {false};
        emitter.onCompletion(() -> { closed[0] = true; registry.cancelSyncTimer(id); });
        emitter.onTimeout(() -> { closed[0] = true; registry.cancelSyncTimer(id); });
        emitter.onError(e -> { closed[0] = true; registry.cancelSyncTimer(id); });

        ScheduledFuture<?> timer = registry.getScheduler().scheduleWithFixedDelay(() -> {
            if (closed[0]) return;
            try {
                ExecutionModel exec = harnessEngine.getExecution(id);
                if (exec == null) {
                    return;
                }

                if (exec.getSteps() != null && !exec.getSteps().isEmpty()) {
                    for (ExecutionModel.ExecutionStepModel step : exec.getSteps()) {
                        Map<String, Object> stepData = new HashMap<>();
                        stepData.put("stepId", step.getId());
                        stepData.put("stepName", step.getStepName());
                        stepData.put("status", step.getStatus());
                        stepData.put("outputData", step.getOutputData() != null ? step.getOutputData() : "");
                        emitter.send(SseEmitter.event().name("step").data(stepData));
                    }
                }

                String status = exec.getStatus();
                if ("completed".equals(status) || "failed".equals(status)
                        || "cancelled".equals(status)) {
                    emitter.send(SseEmitter.event().name("done").data(toExecutionInfo(exec)));
                    emitter.complete();
                    registry.removeEmitter(id);
                    registry.cancelSyncTimer(id);
                    closed[0] = true;
                }
            } catch (Exception e) {
                log.warn("[LintPeriodicSync] SSE sync failed for executionId={}: {}", id, e.getMessage());
                registry.cancelSyncTimer(id);
                closed[0] = true;
            }
        }, 3, 3, TimeUnit.SECONDS);
        registry.putSyncTimer(id, timer);
    }

    @GetMapping("/{id}/report")
    public Result<ExecutionInfo> getReport(@PathVariable Long id) {
        ExecutionModel execution = harnessEngine.getExecution(id);
        return Result.success(toExecutionInfo(execution));
    }

    @GetMapping("/{id}/findings")
    public Result<List<LintFindingInfo>> getFindings(@PathVariable Long id) {
        List<LintFindingDO> findings = lintFindingService.listFindingsByExecution(id);
        List<LintFindingInfo> infos = findings.stream().map(this::toFindingInfo).toList();
        return Result.success(infos);
    }

    @GetMapping("/scope/findings")
    public Result<PageResult<LintFindingInfo>> listScopeFindings(
            @RequestParam Long scopeId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false, defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        IPage<LintFindingDO> paged = lintFindingService.listFindingsPaged(scopeId, type, status, priority, archived, page, size);
        PageResult<LintFindingInfo> pr = new PageResult<>();
        pr.setItems(paged.getRecords().stream().map(this::toFindingInfo).toList());
        pr.setTotal(paged.getTotal());
        pr.setPage(paged.getCurrent());
        pr.setSize(paged.getSize());
        pr.setTotalPages(paged.getPages());
        return Result.success(pr);
    }

    @GetMapping("/scope/health-overview")
    public Result<HealthOverview> getHealthOverview(@RequestParam Long scopeId) {
        HealthOverview overview = new HealthOverview();
        overview.setHealthDistribution(lintFindingService.getHealthDistribution(scopeId));
        overview.setFindingCountsByType(lintFindingService.getFindingCountsByType(scopeId));
        Map<String, Long> rawPriorityCounts = lintFindingService.getHealthSummary(scopeId).entrySet().stream()
            .filter(e -> !"total".equals(e.getKey()))
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> ((Number) e.getValue()).longValue()));
        overview.setFindingCountsByPriority(rawPriorityCounts);
        overview.setTotalActiveFindings(rawPriorityCounts.values().stream().mapToLong(Long::longValue).sum());
        overview.setTotalPages(lintFindingService.getTotalPages(scopeId));
        overview.setLastLintTime(lintFindingService.getLastLintTime(scopeId));
        overview.setTopFindings(lintFindingService.getTopFindings(scopeId, 5).stream().map(this::toFindingInfo).toList());
        return Result.success(overview);
    }

    @PatchMapping("/findings/{id}")
    public Result<Void> updateFindingStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        lintFindingService.updateStatus(id, status);
        return Result.success();
    }

    @GetMapping("/latest-report")
    public Result<Map<String, Object>> getLatestReport(@RequestParam Long scopeId) {
        Map<String, Object> summary = lintFindingService.getHealthSummary(scopeId);
        return Result.success(summary);
    }

    @PostMapping("/{id}/execute-actions")
    public Result<Void> executeActions(@PathVariable Long id) {
        return Result.success();
    }

    @PostMapping("/findings/{id}/auto-resolve")
    public Result<Void> autoResolveFinding(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String handlingMethod = body != null ? body.get("handlingMethod") : null;
        lintFindingService.autoResolve(id, handlingMethod);
        return Result.success();
    }

    @PostMapping("/findings/{id}/resolve-compliance")
    public Result<Void> resolveSchemaCompliance(@PathVariable Long id) {
        lintFindingService.resolveSchemaCompliance(id);
        return Result.success();
    }

    @PostMapping("/scope/{scopeId}/batch-auto-resolve-stale")
    public Result<Map<String, Object>> batchAutoResolveStale(@PathVariable Long scopeId) {
        Map<String, Object> result = lintService.batchRepairStale(scopeId);
        return Result.success(result);
    }

    @PostMapping("/findings/{id}/trigger-repair")
    public Result<Map<String, Object>> triggerRepair(@PathVariable Long id, @RequestParam Long scopeId) {
        Map<String, Object> result = lintService.triggerStaleRepair(scopeId, id);
        return Result.success(result);
    }

    @PostMapping("/findings/{id}/approve")
    public Result<Map<String, Object>> approveFinding(@PathVariable Long id, @RequestParam Long scopeId) {
        Map<String, Object> result = lintService.approveFinding(scopeId, id);
        return Result.success(result);
    }

    @PostMapping("/findings/{id}/execute-ruling")
    public Result<Map<String, Object>> executeConflictRuling(
            @PathVariable Long id, @RequestParam Long scopeId, @RequestBody Map<String, String> body) {
        String action = body.getOrDefault("action", "coexist");
        Map<String, Object> result = lintService.executeConflictRuling(scopeId, id, action);
        return Result.success(result);
    }

    @PostMapping("/findings/{id}/reject")
    public Result<Void> rejectFinding(@PathVariable Long id) {
        lintService.rejectFinding(id);
        return Result.success();
    }

    @PostMapping("/findings/{id}/rollback")
    public Result<Void> rollbackFinding(@PathVariable Long id) {
        lintFindingService.rollbackFinding(id);
        return Result.success();
    }

    @PostMapping("/findings/{id}/retry")
    public Result<Void> retryFailedFinding(@PathVariable Long id) {
        lintFindingService.retryFailedFinding(id);
        return Result.success();
    }

    @PostMapping("/findings/{id}/retry-orphan")
    public Result<Map<String, Object>> retryOrphanFix(@PathVariable Long id, @RequestParam Long scopeId) {
        Map<String, Object> result = lintService.retryOrphanFix(scopeId, id);
        return Result.success(result);
    }

    @PostMapping("/findings/{id}/enrich-page")
    public Result<Map<String, Object>> enrichThinPage(@PathVariable Long id, @RequestParam Long scopeId, @RequestBody Map<String, String> body) {
        String userSupplement = body.getOrDefault("userSupplement", "");
        if (userSupplement.isBlank()) {
            return Result.failed(ErrorCode.LINT_SUPPLEMENT_REQUIRED);
        }
        Map<String, Object> result = lintService.enrichThinPage(scopeId, id, userSupplement);
        return Result.success(result);
    }

    // 批量操作
    @PostMapping("/findings/batch/approve")
    public Result<Map<String, Object>> batchApproveFindings(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.get("ids")).stream()
            .map(Number::longValue)
            .toList();
        Long scopeId = body.get("scopeId") != null ? ((Number) body.get("scopeId")).longValue() : null;
        if (ids == null || ids.isEmpty()) {
            return Result.success(Map.of("processed", 0, "failed", 0));
        }
        int processed = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                lintService.approveFinding(scopeId, id);
                processed++;
            } catch (Exception e) {
                failed++;
            }
        }
        return Result.success(Map.of("processed", processed, "failed", failed));
    }

    @PostMapping("/findings/batch/reject")
    public Result<Map<String, Object>> batchRejectFindings(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.get("ids")).stream()
            .map(Number::longValue)
            .toList();
        if (ids == null || ids.isEmpty()) {
            return Result.success(Map.of("processed", 0, "failed", 0));
        }
        int processed = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                lintService.rejectFinding(id);
                processed++;
            } catch (Exception e) {
                failed++;
            }
        }
        return Result.success(Map.of("processed", processed, "failed", failed));
    }

    @PostMapping("/findings/batch/rollback")
    public Result<Map<String, Object>> batchRollbackFindings(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.get("ids")).stream()
            .map(Number::longValue)
            .toList();
        if (ids == null || ids.isEmpty()) {
            return Result.success(Map.of("processed", 0, "failed", 0));
        }
        int processed = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                lintFindingService.rollbackFinding(id);
                processed++;
            } catch (Exception e) {
                failed++;
            }
        }
        return Result.success(Map.of("processed", processed, "failed", failed));
    }

    @EventListener(condition = "#event.executionType == 'lint'")
    public void onExecutionStatusEvent(ExecutionStatusEvent event) {
        SseEmitter emitter = registry.getEmitter(event.getExecutionId());
        if (emitter == null) return;

        try {
            ExecutionModel execution = harnessEngine.getExecution(event.getExecutionId());
            if (execution == null) return;

            if ("completed".equals(event.getNewStatus())
                    || "failed".equals(event.getNewStatus())
                    || "cancelled".equals(event.getNewStatus())) {
                emitter.send(SseEmitter.event()
                    .name("done")
                    .data(toExecutionInfo(execution)));
                emitter.complete();
                registry.removeEmitter(event.getExecutionId());
            }
        } catch (Exception e) {
            log.error("Failed to send SSE event for lint execution status change", e);
            registry.removeEmitter(event.getExecutionId());
        }
    }

    @EventListener
    public void onStepStatusEvent(StepStatusEvent event) {
        SseEmitter emitter = registry.getEmitter(event.getExecutionId());
        if (emitter == null) return;

        try {
            Map<String, Object> data = Map.of(
                "stepId", event.getStepId(),
                "stepName", event.getStepName(),
                "status", event.getStatus(),
                "outputData", event.getOutputData() != null ? event.getOutputData() : ""
            );
            emitter.send(SseEmitter.event()
                .name("step")
                .data(data));

            if ("completed".equals(event.getStatus()) || "failed".equals(event.getStatus()) || "cancelled".equals(event.getStatus())) {
                ExecutionModel execution = harnessEngine.getExecution(event.getExecutionId());
                if (execution != null) {
                    if ("completed".equals(execution.getStatus()) || "failed".equals(execution.getStatus()) || "cancelled".equals(execution.getStatus())) {
                        emitter.send(SseEmitter.event()
                            .name("done")
                            .data(toExecutionInfo(execution)));
                        emitter.complete();
                        registry.removeEmitter(event.getExecutionId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to send SSE step event for lint", e);
            registry.removeEmitter(event.getExecutionId());
        }
    }

    @EventListener
    public void onStepProgressEvent(StepProgressEvent event) {
        SseEmitter emitter = registry.getEmitter(event.getExecutionId());
        if (emitter == null) return;
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("stepId", event.getStepId());
            data.put("stepName", event.getStepName());
            data.put("current", event.getCurrent());
            data.put("total", event.getTotal());
            data.put("avgMsPerUnit", event.getAvgMsPerUnit());
            if (event.getChunkIndex() != null) {
                data.put("chunkIndex", event.getChunkIndex());
                data.put("chunkPreview", event.getChunkPreview() != null ? event.getChunkPreview() : "");
            }
            emitter.send(SseEmitter.event()
                .name("step_progress")
                .data(data));
        } catch (Exception e) {
            log.error("Failed to send step_progress SSE for lint", e);
            registry.removeEmitter(event.getExecutionId());
        }
    }

    private static final int LINT_TOTAL_STEPS = 9;

    private ExecutionInfo toExecutionInfo(ExecutionModel model) {
        ExecutionInfo info = new ExecutionInfo();
        info.setExecutionId(model.getId());
        info.setOperationType(model.getType());
        info.setStatus(model.getStatus());
        info.setScopeId(model.getScopeId());
        info.setStartTime(model.getStartedAt());
        info.setEndTime(model.getCompletedAt());
        info.setTotalTokens(model.getTotalTokens());

        if ("lint".equals(model.getType())) {
            info.setTotalSteps(LINT_TOTAL_STEPS);
            long completedCount = 0;
            if (model.getSteps() != null) {
                completedCount = model.getSteps().stream()
                    .filter(s -> "completed".equals(s.getStatus()) || "skipped".equals(s.getStatus()))
                    .count();
                ExecutionModel.ExecutionStepModel runningStep = model.getSteps().stream()
                    .filter(s -> "running".equals(s.getStatus()))
                    .findFirst()
                    .orElse(null);
                if (runningStep != null) {
                    info.setCurrentStepName(runningStep.getStepName());
                }
            }
            info.setCompletedSteps((int) completedCount);
        } else if (model.getSteps() != null && !model.getSteps().isEmpty()) {
            info.setTotalSteps(model.getSteps().size());
            long completedCount = model.getSteps().stream()
                .filter(s -> "completed".equals(s.getStatus()))
                .count();
            info.setCompletedSteps((int) completedCount);
            ExecutionModel.ExecutionStepModel runningStep = model.getSteps().stream()
                .filter(s -> "running".equals(s.getStatus()))
                .findFirst()
                .orElse(null);
            if (runningStep != null) {
                info.setCurrentStepName(runningStep.getStepName());
            }
        }

        return info;
    }

    private LintFindingInfo toFindingInfo(LintFindingDO finding) {
        LintFindingInfo info = new LintFindingInfo();
        info.setId(finding.getId());
        info.setScopeId(finding.getScopeId());
        info.setPagePath(finding.getPagePath());
        info.setAssetId(finding.getAssetId());
        info.setFindingType(finding.getFindingType());
        info.setPriority(finding.getPriority());
        info.setTitle(finding.getTitle());
        info.setDetail(finding.getDetail());
        info.setExtra(finding.getExtra());
        info.setRulingBriefJson(finding.getRulingBriefJson());
        info.setHandlingMethod(finding.getHandlingMethod());
        info.setRiskScore(finding.getRiskScore());
        info.setAutoResolvedAt(finding.getAutoResolvedAt());
        info.setRepairExecutionId(finding.getRepairExecutionId());
        info.setStatus(finding.getStatus());
        info.setExecutionId(finding.getExecutionId());
        info.setCreatedAt(finding.getCreatedAt());
        info.setUpdatedAt(finding.getUpdatedAt());
        info.setUserFeedback(finding.getUserFeedback());
        info.setFeedbackCount(finding.getFeedbackCount());
        info.setArchivedAt(finding.getArchivedAt());
        info.setOrphanDiagnosis(finding.getOrphanDiagnosis());
        info.setCrossrefSuggestions(lintFindingService.buildCrossrefSuggestions(finding));
        return info;
    }

    @PostMapping("/findings/{id}/feedback")
    public Result<Void> recordFeedback(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String feedback = body.get("feedback");
        lintFindingService.recordFeedback(id, feedback);
        return Result.success();
    }

    // Phase 2 新增：交叉引用审批 API
    @PostMapping("/findings/{id}/approve-link")
    public Result<Void> approveLink(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            lintFindingService.approveLinkByIds(id);
            return Result.success();
        } catch (Exception e) {
            return Result.failed(ErrorCode.LINT_APPROVE_LINK_FAILED, e.getMessage());
        }
    }

    @PostMapping("/findings/{id}/reject-link")
    public Result<Void> rejectLink(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String sourceTitle = body.get("sourceTitle");
        String targetTitle = body.get("targetTitle");
        String reason = body.get("reason");
        if (sourceTitle == null || targetTitle == null) {
            return Result.failed(ErrorCode.LINT_MISSING_PARAMS);
        }
        lintFindingService.rejectLink(id, sourceTitle, targetTitle, reason);
        return Result.success();
    }

    @PostMapping("/findings/batch/approve-links")
    public Result<Map<String, Object>> batchApproveLinks(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.get("ids")).stream()
            .map(Number::longValue)
            .toList();
        if (ids == null || ids.isEmpty()) {
            return Result.success(Map.of("processed", 0, "failed", 0));
        }
        int processed = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                lintFindingService.approveLinkByIds(id);
                processed++;
            } catch (Exception e) {
                log.warn("batchApproveLinks failed for id={}: {}", id, e.getMessage());
                failed++;
            }
        }
        return Result.success(Map.of("processed", processed, "failed", failed));
    }

    @PostMapping("/findings/batch/reject-links")
    public Result<Map<String, Object>> batchRejectLinks(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.get("ids")).stream()
            .map(Number::longValue)
            .toList();
        if (ids == null || ids.isEmpty()) {
            return Result.success(Map.of("processed", 0, "failed", 0));
        }
        int processed = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                lintFindingService.dismissFinding(id);
                processed++;
            } catch (Exception e) {
                failed++;
            }
        }
        return Result.success(Map.of("processed", processed, "failed", failed));
    }

    private void submitLocalTask(Long executionId, Runnable task) {
        Future<?> future = registry.submitTask(() -> {
            try {
                task.run();
            } finally {
                registry.removeFuture(executionId);
            }
        });
        registry.putFuture(executionId, future);
    }

    private void sendPipelineTask(Long executionId, Long scopeId, boolean fullScan) {
        PipelineTaskMessage msg = new PipelineTaskMessage();
        msg.setExecutionId(executionId);
        msg.setScopeId(scopeId);
        msg.setFullScan(fullScan);
        msg.setTaskType(PipelineTaskMessage.TYPE_LINT_START);
        msg.setNodeId(registry.getNodeId());
        msg.setSubmittedAt(System.currentTimeMillis());
        try {
            rocketMQTemplate.convertAndSend(PipelineTaskMessage.TOPIC, msg);
        } catch (Exception e) {
            log.error("Failed to send lint pipeline task to RocketMQ, executionId={}", executionId, e);
            throw new RuntimeException("Failed to submit lint pipeline task", e);
        }
    }

    private void setNodeOwnership(Long executionId) {
        try {
            ExecutionDO update = new ExecutionDO();
            update.setId(executionId);
            update.setNodeId(registry.getNodeId());
            executionMapper.updateById(update);
        } catch (Exception e) {
            log.warn("Failed to set node ownership for executionId={}", executionId, e);
        }
    }
}