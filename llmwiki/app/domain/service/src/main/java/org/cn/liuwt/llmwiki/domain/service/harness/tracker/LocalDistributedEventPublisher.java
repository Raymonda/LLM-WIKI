package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "llmwiki.rocketmq.enabled", havingValue = "false", matchIfMissing = true)
public class LocalDistributedEventPublisher implements DistributedEventPublisher {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public void publishExecutionStatus(Long executionId, String executionType, String newStatus, String complianceViolations) {
        eventPublisher.publishEvent(new ExecutionStatusEvent(this, executionId, executionType, newStatus, complianceViolations));
    }

    @Override
    public void publishStepStatus(Long executionId, String executionType, Long stepId, String stepName, String status, String outputData) {
        eventPublisher.publishEvent(new StepStatusEvent(this, executionId, executionType, stepId, stepName, status, outputData));
    }

    @Override
    public void publishStepProgress(Long executionId, String executionType, Long stepId, String stepName,
                                    Integer current, Integer total, Long avgMsPerUnit,
                                    Integer chunkIndex, String chunkPreview) {
        eventPublisher.publishEvent(new StepProgressEvent(this, executionId, executionType, stepId, stepName,
                current, total, avgMsPerUnit, chunkIndex, chunkPreview));
    }
}
