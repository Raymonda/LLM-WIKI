package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("execution_event")
public class ExecutionEventDO {
    private Long id;
    private String executionId;
    private Integer seq;
    private String eventType;
    private String payloadJson;
    private String nodeId;
    private LocalDateTime createdAt;
}
