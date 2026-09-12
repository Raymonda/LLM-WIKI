package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class NotificationInfo {
    private Long id;
    private String type;
    private String title;
    private String content;
    private Long scopeId;
    private Long relatedPageId;
    private Long executionId;
    private Long batchId;
    private Integer isRead;
    private String createdAt;
}