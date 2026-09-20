package org.cn.liuwt.llmwiki.integration.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AiSlotRouter {

    private static final Logger log = LoggerFactory.getLogger(AiSlotRouter.class);

    private final AiProviderRegistry registry;
    private final AiRuntimeConfigHolder holder;

    @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String legacyBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String legacyApiKey;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String legacyModel;

    public AiSlotRouter(AiProviderRegistry registry, AiRuntimeConfigHolder holder) {
        this.registry = registry;
        this.holder = holder;
    }

    public record Endpoint(String baseUrl, String apiKey, String model) {}

    public ChatModel getModel(String slotName) {
        AiRuntimeConfig cfg = holder.get();
        if (cfg.isEmpty()) {
            return null;
        }

        AiRuntimeConfig.SlotEntry slot = cfg.slots().get(slotName);
        String providerName = slot != null ? slot.provider() : registry.getDefaultProviderName();
        if (providerName == null) {
            return null;
        }

        ChatModel baseModel = registry.getModel(providerName);
        if (baseModel == null) {
            log.warn("Slot '{}' references provider '{}' which is not available", slotName, providerName);
            return null;
        }

        if (slot != null && StringUtils.hasText(slot.model()) && baseModel instanceof OpenAiChatModel openAiModel) {
            return openAiModel.mutate()
                .defaultOptions(OpenAiChatOptions.builder().model(slot.model()).build())
                .build();
        }

        return baseModel;
    }

    public Endpoint getEndpoint(String slotName) {
        AiRuntimeConfig cfg = holder.get();
        if (cfg.isEmpty()) {
            return new Endpoint(legacyBaseUrl, legacyApiKey, legacyModel);
        }

        AiRuntimeConfig.SlotEntry slot = cfg.slots().get(slotName);
        String providerName = slot != null ? slot.provider() : registry.getDefaultProviderName();
        AiRuntimeConfig.ProviderEntry provider = providerName != null ? registry.getProviderEntry(providerName) : null;
        if (provider == null) {
            log.warn("Slot '{}' references unknown provider, falling back to legacy", slotName);
            return new Endpoint(legacyBaseUrl, legacyApiKey, legacyModel);
        }

        return new Endpoint(provider.baseUrl(), provider.apiKey(), slot != null ? slot.model() : "");
    }

    public String resolveModel(String slotName) {
        AiRuntimeConfig cfg = holder.get();
        if (cfg.isEmpty()) {
            return legacyModel;
        }

        AiRuntimeConfig.SlotEntry slot = cfg.slots().get(slotName);
        return slot != null && StringUtils.hasText(slot.model()) ? slot.model() : legacyModel;
    }

    public boolean isMultiProviderMode() {
        return !holder.get().isEmpty();
    }
}
