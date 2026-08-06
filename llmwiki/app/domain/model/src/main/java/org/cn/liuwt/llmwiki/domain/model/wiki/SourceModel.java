package org.cn.liuwt.llmwiki.domain.model.wiki;

import org.cn.liuwt.llmwiki.facade.model.DuplicateInfo;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SourceModel {
    private Long id;
    private String name;
    private String filePath;
    private String format;
    private Long size;
    private String status;
    private Long scopeId;
    private Long uploadUserId;
    private LocalDateTime createdAt;
    private LocalDateTime fileModifiedAt;
    private String contentHash;
    private DuplicateInfo duplicateInfo;
}