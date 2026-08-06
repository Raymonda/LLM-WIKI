package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.UserDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.UserMapper;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeModel;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.facade.model.LoginRequest;
import org.cn.liuwt.llmwiki.facade.model.LoginResult;
import org.cn.liuwt.llmwiki.facade.model.ScopeBriefInfo;
import org.cn.liuwt.llmwiki.facade.model.UserInfo;
import org.cn.liuwt.llmwiki.domain.model.system.UserModel;
import org.cn.liuwt.llmwiki.service.auth.AuthService;
import org.cn.liuwt.llmwiki.domain.service.system.UserService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Autowired
    private AuthService authService;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ScopeService scopeService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ScopeMapper scopeMapper;

    @PostMapping("/login")
    public Result<LoginResult> login(@RequestBody LoginRequest request) {
        UserModel user = authService.authenticate(request.getUsername(), request.getPassword());
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        LoginResult result = new LoginResult();
        result.setToken(token);
        result.setUser(toUserInfo(user));
        return Result.success(result);
    }

    @PostMapping("/register")
    public Result<LoginResult> register(@RequestBody LoginRequest request) {
        UserModel user = authService.register(request.getUsername(), request.getPassword(), null, request.getConsentKnowledgePromotion());
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        LoginResult result = new LoginResult();
        result.setToken(token);
        result.setUser(toUserInfo(user));
        return Result.success(result);
    }

    @PostMapping("/info")
    public Result<UserInfo> getUserInfo() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        if (userId == null || scopeId == null) {
            return Result.failed(ErrorCode.AUTH_NOT_LOGGED_IN);
        }
        String username = jwtTokenProvider.getCurrentUsername();
        UserInfo info = new UserInfo();
        info.setId(userId);
        info.setUserName(username);
        info.setScopeId(scopeId);
        String role = scopeService.getMemberRole(scopeId, userId);
        info.setRole(role != null ? role : "owner");
        UserDO userDO = userMapper.selectById(userId);
        if (userDO != null) {
            info.setEmail(userDO.getEmail());
            info.setSystemRole(userDO.getRole());
            info.setConsentKnowledgePromotion(userDO.getConsentKnowledgePromotion());
            info.setLanguage(userDO.getLanguage() != null ? userDO.getLanguage() : "zh-CN");
        }
        info.setScopes(buildScopeList(userId));
        return Result.success(info);
    }

    @PutMapping("/language")
    public Result<Void> updateLanguage(@RequestBody Map<String, String> body) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        String lang = body.get("language");
        if (!"zh-CN".equals(lang) && !"en".equals(lang)) {
            throw new BusinessException(ErrorCode.SCOPE_LANGUAGE_INVALID, lang);
        }
        UserDO userDO = userMapper.selectById(userId);
        if (userDO != null) {
            userDO.setLanguage(lang);
            userMapper.updateById(userDO);
        }
        return Result.success();
    }

    @PutMapping("/consent-promotion")
    public Result<Void> updateConsentPromotion(@RequestBody java.util.Map<String, Integer> body) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        Integer consent = body.get("consent");
        if (consent == null || (consent != 0 && consent != 1)) {
            return Result.failed(ErrorCode.AUTH_INVALID_CONSENT);
        }
        userService.updateConsentKnowledgePromotion(userId, consent);
        return Result.success();
    }

    private UserInfo toUserInfo(UserModel user) {
        UserInfo info = new UserInfo();
        info.setId(user.getId());
        info.setUserName(user.getUsername());
        info.setEmail(user.getEmail());
        String scopeRole = scopeService.getMemberRole(user.getScopeId(), user.getId());
        info.setRole(scopeRole != null ? scopeRole : "owner");
        info.setSystemRole(user.getRole());
        info.setScopeId(user.getScopeId());
        info.setConsentKnowledgePromotion(user.getConsentKnowledgePromotion());
        info.setLanguage(user.getLanguage() != null ? user.getLanguage() : "zh-CN");
        info.setScopes(buildScopeList(user.getId()));
        return info;
    }

    private List<ScopeBriefInfo> buildScopeList(Long userId) {
        List<ScopeModel> scopes = scopeService.listScopesByUserId(userId);
        List<ScopeBriefInfo> result = new ArrayList<>();
        for (ScopeModel scope : scopes) {
            ScopeBriefInfo brief = new ScopeBriefInfo();
            brief.setScopeId(scope.getId());
            brief.setScopeName(scope.getName());
            brief.setScopeType(scope.getType());
            brief.setRole(scopeService.getMemberRole(scope.getId(), userId));
            brief.setLanguage(scope.getLanguage() != null ? scope.getLanguage() : "zh-CN");
            result.add(brief);
        }
        return result;
    }
}