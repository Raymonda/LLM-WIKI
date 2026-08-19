package org.cn.liuwt.llmwiki.bootstrap.validation;

import org.cn.liuwt.llmwiki.bootstrap.ConfigValidationBootstrap;
import org.cn.liuwt.llmwiki.bootstrap.StartupConfigValidator;
import org.cn.liuwt.llmwiki.integration.ai.AiProviderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupConfigValidatorTest {

    private static final String REAL_JWT = "a-real-production-secret-value";
    private static final String REAL_KEY = "sk-real-key-123";

    @Test
    void validate_defaultJwtSecret_flagged() {
        List<String> violations = StartupConfigValidator.validate(
                StartupConfigValidator.DEFAULT_JWT_SECRET, REAL_KEY, new AiProviderProperties());
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("JWT_SECRET"));
    }

    @Test
    void validate_blankJwtSecret_flagged() {
        List<String> violations = StartupConfigValidator.validate("  ", REAL_KEY, new AiProviderProperties());
        assertEquals(1, violations.size());
    }

    @Test
    void validate_placeholderSingleProviderKey_flagged() {
        List<String> violations = StartupConfigValidator.validate(
                REAL_JWT, StartupConfigValidator.PLACEHOLDER_API_KEY, new AiProviderProperties());
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("AI Provider API Key"));
    }

    @Test
    void validate_nullSingleProviderKey_flagged() {
        List<String> violations = StartupConfigValidator.validate(REAL_JWT, null, new AiProviderProperties());
        assertEquals(1, violations.size());
    }

    @Test
    void validate_singleProviderAllSet_noViolations() {
        List<String> violations = StartupConfigValidator.validate(REAL_JWT, REAL_KEY, new AiProviderProperties());
        assertTrue(violations.isEmpty());
    }

    @Test
    void validate_multiProviderMissingKey_flagged() {
        AiProviderProperties props = new AiProviderProperties();
        AiProviderProperties.ProviderConfig dashscope = new AiProviderProperties.ProviderConfig();
        dashscope.setApiKey(REAL_KEY);
        AiProviderProperties.ProviderConfig broken = new AiProviderProperties.ProviderConfig();
        broken.setApiKey("");
        props.getProviders().put("dashscope", dashscope);
        props.getProviders().put("openai", broken);

        List<String> violations = StartupConfigValidator.validate(REAL_JWT, null, props);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("openai"));
    }

    @Test
    void validate_multiProviderSlotUnknownProvider_flagged() {
        AiProviderProperties props = new AiProviderProperties();
        AiProviderProperties.ProviderConfig dashscope = new AiProviderProperties.ProviderConfig();
        dashscope.setApiKey(REAL_KEY);
        props.getProviders().put("dashscope", dashscope);
        AiProviderProperties.SlotConfig summary = new AiProviderProperties.SlotConfig();
        summary.setProvider("nonexistent");
        summary.setModel("cheap-model");
        props.getSlots().put("summary", summary);

        List<String> violations = StartupConfigValidator.validate(REAL_JWT, null, props);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("summary"));
    }

    @Test
    void validate_multiProviderAllSet_noViolations() {
        AiProviderProperties props = new AiProviderProperties();
        AiProviderProperties.ProviderConfig dashscope = new AiProviderProperties.ProviderConfig();
        dashscope.setApiKey(REAL_KEY);
        props.getProviders().put("dashscope", dashscope);
        AiProviderProperties.SlotConfig summary = new AiProviderProperties.SlotConfig();
        summary.setProvider("dashscope");
        summary.setModel("qwen-plus");
        props.getSlots().put("summary", summary);

        List<String> violations = StartupConfigValidator.validate(REAL_JWT, null, props);

        assertTrue(violations.isEmpty());
    }

    @Test
    void bootstrap_prodProfile_violationsThrow() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llmwiki.jwt.secret", StartupConfigValidator.DEFAULT_JWT_SECRET)
                .withProperty("spring.ai.openai.api-key", StartupConfigValidator.PLACEHOLDER_API_KEY);
        env.setActiveProfiles("prod");
        ConfigValidationBootstrap bootstrap = new ConfigValidationBootstrap(env, new AiProviderProperties());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        assertTrue(ex.getMessage().contains("生产环境关键配置校验失败"));
    }

    @Test
    void bootstrap_nonProdProfile_violationsOnlyWarn() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llmwiki.jwt.secret", StartupConfigValidator.DEFAULT_JWT_SECRET)
                .withProperty("spring.ai.openai.api-key", StartupConfigValidator.PLACEHOLDER_API_KEY);
        ConfigValidationBootstrap bootstrap = new ConfigValidationBootstrap(env, new AiProviderProperties());

        assertDoesNotThrow(() -> bootstrap.run(null));
    }

    @Test
    void bootstrap_allConfigsValid_noThrow() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llmwiki.jwt.secret", REAL_JWT)
                .withProperty("spring.ai.openai.api-key", REAL_KEY);
        env.setActiveProfiles("prod");
        ConfigValidationBootstrap bootstrap = new ConfigValidationBootstrap(env, new AiProviderProperties());

        assertDoesNotThrow(() -> bootstrap.run(null));
    }
}
