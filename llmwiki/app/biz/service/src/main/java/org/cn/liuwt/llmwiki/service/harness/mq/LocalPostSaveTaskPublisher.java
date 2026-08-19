package org.cn.liuwt.llmwiki.service.harness.mq;

import jakarta.annotation.PreDestroy;
import org.cn.liuwt.llmwiki.domain.service.wiki.PostSaveTaskPublisher;
import org.cn.liuwt.llmwiki.service.wiki.PageSavePostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * PostSaveTaskPublisher 的本地兜底实现（单机模式 / MQ 发送失败时）。
 * 直接在本地线程池执行后处理任务，行为与 MQ 消费等价，但无跨节点投递与自动重试。
 */
@Component
public class LocalPostSaveTaskPublisher implements PostSaveTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalPostSaveTaskPublisher.class);

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "post-save-local");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    private PageSavePostService pageSavePostService;

    @Override
    public void publish(Long scopeId, Long pageId, String content, String category, String contentHash) {
        PipelineTaskMessage msg = new PipelineTaskMessage();
        msg.setTaskType(PipelineTaskMessage.TYPE_PAGE_SAVE_POST);
        msg.setScopeId(scopeId);
        msg.setPageId(pageId);
        msg.setContent(content);
        msg.setCategory(category);
        msg.setContentHash(contentHash);
        msg.setSubmittedAt(System.currentTimeMillis());

        try {
            executor.submit(() -> {
                try {
                    pageSavePostService.executePostSave(msg);
                } catch (Exception e) {
                    // executePostSave 抛出前已将 post_save_status 标记为 failed，此处仅记录
                    log.error("Local PAGE_SAVE_POST processing failed: scopeId={}, pageId={}", scopeId, pageId, e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("PAGE_SAVE_POST skipped: executor shutting down: scopeId={}, pageId={}", scopeId, pageId);
            pageSavePostService.markPageFailed(scopeId, pageId, "本地后处理任务被拒绝：执行器已关闭");
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
