package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ExecutionInfo {
    private Long executionId;
    private String operationType;
    private String status;
    private Long scopeId;
    private Long sourceId;
    private Long schemaConfigId;
    private Long batchId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createdAt;
    private Integer totalTokens;
    private String errorMessage;
    private String sourceName;
    private Integer totalSteps;
    private Integer completedSteps;
    private String currentStepName;
    private List<ExecutionStepInfo> steps;
    private Map<String, Long> baselineProfile;

    @Data
    public static class ExecutionStepInfo {
        private Long stepId;
        private String stepName;
        private Integer stepOrder;
        private String status;
        private String inputData;
        private String outputData;
        private Integer tokensUsed;
        private Integer durationMs;
        private String approvalLevel;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
    }
}