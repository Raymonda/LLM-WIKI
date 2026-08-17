package org.cn.liuwt.llmwiki.web;

import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.service.system.ApiKeyService;
import org.cn.liuwt.llmwiki.web.controller.ApiKeyController;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyControllerTest {

    @Mock
    private ApiKeyService apiKeyService;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private ApiKeyController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiKeyController(apiKeyService, jwtTokenProvider);
    }

    @Test
    void shouldCreateKeyAndReturnRawKeyOnce() {
        when(jwtTokenProvider.getCurrentUserId()).thenReturn(1L);
        when(apiKeyService.createKey("dsh-agent", 7L, 5L, null))
            .thenReturn(new ApiKeyService.ApiKeyCreation(9L, "llmwiki_raw"));

        Result<Map<String, Object>> result = controller.createKey(
            new ApiKeyController.CreateKeyRequest("dsh-agent", 7L, 5L, null));

        assertThat(result.getData()).containsEntry("id", 9L).containsEntry("key", "llmwiki_raw");
    }

    @Test
    void shouldPropagateIllegalArgumentExceptionAsFailedResult() {
        when(jwtTokenProvider.getCurrentUserId()).thenReturn(1L);
        when(apiKeyService.createKey(any(), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("not a member"));

        Result<Map<String, Object>> result = controller.createKey(
            new ApiKeyController.CreateKeyRequest("dsh-agent", 7L, 5L, null));

        assertThat(result.getCode()).isNotEqualTo("0");
    }

    @Test
    void shouldListKeysWithoutHashes() {
        org.cn.liuwt.llmwiki.common.dal.dataobject.ApiKeyDO d =
            new org.cn.liuwt.llmwiki.common.dal.dataobject.ApiKeyDO();
        d.setId(9L);
        d.setName("dsh-agent");
        d.setStatus("active");
        when(jwtTokenProvider.getCurrentUserId()).thenReturn(1L);
        when(apiKeyService.listKeys(1L)).thenReturn(List.of(d));

        Result<List<Map<String, Object>>> result = controller.listKeys();

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0)).doesNotContainKey("keyHash");
    }

    @Test
    void shouldRevokeKey() {
        Result<Void> result = controller.revokeKey(9L);
        verify(apiKeyService).revokeKey(9L);
        assertThat(result.getCode()).isEqualTo("0");
    }
}
