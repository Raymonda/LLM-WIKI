package org.cn.liuwt.llmwiki.integration.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AiProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiProviderRegistry.class);

    private final AiProviderProperties properties;

    private final Map<String, ChatModel> providerModels = new ConcurrentHashMap<>();

    public AiProviderRegistry(AiProviderProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (!properties.isMultiProviderEnabled()) {
            log.info("Multi-provider mode disabled, using legacy single-key configuration");
            return;
        }

        for (Map.Entry<String, AiProviderProperties.ProviderConfig> entry : properties.getProviders().entrySet()) {
            String name = entry.getKey();
            AiProviderProperties.ProviderConfig config = entry.getValue();

            if (!StringUtils.hasText(config.getApiKey())) {
                log.debug("Provider '{}' has no API key configured, skipping ChatModel creation", name);
                continue;
            }

            try {
                OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(config.getBaseUrl())
                    .apiKey(config.getApiKey())
                    .build();

                OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder().build())
                    .build();

                providerModels.put(name, model);
                log.info("Provider '{}' registered: baseUrl={}", name, config.getBaseUrl());
            } catch (Exception e) {
                log.warn("Failed to create ChatModel for provider '{}': {}", name, e.getMessage());
            }
        }

        log.info("AiProviderRegistry initialized with {} active provider(s): {}",
            providerModels.size(), providerModels.keySet());
    }

    public ChatModel getModel(String providerName) {
        return providerModels.get(providerName);
    }

    public boolean hasProvider(String providerName) {
        return providerModels.containsKey(providerName);
    }

    public AiProviderProperties.ProviderConfig getProviderConfig(String providerName) {
        return properties.getProviders().get(providerName);
    }

    public String getDefaultProviderName() {
        if (properties.getProviders().isEmpty()) {
            return null;
        }
        return properties.getProviders().keySet().iterator().next();
    }
}
