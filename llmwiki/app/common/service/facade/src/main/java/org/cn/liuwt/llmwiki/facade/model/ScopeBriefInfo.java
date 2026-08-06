package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class ScopeBriefInfo {
    private Long scopeId;
    private String scopeName;
    private String scopeType;
    private String role;
    private String language;
}