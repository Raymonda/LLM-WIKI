package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class DuplicateInfo {
    private Long existingSourceId;
    private String existingSourceName;
    private String existingSourceStatus;
    private String message;
}