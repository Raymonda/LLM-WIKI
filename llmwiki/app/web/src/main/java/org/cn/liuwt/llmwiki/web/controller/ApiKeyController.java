package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ApiKeyDO;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.service.system.ApiKeyService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/keys")
@PreAuthorize("hasRole('ADMIN')")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final JwtTokenProvider jwtTokenProvider;

    public ApiKeyController(ApiKeyService apiKeyService, JwtTokenProvider jwtTokenProvider) {
        this.apiKeyService = apiKeyService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public record CreateKeyRequest(String name, Long userId, Long scopeId, LocalDateTime expiresAt) {}

    @PostMapping
    public Result<Map<String, Object>> createKey(@RequestBody CreateKeyRequest request) {
        try {
            jwtTokenProvider.getCurrentUserId();
            ApiKeyService.ApiKeyCreation creation = apiKeyService.createKey(
                request.name(), request.userId(), request.scopeId(), request.expiresAt());
            Map<String, Object> data = new HashMap<>();
            data.put("id", creation.id());
            data.put("key", creation.rawKey());
            return Result.success(data);
        } catch (IllegalArgumentException e) {
            return Result.failed(ErrorCode.INVALID_PARAM);
        }
    }

    @GetMapping
    public Result<List<Map<String, Object>>> listKeys() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        List<Map<String, Object>> keys = apiKeyService.listKeys(userId).stream().map(this::toView).toList();
        return Result.success(keys);
    }

    @DeleteMapping("/{id}")
    public Result<Void> revokeKey(@PathVariable Long id) {
        apiKeyService.revokeKey(id);
        return Result.success(null);
    }

    private Map<String, Object> toView(ApiKeyDO d) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", d.getId());
        view.put("name", d.getName());
        view.put("userId", d.getUserId());
        view.put("scopeId", d.getScopeId());
        view.put("status", d.getStatus());
        view.put("expiresAt", d.getExpiresAt());
        view.put("lastUsedAt", d.getLastUsedAt());
        view.put("createdAt", d.getCreatedAt());
        return view;
    }
}
