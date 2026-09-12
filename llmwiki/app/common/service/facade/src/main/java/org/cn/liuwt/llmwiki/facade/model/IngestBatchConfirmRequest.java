package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class IngestBatchConfirmRequest {
    private List<Long> executionIds;
}
