package org.cn.liuwt.llmwiki.integration.ai;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AiSlotRouterTest {

    private AiRuntimeConfig config() {
        return new AiRuntimeConfig(
            Map.of("dash", new AiRuntimeConfig.ProviderEntry("https://dashscope.aliyuncs.com/compatible-mode", "sk-x", true),
                   "off", new AiRuntimeConfig.ProviderEntry("https://x", "sk-y", false)),
            Map.of("main", new AiRuntimeConfig.SlotEntry("dash", "qwen-plus"),
                   "ocr", new AiRuntimeConfig.SlotEntry("dash", "qwen-vl-ocr")));
    }

    @Test
    void shouldResolveSlotModelWhenSnapshotPresent() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiRuntimeConfigHolder holder = new AiRuntimeConfigHolder();
        holder.update(config());
        registry.applyConfig(holder.get());
        AiSlotRouter router = new AiSlotRouter(registry, holder);
        assertTrue(router.isMultiProviderMode());
        assertNotNull(router.getModel("main"));
        assertEquals("qwen-vl-ocr", router.resolveModel("ocr"));
    }

    @Test
    void shouldFallbackToDefaultProviderWhenSlotMissing() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiRuntimeConfigHolder holder = new AiRuntimeConfigHolder();
        holder.update(config());
        registry.applyConfig(holder.get());
        AiSlotRouter router = new AiSlotRouter(registry, holder);
        assertNotNull(router.getModel("diagram"));
    }

    @Test
    void shouldReportLegacyModeWhenSnapshotEmpty() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = new AiSlotRouter(registry, new AiRuntimeConfigHolder());
        assertFalse(router.isMultiProviderMode());
        assertNull(router.getModel("main"));
    }

    @Test
    void shouldNotBuildModelForDisabledProviderWhenApplyingConfig() {
        AiProviderRegistry registry = new AiProviderRegistry();
        registry.applyConfig(config());
        assertNull(registry.getModel("off"));
        assertNotNull(registry.getProviderEntry("off"));
    }
}
