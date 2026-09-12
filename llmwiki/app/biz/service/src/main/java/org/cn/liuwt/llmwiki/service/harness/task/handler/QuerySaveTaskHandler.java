package org.cn.liuwt.llmwiki.service.harness.task.handler;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.service.harness.task.BackgroundTaskHandler;
import org.cn.liuwt.llmwiki.service.harness.task.TaskContext;
import org.cn.liuwt.llmwiki.service.query.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class QuerySaveTaskHandler implements BackgroundTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(QuerySaveTaskHandler.class);

    @Autowired
    private QueryService queryService;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private NotificationService notificationService;

    @Override
    public String taskType() {
        return "query_save";
    }

    @Override
    public void validate(TaskContext ctx) {
        if (asString(ctx.payload().get("question")) == null) {
            throw new BusinessException(ErrorCode.TASK_PAYLOAD_INVALID, "question");
        }
        if (asString(ctx.payload().get("answer")) == null) {
            throw new BusinessException(ErrorCode.TASK_PAYLOAD_INVALID, "answer");
        }
    }

    @Override
    public void execute(TaskContext ctx) throws Exception {
        try {
            String question = asString(ctx.payload().get("question"));
            String answer = asString(ctx.payload().get("answer"));
            String sessionId = asString(ctx.payload().get("sessionId"));
            WikiPageDO page = queryService.saveAnswerToWikiWithExecution(
                ctx.executionId(), ctx.scopeId(), question, answer, sessionId);
            recordResultStep(ctx, page);
            notify(ctx, "问答已保存", "已保存为《" + page.getTitle() + "》", page.getId());
            log.info("Query save task completed: executionId={}, pageId={}, title={}",
                ctx.executionId(), page.getId(), page.getTitle());
        } catch (Exception e) {
            notify(ctx, "问答保存失败", "保存到知识库失败：" + summarize(e));
            throw e;
        }
    }

    private void recordResultStep(TaskContext ctx, WikiPageDO page) {
        try {
            ExecutionStepModel step = executionTracker.createStep(ctx.executionId(), "RECORD_RESULT", 5, "AUTO");
            executionTracker.updateStepStatus(step.getId(), "running");
            executionTracker.completeStep(step.getId(),
                "saved pageId=" + page.getId() + ", title=" + page.getTitle(), 0, 0);
        } catch (Exception e) {
            log.warn("Failed to record RECORD_RESULT step: executionId={}", ctx.executionId(), e);
        }
    }

    private void notify(TaskContext ctx, String title, String content) {
        notify(ctx, title, content, null);
    }

    private void notify(TaskContext ctx, String title, String content, Long relatedPageId) {
        if (ctx.submittedBy() == null) {
            return;
        }
        try {
            notificationService.createNotification(ctx.submittedBy(), "query_save",
                title, content, ctx.scopeId(), relatedPageId, ctx.executionId());
        } catch (Exception e) {
            log.warn("Failed to send query_save notification: executionId={}", ctx.executionId(), e);
        }
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String summarize(Exception e) {
        String message = e.getMessage();
        String summary = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        return summary.length() > 200 ? summary.substring(0, 200) + "..." : summary;
    }
}
