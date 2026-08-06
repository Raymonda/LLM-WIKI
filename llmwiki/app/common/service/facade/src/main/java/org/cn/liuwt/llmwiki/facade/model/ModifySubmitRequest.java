package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class ModifySubmitRequest {
    private Long pageId;
    private String instruction;
    private boolean overrideSchema;
}
