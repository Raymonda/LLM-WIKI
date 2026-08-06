package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ScopeMemberInfo {
    private Long id;
    private Long scopeId;
    private Long userId;
    private String userName;
    private String role;
    private LocalDateTime joinedAt;
}