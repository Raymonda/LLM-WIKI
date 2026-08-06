package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class SavePageRequest {
    private Long draftId;
    private Long pageId;
    private Long sessionId;
    private String title;
    private String content;
    private String category;
    private List<String> tags;
    private String saveMode;
}
