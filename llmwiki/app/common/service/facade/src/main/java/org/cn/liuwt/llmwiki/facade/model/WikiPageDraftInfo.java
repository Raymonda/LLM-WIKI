package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class WikiPageDraftInfo {
    private Long id;
    private Long scopeId;
    private Long pageId;
    private String baseContentHash;
    private String title;
    private String content;
    private String category;
    private List<String> tags;
    private String pageType;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
