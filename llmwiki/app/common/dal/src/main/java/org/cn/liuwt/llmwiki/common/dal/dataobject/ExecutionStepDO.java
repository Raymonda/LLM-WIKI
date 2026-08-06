package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("execution_step")
public class ExecutionStepDO {
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