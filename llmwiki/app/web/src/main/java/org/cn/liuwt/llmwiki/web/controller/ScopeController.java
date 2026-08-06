package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeJoinRequestDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.UserDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.UserMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeMemberModel;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeModel;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.facade.model.AddMemberRequest;
import org.cn.liuwt.llmwiki.facade.model.CreateScopeRequest;
import org.cn.liuwt.llmwiki.facade.model.ScopeInfo;
import org.cn.liuwt.llmwiki.facade.model.ScopeMemberInfo;
import org.cn.liuwt.llmwiki.facade.model.ContributorInfo;
import org.cn.liuwt.llmwiki.domain.service.wiki.PromotionService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.cn.liuwt.llmwiki.web.security.RequireScopeRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scope")
public class ScopeController {

    @Autowired
    private ScopeService scopeService;

    @Autowired
    private WikiFileServiceImpl wikiFileService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PromotionService promotionService;

    @PostMapping
    public Result<ScopeInfo> createScope(@RequestBody CreateScopeRequest request) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        ScopeModel scopeModel = new ScopeModel();
        scopeModel.setName(request.getName());
        scopeModel.setDescription(request.getDescription());
        scopeModel.setOwnerId(userId);
        ScopeModel created = scopeService.createScope(scopeModel);
        wikiFileService.initWikiData(created.getId());
        return Result.success(toScopeInfo(created));
    }

    @GetMapping("/{scopeId}")
    public Result<ScopeInfo> getScope(@PathVariable Long scopeId) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (!scopeService.canView(scopeId, userId)) {
            return Result.failed(ErrorCode.AUTH_ACCESS_DENIED);
        }
        ScopeModel scope = scopeService.getScope(scopeId);
        return Result.success(toScopeInfo(scope));
    }

    @GetMapping("/list")
    public Result<List<ScopeInfo>> listScopes() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        List<ScopeModel> scopes = scopeService.listScopesByUserId(userId);
        List<ScopeInfo> infos = scopes.stream().map(this::toScopeInfo).toList();
        return Result.success(infos);
    }

    @PutMapping("/{scopeId}")
    public Result<ScopeInfo> updateScope(@PathVariable Long scopeId, @RequestBody ScopeInfo request) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (!scopeService.isOwnerOrAdmin(scopeId, userId)) {
            return Result.failed(ErrorCode.SCOPE_PERMISSION_UPDATE_CONFIG);
        }
        ScopeModel model = new ScopeModel();
        model.setId(scopeId);
        model.setName(request.getName());
        model.setDescription(request.getDescription());
        model.setMonthlyBudget(request.getMonthlyBudget());
        model.setDefaultApproval(request.getDefaultApproval());
        model.setMaxFileSize(request.getMaxFileSize());
        model.setMaxConcurrent(request.getMaxConcurrent());
        scopeService.updateScope(model);
        ScopeModel updated = scopeService.getScope(scopeId);
        return Result.success(toScopeInfo(updated));
    }

    @DeleteMapping("/{scopeId}")
    public Result<Void> deleteScope(@PathVariable Long scopeId) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        scopeService.deleteScope(scopeId, userId);
        return Result.success(null);
    }

    @PutMapping("/{scopeId}/language")
    public Result<Void> updateScopeLanguage(@PathVariable Long scopeId, @RequestBody Map<String, String> body) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (!scopeService.isOwnerOrAdmin(scopeId, userId)) {
            throw new BusinessException(ErrorCode.AUTH_ACCESS_DENIED);
        }
        String lang = body.get("language");
        if (!"zh-CN".equals(lang) && !"en".equals(lang)) {
            throw new BusinessException(ErrorCode.SCOPE_LANGUAGE_INVALID, lang);
        }
        scopeService.updateLanguage(scopeId, lang);
        return Result.success();
    }

    @PostMapping("/{scopeId}/members")
    @RequireScopeRole({"owner", "admin"})
    public Result<ScopeMemberInfo> addMember(@PathVariable Long scopeId, @RequestBody AddMemberRequest request) {
        Long operatorId = jwtTokenProvider.getCurrentUserId();
        ScopeMemberModel member = scopeService.addMember(scopeId, request.getUserId(), request.getRole(), operatorId);
        return Result.success(toMemberInfo(member));
    }

    @DeleteMapping("/{scopeId}/members/{userId}")
    @RequireScopeRole({"owner", "admin"})
    public Result<Void> removeMember(@PathVariable Long scopeId, @PathVariable Long userId) {
        Long operatorId = jwtTokenProvider.getCurrentUserId();
        scopeService.removeMember(scopeId, userId, operatorId);
        return Result.success(null);
    }

    @PutMapping("/{scopeId}/members/{userId}/role")
    @RequireScopeRole({"owner", "admin"})
    public Result<Void> updateMemberRole(@PathVariable Long scopeId, @PathVariable Long userId, @RequestBody AddMemberRequest request) {
        Long operatorId = jwtTokenProvider.getCurrentUserId();
        scopeService.updateMemberRole(scopeId, userId, request.getRole(), operatorId);
        return Result.success(null);
    }

    @GetMapping("/{scopeId}/members")
    public Result<List<ScopeMemberInfo>> listMembers(@PathVariable Long scopeId) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (!scopeService.canView(scopeId, userId)) {
            return Result.failed(ErrorCode.AUTH_ACCESS_DENIED);
        }
        List<ScopeMemberModel> members = scopeService.listMembers(scopeId);
        List<ScopeMemberInfo> infos = members.stream().map(m -> {
            ScopeMemberInfo info = toMemberInfo(m);
            UserDO user = userMapper.selectById(m.getUserId());
            if (user != null) {
                info.setUserName(user.getUsername());
            }
            return info;
        }).toList();
        return Result.success(infos);
    }

    @GetMapping("/{scopeId}/contributors")
    public Result<List<ContributorInfo>> getContributors(@PathVariable Long scopeId) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (!scopeService.canView(scopeId, userId)) {
            return Result.failed(ErrorCode.AUTH_ACCESS_DENIED);
        }
        List<ContributorInfo> contributors = promotionService.getContributors(scopeId);
        return Result.success(contributors);
    }

    @PostMapping("/{scopeId}/recall")
    public Result<Void> recallPromotedPages(@PathVariable Long scopeId, @RequestBody java.util.Map<String, String> body) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        String pagePath = body.get("pagePath");
        if (pagePath == null || pagePath.isEmpty()) {
            return Result.failed(ErrorCode.SCOPE_PAGE_PATH_REQUIRED);
        }
        promotionService.softRecall(scopeId, pagePath, userId);
        return Result.success();
    }

    @GetMapping("/plaza")
    public Result<List<ScopeInfo>> listPlaza() {
        List<ScopeModel> scopes = scopeService.listOrgVisibleScopes();
        Long userId = jwtTokenProvider.getCurrentUserId();
        List<ScopeInfo> result = scopes.stream().map(s -> {
            ScopeInfo info = toScopeInfo(s);
            info.setMembers(scopeService.listMembers(s.getId()).stream()
                    .map(this::toMemberInfo).toList());
            return info;
        }).toList();
        return Result.success(result);
    }

    @PostMapping("/{scopeId}/join-requests")
    public Result<Void> createJoinRequest(@PathVariable Long scopeId, @RequestBody java.util.Map<String, String> body) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        scopeService.createJoinRequest(scopeId, userId, body.getOrDefault("message", ""));
        return Result.success();
    }

    @GetMapping("/{scopeId}/join-requests")
    public Result<List<ScopeJoinRequestDO>> listJoinRequests(@PathVariable Long scopeId) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (!scopeService.isOwnerOrAdmin(scopeId, userId)) {
            return Result.failed(ErrorCode.SCOPE_PERMISSION_VIEW_REQUESTS);
        }
        return Result.success(scopeService.listPendingRequests(scopeId));
    }

    @PostMapping("/{scopeId}/join-requests/{requestId}/review")
    public Result<Void> reviewJoinRequest(@PathVariable Long scopeId, @PathVariable Long requestId,
                                          @RequestBody java.util.Map<String, Object> body) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (!scopeService.isOwnerOrAdmin(scopeId, userId)) {
            return Result.failed(ErrorCode.SCOPE_PERMISSION_APPROVE_REQUESTS);
        }
        boolean approve = Boolean.TRUE.equals(body.get("approve"));
        String reviewMessage = (String) body.getOrDefault("message", "");
        scopeService.reviewJoinRequest(requestId, scopeId, userId, approve, reviewMessage);
        return Result.success();
    }

    private ScopeInfo toScopeInfo(ScopeModel model) {
        ScopeInfo info = new ScopeInfo();
        info.setId(model.getId());
        info.setName(model.getName());
        info.setDescription(model.getDescription());
        info.setType(model.getType());
        info.setOwnerId(model.getOwnerId());
        info.setMonthlyBudget(model.getMonthlyBudget());
        info.setDefaultApproval(model.getDefaultApproval());
        info.setMaxFileSize(model.getMaxFileSize());
        info.setMaxConcurrent(model.getMaxConcurrent());
        info.setUpstreamScopeIds(model.getUpstreamScopeIds());
        info.setVisibility(model.getVisibility());
        info.setLanguage(model.getLanguage() != null ? model.getLanguage() : "zh-CN");
        info.setCreatedAt(model.getCreatedAt());
        info.setUpdatedAt(model.getUpdatedAt());
        UserDO owner = userMapper.selectById(model.getOwnerId());
        if (owner != null) {
            info.setOwnerName(owner.getUsername());
        }
        return info;
    }

    private ScopeMemberInfo toMemberInfo(ScopeMemberModel model) {
        ScopeMemberInfo info = new ScopeMemberInfo();
        info.setId(model.getId());
        info.setScopeId(model.getScopeId());
        info.setUserId(model.getUserId());
        info.setRole(model.getRole());
        info.setJoinedAt(model.getJoinedAt());
        return info;
    }
}