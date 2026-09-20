package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import org.cn.liuwt.llmwiki.integration.ai.AiConfigChangedEvent;
import org.cn.liuwt.llmwiki.integration.ai.AiProviderProperties;
import org.cn.liuwt.llmwiki.integration.ai.AiRuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AiRuntimeConfigInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiRuntimeConfigInitializer.class);

    private final AiRuntimeConfigService aiRuntimeConfigService;
    private final AiProviderProperties aiProviderProperties;
    private final ApplicationEventPublisher eventPublisher;

    public AiRuntimeConfigInitializer(AiRuntimeConfigService aiRuntimeConfigService,
                                      AiProviderProperties aiProviderProperties,
                                      ApplicationEventPublisher eventPublisher) {
        this.aiRuntimeConfigService = aiRuntimeConfigService;
        this.aiProviderProperties = aiProviderProperties;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void run(ApplicationArguments args) {
        AiRuntimeConfig effective;
        try {
            effective = aiRuntimeConfigService.resolveEffective(aiProviderProperties);
        } catch (Exception e) {
            log.warn("Failed to resolve AI runtime config from DB, falling back to YAML", e);
            effective = aiRuntimeConfigService.resolveEffective(null)
                .isEmpty() ? fallbackYamlOnly() : AiRuntimeConfig.empty();
        }
        eventPublisher.publishEvent(new AiConfigChangedEvent(effective, "startup"));
        log.info("AI runtime config initialized, source={}, providers={}",
            effective.isEmpty() ? "legacy" : "db/yaml", effective.providers().keySet());
    }

    private AiRuntimeConfig fallbackYamlOnly() {
        try {
            if (aiProviderProperties != null && aiProviderProperties.isMultiProviderEnabled()) {
                return aiRuntimeConfigService.resolveEffective(aiProviderProperties);
            }
        } catch (Exception e) {
            log.warn("Failed to convert YAML AI provider config", e);
        }
        return AiRuntimeConfig.empty();
    }
}
