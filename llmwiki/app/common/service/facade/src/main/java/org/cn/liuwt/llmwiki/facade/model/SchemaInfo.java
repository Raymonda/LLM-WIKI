package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SchemaInfo {
    private Long id;
    private String configKey;
    private String configValue;
    private String configGroup;
    private Long scopeId;
    private String description;
    private LocalDateTime updatedAt;
}