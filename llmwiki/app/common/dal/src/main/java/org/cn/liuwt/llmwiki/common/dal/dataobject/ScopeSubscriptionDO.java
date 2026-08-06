package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scope_subscription")
public class ScopeSubscriptionDO {
    private Long id;
    private Long subscriberScopeId;
    private Long publisherScopeId;
    private String status;
    private String subscriptionType;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
