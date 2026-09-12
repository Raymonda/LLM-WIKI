package org.cn.liuwt.llmwiki.service.harness.task;

import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.service.harness.mq.IngestDispatcher;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.cn.liuwt.llmwiki.service.ingest.IngestBatchScheduler;
import org.cn.liuwt.llmwiki.service.ingest.IngestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackgroundTaskRetryTest {

    @Mock
    private BackgroundTaskRegistry registry;

    @Mock
    private ExecutionTracker executionTracker;

    @Mock
    private BackgroundTaskExecutor backgroundTaskExecutor;

    @Mock
    private IngestDispatcher ingestDispatcher;

    @Mock
    private IngestService ingestService;

    @Mock
    private IngestBatchScheduler ingestBatchScheduler;

    @InjectMocks
    private BackgroundTaskService service;

    @Test
    void shouldResetAndRedispatchWhenTaskFailed() {
        TestHandler handler = new TestHandler("index_rebuild");
        when(registry.getHandler("index_rebuild")).thenReturn(handler);
        ExecutionModel failed = execution(50L, "index_rebuild", 1L, "failed");
        failed.setPayloadJson("{}");
        when(executionTracker.getExecution(50L)).thenReturn(failed);

        TaskReceipt receipt = service.retry(1L, 50L, 7L);

        assertEquals("running", receipt.status());
        verify(executionTracker).deleteSteps(50L);
        verify(executionTracker).resetExecutionForRetry(50L);
        verify(ingestDispatcher).dispatchTask(any(PipelineTaskMessage.class), any(Runnable.class));
    }

    @Test
    void shouldRejectRetryWhenExecutionNotFound() {
        when(executionTracker.getExecution(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.retry(1L, 404L, 7L));

        assertEquals("TASK_003", ex.getCode());
        verify(ingestDispatcher, never()).dispatchTask(any(), any());
    }

    @Test
    void shouldRejectRetryWhenScopeMismatch() {
        ExecutionModel failed = execution(50L, "index_rebuild", 2L, "failed");
        when(executionTracker.getExecution(50L)).thenReturn(failed);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.retry(1L, 50L, 7L));

        assertEquals("TASK_004", ex.getCode());
        verify(executionTracker, never()).resetExecutionForRetry(any());
    }

    @Test
    void shouldRejectRetryWhenStatusNotFailed() {
        ExecutionModel running = execution(50L, "index_rebuild", 1L, "running");
        when(executionTracker.getExecution(50L)).thenReturn(running);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.retry(1L, 50L, 7L));

        assertEquals("TASK_005", ex.getCode());
        verify(executionTracker, never()).resetExecutionForRetry(any());
    }

    @Test
    void shouldQueueResumeAndKickSchedulerWhenRetryingIngest() {
        ExecutionModel failed = execution(60L, "ingest", 1L, "failed");
        when(executionTracker.getExecution(60L)).thenReturn(failed);
        when(ingestService.queueResume(60L, null)).thenReturn(true);

        TaskReceipt receipt = service.retry(1L, 60L, 7L);

        assertEquals("ingest", receipt.taskType());
        assertEquals("pending", receipt.status());
        verify(ingestService).queueResume(60L, null);
        verify(ingestBatchScheduler).kick(1L);
        verify(ingestDispatcher, never()).dispatchTask(any(), any());
    }

    @Test
    void shouldRejectIngestRetryWhenQueueResumeCasLoses() {
        ExecutionModel failed = execution(60L, "ingest", 1L, "failed");
        when(executionTracker.getExecution(60L)).thenReturn(failed);
        when(ingestService.queueResume(60L, null)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.retry(1L, 60L, 7L));

        assertEquals("TASK_005", ex.getCode());
        verify(ingestBatchScheduler, never()).kick(any());
    }

    private static ExecutionModel execution(Long id, String type, Long scopeId, String status) {
        ExecutionModel model = new ExecutionModel();
        model.setId(id);
        model.setType(type);
        model.setScopeId(scopeId);
        model.setStatus(status);
        model.setTotalTokens(0);
        return model;
    }

    private static class TestHandler implements BackgroundTaskHandler {
        private final String type;
        private TestHandler(String type) { this.type = type; }
        @Override public String taskType() { return type; }
        @Override public void execute(TaskContext ctx) {}
    }
}
