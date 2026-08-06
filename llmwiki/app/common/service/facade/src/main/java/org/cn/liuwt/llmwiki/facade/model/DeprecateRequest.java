package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class DeprecateRequest {
    private Long pageId;
    private String reason;
}
