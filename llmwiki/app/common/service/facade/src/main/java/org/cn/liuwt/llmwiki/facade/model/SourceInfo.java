package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SourceInfo {
    private Long id;
    private String name;
    private String filePath;
    private String format;
    private Long size;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime fileModifiedAt;
    private String contentHash;
    private String lifecycleStatus;
    private LocalDateTime deprecatedAt;
    private String deprecatedCategory;
    private String deprecatedReason;
    private Long deprecatedBy;
    private DuplicateInfo duplicateInfo;
}