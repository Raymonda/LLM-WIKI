package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class EditSessionInfo {
    private Long id;
    private Long scopeId;
    private Long pageId;
    private Long draftId;
    private String currentContent;
    private String contentHash;
    private String outline;
    private Integer stepCount;
    private String status;
    private List<EditStepInfo> steps;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
