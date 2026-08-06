package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scope")
public class ScopeDO {
    private Long id;
    private String name;
    private String description;
    private String type;
    private Long ownerId;
    private Integer monthlyBudget;
    private String defaultApproval;
    private Integer maxFileSize;
    private Integer maxConcurrent;
    private String upstreamScopeIds;
    private String visibility;
    private String language;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}