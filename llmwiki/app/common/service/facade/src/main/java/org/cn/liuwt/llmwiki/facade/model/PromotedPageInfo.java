package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class PromotedPageInfo {
    private Long pageId;
    private String title;
    private String path;
    private Long targetScopeId;
    private String targetScopeName;
    private String promotedAt;
}