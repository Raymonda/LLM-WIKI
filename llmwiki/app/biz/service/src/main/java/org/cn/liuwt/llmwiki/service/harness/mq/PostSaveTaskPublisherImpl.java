package org.cn.liuwt.llmwiki.service.harness.mq;

import org.cn.liuwt.llmwiki.domain.service.wiki.PostSaveTaskPublisher;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * PostSaveTaskPublisher 的 RocketMQ 实现。
 * MQ 未启用或发送失败时降级到 LocalPostSaveTaskPublisher，
 * 保证 post_save_status 不会永久停留在 pending。
 */
@Component
@Primary
public class PostSaveTaskPublisherImpl implements PostSaveTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(PostSaveTaskPublisherImpl.class);

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    @Value("${llmwiki.rocketmq.enabled:false}")
    private boolean mqEnabled;

    @Autowired
    private MqHealthService mqHealthService;

    @Autowired
    private LocalPostSaveTaskPublisher localFallback;

    @Override
    public void publish(Long scopeId, Long pageId, String content, String category, String contentHash) {
        if (mqEnabled && rocketMQTemplate != null && mqHealthService.shouldAttempt()) {
            PipelineTaskMessage msg = new PipelineTaskMessage();
            msg.setTaskType(PipelineTaskMessage.TYPE_PAGE_SAVE_POST);
            msg.setScopeId(scopeId);
            msg.setPageId(pageId);
            msg.setContent(content);
            msg.setCategory(category);
            msg.setContentHash(contentHash);
            msg.setSubmittedAt(System.currentTimeMillis());

            try {
                rocketMQTemplate.convertAndSend(PipelineTaskMessage.TOPIC, msg);
                mqHealthService.markSendSuccess();
                log.info("Published PAGE_SAVE_POST: scopeId={}, pageId={}, contentHash={}", scopeId, pageId, contentHash);
                return;
            } catch (Exception e) {
                mqHealthService.markSendFailed();
                log.error("Failed to publish PAGE_SAVE_POST message, fallback to local execution: pageId={}", pageId, e);
            }
        }
        localFallback.publish(scopeId, pageId, content, category, contentHash);
    }
}
