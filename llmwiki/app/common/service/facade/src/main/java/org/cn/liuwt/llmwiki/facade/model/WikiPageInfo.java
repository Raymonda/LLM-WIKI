package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class WikiPageInfo {
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
    private List<String> tags;
    private List<String> keywords;
    private List<SourceInfo> sources;
    private List<WikiPageInfo> relatedPages;
    private Map<String, Long> linkResolution;

    private String pageType;
    private String lifecycleStatus;

    private LocalDateTime deprecatedAt;
    private String deprecatedReason;
    private LocalDateTime deletedAt;
    private Long mergedIntoPageId;
    private Integer userModified;

    private Integer inboundLinkCount;
    private Integer outboundLinkCount;

    private String postSaveStatus;
    private String postSaveError;

    private String sourceScopeName;
}