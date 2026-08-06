package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("search_index_retry")
public class SearchIndexRetryDO {
    private Long id;
    private Long scopeId;
    private Long pageId;
    private String operation;
    private String payload;
    private Integer retryCount;
    private Integer maxAttempts;
    private String status;
    private String lastError;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
