package org.cn.liuwt.llmwiki.service.harness.task;

import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.service.harness.mq.IngestDispatcher;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.cn.liuwt.llmwiki.service.ingest.IngestBatchScheduler;
import org.cn.liuwt.llmwiki.service.ingest.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class BackgroundTaskService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskService.class);

    @Autowired
    private BackgroundTaskRegistry registry;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private BackgroundTaskExecutor backgroundTaskExecutor;

    @Autowired
    private IngestDispatcher ingestDispatcher;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private IngestBatchScheduler ingestBatchScheduler;

    public TaskReceipt submit(Long scopeId, Long submittedBy, String taskType, Map<String, Object> payload) {
        BackgroundTaskHandler handler = requireHandler(taskType);
        assertNoActiveDuplicate(handler, scopeId, payload, null);
        handler.validate(new TaskContext(null, scopeId, submittedBy, payload, null));
        String payloadJson = TaskPayloads.write(payload);
        ExecutionModel execution = executionTracker.createTaskExecution(taskType, scopeId, payloadJson, submittedBy);
        dispatchExecution(execution.getId(), scopeId, taskType, payloadJson);
        log.info("Background task submitted: executionId={}, taskType={}, scopeId={}",
            execution.getId(), taskType, scopeId);
        return new TaskReceipt(execution.getId(), taskType, "pending");
    }

    public TaskReceipt retry(Long scopeId, Long executionId, Long submittedBy) {
        ExecutionModel execution = executionTracker.getExecution(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, String.valueOf(executionId));
        }
        if (!scopeId.equals(execution.getScopeId())) {
            throw new BusinessException(ErrorCode.TASK_SCOPE_MISMATCH, String.valueOf(executionId));
        }
        if (!"failed".equals(execution.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_NOT_RETRYABLE, execution.getStatus());
        }
        if ("ingest".equals(execution.getType())) {
            return retryIngest(execution, submittedBy);
        }
        BackgroundTaskHandler handler = registry.getHandler(execution.getType());
        if (handler == null) {
            throw new BusinessException(ErrorCode.TASK_TYPE_UNSUPPORTED, execution.getType());
        }
        Map<String, Object> payload = TaskPayloads.parse(execution.getPayloadJson());
        assertNoActiveDuplicate(handler, scopeId, payload, executionId);
        executionTracker.deleteSteps(executionId);
        executionTracker.resetExecutionForRetry(executionId);
        dispatchExecution(executionId, scopeId, execution.getType(), execution.getPayloadJson());
        log.info("Background task retried: executionId={}, taskType={}, by={}",
            executionId, execution.getType(), submittedBy);
        return new TaskReceipt(executionId, execution.getType(), "running");
    }

    private TaskReceipt retryIngest(ExecutionModel execution, Long submittedBy) {
        if (!ingestService.queueResume(execution.getId(), null)) {
            throw new BusinessException(ErrorCode.TASK_NOT_RETRYABLE, execution.getStatus());
        }
        ingestBatchScheduler.kick(execution.getScopeId());
        log.info("Ingest execution retried: executionId={}, by={}", execution.getId(), submittedBy);
        return new TaskReceipt(execution.getId(), execution.getType(), "pending");
    }

    private BackgroundTaskHandler requireHandler(String taskType) {
        BackgroundTaskHandler handler = registry.getHandler(taskType);
        if (handler == null) {
            throw new BusinessException(ErrorCode.TASK_TYPE_UNSUPPORTED, taskType);
        }
        return handler;
    }

    private void assertNoActiveDuplicate(BackgroundTaskHandler handler, Long scopeId,
                                         Map<String, Object> payload, Long excludeExecutionId) {
        String candidateKey = handler.idempotencyKey(payload);
        if (candidateKey == null) {
            return;
        }
        for (ExecutionModel active : executionTracker.listActiveExecutions(scopeId, handler.taskType())) {
            if (excludeExecutionId != null && excludeExecutionId.equals(active.getId())) {
                continue;
            }
            Map<String, Object> activePayload = TaskPayloads.parse(active.getPayloadJson());
            if (candidateKey.equals(handler.idempotencyKey(activePayload))) {
                throw new BusinessException(ErrorCode.TASK_DUPLICATE, String.valueOf(active.getId()));
            }
        }
    }

    private void dispatchExecution(Long executionId, Long scopeId, String taskType, String payloadJson) {
        PipelineTaskMessage msg = new PipelineTaskMessage();
        msg.setExecutionId(executionId);
        msg.setScopeId(scopeId);
        msg.setTaskType(taskType);
        msg.setPayloadJson(payloadJson);
        msg.setNodeId(ingestDispatcher.getNodeId());
        msg.setSubmittedAt(System.currentTimeMillis());
        ingestDispatcher.dispatchTask(msg, () -> backgroundTaskExecutor.execute(executionId, taskType));
    }
}
