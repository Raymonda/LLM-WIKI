package org.cn.liuwt.llmwiki.bootstrap;

import org.cn.liuwt.llmwiki.domain.service.harness.governance.AiRuntimeConfigService;
import org.cn.liuwt.llmwiki.integration.ai.AiProviderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConfigValidationBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigValidationBootstrap.class);

    private final Environment environment;
    private final AiProviderProperties aiProviderProperties;
    private final AiRuntimeConfigService aiRuntimeConfigService;

    public ConfigValidationBootstrap(Environment environment, AiProviderProperties aiProviderProperties,
                                     AiRuntimeConfigService aiRuntimeConfigService) {
        this.environment = environment;
        this.aiProviderProperties = aiProviderProperties;
        this.aiRuntimeConfigService = aiRuntimeConfigService;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean dbConfigured;
        try {
            dbConfigured = aiRuntimeConfigService.isConfiguredInDb();
        } catch (Exception e) {
            LOGGER.warn("Failed to check DB AI runtime config, treating as not configured", e);
            dbConfigured = false;
        }
        List<String> violations = StartupConfigValidator.validate(
                environment.getProperty("llmwiki.jwt.secret"),
                environment.getProperty("spring.ai.openai.api-key"),
                aiProviderProperties, dbConfigured);
        if (violations.isEmpty()) {
            LOGGER.info("Startup config validation passed");
            return;
        }
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (prod) {
            throw new IllegalStateException("生产环境关键配置校验失败（误配置大声失败）:\n- " + String.join("\n- ", violations));
        }
        for (String violation : violations) {
            LOGGER.warn("Non-prod config warning: {}", violation);
        }
    }
}
