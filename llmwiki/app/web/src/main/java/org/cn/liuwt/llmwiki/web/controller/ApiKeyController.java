package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ApiKeyDO;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeModel;
import org.cn.liuwt.llmwiki.domain.model.system.UserModel;
import org.cn.liuwt.llmwiki.domain.service.system.ApiKeyService;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.domain.service.system.UserService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserService userService;
    private final ScopeService scopeService;

    public ApiKeyController(ApiKeyService apiKeyService, JwtTokenProvider jwtTokenProvider,
                            UserService userService, ScopeService scopeService) {
        this.apiKeyService = apiKeyService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userService = userService;
        this.scopeService = scopeService;
    }

    public record CreateKeyRequest(String name, Long scopeId, LocalDateTime expiresAt) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public Result<Map<String, Object>> createKey(@RequestBody CreateKeyRequest request) {
        try {
            Long userId = jwtTokenProvider.getCurrentUserId();
            ApiKeyService.ApiKeyCreation creation = apiKeyService.createKey(
                request.name(), userId, request.scopeId(), request.expiresAt(), userId);
            Map<String, Object> data = new HashMap<>();
            data.put("id", creation.id());
            data.put("key", creation.rawKey());
            return Result.success(data);
        } catch (IllegalArgumentException e) {
            return Result.failed(ErrorCode.INVALID_PARAM);
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public Result<List<Map<String, Object>>> listKeys() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        List<Map<String, Object>> keys = apiKeyService.listKeys(userId).stream().map(this::toView).toList();
        return Result.success(keys);
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<Map<String, Object>>> listAllKeys() {
        List<Map<String, Object>> keys = apiKeyService.listAllKeys().stream().map(this::toAdminView).toList();
        return Result.success(keys);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public Result<Void> revokeKey(@PathVariable Long id) {
        try {
            Long userId = jwtTokenProvider.getCurrentUserId();
            apiKeyService.revokeKey(id, userId, isCurrentUserAdmin());
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.failed(ErrorCode.AUTH_ACCESS_DENIED);
        }
    }

    private boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private Map<String, Object> toView(ApiKeyDO d) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", d.getId());
        view.put("name", d.getName());
        view.put("userId", d.getUserId());
        view.put("scopeId", d.getScopeId());
        view.put("scopeName", resolveScopeName(d.getScopeId()));
        view.put("scopeType", resolveScopeType(d.getScopeId()));
        view.put("status", d.getStatus());
        view.put("expiresAt", d.getExpiresAt());
        view.put("lastUsedAt", d.getLastUsedAt());
        view.put("createdAt", d.getCreatedAt());
        return view;
    }

    private Map<String, Object> toAdminView(ApiKeyDO d) {
        Map<String, Object> view = toView(d);
        UserModel user = userService.getUserById(d.getUserId());
        view.put("username", user != null ? user.getUsername() : null);
        return view;
    }

    private String resolveScopeName(Long scopeId) {
        if (scopeId == null) {
            return null;
        }
        ScopeModel scope = scopeService.getScope(scopeId);
        return scope != null ? scope.getName() : null;
    }

    private String resolveScopeType(Long scopeId) {
        if (scopeId == null) {
            return null;
        }
        ScopeModel scope = scopeService.getScope(scopeId);
        return scope != null ? scope.getType() : null;
    }
}
