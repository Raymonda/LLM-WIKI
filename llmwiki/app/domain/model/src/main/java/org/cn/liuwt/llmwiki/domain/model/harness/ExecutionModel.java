package org.cn.liuwt.llmwiki.domain.model.harness;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ExecutionModel {
    private Long id;
    private String type;
    private String status;
    private Long scopeId;
    private Long sourceId;
    private Long schemaConfigId;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private Integer totalTokens;
    private String errorMessage;
    private String nodeId;
    private Long batchId;
    private String guidance;
    private List<ExecutionStepModel> steps;

    @Data
    public static class ExecutionStepModel {
        private Long id;
        private Long executionId;
        private String stepName;
        private Integer stepOrder;
        private String status;
        private String inputData;
        private String outputData;
        private Integer tokensUsed;
        private Integer durationMs;
        private String approvalLevel;
        private Long approvedBy;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
    }
}