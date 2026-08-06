package org.cn.liuwt.llmwiki.domain.service.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeSubscriptionDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeSubscriptionMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeSubscriptionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SubscriptionServiceImpl implements SubscriptionService {

    @Autowired
    private ScopeSubscriptionMapper subscriptionMapper;

    @Autowired
    private ScopeService scopeService;

    @Override
    public ScopeSubscriptionModel createSubscription(Long subscriberScopeId, Long publisherScopeId, Long createdBy) {
        if (subscriberScopeId.equals(publisherScopeId)) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_SELF_SUBSCRIBE);
        }
        scopeService.getScope(publisherScopeId);
        ScopeSubscriptionDO existing = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<ScopeSubscriptionDO>()
                        .eq(ScopeSubscriptionDO::getSubscriberScopeId, subscriberScopeId)
                        .eq(ScopeSubscriptionDO::getPublisherScopeId, publisherScopeId));
        if (existing != null) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_DUPLICATE);
        }
        ScopeSubscriptionDO subDO = new ScopeSubscriptionDO();
        subDO.setSubscriberScopeId(subscriberScopeId);
        subDO.setPublisherScopeId(publisherScopeId);
        subDO.setStatus("active");
        subDO.setSubscriptionType("mirror");
        subDO.setCreatedBy(createdBy);
        subscriptionMapper.insert(subDO);
        return toModel(subDO);
    }

    @Override
    public List<ScopeSubscriptionModel> listSubscriptions(Long subscriberScopeId) {
        List<ScopeSubscriptionDO> list = subscriptionMapper.selectList(
                new LambdaQueryWrapper<ScopeSubscriptionDO>()
                        .eq(ScopeSubscriptionDO::getSubscriberScopeId, subscriberScopeId)
                        .eq(ScopeSubscriptionDO::getStatus, "active")
                        .orderByDesc(ScopeSubscriptionDO::getCreatedAt));
        return list.stream().map(this::toModel).toList();
    }

    @Override
    public List<ScopeSubscriptionModel> listSubscribers(Long publisherScopeId) {
        List<ScopeSubscriptionDO> list = subscriptionMapper.selectList(
                new LambdaQueryWrapper<ScopeSubscriptionDO>()
                        .eq(ScopeSubscriptionDO::getPublisherScopeId, publisherScopeId)
                        .eq(ScopeSubscriptionDO::getStatus, "active")
                        .orderByDesc(ScopeSubscriptionDO::getCreatedAt));
        return list.stream().map(this::toModel).toList();
    }

    @Override
    public void cancelSubscription(Long subscriptionId, Long operatorScopeId, Long operatorId) {
        ScopeSubscriptionDO sub = subscriptionMapper.selectById(subscriptionId);
        if (sub == null) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND);
        }
        if (!sub.getSubscriberScopeId().equals(operatorScopeId)) {
            throw new BusinessException(ErrorCode.AUTH_ACCESS_DENIED);
        }
        subscriptionMapper.deleteById(subscriptionId);
    }

    @Override
    public List<Long> getSubscribedScopeIds(Long subscriberScopeId) {
        List<ScopeSubscriptionDO> list = subscriptionMapper.selectList(
                new LambdaQueryWrapper<ScopeSubscriptionDO>()
                        .select(ScopeSubscriptionDO::getPublisherScopeId)
                        .eq(ScopeSubscriptionDO::getSubscriberScopeId, subscriberScopeId)
                        .eq(ScopeSubscriptionDO::getStatus, "active"));
        return list.stream().map(ScopeSubscriptionDO::getPublisherScopeId).toList();
    }

    @Override
    public boolean isSubscribed(Long subscriberScopeId, Long publisherScopeId) {
        Long count = subscriptionMapper.selectCount(
                new LambdaQueryWrapper<ScopeSubscriptionDO>()
                        .eq(ScopeSubscriptionDO::getSubscriberScopeId, subscriberScopeId)
                        .eq(ScopeSubscriptionDO::getPublisherScopeId, publisherScopeId)
                        .eq(ScopeSubscriptionDO::getStatus, "active"));
        return count != null && count > 0;
    }

    @Override
    public ScopeSubscriptionModel getSubscription(Long subscriptionId) {
        ScopeSubscriptionDO sub = subscriptionMapper.selectById(subscriptionId);
        if (sub == null) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND);
        }
        return toModel(sub);
    }

    private ScopeSubscriptionModel toModel(ScopeSubscriptionDO subDO) {
        ScopeSubscriptionModel model = new ScopeSubscriptionModel();
        model.setId(subDO.getId());
        model.setSubscriberScopeId(subDO.getSubscriberScopeId());
        model.setPublisherScopeId(subDO.getPublisherScopeId());
        model.setStatus(subDO.getStatus());
        model.setSubscriptionType(subDO.getSubscriptionType());
        model.setCreatedBy(subDO.getCreatedBy());
        model.setCreatedAt(subDO.getCreatedAt());
        model.setUpdatedAt(subDO.getUpdatedAt());
        return model;
    }
}
