package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SearchResultInfo {
    public enum ResultType {
        WIKI_PAGE
    }

    private Long id;
    private String title;
    private String path;
    private String category;
    private String summary;
    private String healthStatus;
    private Double score;
    private String pageType;
    private String lifecycleStatus;
    private LocalDateTime deprecatedAt;
    private List<String> highlightedTitle;
    private List<String> highlightedSummary;
    private List<String> highlightedContent;

    private Long sourceScopeId;
    private String sourceScopeName;

    private ResultType resultType = ResultType.WIKI_PAGE;
}