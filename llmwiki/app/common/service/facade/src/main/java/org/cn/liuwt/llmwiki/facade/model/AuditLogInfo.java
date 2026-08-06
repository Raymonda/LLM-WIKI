package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditLogInfo {
    private Long id;
    private Long actorUserId;
    private String actorUsername;
    private String action;
    private String targetType;
    private Long targetId;
    private String targetName;
    private Long scopeId;
    private String detailJson;
    private String ipAddress;
    private LocalDateTime createdAt;
}
