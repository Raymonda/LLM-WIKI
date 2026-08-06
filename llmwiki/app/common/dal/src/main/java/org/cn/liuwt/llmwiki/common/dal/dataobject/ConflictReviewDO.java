package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conflict_review")
public class ConflictReviewDO {
    private Long id;
    private Long scopeId;
    private String sourceType;
    private Long sourceExecutionId;
    private Long fromPageId;
    private Long toPageId;
    private String fromPageTitle;
    private String toPageTitle;
    private String conflictType;
    private String strategyKey;
    private String strategyLabel;
    private String routeReason;
    private String rulingAction;
    private String rulingDetail;
    private String status;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private LocalDateTime executedAt;
    private String executionError;
    private LocalDateTime createdAt;
}
