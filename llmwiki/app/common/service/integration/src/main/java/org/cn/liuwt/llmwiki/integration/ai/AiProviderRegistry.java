package org.cn.liuwt.llmwiki.integration.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AiProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiProviderRegistry.class);

    private final AtomicReference<Map<String, ChatModel>> providerModels = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, AiRuntimeConfig.ProviderEntry>> providerEntries = new AtomicReference<>(Map.of());

    public void applyConfig(AiRuntimeConfig cfg) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        Map<String, AiRuntimeConfig.ProviderEntry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, AiRuntimeConfig.ProviderEntry> e : cfg.providers().entrySet()) {
            AiRuntimeConfig.ProviderEntry p = e.getValue();
            entries.put(e.getKey(), p);
            if (!p.enabled() || !StringUtils.hasText(p.apiKey())) {
                log.debug("Provider '{}' disabled or has no API key, skipping ChatModel creation", e.getKey());
                continue;
            }
            try {
                OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(p.baseUrl())
                    .apiKey(p.apiKey())
                    .build();
                models.put(e.getKey(), OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder().build())
                    .build());
                log.info("Provider '{}' registered: baseUrl={}", e.getKey(), p.baseUrl());
            } catch (Exception ex) {
                log.warn("Failed to create ChatModel for provider '{}': {}", e.getKey(), ex.getMessage());
            }
        }
        providerEntries.set(Map.copyOf(entries));
        providerModels.set(Map.copyOf(models));
        log.info("AiProviderRegistry applied: {} provider(s), {} active model(s)", entries.size(), models.size());
    }

    public ChatModel getModel(String providerName) {
        return providerModels.get().get(providerName);
    }

    public AiRuntimeConfig.ProviderEntry getProviderEntry(String providerName) {
        return providerEntries.get().get(providerName);
    }

    public String getDefaultProviderName() {
        Map<String, ChatModel> models = providerModels.get();
        return models.isEmpty() ? null : models.keySet().iterator().next();
    }
}
