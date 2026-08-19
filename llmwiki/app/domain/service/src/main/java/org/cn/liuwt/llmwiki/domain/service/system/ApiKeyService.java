package org.cn.liuwt.llmwiki.domain.service.system;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ApiKeyDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ApiKeyMapper;
import org.cn.liuwt.llmwiki.domain.model.system.UserModel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "llmwiki_";
    private static final long CACHE_TTL_MS = 60_000L;

    public record ApiKeyCreation(Long id, String rawKey) {}
    public record ApiKeyValidation(Long userId, Long scopeId, String role) {}

    private record CachedValidation(ApiKeyValidation validation, LocalDateTime expiresAt, long cachedAtMs) {}

    private final ApiKeyMapper apiKeyMapper;
    private final UserService userService;
    private final ScopeService scopeService;
    private final Map<String, CachedValidation> validationCache = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyMapper apiKeyMapper, UserService userService, ScopeService scopeService) {
        this.apiKeyMapper = apiKeyMapper;
        this.userService = userService;
        this.scopeService = scopeService;
    }

    public ApiKeyCreation createKey(String name, Long userId, Long scopeId, LocalDateTime expiresAt, Long operatorId) {
        if (operatorId == null || !operatorId.equals(userId)) {
            throw new IllegalArgumentException("operator " + operatorId + " cannot create key bound to user " + userId);
        }
        UserModel user = userService.getUserById(userId);
        if (user == null || !"active".equals(user.getStatus())) {
            throw new IllegalArgumentException("user not found or inactive: " + userId);
        }
        if (scopeService.getMemberRole(scopeId, userId) == null) {
            throw new IllegalArgumentException("user " + userId + " is not a member of scope " + scopeId);
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String rawKey = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        ApiKeyDO d = new ApiKeyDO();
        d.setKeyHash(sha256Hex(rawKey));
        d.setName(name);
        d.setUserId(userId);
        d.setScopeId(scopeId);
        d.setStatus("active");
        d.setExpiresAt(expiresAt);
        apiKeyMapper.insert(d);
        log.info("API key created: id={}, name={}, userId={}, scopeId={}", d.getId(), name, userId, scopeId);
        return new ApiKeyCreation(d.getId(), rawKey);
    }

    public ApiKeyValidation validate(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(KEY_PREFIX)) {
            return null;
        }
        String hash = sha256Hex(rawKey);
        CachedValidation cached = validationCache.get(hash);
        if (cached != null && System.currentTimeMillis() - cached.cachedAtMs() < CACHE_TTL_MS) {
            if (cached.expiresAt() != null && cached.expiresAt().isBefore(LocalDateTime.now())) {
                validationCache.remove(hash);
                return null;
            }
            return cached.validation();
        }
        ApiKeyDO d = apiKeyMapper.selectOne(
            new LambdaQueryWrapper<ApiKeyDO>().eq(ApiKeyDO::getKeyHash, hash).last("LIMIT 1"));
        if (d == null || !"active".equals(d.getStatus())) {
            return null;
        }
        if (d.getExpiresAt() != null && d.getExpiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        UserModel user = userService.getUserById(d.getUserId());
        if (user == null || !"active".equals(user.getStatus())) {
            return null;
        }
        ApiKeyValidation validation = new ApiKeyValidation(
            d.getUserId(), d.getScopeId(), user.getRole() != null ? user.getRole() : "user");
        validationCache.put(hash, new CachedValidation(validation, d.getExpiresAt(), System.currentTimeMillis()));
        touchLastUsed(d.getId());
        return validation;
    }

    public void revokeKey(Long id, Long operatorId, boolean operatorIsAdmin) {
        ApiKeyDO d = apiKeyMapper.selectById(id);
        if (d == null) {
            throw new IllegalArgumentException("api key not found: " + id);
        }
        if (!operatorIsAdmin && (operatorId == null || !operatorId.equals(d.getUserId()))) {
            throw new IllegalArgumentException("operator " + operatorId + " is not allowed to revoke key " + id);
        }
        ApiKeyDO update = new ApiKeyDO();
        update.setId(id);
        update.setStatus("revoked");
        apiKeyMapper.updateById(update);
        validationCache.remove(d.getKeyHash());
        log.info("API key revoked: id={}, operatorId={}", id, operatorId);
    }

    public List<ApiKeyDO> listKeys(Long userId) {
        return apiKeyMapper.selectList(
            new LambdaQueryWrapper<ApiKeyDO>()
                .eq(ApiKeyDO::getUserId, userId)
                .orderByDesc(ApiKeyDO::getCreatedAt));
    }

    public List<ApiKeyDO> listAllKeys() {
        return apiKeyMapper.selectList(
            new LambdaQueryWrapper<ApiKeyDO>().orderByDesc(ApiKeyDO::getCreatedAt));
    }

    private void touchLastUsed(Long id) {
        try {
            ApiKeyDO update = new ApiKeyDO();
            update.setId(id);
            update.setLastUsedAt(LocalDateTime.now());
            apiKeyMapper.updateById(update);
        } catch (Exception e) {
            log.debug("Failed to update last_used_at for api key {}", id);
        }
    }

    private String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
