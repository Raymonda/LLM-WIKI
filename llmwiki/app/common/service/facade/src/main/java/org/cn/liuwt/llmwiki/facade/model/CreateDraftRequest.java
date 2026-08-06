package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class CreateDraftRequest {
    private Long pageId;
    private String title;
    private String content;
    private String category;
    private List<String> tags;
    private String pageType;
}
