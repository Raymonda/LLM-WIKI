package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class UserInfo {
    private Long id;
    private String userName;
    private String email;
    private String role;
    private String systemRole;
    private Long scopeId;
    private String language;
    private List<ScopeBriefInfo> scopes;
}
