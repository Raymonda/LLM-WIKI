package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lint_finding")
public class LintFindingDO {
    private Long id;
    private Long scopeId;
    private String pagePath;
    private Long assetId;
    private String findingType;
    private String priority;
    private String title;
    private String detail;
    private String extra;
    private String rulingBriefJson;
    private String userFeedback;
    private Integer feedbackCount;
    private LocalDateTime archivedAt;
    private String handlingMethod;
    private Integer riskScore;
    private LocalDateTime autoResolvedAt;
    private Long repairExecutionId;
    private String status;
    private Long executionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String orphanDiagnosis;
}
