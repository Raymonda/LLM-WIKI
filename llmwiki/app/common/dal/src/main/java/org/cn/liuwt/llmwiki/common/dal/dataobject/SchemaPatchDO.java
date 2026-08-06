package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("schema_patch")
public class SchemaPatchDO {
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
}
