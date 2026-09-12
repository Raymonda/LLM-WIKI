package org.cn.liuwt.llmwiki.service.harness.task.handler;

import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.service.harness.task.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexRebuildTaskHandlerTest {

    @Mock
    private SearchService searchService;

    @Mock
    private ExecutionTracker executionTracker;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private IndexRebuildTaskHandler handler;

    @Test
    void shouldReturnScopeLevelIdempotencyKey() {
        assertEquals("scope", handler.idempotencyKey(Map.of()));
        assertEquals("scope", handler.idempotencyKey(Map.of("title", "重建检索索引")));
    }

    @Test
    void shouldRebuildIndexAndNotifyWhenExecuted() throws Exception {
        ExecutionStepModel step = new ExecutionStepModel();
        step.setId(700L);
        when(executionTracker.createStep(100L, "REBUILD_INDEX", 1, "AUTO")).thenReturn(step);

        handler.execute(context());

        verify(searchService).rebuildIndex(1L);
        verify(executionTracker).updateStepStatus(700L, "running");
        verify(executionTracker).completeStep(eq(700L), anyString(), anyInt(), anyInt());
        verify(notificationService).createNotification(eq(7L), eq("index_rebuild"),
            anyString(), contains("重建完成"), eq(1L), any(), eq(100L));
    }

    @Test
    void shouldNotifyFailureAndRethrowWhenRebuildFails() {
        ExecutionStepModel step = new ExecutionStepModel();
        step.setId(700L);
        when(executionTracker.createStep(100L, "REBUILD_INDEX", 1, "AUTO")).thenReturn(step);
        doThrow(new RuntimeException("ES unavailable")).when(searchService).rebuildIndex(1L);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.execute(context()));

        assertEquals("ES unavailable", ex.getMessage());
        verify(executionTracker).updateStepStatus(700L, "failed");
        verify(notificationService).createNotification(eq(7L), eq("index_rebuild"),
            contains("失败"), contains("ES unavailable"), eq(1L), any(), eq(100L));
    }

    private static TaskContext context() {
        return new TaskContext(100L, 1L, 7L, Map.of("title", "重建检索索引"), new ExecutionModel());
    }
}
