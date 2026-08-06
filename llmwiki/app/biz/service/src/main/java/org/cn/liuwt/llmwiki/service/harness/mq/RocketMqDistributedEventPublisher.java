package org.cn.liuwt.llmwiki.service.harness.mq;

import org.cn.liuwt.llmwiki.domain.service.harness.tracker.DistributedEventPublisher;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "llmwiki.rocketmq.enabled", havingValue = "true")
public class RocketMqDistributedEventPublisher implements DistributedEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqDistributedEventPublisher.class);

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Override
    public void publishExecutionStatus(Long executionId, String executionType, String newStatus, String complianceViolations) {
        ExecutionEventMessage msg = new ExecutionEventMessage();
        msg.setEventType(ExecutionEventMessage.TYPE_EXECUTION_STATUS);
        msg.setExecutionId(executionId);
        msg.setExecutionType(executionType);
        msg.setStatus(newStatus);
        msg.setComplianceViolations(complianceViolations);
        msg.setPublishedAt(System.currentTimeMillis());
        send(executionId, msg);
    }

    @Override
    public void publishStepStatus(Long executionId, String executionType, Long stepId, String stepName, String status, String outputData) {
        ExecutionEventMessage msg = new ExecutionEventMessage();
        msg.setEventType(ExecutionEventMessage.TYPE_STEP_STATUS);
        msg.setExecutionId(executionId);
        msg.setExecutionType(executionType);
        msg.setStepId(stepId);
        msg.setStepName(stepName);
        msg.setStatus(status);
        msg.setPublishedAt(System.currentTimeMillis());
        send(executionId, msg);
    }

    @Override
    public void publishStepProgress(Long executionId, String executionType, Long stepId, String stepName,
                                     Integer current, Integer total, Long avgMsPerUnit,
                                     Integer chunkIndex, String chunkPreview) {
        ExecutionEventMessage msg = new ExecutionEventMessage();
        msg.setEventType(ExecutionEventMessage.TYPE_STEP_PROGRESS);
        msg.setExecutionId(executionId);
        msg.setExecutionType(executionType);
        msg.setStepId(stepId);
        msg.setStepName(stepName);
        msg.setCurrent(current);
        msg.setTotal(total);
        msg.setAvgMsPerUnit(avgMsPerUnit);
        msg.setChunkIndex(chunkIndex);
        msg.setChunkPreview(chunkPreview);
        msg.setPublishedAt(System.currentTimeMillis());
        send(executionId, msg);
    }

    private void send(Long executionId, ExecutionEventMessage msg) {
        try {
            String destination = ExecutionEventMessage.TOPIC + ":" + executionId;
            rocketMQTemplate.convertAndSend(destination, msg);
        } catch (Exception e) {
            log.error("Failed to publish execution event to RocketMQ, executionId={}, eventType={}",
                    executionId, msg.getEventType(), e);
        }
    }
}
