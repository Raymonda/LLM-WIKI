package org.cn.liuwt.llmwiki.domain.model.wiki;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WikiPageModel {
    private Long id;
    private String title;
    private String path;
    private String content;
    private String category;
    private String summary;
    private Long scopeId;
    private Integer sourceCount;
    private String healthStatus;
    private String visibility;
    private Long promotedFromScopeId;
    private Long promotedFromPageId;
    private String promotedFromUsername;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime contentUpdatedAt;
    private LocalDateTime lastModified;
    private String pageType;

    private String lifecycleStatus;

    private LocalDateTime deprecatedAt;
    private String deprecatedReason;
    private LocalDateTime deletedAt;
    private Long mergedIntoPageId;
    private Integer userModified;

    private String postSaveStatus;
    private String postSaveError;
}