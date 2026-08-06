package org.cn.liuwt.llmwiki.domain.model.system;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ScopeMemberModel {
    private Long id;
    private Long scopeId;
    private Long userId;
    private String role;
    private LocalDateTime joinedAt;
}