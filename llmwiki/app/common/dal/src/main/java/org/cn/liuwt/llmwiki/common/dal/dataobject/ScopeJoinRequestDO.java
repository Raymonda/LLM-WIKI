package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scope_join_request")
public class ScopeJoinRequestDO {
    private Long id;
    private Long scopeId;
    private Long userId;
    private String message;
    private String status;
    private Long reviewerId;
    private String reviewMessage;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
}
