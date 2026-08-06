package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserManageInfo {
    private Long id;
    private String userName;
    private String email;
    private String role;
    private String status;
    private Long scopeId;
    private Integer consentKnowledgePromotion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
