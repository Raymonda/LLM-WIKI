package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class AiEditRequest {
    private Long sessionId;
    private String selectedLines;
    private String selectedText;
    private String instruction;
    private List<Long> confirmedKnowledgeIds;
    private String expectedHash;
}
