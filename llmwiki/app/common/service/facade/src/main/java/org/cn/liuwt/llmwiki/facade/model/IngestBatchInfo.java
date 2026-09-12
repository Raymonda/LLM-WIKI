package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IngestBatchInfo {
    private Long batchId;
    private String status;
    private Integer totalCount;
    private String guidance;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private int awaitingCount;
    private int pendingCount;
    private int runningCount;
    private int confirmedCount;
    private int completedCount;
    private int failedCount;
    private int cancelledCount;
}
