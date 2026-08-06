package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class RelatedPageInfo {
    private Long id;
    private String title;
    private String path;
    private String summary;
    private String linkType;
    private String direction;
}