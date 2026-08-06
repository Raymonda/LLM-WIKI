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

    private final AiProviderProperties properties;
    private final AiProviderRegistry registry;

    @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String legacyBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String legacyApiKey;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String legacyModel;

    public AiSlotRouter(AiProviderProperties properties, AiProviderRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    public record Endpoint(String baseUrl, String apiKey, String model) {}

    public ChatModel getModel(String slotName) {
        if (!properties.isMultiProviderEnabled()) {
            return null;
        }

        AiProviderProperties.SlotConfig slot = properties.getSlots().get(slotName);
        if (slot == null) {
            String defaultProvider = registry.getDefaultProviderName();
            if (defaultProvider == null) return null;
            return registry.getModel(defaultProvider);
        }

        ChatModel baseModel = registry.getModel(slot.getProvider());
        if (baseModel == null) {
            log.warn("Slot '{}' references provider '{}' which is not available", slotName, slot.getProvider());
            return null;
        }

        if (StringUtils.hasText(slot.getModel()) && baseModel instanceof OpenAiChatModel openAiModel) {
            return openAiModel.mutate()
                .defaultOptions(OpenAiChatOptions.builder().model(slot.getModel()).build())
                .build();
        }

        return baseModel;
    }

    public Endpoint getEndpoint(String slotName) {
        if (!properties.isMultiProviderEnabled()) {
            return new Endpoint(legacyBaseUrl, legacyApiKey, legacyModel);
        }

        AiProviderProperties.SlotConfig slot = properties.getSlots().get(slotName);
        if (slot == null) {
            String defaultProvider = registry.getDefaultProviderName();
            if (defaultProvider == null) {
                return new Endpoint(legacyBaseUrl, legacyApiKey, legacyModel);
            }
            AiProviderProperties.ProviderConfig config = registry.getProviderConfig(defaultProvider);
            return new Endpoint(config.getBaseUrl(), config.getApiKey(), "");
        }

        AiProviderProperties.ProviderConfig config = registry.getProviderConfig(slot.getProvider());
        if (config == null) {
            log.warn("Slot '{}' references unknown provider '{}', falling back to legacy", slotName, slot.getProvider());
            return new Endpoint(legacyBaseUrl, legacyApiKey, legacyModel);
        }

        return new Endpoint(config.getBaseUrl(), config.getApiKey(), slot.getModel());
    }

    public String resolveModel(String slotName) {
        if (!properties.isMultiProviderEnabled()) {
            return legacyModel;
        }

        AiProviderProperties.SlotConfig slot = properties.getSlots().get(slotName);
        if (slot != null && StringUtils.hasText(slot.getModel())) {
            return slot.getModel();
        }
        return legacyModel;
    }

    public boolean isMultiProviderMode() {
        return properties.isMultiProviderEnabled();
    }
}
