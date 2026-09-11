package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class DeprecateSourceRequest {
    private String category;
    private String reason;
}
