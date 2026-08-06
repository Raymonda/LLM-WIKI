package org.cn.liuwt.llmwiki.domain.model.system;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ScopeModel {
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