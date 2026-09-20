package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class IngestBatchItemInfo {
    private Long executionId;
    private Long sourceId;
    private String sourceName;
    private String sourceFormat;
    private String status;
    private Integer totalTokens;
    private String errorMessage;
    private String analyzeOutput;
    private String guidance;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Boolean phase1Completed;
    private Map<String, Object> autoDecision;
    private Integer qualityCritical;
    private Integer qualityWarnings;
    private String errorSummary;
}
