package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ScopeInfo {
    private Long id;
    private String name;
    private String description;
    private String type;
    private Long ownerId;
    private String ownerName;
    private Integer monthlyBudget;
    private String defaultApproval;
    private Integer maxFileSize;
    private Integer maxConcurrent;
    private String upstreamScopeIds;
    private String visibility;
    private String language;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<ScopeMemberInfo> members;
}