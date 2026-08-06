package org.cn.liuwt.llmwiki.service.harness.mq;

import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "llmwiki.rocketmq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = ControlMessage.TOPIC,
        consumerGroup = ControlMessage.CONSUMER_GROUP,
        messageModel = MessageModel.BROADCASTING,
        namespace = "${rocketmq.consumer.namespace:}"
)
public class ExecutionCtrlConsumer implements RocketMQListener<ControlMessage> {

    private static final Logger log = LoggerFactory.getLogger(ExecutionCtrlConsumer.class);

    @Autowired
    private ExecutionNodeRegistry registry;

    @Override
    public void onMessage(ControlMessage msg) {
        if (!registry.hasLocalExecution(msg.getExecutionId())) {
            return;
        }

        switch (msg.getAction()) {
            case ControlMessage.ACTION_CANCEL:
                log.info("Received CANCEL control for executionId={} on node {}", msg.getExecutionId(), registry.getNodeId());
                registry.cancelAndRemoveFuture(msg.getExecutionId());
                break;
            case ControlMessage.ACTION_PAUSE:
                log.info("Received PAUSE control for executionId={} on node {}", msg.getExecutionId(), registry.getNodeId());
                registry.cancelAndRemoveFuture(msg.getExecutionId());
                break;
            default:
                log.warn("Unknown control action: {} for executionId={}", msg.getAction(), msg.getExecutionId());
        }
    }
}
