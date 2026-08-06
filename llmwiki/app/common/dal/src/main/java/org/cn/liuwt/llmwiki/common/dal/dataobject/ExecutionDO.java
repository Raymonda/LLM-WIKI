package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("execution")
public class ExecutionDO {
    private Long id;
    private String type;
    private String status;
    private Long scopeId;
    private Long sourceId;
    private Long schemaConfigId;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer totalTokens;
    private java.math.BigDecimal totalCost;
    private LocalDateTime createdAt;
    private String errorMessage;
    private String nodeId;
}