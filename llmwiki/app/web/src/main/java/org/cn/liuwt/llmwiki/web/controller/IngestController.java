package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionStatusEvent;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.StepStatusEvent;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.StepProgressEvent;
import org.cn.liuwt.llmwiki.facade.model.IngestRequest;
import org.cn.liuwt.llmwiki.facade.model.ExecutionInfo;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.domain.service.harness.baseline.ExecutionBaselineService;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestStep;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.service.harness.mq.ControlMessage;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.harness.mq.MqHealthService;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.cn.liuwt.llmwiki.service.ingest.IngestOrchestrationService;
import org.cn.liuwt.llmwiki.service.ingest.IngestService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    @Autowired
    private IngestService ingestService;

    @Autowired
    private SourceService sourceService;

    @Autowired(required = false)
    private ExecutionBaselineService baselineService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private ExecutionMapper executionMapper;

    @Autowired
    private ExecutionNodeRegistry registry;

    @Autowired(required = false)
    private org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    @Autowired
    private MqHealthService mqHealthService;

    @Autowired
    private IngestOrchestrationService ingestOrchestrationService;

    @Autowired
    private ScopeService scopeService;

    @Value("${llmwiki.rocketmq.enabled:false}")
    private boolean mqEnabled;

    private boolean isMqAvailable() {
        return rocketMQTemplate != null && mqEnabled;
    }

    private void assertExecutionReadable(ExecutionModel execution) {
        if (execution == null || execution.getScopeId() == null) {
            throw new BusinessException(ErrorCode.INGEST_EXECUTION_NOT_FOUND);
        }
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (userId == null || !scopeService.canView(execution.getScopeId(), userId)) {
            throw new BusinessException(ErrorCode.AUTH_ACCESS_DENIED);
        }
    }

    /**
     * 解析本次写入的目标 scope。以认证上下文（已经成员校验）的 scope 为准；
     * 请求体显式传入的 scopeId 只有在与认证 scope 一致、或调用者确为其成员时才接受，
     * 否则拒绝，防止跨 scope 越权写入。
     */
    private Long resolveScopeId(Long requestedScopeId) {
        Long authScopeId = jwtTokenProvider.getCurrentScopeId();
        if (requestedScopeId == null || requestedScopeId.equals(authScopeId)) {
            return authScopeId;
        }
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (userId != null && scopeService.canView(requestedScopeId, userId)) {
            return requestedScopeId;
        }
        throw new BusinessException(ErrorCode.AUTH_ACCESS_DENIED);
    }

    @PostMapping("/start")
    public Result<ExecutionInfo> startIngest(@RequestBody IngestRequest request) {
        // scope 以认证上下文为准（API key 客户端不传 scopeId，JWT 前端传的 scopeId
        // 必须经成员校验），防止请求体 scopeId 被伪造以跨 scope 写入。
        Long scopeId = resolveScopeId(request.getScopeId());
        SourceModel source = sourceService.getSource(request.getSourceId(), scopeId);
        if (source == null) {
            return Result.failed(ErrorCode.INGEST_SOURCE_NOT_FOUND);
        }
        logDuplicateWarning(source, scopeId);
        try {
            ExecutionModel execution = ingestOrchestrationService.startIngest(scopeId, request.getSourceId(), request.getGuidance());
            return Result.success(toExecutionInfo(execution));
        } catch (IllegalArgumentException e) {
            return Result.failed(ErrorCode.INGEST_SOURCE_NOT_FOUND);
        }
    }

    @PostMapping("/analyze")
    public Result<ExecutionInfo> startAnalysis(@RequestBody IngestRequest request) {
        Long scopeId = resolveScopeId(request.getScopeId());
        SourceModel source = sourceService.getSource(request.getSourceId(), scopeId);
        if (source == null) {
            return Result.failed(ErrorCode.INGEST_SOURCE_NOT_FOUND);
        }
        logDuplicateWarning(source, scopeId);

        ExecutionModel execution = ingestService.createExecution(scopeId, request.getSourceId());
        setNodeOwnership(execution.getId());
        ExecutionInfo info = toExecutionInfo(execution);
        if (baselineService != null) {
            info.setBaselineProfile(baselineService.getProfile(scopeId, source.getFormat()));
        }
        String guidance = request.getGuidance();

        dispatchToMqOrLocal(execution.getId(), scopeId, request.getSourceId(), guidance,
                PipelineTaskMessage.TYPE_INGEST_ANALYZE,
                () -> {
                    submitLocalTask(execution.getId(), () -> {
                        try {
                            ingestService.runIngestAnalysis(execution.getId(), scopeId, request.getSourceId(), guidance);
                        } catch (Exception e) {
                            log.error("Ingest analysis failed for executionId={}", execution.getId(), e);
                            ExecutionModel current = ingestService.getProgress(execution.getId());
                            if (current == null || !"cancelled".equals(current.getStatus())) {
                                ingestService.failExecution(execution.getId(), e.getMessage());
                            }
                        }
                    });
                });
        return Result.success(info);
    }

    @PostMapping("/{id}/execute")
    public Result<Void> executeIngest(@PathVariable Long id, @RequestBody(required = false) IngestRequest request) {
        ExecutionModel execution = ingestService.getProgress(id);
        if (execution == null) {
            return Result.failed(ErrorCode.INGEST_EXECUTION_NOT_FOUND);
        }
        assertExecutionReadable(execution);
        if (!"awaiting_confirmation".equals(execution.getStatus()) && !"awaiting_review".equals(execution.getStatus())) {
            return Result.failed(ErrorCode.INGEST_INVALID_STATUS_REVIEW);
        }
        String guidance = request != null ? request.getGuidance() : null;
        setNodeOwnership(id);

        dispatchToMqOrLocal(id, execution.getScopeId(), execution.getSourceId(), guidance,
                PipelineTaskMessage.TYPE_INGEST_EXECUTE,
                () -> submitLocalTask(id, () -> {
                    try {
                        ingestService.runIngestExecution(id, execution.getScopeId(), execution.getSourceId(), guidance);
                    } catch (Exception e) {
                        log.error("Ingest execution failed for executionId={}", id, e);
                        ExecutionModel current = ingestService.getProgress(id);
                        if (current == null || !"cancelled".equals(current.getStatus())) {
                            ingestService.failExecution(id, e.getMessage());
                        }
                    }
                }));
        return Result.success(null);
    }

    @PostMapping("/{id}/reanalyze")
    public Result<Void> reanalyzeIngest(@PathVariable Long id, @RequestBody(required = false) IngestRequest request) {
        ExecutionModel execution = ingestService.getProgress(id);
        if (execution == null) {
            return Result.failed(ErrorCode.INGEST_EXECUTION_NOT_FOUND);
        }
        assertExecutionReadable(execution);
        if (!"awaiting_confirmation".equals(execution.getStatus()) && !"awaiting_review".equals(execution.getStatus())) {
            return Result.failed(ErrorCode.INGEST_INVALID_STATUS_REVIEW);
        }
        String guidance = request != null ? request.getGuidance() : null;
        setNodeOwnership(id);

        dispatchToMqOrLocal(id, execution.getScopeId(), execution.getSourceId(), guidance,
                PipelineTaskMessage.TYPE_INGEST_REANALYZE,
                () -> submitLocalTask(id, () -> {
                    try {
                        ingestService.reanalyzeIngest(id, execution.getScopeId(), execution.getSourceId(), guidance);
                    } catch (Exception e) {
                        log.error("Ingest re-analysis failed for executionId={}", id, e);
                        ExecutionModel current = ingestService.getProgress(id);
                        if (current == null || !"cancelled".equals(current.getStatus())) {
                            ingestService.failExecution(id, e.getMessage());
                        }
                    }
                }));
        return Result.success(null);
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@PathVariable Long id) {
        ExecutionModel current = ingestService.getProgress(id);
        assertExecutionReadable(current);
        SseEmitter emitter = registry.createEmitter(id);

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
                if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status) || "budget_exhausted".equals(status)) {
                    emitter.send(SseEmitter.event()
                        .name("done")
                        .data(toExecutionInfo(current)));
                    emitter.complete();
                    registry.removeEmitter(id);
                    return emitter;
                } else if ("awaiting_confirmation".equals(status)) {
                    emitter.send(SseEmitter.event()
                        .name("phase1_done")
                        .data(toExecutionInfo(current)));
                } else if ("paused".equals(status)) {
                    emitter.send(SseEmitter.event()
                        .name("pause")
                        .data(toExecutionInfo(current)));
                }
            } catch (Exception e) {
                log.error("Failed to send init event", e);
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
                ExecutionModel exec = ingestService.getProgress(id);
                if (exec == null) {
                    log.debug("Periodic sync: executionId={} not found", id);
                    return;
                }

                if (exec.getSteps() != null && !exec.getSteps().isEmpty()) {
                    log.debug("Periodic sync: executionId={} status={} steps={}", id, exec.getStatus(),
                            exec.getSteps().stream().map(s -> s.getStepName() + ":" + s.getStatus()).collect(java.util.stream.Collectors.joining(",")));
                    for (ExecutionModel.ExecutionStepModel step : exec.getSteps()) {
                        Map<String, Object> stepData = new HashMap<>();
                        stepData.put("stepId", step.getId());
                        stepData.put("stepName", step.getStepName());
                        stepData.put("status", step.getStatus());
                        stepData.put("outputData", step.getOutputData() != null ? step.getOutputData() : "");
                        emitter.send(SseEmitter.event().name("step").data(stepData));
                    }
                } else {
                    log.debug("Periodic sync: executionId={} status={} no steps yet", id, exec.getStatus());
                }

                String status = exec.getStatus();
                if ("completed".equals(status) || "failed".equals(status)
                        || "cancelled".equals(status) || "budget_exhausted".equals(status)) {
                    emitter.send(SseEmitter.event().name("done").data(toExecutionInfo(exec)));
                    emitter.complete();
                    registry.removeEmitter(id);
                    registry.cancelSyncTimer(id);
                    closed[0] = true;
                } else if ("paused".equals(status)) {
                    emitter.send(SseEmitter.event().name("pause").data(toExecutionInfo(exec)));
                }
            } catch (Exception e) {
                log.warn("Periodic SSE sync failed for executionId={}: {}", id, e.getMessage());
                registry.cancelSyncTimer(id);
                closed[0] = true;
            }
        }, 3, 3, TimeUnit.SECONDS);
        registry.putSyncTimer(id, timer);
    }

    @GetMapping("/{id}/progress")
    public Result<ExecutionInfo> getProgress(@PathVariable Long id) {
        ExecutionModel execution = ingestService.getProgress(id);
        assertExecutionReadable(execution);
        return Result.success(toExecutionInfo(execution));
    }

    @GetMapping("/{id}/result")
    public Result<ExecutionInfo> getResult(@PathVariable Long id) {
        ExecutionModel execution = ingestService.getResult(id);
        assertExecutionReadable(execution);
        return Result.success(toExecutionInfo(execution));
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancelIngest(@PathVariable Long id) {
        ExecutionModel execution = ingestService.getProgress(id);
        if (execution == null) {
            return Result.failed(ErrorCode.INGEST_EXECUTION_NOT_FOUND);
        }
        assertExecutionReadable(execution);
        String currentStatus = execution.getStatus();
        if ("completed".equals(currentStatus) || "failed".equals(currentStatus)
                || "cancelled".equals(currentStatus) || "budget_exhausted".equals(currentStatus)) {
            return Result.failed(ErrorCode.INGEST_ALREADY_FINISHED_CANCEL);
        }

        ingestService.markExecutionCancelled(id);

        if (isMqAvailable() && mqHealthService.shouldAttempt()) {
            try {
                sendControlMessage(id, ControlMessage.ACTION_CANCEL, "用户手动取消");
                mqHealthService.markSendSuccess();
            } catch (Exception e) {
                mqHealthService.markSendFailed();
                log.error("Failed to send cancel control message, cancelling locally, executionId={}", id, e);
                cancelIngestLocally(id);
            }
        } else {
            cancelIngestLocally(id);
        }

        ingestService.cleanupCancelledExecution(id, execution.getScopeId());

        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteIngest(@PathVariable Long id) {
        ExecutionModel execution = ingestService.getProgress(id);
        if (execution == null) {
            return Result.failed(ErrorCode.INGEST_EXECUTION_NOT_FOUND);
        }
        assertExecutionReadable(execution);

        String currentStatus = execution.getStatus();
        boolean wasActive = "running".equals(currentStatus) || "pending".equals(currentStatus) || "paused".equals(currentStatus);
        if (wasActive) {
            ingestService.markExecutionCancelled(id);
        }

        registry.cancelAndRemoveFuture(id);
        registry.completeAndRemoveEmitter(id);

        if (wasActive) {
            ingestService.cleanupCancelledExecution(id, execution.getScopeId());
        }

        executionTracker.deleteExecution(id);
        log.info("Deleted ingest execution id={} for scopeId={}", id, execution.getScopeId());
        return Result.success();
    }

    @PostMapping("/{id}/pause")
    public Result<Void> pauseIngest(@PathVariable Long id) {
        ExecutionModel execution = ingestService.getProgress(id);
        if (execution == null) {
            return Result.failed(ErrorCode.INGEST_EXECUTION_NOT_FOUND);
        }
        assertExecutionReadable(execution);
        String currentStatus = execution.getStatus();
        if ("completed".equals(currentStatus) || "failed".equals(currentStatus)
                || "cancelled".equals(currentStatus) || "budget_exhausted".equals(currentStatus)
                || "paused".equals(currentStatus) || "awaiting_confirmation".equals(currentStatus)) {
            return Result.failed(ErrorCode.INGEST_ALREADY_FINISHED_PAUSE);
        }

        ingestService.pauseExecution(id, execution.getScopeId());

        if (isMqAvailable() && mqHealthService.shouldAttempt()) {
            try {
                sendControlMessage(id, ControlMessage.ACTION_PAUSE, "用户手动暂停");
                mqHealthService.markSendSuccess();
            } catch (Exception e) {
                mqHealthService.markSendFailed();
                log.error("Failed to send pause control message, pausing locally, executionId={}", id, e);
                pauseIngestLocally(id);
            }
        } else {
            pauseIngestLocally(id);
        }

        return Result.success();
    }

    @GetMapping("/active")
    public Result<java.util.List<ExecutionInfo>> listActiveIngest(@RequestParam Long scopeId) {
        java.util.List<ExecutionModel> executions = executionTracker.listExecutions(scopeId, "ingest");
        java.util.List<ExecutionModel> relevantModels = executions.stream()
            .filter(e -> {
                String status = e.getStatus();
                if ("running".equals(status) || "pending".equals(status) || "paused".equals(status)) return true;
                if ("completed".equals(status) || "budget_exhausted".equals(status)) {
                    if (e.getCompletedAt() != null) {
                        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusMinutes(30);
                        return e.getCompletedAt().isAfter(cutoff);
                    }
                    return true;
                }
                return false;
            })
            .collect(java.util.stream.Collectors.toList());
        java.util.Set<Long> sourceIds = relevantModels.stream()
            .map(ExecutionModel::getSourceId)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        java.util.Map<Long, String> sourceNameMap = sourceIds.isEmpty() ? java.util.Map.of() :
            sourceMapper.selectBatchIds(sourceIds).stream()
                .collect(java.util.stream.Collectors.toMap(SourceDO::getId, SourceDO::getName));
        java.util.List<ExecutionInfo> active = relevantModels.stream()
            .map(m -> toExecutionInfoWithMap(m, sourceNameMap))
            .collect(java.util.stream.Collectors.toList());
        return Result.success(active);
    }

    @EventListener(condition = "#event.executionType != 'lint'")
    public void onExecutionStatusEvent(ExecutionStatusEvent event) {
        SseEmitter emitter = registry.getEmitter(event.getExecutionId());
        if (emitter == null) return;

        try {
            ExecutionModel execution = ingestService.getProgress(event.getExecutionId());
            if (execution == null) return;

            if ("completed".equals(event.getNewStatus())
                    || "failed".equals(event.getNewStatus())
                    || "cancelled".equals(event.getNewStatus())
                    || "budget_exhausted".equals(event.getNewStatus())) {
                emitter.send(SseEmitter.event()
                    .name("done")
                    .data(toExecutionInfo(execution)));
                emitter.complete();
                registry.removeEmitter(event.getExecutionId());
            } else if ("awaiting_confirmation".equals(event.getNewStatus())) {
                emitter.send(SseEmitter.event()
                    .name("phase1_done")
                    .data(toExecutionInfo(execution)));
            } else if ("paused".equals(event.getNewStatus())) {
                emitter.send(SseEmitter.event()
                    .name("pause")
                    .data(toExecutionInfo(execution)));
            }
        } catch (Exception e) {
            log.error("Failed to send SSE event for execution status change", e);
            registry.removeEmitter(event.getExecutionId());
        }
    }

    @EventListener(condition = "#event.executionType != 'lint'")
    public void onStepStatusEvent(StepStatusEvent event) {
        SseEmitter emitter = registry.getEmitter(event.getExecutionId());
        if (emitter != null) {
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
                    ExecutionModel execution = ingestService.getProgress(event.getExecutionId());
                    if (execution != null && ("completed".equals(execution.getStatus()) || "failed".equals(execution.getStatus()) || "cancelled".equals(execution.getStatus()) || "budget_exhausted".equals(execution.getStatus()))) {
                        emitter.send(SseEmitter.event()
                            .name("done")
                            .data(toExecutionInfo(execution)));
                        emitter.complete();
                        registry.removeEmitter(event.getExecutionId());
                    }
                } else if ("paused".equals(event.getStatus())) {
                    ExecutionModel execution = ingestService.getProgress(event.getExecutionId());
                    if (execution != null && "paused".equals(execution.getStatus())) {
                        emitter.send(SseEmitter.event()
                            .name("pause")
                            .data(toExecutionInfo(execution)));
                    }
                }
            } catch (Exception e) {
                log.error("Failed to send SSE event", e);
                registry.removeEmitter(event.getExecutionId());
            }
        }
    }

    @EventListener(condition = "#event.executionType != 'lint'")
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
            log.error("Failed to send step_progress SSE", e);
            registry.removeEmitter(event.getExecutionId());
        }
    }

    @PostMapping("/{id}/resume")
    public Result<ExecutionInfo> resumeIngest(@PathVariable Long id, @RequestBody(required = false) IngestRequest request) {
        ExecutionModel execution = ingestService.getProgress(id);
        if (execution == null) {
            return Result.failed(ErrorCode.INGEST_EXECUTION_NOT_FOUND);
        }
        assertExecutionReadable(execution);
        if (!"failed".equals(execution.getStatus()) && !"paused".equals(execution.getStatus())) {
            if ("cancelled".equals(execution.getStatus())) {
                return Result.failed(ErrorCode.INGEST_CANCELLED_CANNOT_RESUME);
            }
            return Result.failed(ErrorCode.INGEST_INVALID_STATUS_RESUME, execution.getStatus());
        }
        String guidance = request != null ? request.getGuidance() : null;
        setNodeOwnership(id);

        boolean phase1Completed = IngestStep.isPhase1Completed(execution.getSteps());

        dispatchToMqOrLocal(id, execution.getScopeId(), execution.getSourceId(), guidance,
                PipelineTaskMessage.TYPE_INGEST_RESUME,
                () -> submitLocalTask(id, () -> {
                    try {
                        if (phase1Completed) {
                            ingestService.resumeIngestExecution(id, execution.getScopeId(), execution.getSourceId(), guidance);
                        } else {
                            ingestService.resumeIngestAnalysis(id, execution.getScopeId(), execution.getSourceId(), guidance);
                        }
                    } catch (Exception e) {
                        log.error("Resume ingest failed for executionId={}", id, e);
                        ExecutionModel current = ingestService.getProgress(id);
                        if (current == null || !"cancelled".equals(current.getStatus())) {
                            ingestService.failExecution(id, e.getMessage());
                        }
                    }
                }));
        return Result.success(toExecutionInfo(ingestService.getProgress(id)));
    }

    private void submitLocalTask(Long executionId, Runnable task) {
        AtomicReference<Future<?>> futureRef = new AtomicReference<>();
        Future<?> future = registry.submitTask(() -> {
            try {
                task.run();
            } finally {
                Future<?> self = futureRef.get();
                if (self != null) {
                    registry.removeFutureIfSame(executionId, self);
                }
            }
        });
        futureRef.set(future);
        registry.putFuture(executionId, future);
        if (future.isDone()) {
            registry.removeFutureIfSame(executionId, future);
        }
    }

    private void dispatchToMqOrLocal(Long executionId, Long scopeId, Long sourceId, String guidance,
                                     String taskType, Runnable localFallback) {
        if (!isMqAvailable() || !mqHealthService.shouldAttempt()) {
            localFallback.run();
            return;
        }
        try {
            sendPipelineTask(executionId, scopeId, sourceId, guidance, taskType);
            mqHealthService.markSendSuccess();
        } catch (Exception e) {
            mqHealthService.markSendFailed();
            log.error("Failed to send pipeline task to RocketMQ (type={}, executionId={}), falling back to local execution",
                taskType, executionId, e);
            localFallback.run();
        }
    }

    private void cancelIngestLocally(Long executionId) {
        registry.cancelAndRemoveFuture(executionId);

        SseEmitter emitter = registry.removeEmitter(executionId);
        if (emitter != null) {
            try {
                ExecutionModel cancelled = ingestService.getProgress(executionId);
                emitter.send(SseEmitter.event().name("done").data(toExecutionInfo(cancelled)));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Failed to send cancel SSE event for executionId={}", executionId);
            }
        }
    }

    private void pauseIngestLocally(Long executionId) {
        registry.cancelAndRemoveFuture(executionId);

        SseEmitter emitter = registry.getEmitter(executionId);
        if (emitter != null) {
            try {
                ExecutionModel paused = ingestService.getProgress(executionId);
                emitter.send(SseEmitter.event().name("pause").data(toExecutionInfo(paused)));
            } catch (Exception e) {
                log.warn("Failed to send pause SSE event for executionId={}", executionId);
            }
        }
    }

    private void sendPipelineTask(Long executionId, Long scopeId, Long sourceId, String guidance, String taskType) {
        PipelineTaskMessage msg = new PipelineTaskMessage();
        msg.setExecutionId(executionId);
        msg.setScopeId(scopeId);
        msg.setSourceId(sourceId);
        msg.setGuidance(guidance);
        msg.setTaskType(taskType);
        msg.setNodeId(registry.getNodeId());
        msg.setSubmittedAt(System.currentTimeMillis());
        try {
            rocketMQTemplate.convertAndSend(PipelineTaskMessage.TOPIC, msg);
            log.info("[MQ] Pipeline task sent: executionId={}, taskType={}, topic={}", executionId, taskType, PipelineTaskMessage.TOPIC);
        } catch (Exception e) {
            log.error("[MQ] Failed to send pipeline task: executionId={}, topic={}", executionId, PipelineTaskMessage.TOPIC, e);
            throw new RuntimeException("Failed to submit pipeline task", e);
        }
    }

    private void sendControlMessage(Long executionId, String action, String reason) {
        ControlMessage msg = new ControlMessage();
        msg.setExecutionId(executionId);
        msg.setAction(action);
        msg.setReason(reason);
        msg.setIssuedBy(registry.getNodeId());
        msg.setIssuedAt(System.currentTimeMillis());
        rocketMQTemplate.convertAndSend(ControlMessage.TOPIC, msg);
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

    private ExecutionInfo toExecutionInfo(ExecutionModel model) {
        if (model == null) return null;
        ExecutionInfo info = new ExecutionInfo();
        info.setExecutionId(model.getId());
        info.setOperationType(model.getType());
        info.setStatus(model.getStatus());
        info.setScopeId(model.getScopeId());
        info.setSourceId(model.getSourceId());
        info.setStartTime(model.getStartedAt());
        info.setEndTime(model.getCompletedAt());
        info.setTotalTokens(model.getTotalTokens());
        info.setErrorMessage(model.getErrorMessage());
        if (model.getSourceId() != null) {
            SourceDO source = sourceMapper.selectById(model.getSourceId());
            if (source != null) {
                info.setSourceName(source.getName());
            }
        }
        return info;
    }

    private ExecutionInfo toExecutionInfoWithMap(ExecutionModel model, java.util.Map<Long, String> sourceNameMap) {
        if (model == null) return null;
        ExecutionInfo info = new ExecutionInfo();
        info.setExecutionId(model.getId());
        info.setOperationType(model.getType());
        info.setStatus(model.getStatus());
        info.setScopeId(model.getScopeId());
        info.setSourceId(model.getSourceId());
        info.setStartTime(model.getStartedAt());
        info.setEndTime(model.getCompletedAt());
        info.setTotalTokens(model.getTotalTokens());
        info.setErrorMessage(model.getErrorMessage());
        if (model.getSourceId() != null && sourceNameMap.containsKey(model.getSourceId())) {
            info.setSourceName(sourceNameMap.get(model.getSourceId()));
        }
        return info;
    }

    private void logDuplicateWarning(SourceModel source, Long scopeId) {
        if (source.getContentHash() == null) return;
        SourceModel duplicate = sourceService.findDuplicateSource(scopeId, source.getContentHash());
        if (duplicate != null) {
            log.info("Duplicate source detected: sourceId={} has same content hash as processed sourceId={} (name='{}') in scopeId={}",
                source.getId(), duplicate.getId(), duplicate.getName(), scopeId);
        }
    }
}
