package org.cn.liuwt.llmwiki.domain.service.system;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ApiKeyDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ApiKeyMapper;
import org.cn.liuwt.llmwiki.domain.model.system.UserModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiKeyServiceTest {

    private ApiKeyMapper apiKeyMapper;
    private UserService userService;
    private ScopeService scopeService;
    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        apiKeyMapper = mock(ApiKeyMapper.class);
        userService = mock(UserService.class);
        scopeService = mock(ScopeService.class);
        apiKeyService = new ApiKeyService(apiKeyMapper, userService, scopeService);
    }

    @Test
    void shouldReturnRawKeyAndStoreSha256WhenCreateKeySucceeds() {
        UserModel user = new UserModel();
        user.setId(7L);
        user.setStatus("active");
        user.setRole("user");
        when(userService.getUserById(7L)).thenReturn(user);
        when(scopeService.getMemberRole(5L, 7L)).thenReturn("owner");
        when(apiKeyMapper.insert(any(ApiKeyDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, ApiKeyDO.class).setId(1L);
            return 1;
        });

        ApiKeyService.ApiKeyCreation creation = apiKeyService.createKey("dsh-agent", 7L, 5L, null, 7L);

        assertThat(creation.rawKey()).startsWith("llmwiki_");
        assertThat(creation.rawKey()).hasSize(51);
        verify(apiKeyMapper).insert(argThat((ApiKeyDO d) ->
            d.getUserId().equals(7L) && d.getScopeId().equals(5L)
                && d.getStatus().equals("active") && d.getKeyHash().length() == 64));
    }

    @Test
    void shouldThrowWhenCreateKeyTargetsNonMemberScope() {
        UserModel user = new UserModel();
        user.setId(7L);
        user.setStatus("active");
        when(userService.getUserById(7L)).thenReturn(user);
        when(scopeService.getMemberRole(5L, 7L)).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> apiKeyService.createKey("dsh-agent", 7L, 5L, null, 7L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowWhenOperatorDiffersFromBoundUser() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> apiKeyService.createKey("dsh-agent", 7L, 5L, null, 8L))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(userService, scopeService);
    }

    @Test
    void shouldReturnValidationWhenKeyActiveAndNotExpired() {
        ApiKeyDO d = new ApiKeyDO();
        d.setUserId(7L);
        d.setScopeId(5L);
        d.setStatus("active");
        d.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(apiKeyMapper.selectOne(any())).thenReturn(d);
        UserModel user = new UserModel();
        user.setId(7L);
        user.setStatus("active");
        user.setRole("admin");
        when(userService.getUserById(7L)).thenReturn(user);

        ApiKeyService.ApiKeyValidation v = apiKeyService.validate("llmwiki_" + "x".repeat(43));

        assertThat(v).isNotNull();
        assertThat(v.userId()).isEqualTo(7L);
        assertThat(v.scopeId()).isEqualTo(5L);
        assertThat(v.role()).isEqualTo("admin");
    }

    @Test
    void shouldReturnNullWhenKeyRevoked() {
        ApiKeyDO d = new ApiKeyDO();
        d.setUserId(7L);
        d.setScopeId(5L);
        d.setStatus("revoked");
        when(apiKeyMapper.selectOne(any())).thenReturn(d);

        assertThat(apiKeyService.validate("llmwiki_" + "x".repeat(43))).isNull();
    }

    @Test
    void shouldReturnNullWhenUserInactive() {
        ApiKeyDO d = new ApiKeyDO();
        d.setUserId(7L);
        d.setScopeId(5L);
        d.setStatus("active");
        when(apiKeyMapper.selectOne(any())).thenReturn(d);
        UserModel user = new UserModel();
        user.setId(7L);
        user.setStatus("disabled");
        when(userService.getUserById(7L)).thenReturn(user);

        assertThat(apiKeyService.validate("llmwiki_" + "x".repeat(43))).isNull();
    }

    @Test
    void shouldReturnNullWhenCachedKeyExpiresWithinTtl() throws InterruptedException {
        ApiKeyDO d = new ApiKeyDO();
        d.setUserId(7L);
        d.setScopeId(5L);
        d.setStatus("active");
        d.setExpiresAt(LocalDateTime.now().plusNanos(150_000_000L));
        when(apiKeyMapper.selectOne(any())).thenReturn(d);
        UserModel user = new UserModel();
        user.setId(7L);
        user.setStatus("active");
        user.setRole("user");
        when(userService.getUserById(7L)).thenReturn(user);
        String rawKey = "llmwiki_" + "x".repeat(43);

        assertThat(apiKeyService.validate(rawKey)).isNotNull();

        Thread.sleep(200L);

        assertThat(apiKeyService.validate(rawKey)).isNull();
        verify(apiKeyMapper, times(1)).selectOne(any());
    }

    @Test
    void shouldSetRevokedWhenRevokeKey() {
        ApiKeyDO d = new ApiKeyDO();
        d.setId(3L);
        d.setUserId(7L);
        d.setStatus("active");
        d.setKeyHash("a".repeat(64));
        when(apiKeyMapper.selectById(3L)).thenReturn(d);
        when(apiKeyMapper.updateById(any(ApiKeyDO.class))).thenReturn(1);

        apiKeyService.revokeKey(3L, 7L, false);

        verify(apiKeyMapper).updateById(argThat((ApiKeyDO u) -> u.getStatus().equals("revoked")));
    }

    @Test
    void shouldRevokeForeignKeyWhenOperatorIsAdmin() {
        ApiKeyDO d = new ApiKeyDO();
        d.setId(3L);
        d.setUserId(7L);
        d.setStatus("active");
        d.setKeyHash("a".repeat(64));
        when(apiKeyMapper.selectById(3L)).thenReturn(d);
        when(apiKeyMapper.updateById(any(ApiKeyDO.class))).thenReturn(1);

        apiKeyService.revokeKey(3L, 1L, true);

        verify(apiKeyMapper).updateById(argThat((ApiKeyDO u) -> u.getStatus().equals("revoked")));
    }

    @Test
    void shouldThrowWhenRevokingForeignKeyAsNonOwner() {
        ApiKeyDO d = new ApiKeyDO();
        d.setId(3L);
        d.setUserId(7L);
        d.setStatus("active");
        when(apiKeyMapper.selectById(3L)).thenReturn(d);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> apiKeyService.revokeKey(3L, 8L, false))
            .isInstanceOf(IllegalArgumentException.class);
        verify(apiKeyMapper, never()).updateById(any(ApiKeyDO.class));
    }

    @Test
    void shouldListKeysByUser() {
        ApiKeyDO d = new ApiKeyDO();
        d.setId(3L);
        d.setUserId(7L);
        when(apiKeyMapper.selectList(any())).thenReturn(List.of(d));

        assertThat(apiKeyService.listKeys(7L)).hasSize(1);
    }

    @Test
    void shouldListAllKeys() {
        ApiKeyDO d = new ApiKeyDO();
        d.setId(3L);
        d.setUserId(7L);
        when(apiKeyMapper.selectList(any())).thenReturn(List.of(d));

        assertThat(apiKeyService.listAllKeys()).hasSize(1);
    }
}
