package org.cn.liuwt.llmwiki.bootstrap.validation;

import org.cn.liuwt.llmwiki.bootstrap.ConfigValidationBootstrap;
import org.cn.liuwt.llmwiki.bootstrap.StartupConfigValidator;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.AiRuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StartupConfigValidatorTest {

    private static final String REAL_JWT = "a-real-production-secret-value";

    @Test
    void shouldFlagDefaultJwtSecret() {
        List<String> violations = StartupConfigValidator.validate("llmwiki-default-secret-change-in-production");
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("JWT_SECRET"));
    }

    @Test
    void shouldFlagBlankJwtSecret() {
        assertFalse(StartupConfigValidator.validate("").isEmpty());
        assertFalse(StartupConfigValidator.validate(null).isEmpty());
    }

    @Test
    void shouldPassWithRealJwtSecret() {
        assertTrue(StartupConfigValidator.validate(REAL_JWT).isEmpty());
    }

    @Test
    void shouldThrowInProdWhenJwtDefault() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.profiles.active", "prod")
            .withProperty("llmwiki.jwt.secret", "llmwiki-default-secret-change-in-production");
        ConfigValidationBootstrap bootstrap = new ConfigValidationBootstrap(env, unconfiguredDbService());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        assertTrue(e.getMessage().contains("生产环境关键配置校验失败"));
    }

    @Test
    void shouldNotThrowOutsideProdWhenJwtDefault() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.profiles.active", "dev")
            .withProperty("llmwiki.jwt.secret", "llmwiki-default-secret-change-in-production");
        ConfigValidationBootstrap bootstrap = new ConfigValidationBootstrap(env, unconfiguredDbService());
        assertDoesNotThrow(() -> bootstrap.run(null));
    }

    @Test
    void shouldNotThrowInProdWhenJwtReal() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.profiles.active", "prod")
            .withProperty("llmwiki.jwt.secret", REAL_JWT);
        ConfigValidationBootstrap bootstrap = new ConfigValidationBootstrap(env, unconfiguredDbService());
        assertDoesNotThrow(() -> bootstrap.run(null));
    }

    private AiRuntimeConfigService unconfiguredDbService() {
        AiRuntimeConfigService svc = mock(AiRuntimeConfigService.class);
        when(svc.isConfiguredInDb()).thenReturn(false);
        return svc;
    }
}
