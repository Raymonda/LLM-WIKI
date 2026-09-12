package org.cn.liuwt.llmwiki.service.harness.task;

import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackgroundTaskExecutorTest {

    @Mock
    private BackgroundTaskRegistry registry;

    @Mock
    private ExecutionTracker executionTracker;

    @InjectMocks
    private BackgroundTaskExecutor executor;

    @Test
    void shouldRunHandlerAndCompleteExecutionWhenTaskSucceeds() {
        RecordingHandler handler = new RecordingHandler();
        when(registry.getHandler("query_save")).thenReturn(handler);
        ExecutionModel loaded = execution(100L, "pending");
        loaded.setPayloadJson("{\"question\":\"q\"}");
        ExecutionModel running = execution(100L, "running");
        when(executionTracker.getExecution(100L)).thenReturn(loaded, running);

        executor.execute(100L, "query_save");

        assertEquals("q", handler.seenPayload.get("question"));
        verify(executionTracker).updateExecutionStatus(100L, "running");
        verify(executionTracker).completeExecution(100L, 0);
    }

    @Test
    void shouldFailExecutionAndRethrowWhenHandlerThrows() {
        FailingHandler handler = new FailingHandler();
        when(registry.getHandler("query_save")).thenReturn(handler);
        ExecutionModel loaded = execution(100L, "pending");
        loaded.setPayloadJson("{}");
        when(executionTracker.getExecution(100L)).thenReturn(loaded);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> executor.execute(100L, "query_save"));

        assertEquals("boom", ex.getMessage());
        verify(executionTracker).failExecution(100L, "boom");
        verify(executionTracker, never()).completeExecution(any(), any());
    }

    private static ExecutionModel execution(Long id, String status) {
        ExecutionModel model = new ExecutionModel();
        model.setId(id);
        model.setType("query_save");
        model.setScopeId(1L);
        model.setStatus(status);
        model.setTotalTokens(0);
        return model;
    }

    private static class RecordingHandler implements BackgroundTaskHandler {
        Map<String, Object> seenPayload;
        @Override public String taskType() { return "query_save"; }
        @Override public void execute(TaskContext ctx) { seenPayload = ctx.payload(); }
    }

    private static class FailingHandler implements BackgroundTaskHandler {
        @Override public String taskType() { return "query_save"; }
        @Override public void execute(TaskContext ctx) { throw new IllegalStateException("boom"); }
    }
}
