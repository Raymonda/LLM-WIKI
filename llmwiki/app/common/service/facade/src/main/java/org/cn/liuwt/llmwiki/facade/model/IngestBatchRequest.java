package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class IngestBatchRequest {
    private Long scopeId;
    private List<Long> sourceIds;
    private String guidance;
}
