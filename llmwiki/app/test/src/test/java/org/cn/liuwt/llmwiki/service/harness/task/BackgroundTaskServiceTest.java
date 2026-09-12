package org.cn.liuwt.llmwiki.service.harness.task;

import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.service.harness.mq.IngestDispatcher;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackgroundTaskServiceTest {

    @Mock
    private BackgroundTaskRegistry registry;

    @Mock
    private ExecutionTracker executionTracker;

    @Mock
    private BackgroundTaskExecutor backgroundTaskExecutor;

    @Mock
    private IngestDispatcher ingestDispatcher;

    @InjectMocks
    private BackgroundTaskService service;

    @Test
    void shouldCreateExecutionAndDispatchWhenSubmittingRegisteredTask() {
        TestHandler handler = new TestHandler("query_save", null);
        when(registry.getHandler("query_save")).thenReturn(handler);
        when(executionTracker.createTaskExecution(eq("query_save"), eq(1L), any(), eq(7L)))
            .thenReturn(execution(100L, "query_save", 1L));

        TaskReceipt receipt = service.submit(1L, 7L, "query_save", Map.of("question", "q"));

        assertEquals(100L, receipt.executionId());
        assertEquals("query_save", receipt.taskType());
        assertEquals("pending", receipt.status());
        ArgumentCaptor<PipelineTaskMessage> msgCaptor = ArgumentCaptor.forClass(PipelineTaskMessage.class);
        verify(ingestDispatcher).dispatchTask(msgCaptor.capture(), any(Runnable.class));
        assertEquals(100L, msgCaptor.getValue().getExecutionId());
        assertEquals("query_save", msgCaptor.getValue().getTaskType());
        assertEquals(1L, msgCaptor.getValue().getScopeId());
        assertTrue(msgCaptor.getValue().getPayloadJson().contains("q"));
    }

    @Test
    void shouldRejectSubmitWhenTaskTypeNotRegistered() {
        when(registry.getHandler("mystery")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.submit(1L, 7L, "mystery", Map.of()));

        assertEquals("TASK_001", ex.getCode());
        verify(executionTracker, never()).createTaskExecution(any(), any(), any(), any());
        verify(ingestDispatcher, never()).dispatchTask(any(), any());
    }

    @Test
    void shouldRejectSubmitWhenActiveTaskHasSameIdempotencyKey() {
        TestHandler handler = new TestHandler("conflict_ruling", "review:5");
        when(registry.getHandler("conflict_ruling")).thenReturn(handler);
        ExecutionModel active = execution(9L, "conflict_ruling", 1L);
        active.setPayloadJson("{\"reviewId\":5}");
        when(executionTracker.listActiveExecutions(1L, "conflict_ruling")).thenReturn(List.of(active));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.submit(1L, 7L, "conflict_ruling", Map.of("reviewId", 5)));

        assertEquals("TASK_002", ex.getCode());
        verify(executionTracker, never()).createTaskExecution(any(), any(), any(), any());
    }

    private static ExecutionModel execution(Long id, String type, Long scopeId) {
        ExecutionModel model = new ExecutionModel();
        model.setId(id);
        model.setType(type);
        model.setScopeId(scopeId);
        model.setStatus("pending");
        model.setTotalTokens(0);
        return model;
    }

    private record TestHandler(String type, String key) implements BackgroundTaskHandler {
        @Override public String taskType() { return type; }
        @Override public String idempotencyKey(Map<String, Object> payload) { return key; }
        @Override public void execute(TaskContext ctx) {}
    }
}
