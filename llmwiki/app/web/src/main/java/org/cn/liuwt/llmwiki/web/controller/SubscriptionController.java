package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.UserDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.UserMapper;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeSubscriptionModel;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.domain.service.system.SubscriptionService;
import org.cn.liuwt.llmwiki.facade.model.CreateSubscriptionRequest;
import org.cn.liuwt.llmwiki.facade.model.SubscriptionInfo;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.cn.liuwt.llmwiki.web.security.RequireScopeRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Deprecated
@RestController
@RequestMapping("/api/scope")
public class SubscriptionController {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private ScopeService scopeService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ScopeMapper scopeMapper;

    @Autowired
    private UserMapper userMapper;

    @PostMapping("/{scopeId}/subscriptions")
    @RequireScopeRole({"owner", "admin"})
    public Result<SubscriptionInfo> createSubscription(
            @PathVariable Long scopeId,
            @RequestBody CreateSubscriptionRequest request) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        ScopeSubscriptionModel model = subscriptionService.createSubscription(
                scopeId, request.getPublisherScopeId(), userId);
        return Result.success(toInfo(model));
    }

    @GetMapping("/{scopeId}/subscriptions")
    public Result<List<SubscriptionInfo>> listSubscriptions(@PathVariable Long scopeId) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (!scopeService.canView(scopeId, userId)) {
            return Result.failed(ErrorCode.AUTH_ACCESS_DENIED);
        }
        List<ScopeSubscriptionModel> models = subscriptionService.listSubscriptions(scopeId);
        List<SubscriptionInfo> infos = models.stream().map(this::toInfo).toList();
        return Result.success(infos);
    }

    @DeleteMapping("/{scopeId}/subscriptions/{subId}")
    @RequireScopeRole({"owner", "admin"})
    public Result<Void> cancelSubscription(@PathVariable Long scopeId, @PathVariable Long subId) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        subscriptionService.cancelSubscription(subId, scopeId, userId);
        return Result.success(null);
    }

    @GetMapping("/{scopeId}/subscribers")
    @RequireScopeRole({"owner", "admin"})
    public Result<List<SubscriptionInfo>> listSubscribers(@PathVariable Long scopeId) {
        List<ScopeSubscriptionModel> models = subscriptionService.listSubscribers(scopeId);
        List<SubscriptionInfo> infos = models.stream().map(this::toInfo).toList();
        return Result.success(infos);
    }

    private SubscriptionInfo toInfo(ScopeSubscriptionModel model) {
        SubscriptionInfo info = new SubscriptionInfo();
        info.setId(model.getId());
        info.setSubscriberScopeId(model.getSubscriberScopeId());
        info.setPublisherScopeId(model.getPublisherScopeId());
        info.setStatus(model.getStatus());
        info.setSubscriptionType(model.getSubscriptionType());
        info.setCreatedBy(model.getCreatedBy());
        info.setCreatedAt(model.getCreatedAt());
        info.setUpdatedAt(model.getUpdatedAt());

        ScopeDO subScope = scopeMapper.selectById(model.getSubscriberScopeId());
        if (subScope != null) {
            info.setSubscriberScopeName(subScope.getName());
        }
        ScopeDO pubScope = scopeMapper.selectById(model.getPublisherScopeId());
        if (pubScope != null) {
            info.setPublisherScopeName(pubScope.getName());
        }
        if (model.getCreatedBy() != null) {
            UserDO user = userMapper.selectById(model.getCreatedBy());
            if (user != null) {
                info.setCreatedByName(user.getUsername());
            }
        }
        return info;
    }
}
