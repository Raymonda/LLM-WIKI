package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class IngestBatchCreateResponse {
    private Long batchId;
    private List<Long> executionIds;
    private List<String> warnings;
}
