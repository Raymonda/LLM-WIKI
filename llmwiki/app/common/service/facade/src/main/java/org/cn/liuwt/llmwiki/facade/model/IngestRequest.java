package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class IngestRequest {
    private Long scopeId;
    private Long sourceId;
    private String guidance;
}