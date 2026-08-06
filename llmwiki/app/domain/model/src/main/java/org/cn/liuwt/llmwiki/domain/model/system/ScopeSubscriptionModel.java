package org.cn.liuwt.llmwiki.domain.model.system;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ScopeSubscriptionModel {
    private Long id;
    private Long subscriberScopeId;
    private Long publisherScopeId;
    private String status;
    private String subscriptionType;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
