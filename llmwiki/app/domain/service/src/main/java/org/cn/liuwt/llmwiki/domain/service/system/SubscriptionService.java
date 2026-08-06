package org.cn.liuwt.llmwiki.domain.service.system;

import org.cn.liuwt.llmwiki.domain.model.system.ScopeSubscriptionModel;

import java.util.List;

public interface SubscriptionService {
    ScopeSubscriptionModel createSubscription(Long subscriberScopeId, Long publisherScopeId, Long createdBy);
    List<ScopeSubscriptionModel> listSubscriptions(Long subscriberScopeId);
    List<ScopeSubscriptionModel> listSubscribers(Long publisherScopeId);
    void cancelSubscription(Long subscriptionId, Long operatorScopeId, Long operatorId);
    List<Long> getSubscribedScopeIds(Long subscriberScopeId);
    boolean isSubscribed(Long subscriberScopeId, Long publisherScopeId);
    ScopeSubscriptionModel getSubscription(Long subscriptionId);
}
