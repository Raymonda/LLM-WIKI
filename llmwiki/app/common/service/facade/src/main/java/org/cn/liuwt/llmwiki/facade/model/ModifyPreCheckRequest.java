package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class ModifyPreCheckRequest {
    private Long pageId;
    private String instruction;
}
