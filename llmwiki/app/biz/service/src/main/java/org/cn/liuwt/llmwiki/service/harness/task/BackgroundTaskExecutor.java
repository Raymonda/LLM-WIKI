package org.cn.liuwt.llmwiki.service.harness.task;

import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BackgroundTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskExecutor.class);

    private static final int ERROR_SUMMARY_MAX = 500;

    @Autowired
    private BackgroundTaskRegistry registry;

    @Autowired
    private ExecutionTracker executionTracker;

    public void execute(Long executionId, String taskType) {
        BackgroundTaskHandler handler = registry.getHandler(taskType);
        if (handler == null) {
            throw new IllegalStateException("No BackgroundTaskHandler registered for taskType=" + taskType);
        }
        try {
            ExecutionModel execution = executionTracker.getExecution(executionId);
            if (execution == null) {
                throw new IllegalStateException("Execution not found: " + executionId);
            }
            Map<String, Object> payload = TaskPayloads.parse(execution.getPayloadJson());
            TaskContext ctx = new TaskContext(executionId, execution.getScopeId(),
                execution.getSubmittedBy(), payload, execution);
            handler.validate(ctx);
            executionTracker.updateExecutionStatus(executionId, "running");
            handler.execute(ctx);
            completeIfStillRunning(executionId);
        } catch (Exception e) {
            log.error("Background task failed: executionId={}, taskType={}", executionId, taskType, e);
            executionTracker.failExecution(executionId, summarize(e));
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Background task failed: executionId=" + executionId, e);
        }
    }

    private void completeIfStillRunning(Long executionId) {
        ExecutionModel current = executionTracker.getExecution(executionId);
        if (current != null && "running".equals(current.getStatus())) {
            Integer tokens = current.getTotalTokens() != null ? current.getTotalTokens() : 0;
            executionTracker.completeExecution(executionId, tokens);
        }
    }

    private String summarize(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        if (e instanceof BusinessException be && be.getArgs() != null) {
            for (int i = 0; i < be.getArgs().length; i++) {
                if (be.getArgs()[i] != null) {
                    message = message.replace("{" + i + "}", String.valueOf(be.getArgs()[i]));
                }
            }
        }
        return message.length() > ERROR_SUMMARY_MAX ? message.substring(0, ERROR_SUMMARY_MAX) : message;
    }
}
