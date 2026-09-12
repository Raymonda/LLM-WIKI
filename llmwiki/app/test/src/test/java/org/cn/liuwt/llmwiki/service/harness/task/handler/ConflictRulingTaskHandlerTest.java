package org.cn.liuwt.llmwiki.service.harness.task.handler;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ConflictReviewDO;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictReviewService;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.service.harness.task.TaskContext;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConflictRulingTaskHandlerTest {

    @Mock
    private ConflictReviewService conflictReviewService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ConflictRulingTaskHandler handler;

    @Test
    void shouldRejectValidateWhenReviewMissing() {
        when(conflictReviewService.getReview(77L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> handler.validate(context(77L, "merge", 1L)));

        assertEquals("CONFLICT_001", ex.getCode());
    }

    @Test
    void shouldRejectValidateWhenActionInvalid() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> handler.validate(context(77L, "bogus", 1L)));

        assertEquals("TASK_006", ex.getCode());
        verify(conflictReviewService, never()).getReview(any());
    }

    @Test
    void shouldRejectValidateWhenReviewAlreadyExecuted() {
        when(conflictReviewService.getReview(77L)).thenReturn(review(77L, 1L, "executed"));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> handler.validate(context(77L, "merge", 1L)));

        assertEquals("CONFLICT_002", ex.getCode());
    }

    @Test
    void shouldRejectValidateWhenScopeMismatch() {
        when(conflictReviewService.getReview(77L)).thenReturn(review(77L, 2L, "pending"));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> handler.validate(context(77L, "merge", 1L)));

        assertEquals("CONFLICT_001", ex.getCode());
    }

    @Test
    void shouldPrepareAndExecuteRulingWhenValid() {
        when(conflictReviewService.executeRuling(eq(77L), eq(7L), eq("merge"), any()))
            .thenReturn(review(77L, 1L, "executed"));

        handler.execute(context(77L, "merge", 1L));

        verify(conflictReviewService).prepareForRetry(77L);
        verify(conflictReviewService).executeRuling(eq(77L), eq(7L), eq("merge"), any());
        verify(notificationService).createNotification(eq(7L), eq("conflict_ruling"),
            anyString(), anyString(), eq(1L), any(), any());
    }

    @Test
    void shouldThrowTaskExecutionFailedWhenRulingFails() {
        ConflictReviewDO failed = review(77L, 1L, "failed");
        failed.setExecutionError("LLM timeout");
        when(conflictReviewService.executeRuling(eq(77L), eq(7L), anyString(), any())).thenReturn(failed);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> handler.execute(context(77L, "merge", 1L)));

        assertEquals("TASK_007", ex.getCode());
        verify(conflictReviewService).prepareForRetry(77L);
        verify(notificationService).createNotification(eq(7L), eq("conflict_ruling"),
            anyString(), anyString(), eq(1L), any(), any());
    }

    private static TaskContext context(Long reviewId, String action, Long scopeId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reviewId", reviewId);
        payload.put("action", action);
        return new TaskContext(null, scopeId, 7L, payload, null);
    }

    private static ConflictReviewDO review(Long id, Long scopeId, String status) {
        ConflictReviewDO review = new ConflictReviewDO();
        review.setId(id);
        review.setScopeId(scopeId);
        review.setStatus(status);
        review.setFromPageTitle("特朗普");
        review.setToPageTitle("拜登");
        return review;
    }
}
