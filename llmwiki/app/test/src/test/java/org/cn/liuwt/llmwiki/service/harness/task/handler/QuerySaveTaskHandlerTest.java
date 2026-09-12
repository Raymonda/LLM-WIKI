package org.cn.liuwt.llmwiki.service.harness.task.handler;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.service.harness.task.TaskContext;
import org.cn.liuwt.llmwiki.service.query.QueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuerySaveTaskHandlerTest {

    @Mock
    private QueryService queryService;

    @Mock
    private ExecutionTracker executionTracker;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private QuerySaveTaskHandler handler;

    @Test
    void shouldRejectValidateWhenQuestionMissing() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("answer", "A");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> handler.validate(context(payload)));

        assertEquals("TASK_006", ex.getCode());
    }

    @Test
    void shouldRejectValidateWhenAnswerBlank() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("question", "Q");
        payload.put("answer", "   ");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> handler.validate(context(payload)));

        assertEquals("TASK_006", ex.getCode());
        verify(queryService, never()).saveAnswerToWikiWithExecution(any(), any(), any(), any(), any());
    }

    @Test
    void shouldSavePageRecordStepAndNotifyWhenExecuted() throws Exception {
        WikiPageDO page = new WikiPageDO();
        page.setId(55L);
        page.setTitle("九坤持仓分析");
        when(queryService.saveAnswerToWikiWithExecution(100L, 1L, "Q", "A", null)).thenReturn(page);
        ExecutionStepModel step = new ExecutionStepModel();
        step.setId(900L);
        when(executionTracker.createStep(100L, "RECORD_RESULT", 5, "AUTO")).thenReturn(step);

        handler.execute(context(Map.of("question", "Q", "answer", "A")));

        verify(queryService).saveAnswerToWikiWithExecution(100L, 1L, "Q", "A", null);
        verify(executionTracker, never()).createExecution(any(), any(), any(), any());
        verify(executionTracker).updateStepStatus(900L, "running");
        verify(executionTracker).completeStep(eq(900L), contains("pageId=55"), anyInt(), anyInt());
        verify(notificationService).createNotification(eq(7L), eq("query_save"),
            anyString(), contains("《九坤持仓分析》"), eq(1L), eq(55L), eq(100L));
    }

    @Test
    void shouldNotifyFailureAndRethrowWhenSaveFails() {
        when(queryService.saveAnswerToWikiWithExecution(100L, 1L, "Q", "A", null))
            .thenThrow(new RuntimeException("AI 服务未配置或不可用"));

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> handler.execute(context(Map.of("question", "Q", "answer", "A"))));

        assertEquals("AI 服务未配置或不可用", ex.getMessage());
        verify(notificationService).createNotification(eq(7L), eq("query_save"),
            contains("失败"), contains("AI 服务未配置"), eq(1L), isNull(), eq(100L));
    }

    private static TaskContext context(Map<String, Object> payload) {
        return new TaskContext(100L, 1L, 7L, payload, new ExecutionModel());
    }
}
