package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class IngestBatchDetailInfo {
    private Long batchId;
    private String status;
    private Integer totalCount;
    private String guidance;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private List<IngestBatchItemInfo> items;
    private long total;
    private int page;
    private int size;
}
