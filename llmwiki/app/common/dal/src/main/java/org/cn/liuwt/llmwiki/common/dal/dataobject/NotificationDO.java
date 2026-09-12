package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification")
public class NotificationDO {
    private Long id;
    private Long userId;
    private String type;
    private String title;
    private String content;
    private Long scopeId;
    private Long relatedPageId;
    private Long executionId;
    private Long batchId;
    private Integer isRead;
    private LocalDateTime createdAt;
}