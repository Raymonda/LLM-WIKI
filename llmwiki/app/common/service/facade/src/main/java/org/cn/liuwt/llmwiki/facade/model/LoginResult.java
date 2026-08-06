package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class LoginResult {
    private String token;
    private UserInfo user;
}