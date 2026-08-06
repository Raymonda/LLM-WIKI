package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class CreateUserRequest {
    private String userName;
    private String email;
    private String password;
    private String role;
}
