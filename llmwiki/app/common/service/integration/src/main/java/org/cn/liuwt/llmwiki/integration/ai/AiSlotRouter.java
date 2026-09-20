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

    public record Endpoint(String baseUrl, String apiKey, String model) {
        public Endpoint {
            baseUrl = AiBaseUrlNormalizer.normalize(baseUrl);
        }
    }

    public ChatModel getModel(String slotName) {
        AiRuntimeConfig cfg = holder.get();
        if (cfg.isEmpty()) {
            return null;
        }

        AiRuntimeConfig.SlotEntry slot = cfg.slots().get(slotName);
        if (slot == null) {
            return "main".equals(slotName) ? null : getModel("main");
        }

        ChatModel baseModel = registry.getModel(slot.provider());
        if (baseModel == null) {
            log.warn("Slot '{}' references provider '{}' which is not available", slotName, slot.provider());
            return "main".equals(slotName) ? null : getModel("main");
        }

        String effectiveModel = StringUtils.hasText(slot.model()) ? slot.model() : legacyModel;
        if (StringUtils.hasText(effectiveModel) && baseModel instanceof OpenAiChatModel openAiModel) {
            return openAiModel.mutate()
                .defaultOptions(OpenAiChatOptions.builder().model(effectiveModel).build())
                .build();
        }

        if (!StringUtils.hasText(effectiveModel)) {
            log.warn("Slot '{}' has no model configured and no legacy model fallback; calls will fail until configured", slotName);
        }
        return baseModel;
    }

    public ChatModel getQueryMultimodalModel() {
        AiRuntimeConfig cfg = holder.get();
        if (cfg.isEmpty()) {
            return null;
        }
        AiRuntimeConfig.SlotEntry main = cfg.slots().get("main");
        if (main != null && main.multimodal()) {
            return getModel("main");
        }
        return getModel("multimodal");
    }

    public ChatModel getDeepMultimodalModel() {
        AiRuntimeConfig cfg = holder.get();
        if (cfg.isEmpty()) {
            return null;
        }
        AiRuntimeConfig.SlotEntry deep = cfg.slots().get("deep-analysis");
        if (deep != null && deep.multimodal()) {
            return getModel("deep-analysis");
        }
        return getModel("multimodal");
    }

    public Endpoint getEndpoint(String slotName) {
        AiRuntimeConfig cfg = holder.get();
        if (cfg.isEmpty()) {
            return new Endpoint(legacyBaseUrl, legacyApiKey, legacyModel);
        }

        AiRuntimeConfig.SlotEntry slot = cfg.slots().get(slotName);
        if (slot == null) {
            return "main".equals(slotName)
                ? new Endpoint(legacyBaseUrl, legacyApiKey, legacyModel)
                : getEndpoint("main");
        }
        AiRuntimeConfig.ProviderEntry provider = registry.getProviderEntry(slot.provider());
        if (provider == null) {
            log.warn("Slot '{}' references unknown provider '{}', falling back", slotName, slot.provider());
            return "main".equals(slotName)
                ? new Endpoint(legacyBaseUrl, legacyApiKey, legacyModel)
                : getEndpoint("main");
        }

        return new Endpoint(provider.baseUrl(), provider.apiKey(), slot.model());
    }

    public String resolveModel(String slotName) {
        AiRuntimeConfig cfg = holder.get();
        if (cfg.isEmpty()) {
            return legacyModel;
        }

        AiRuntimeConfig.SlotEntry slot = cfg.slots().get(slotName);
        if (slot == null) {
            return "main".equals(slotName) ? legacyModel : resolveModel("main");
        }
        return StringUtils.hasText(slot.model()) ? slot.model() : legacyModel;
    }

    public boolean isMultiProviderMode() {
        return !holder.get().isEmpty();
    }
}
