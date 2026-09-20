package org.cn.liuwt.llmwiki.facade.model;

import java.util.List;

public final class AiRuntimeConfigDtos {

    private AiRuntimeConfigDtos() {
    }

    public record AiProviderView(String name, String baseUrl, boolean enabled,
                                 boolean apiKeyConfigured, String apiKeyMasked) {
    }

    public record AiSlotView(String slot, String provider, String model, boolean multimodal) {
    }

    public record AiRuntimeConfigView(List<AiProviderView> providers, List<AiSlotView> slots) {
    }

    public record AiProviderInput(String name, String baseUrl, String apiKey, Boolean enabled) {
    }

    public record AiSlotInput(String slot, String provider, String model, Boolean multimodal) {
    }

    public record AiRuntimeConfigSaveRequest(List<AiProviderInput> providers, List<AiSlotInput> slots) {
    }

    public record AiConnectionTestRequest(String baseUrl, String apiKey, String providerName, String model) {
    }

    public record AiConnectionTestResult(boolean ok, long latencyMs, String message) {
    }
}
