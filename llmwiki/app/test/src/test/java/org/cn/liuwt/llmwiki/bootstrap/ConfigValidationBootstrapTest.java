package org.cn.liuwt.llmwiki.bootstrap;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.AiRuntimeConfigService;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConfigValidationBootstrapTest {

    private Environment devEnvironment() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(env.getProperty("llmwiki.jwt.secret")).thenReturn("test-secret");
        return env;
    }

    private AiRuntimeConfigService configuredAiService() {
        AiRuntimeConfigService service = mock(AiRuntimeConfigService.class);
        when(service.isConfiguredInDb()).thenReturn(true);
        return service;
    }

    private static SourceDO source(long id, long scopeId, String filePath) {
        SourceDO source = new SourceDO();
        source.setId(id);
        source.setScopeId(scopeId);
        source.setFilePath(filePath);
        return source;
    }

    @Test
    void shouldWarnAndContinueWhenMajorityOfSampledSourcesMissing() {
        SourceMapper mapper = mock(SourceMapper.class);
        when(mapper.selectCount(isNull())).thenReturn(10L);
        when(mapper.selectList(any())).thenReturn(List.of(
                source(1, 1, "raw/aa/bb/h1"), source(2, 1, "raw/aa/bb/h2"), source(3, 1, "raw/aa/bb/h3")));
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.exists(anyString(), anyString())).thenReturn(false);
        when(storage.getUrl(anyString(), anyString())).thenReturn("D:\\data\\wiki-data\\1\\raw\\aa\\bb\\h1");

        ConfigValidationBootstrap bootstrap = new ConfigValidationBootstrap(
                devEnvironment(), configuredAiService(), mapper, storage, true);

        assertDoesNotThrow(() -> bootstrap.run(null));
    }

    @Test
    void shouldPassWhenMinorityOfSampledSourcesMissing() {
        SourceMapper mapper = mock(SourceMapper.class);
        when(mapper.selectCount(isNull())).thenReturn(4L);
        when(mapper.selectList(any())).thenReturn(List.of(
                source(1, 1, "raw/ok1"), source(2, 1, "raw/ok2"),
                source(3, 1, "raw/ok3"), source(4, 1, "raw/missing")));
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.exists(anyString(), eq("raw/missing"))).thenReturn(false);
        when(storage.exists(anyString(), startsWith("raw/ok"))).thenReturn(true);
        when(storage.getUrl(anyString(), anyString())).thenReturn("D:\\data\\wiki-data\\1\\raw\\missing");

        ConfigValidationBootstrap bootstrap = new ConfigValidationBootstrap(
                devEnvironment(), configuredAiService(), mapper, storage, true);

        assertDoesNotThrow(() -> bootstrap.run(null));
    }

    @Test
    void shouldSkipCheckWhenNoSources() {
        SourceMapper mapper = mock(SourceMapper.class);
        when(mapper.selectCount(isNull())).thenReturn(0L);
        StorageProvider storage = mock(StorageProvider.class);

        ConfigValidationBootstrap bootstrap = new ConfigValidationBootstrap(
                devEnvironment(), configuredAiService(), mapper, storage, true);

        assertDoesNotThrow(() -> bootstrap.run(null));
        verify(mapper, never()).selectList(any());
        verify(storage, never()).exists(anyString(), anyString());
    }

    @Test
    void shouldSkipCheckWhenDisabled() {
        SourceMapper mapper = mock(SourceMapper.class);
        StorageProvider storage = mock(StorageProvider.class);

        ConfigValidationBootstrap bootstrap = new ConfigValidationBootstrap(
                devEnvironment(), configuredAiService(), mapper, storage, false);

        assertDoesNotThrow(() -> bootstrap.run(null));
        verifyNoInteractions(mapper, storage);
    }

    @Test
    void shouldSkipCheckWhenAllProbesFail() {
        SourceMapper mapper = mock(SourceMapper.class);
        when(mapper.selectCount(isNull())).thenReturn(2L);
        when(mapper.selectList(any())).thenReturn(List.of(source(1, 1, "raw/a"), source(2, 1, "raw/b")));
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.exists(anyString(), anyString())).thenThrow(new RuntimeException("storage unreachable"));

        ConfigValidationBootstrap bootstrap = new ConfigValidationBootstrap(
                devEnvironment(), configuredAiService(), mapper, storage, true);

        assertDoesNotThrow(() -> bootstrap.run(null));
    }
}
