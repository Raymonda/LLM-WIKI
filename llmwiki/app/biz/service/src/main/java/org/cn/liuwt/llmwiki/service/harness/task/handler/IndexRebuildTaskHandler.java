package org.cn.liuwt.llmwiki.service.harness.task.handler;

import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.service.harness.task.BackgroundTaskHandler;
import org.cn.liuwt.llmwiki.service.harness.task.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class IndexRebuildTaskHandler implements BackgroundTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(IndexRebuildTaskHandler.class);

    @Autowired
    private SearchService searchService;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private NotificationService notificationService;

    @Override
    public String taskType() {
        return "index_rebuild";
    }

    @Override
    public String idempotencyKey(Map<String, Object> payload) {
        return "scope";
    }

    @Override
    public void execute(TaskContext ctx) throws Exception {
        long start = System.currentTimeMillis();
        ExecutionStepModel step = null;
        try {
            step = executionTracker.createStep(ctx.executionId(), "REBUILD_INDEX", 1, "AUTO");
            executionTracker.updateStepStatus(step.getId(), "running");
            searchService.rebuildIndex(ctx.scopeId());
            executionTracker.completeStep(step.getId(), "rebuild completed", 0,
                (int) Math.min(System.currentTimeMillis() - start, Integer.MAX_VALUE));
            notify(ctx, "索引重建完成", "检索索引已重建完成");
            log.info("Index rebuild task completed: executionId={}, scopeId={}", ctx.executionId(), ctx.scopeId());
        } catch (Exception e) {
            if (step != null) {
                executionTracker.updateStepStatus(step.getId(), "failed");
            }
            notify(ctx, "索引重建失败", "重建检索索引失败：" + summarize(e));
            throw e;
        }
    }

    private void notify(TaskContext ctx, String title, String content) {
        if (ctx.submittedBy() == null) {
            return;
        }
        try {
            notificationService.createNotification(ctx.submittedBy(), "index_rebuild",
                title, content, ctx.scopeId(), null, ctx.executionId());
        } catch (Exception e) {
            log.warn("Failed to send index_rebuild notification: executionId={}", ctx.executionId(), e);
        }
    }

    private static String summarize(Exception e) {
        String message = e.getMessage();
        String summary = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        return summary.length() > 200 ? summary.substring(0, 200) + "..." : summary;
    }
}
