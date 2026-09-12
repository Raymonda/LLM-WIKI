package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class TaskReceiptInfo {
    private Long executionId;
    private String taskType;
    private String status;
}
