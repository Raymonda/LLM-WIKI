package org.cn.liuwt.llmwiki.domain.model.harness;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SchemaPatchModel {
    private Long id;
    private Long scopeId;
    private Long sourceExecutionId;
    private String sourceType;
    private String sectionTitle;
    private String operation;
    private String diffBefore;
    private String diffAfter;
    private String rationale;
    private String evidenceJson;
    private BigDecimal confidence;
    private String status;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private Long appliedSchemaId;
    private String gatekeeperDecision;
    private String gatekeeperReason;
    private LocalDateTime createdAt;

    public enum SourceType { INGEST, QUERY, LINT, MANUAL }

    public enum Operation { ADD, MODIFY, DELETE }

    public enum Status { PENDING, OBSERVING, ACCEPTED, REJECTED, IGNORED, SUPERSEDED, EXPIRED, CLEARED }
}
