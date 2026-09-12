package org.cn.liuwt.llmwiki.service.harness.task.handler;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ConflictReviewDO;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictReviewService;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.service.harness.task.BackgroundTaskHandler;
import org.cn.liuwt.llmwiki.service.harness.task.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class ConflictRulingTaskHandler implements BackgroundTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(ConflictRulingTaskHandler.class);

    private static final Set<String> VALID_ACTIONS = Set.of("merge", "coexist", "choose_a", "choose_b");
    private static final Set<String> EXECUTABLE_STATUSES = Set.of("pending", "failed");

    @Autowired
    private ConflictReviewService conflictReviewService;

    @Autowired
    private NotificationService notificationService;

    @Override
    public String taskType() {
        return "conflict_ruling";
    }

    @Override
    public String idempotencyKey(Map<String, Object> payload) {
        Object reviewId = payload.get("reviewId");
        return reviewId != null ? "review:" + reviewId : null;
    }

    @Override
    public void validate(TaskContext ctx) {
        Map<String, Object> payload = ctx.payload();
        Long reviewId = asLong(payload.get("reviewId"));
        if (reviewId == null) {
            throw new BusinessException(ErrorCode.TASK_PAYLOAD_INVALID, "reviewId 缺失");
        }
        String action = asString(payload.get("action"));
        if (action == null || !VALID_ACTIONS.contains(action)) {
            throw new BusinessException(ErrorCode.TASK_PAYLOAD_INVALID, "action 非法: " + action);
        }
        ConflictReviewDO review = conflictReviewService.getReview(reviewId);
        if (review == null || !ctx.scopeId().equals(review.getScopeId())) {
            throw new BusinessException(ErrorCode.CONFLICT_REVIEW_NOT_FOUND);
        }
        if (review.getStatus() == null || !EXECUTABLE_STATUSES.contains(review.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT_ALREADY_PROCESSED);
        }
    }

    @Override
    public void execute(TaskContext ctx) {
        Long reviewId = asLong(ctx.payload().get("reviewId"));
        String action = asString(ctx.payload().get("action"));
        String detail = asString(ctx.payload().get("detail"));
        conflictReviewService.prepareForRetry(reviewId);
        ConflictReviewDO result = conflictReviewService.executeRuling(
            reviewId, ctx.submittedBy(), action, detail);
        if (result == null || "failed".equals(result.getStatus())) {
            String error = result != null && result.getExecutionError() != null
                ? result.getExecutionError() : "裁决执行失败";
            notify(ctx, "裁决执行失败", "冲突裁决执行出错：" + summarize(error));
            throw new BusinessException(ErrorCode.TASK_EXECUTION_FAILED, error);
        }
        notify(ctx, "裁决已执行", "冲突裁决已完成：" + displayTitle(result.getFromPageTitle())
            + " ↔ " + displayTitle(result.getToPageTitle()));
        log.info("Conflict ruling executed: reviewId={}, action={}, executionId={}",
            reviewId, action, ctx.executionId());
    }

    private void notify(TaskContext ctx, String title, String content) {
        if (ctx.submittedBy() == null) {
            return;
        }
        try {
            notificationService.createNotification(ctx.submittedBy(), "conflict_ruling",
                title, content, ctx.scopeId(), null, ctx.executionId());
        } catch (Exception e) {
            log.warn("Failed to send conflict ruling notification: executionId={}", ctx.executionId(), e);
        }
    }

    private static String displayTitle(String title) {
        return title != null && !title.isBlank() ? title : "未知页面";
    }

    private static String summarize(String error) {
        return error.length() > 200 ? error.substring(0, 200) + "..." : error;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }
}
