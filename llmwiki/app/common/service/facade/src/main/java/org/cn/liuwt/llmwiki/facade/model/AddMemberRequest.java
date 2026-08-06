package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class AddMemberRequest {
    private Long userId;
    private String role;
}