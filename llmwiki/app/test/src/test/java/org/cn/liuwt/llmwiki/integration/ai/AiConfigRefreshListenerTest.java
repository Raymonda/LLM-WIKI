package org.cn.liuwt.llmwiki.integration.ai;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AiConfigRefreshListenerTest {

    @Test
    void shouldMakeLlmClientAvailableWhenMainSlotConfigured() {
        AiRuntimeConfigHolder holder = new AiRuntimeConfigHolder();
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = new AiSlotRouter(registry, holder);
        LlmClient client = new LlmClient();
        inject(client, "slotRouter", router);
        inject(client, "configHolder", holder);
        AiConfigRefreshListener listener = new AiConfigRefreshListener(holder, registry, client);
        assertFalse(client.isAvailable());
        listener.onChange(new AiConfigChangedEvent(new AiRuntimeConfig(
            Map.of("dash", new AiRuntimeConfig.ProviderEntry("https://dashscope.aliyuncs.com/compatible-mode", "sk-x", true)),
            Map.of("main", new AiRuntimeConfig.SlotEntry("dash", "qwen-plus", false))), "test"));
        assertTrue(client.isAvailable());
    }

    private static void inject(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
