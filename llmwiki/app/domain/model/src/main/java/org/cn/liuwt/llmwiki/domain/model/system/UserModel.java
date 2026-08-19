package org.cn.liuwt.llmwiki.domain.model.system;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserModel {
    private Long id;
    private String username;
    private String passwordHash;
    private String email;
    private String avatar;
    private String role;
    private String status;
    private Long scopeId;
    private String language;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
