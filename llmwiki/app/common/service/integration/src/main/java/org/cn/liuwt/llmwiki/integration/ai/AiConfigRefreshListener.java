package org.cn.liuwt.llmwiki.integration.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AiConfigRefreshListener {

    private static final Logger log = LoggerFactory.getLogger(AiConfigRefreshListener.class);

    private final AiRuntimeConfigHolder holder;
    private final AiProviderRegistry registry;
    private final LlmClient llmClient;

    public AiConfigRefreshListener(AiRuntimeConfigHolder holder, AiProviderRegistry registry, LlmClient llmClient) {
        this.holder = holder;
        this.registry = registry;
        this.llmClient = llmClient;
    }

    @EventListener
    public void onChange(AiConfigChangedEvent event) {
        holder.update(event.config());
        registry.applyConfig(event.config());
        llmClient.applyRuntimeConfig();
        log.info("AI runtime config applied, source={}, providers={}", event.source(), event.config().providers().keySet());
    }
}
