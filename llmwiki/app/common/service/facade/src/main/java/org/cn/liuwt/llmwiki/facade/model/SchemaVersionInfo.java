package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SchemaVersionInfo {
    private Long id;
    private Long scopeId;
    private String configKey;
    private String configValue;
    private Long parentVersionId;
    private Integer versionNumber;
    private String sourceType;
    private Long sourceOpId;
    private Long createdBy;
    private LocalDateTime createdAt;
}
