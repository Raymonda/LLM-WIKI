package org.cn.liuwt.llmwiki.service.harness.mq;

import org.cn.liuwt.llmwiki.domain.service.wiki.PostSaveTaskPublisher;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PostSaveTaskPublisher 的 RocketMQ 实现。
 * 仅在 MQ 启用时生效（@ConditionalOnProperty）。
 */
@Component
@ConditionalOnProperty(name = "llmwiki.rocketmq.enabled", havingValue = "true")
public class PostSaveTaskPublisherImpl implements PostSaveTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(PostSaveTaskPublisherImpl.class);

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

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
            rocketMQTemplate.convertAndSend(PipelineTaskMessage.TOPIC, msg);
            log.info("Published PAGE_SAVE_POST: scopeId={}, pageId={}, contentHash={}", scopeId, pageId, contentHash);
        } catch (Exception e) {
            log.error("Failed to publish PAGE_SAVE_POST message: pageId={}", pageId, e);
            throw e;
        }
    }
}
