package org.cn.liuwt.llmwiki.integration.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "llmwiki.ai")
public class AiProviderProperties {

    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    private Map<String, SlotConfig> slots = new LinkedHashMap<>();

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers;
    }

    public Map<String, SlotConfig> getSlots() {
        return slots;
    }

    public void setSlots(Map<String, SlotConfig> slots) {
        this.slots = slots;
    }

    public boolean isMultiProviderEnabled() {
        return providers != null && !providers.isEmpty();
    }

    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    public static class SlotConfig {
        private String provider;
        private String model;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
