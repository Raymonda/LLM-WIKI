package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;

import java.util.List;

public interface ExecutionTracker {
    ExecutionModel createExecution(String type, Long scopeId, Long sourceId, Long schemaConfigId);
    ExecutionModel createTaskExecution(String type, Long scopeId, String payloadJson, Long submittedBy);
    List<ExecutionModel> listActiveExecutions(Long scopeId, String type);
    void deleteSteps(Long executionId);
    ExecutionStepModel createStep(Long executionId, String stepName, Integer stepOrder, String approvalLevel);
    void updateExecutionStatus(Long executionId, String status);
    void updateExecutionStatus(Long executionId, String status, String complianceViolations);
    void failExecution(Long executionId, String errorMessage);
    boolean cancelExecution(Long executionId, String reason);
    boolean pauseExecution(Long executionId, String reason);
    void clearStepStartedAt(Long executionId);
    void updateStepStatus(Long stepId, String status);
    void updateStepOutputData(Long stepId, String outputData);
    void completeStep(Long stepId, String outputData, Integer tokensUsed, Integer durationMs);
    void publishStepProgress(Long executionId, Long stepId, String stepName, Integer current, Integer total, Long avgMsPerUnit);
    void publishStepProgress(Long executionId, Long stepId, String stepName, Integer current, Integer total, Long avgMsPerUnit, Integer chunkIndex, String chunkPreview);
    void completeExecution(Long executionId, Integer totalTokens);
    ExecutionModel getExecution(Long executionId);
    List<ExecutionModel> listExecutions(Long scopeId, String type);
    IPage<ExecutionModel> listExecutionsPaged(Long scopeId, String type, List<String> statuses, int page, int size);
    ExecutionStepModel getStep(Long stepId);
    List<ExecutionStepModel> listSteps(Long executionId);
    void resetStepForRetry(Long stepId);
    void resetExecutionForRetry(Long executionId);
    void deleteExecution(Long executionId);
}