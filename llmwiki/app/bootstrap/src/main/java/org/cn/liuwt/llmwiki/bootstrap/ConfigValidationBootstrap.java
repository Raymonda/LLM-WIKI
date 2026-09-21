package org.cn.liuwt.llmwiki.bootstrap;

import org.cn.liuwt.llmwiki.domain.service.harness.governance.AiRuntimeConfigService;
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
    private final AiRuntimeConfigService aiRuntimeConfigService;

    public ConfigValidationBootstrap(Environment environment, AiRuntimeConfigService aiRuntimeConfigService) {
        this.environment = environment;
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
        if (!dbConfigured) {
            LOGGER.warn("AI 模型未配置：请在 系统设置 → 通用设置 完成配置（应用正常启动，配置后即时生效）");
        }
        List<String> violations = StartupConfigValidator.validate(
                environment.getProperty("llmwiki.jwt.secret"));
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
