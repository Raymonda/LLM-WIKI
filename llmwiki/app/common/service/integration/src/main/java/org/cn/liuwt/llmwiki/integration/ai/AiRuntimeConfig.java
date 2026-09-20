package org.cn.liuwt.llmwiki.integration.ai;

import java.util.Map;

public record AiRuntimeConfig(Map<String, ProviderEntry> providers, Map<String, SlotEntry> slots) {

    public AiRuntimeConfig {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }

    public record ProviderEntry(String baseUrl, String apiKey, boolean enabled) {
        public ProviderEntry {
            baseUrl = AiBaseUrlNormalizer.normalize(baseUrl);
        }
    }
    public record SlotEntry(String provider, String model, boolean multimodal) {}

    public boolean isEmpty() { return providers.isEmpty(); }
    public static AiRuntimeConfig empty() { return new AiRuntimeConfig(Map.of(), Map.of()); }
}
