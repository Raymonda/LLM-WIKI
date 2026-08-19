package org.cn.liuwt.llmwiki.bootstrap;

import org.cn.liuwt.llmwiki.integration.ai.AiProviderProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class StartupConfigValidator {

    public static final String DEFAULT_JWT_SECRET = "llmwiki-default-secret-change-in-production";
    public static final String PLACEHOLDER_API_KEY = "placeholder-not-configured";

    private StartupConfigValidator() {
    }

    public static List<String> validate(String jwtSecret, String singleProviderApiKey, AiProviderProperties aiProperties) {
        List<String> violations = new ArrayList<>();
        if (isMissingOrDefault(jwtSecret, DEFAULT_JWT_SECRET)) {
            violations.add("JWT_SECRET 未配置或仍为默认值，必须通过 JWT_SECRET 环境变量覆盖（生产环境启动将被阻断）");
        }
        if (aiProperties != null && aiProperties.isMultiProviderEnabled()) {
            validateMultiProvider(aiProperties, violations);
        } else if (isMissingOrDefault(singleProviderApiKey, PLACEHOLDER_API_KEY)) {
            violations.add("AI Provider API Key 未配置（单 Provider 模式需设置 AI_DASHSCOPE_API_KEY 或任意 OpenAI 兼容 Key）");
        }
        return violations;
    }

    private static void validateMultiProvider(AiProviderProperties aiProperties, List<String> violations) {
        for (Map.Entry<String, AiProviderProperties.ProviderConfig> entry : aiProperties.getProviders().entrySet()) {
            String apiKey = entry.getValue().getApiKey();
            if (isMissingOrDefault(apiKey, PLACEHOLDER_API_KEY)) {
                violations.add("多 Provider 配置 llmwiki.ai.providers[" + entry.getKey() + "].api-key 缺失或为占位符");
            }
        }
        for (Map.Entry<String, AiProviderProperties.SlotConfig> entry : aiProperties.getSlots().entrySet()) {
            String provider = entry.getValue().getProvider();
            if (provider != null && !provider.isBlank() && !aiProperties.getProviders().containsKey(provider)) {
                violations.add("槽位 llmwiki.ai.slots[" + entry.getKey() + "] 引用了不存在的 provider: " + provider);
            }
        }
    }

    private static boolean isMissingOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() || defaultValue.equals(value);
    }
}
