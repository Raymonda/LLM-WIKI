package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ingest_batch")
public class IngestBatchDO {
    private Long id;
    private Long scopeId;
    private Long userId;
    private String status;
    private Integer totalCount;
    private String guidance;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
