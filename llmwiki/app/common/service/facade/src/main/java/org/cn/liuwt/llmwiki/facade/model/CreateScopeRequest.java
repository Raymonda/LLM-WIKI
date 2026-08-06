package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class CreateScopeRequest {
    private String name;
    private String description;
}