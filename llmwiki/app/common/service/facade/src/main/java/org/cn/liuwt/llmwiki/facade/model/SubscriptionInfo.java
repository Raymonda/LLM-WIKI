package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SubscriptionInfo {
    private Long id;
    private Long subscriberScopeId;
    private String subscriberScopeName;
    private Long publisherScopeId;
    private String publisherScopeName;
    private String status;
    private String subscriptionType;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
