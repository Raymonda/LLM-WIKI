package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
}