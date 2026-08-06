package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

public interface DistributedEventPublisher {

    void publishExecutionStatus(Long executionId, String executionType, String newStatus, String complianceViolations);

    void publishStepStatus(Long executionId, String executionType, Long stepId, String stepName, String status, String outputData);

    void publishStepProgress(Long executionId, String executionType, Long stepId, String stepName,
                             Integer current, Integer total, Long avgMsPerUnit,
                             Integer chunkIndex, String chunkPreview);
}
