package org.cn.liuwt.llmwiki.integration.ai;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AiSlotRouterTest {

    private AiRuntimeConfig config() {
        return new AiRuntimeConfig(
            Map.of("dash", new AiRuntimeConfig.ProviderEntry("https://dashscope.aliyuncs.com/compatible-mode", "sk-x", true),
                   "off", new AiRuntimeConfig.ProviderEntry("https://x", "sk-y", false)),
            Map.of("main", new AiRuntimeConfig.SlotEntry("dash", "qwen-plus", false),
                   "ocr", new AiRuntimeConfig.SlotEntry("dash", "qwen-vl-ocr", false)));
    }

    private AiSlotRouter router(AiProviderRegistry registry, AiRuntimeConfig cfg) {
        AiRuntimeConfigHolder holder = new AiRuntimeConfigHolder();
        holder.update(cfg);
        registry.applyConfig(holder.get());
        return new AiSlotRouter(registry, holder);
    }

    @Test
    void shouldResolveSlotModelWhenSnapshotPresent() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = router(registry, config());
        assertTrue(router.isMultiProviderMode());
        assertNotNull(router.getModel("main"));
        assertEquals("qwen-vl-ocr", router.resolveModel("ocr"));
    }

    @Test
    void shouldFallbackToMainSlotWhenSlotMissing() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = router(registry, config());
        assertNotNull(router.getModel("diagram"));
        assertEquals("qwen-plus", router.getEndpoint("diagram").model());
        assertEquals("qwen-plus", router.resolveModel("diagram"));
    }

    @Test
    void shouldDeriveQueryMultimodalFromMainWhenMainMarkedMultimodal() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = router(registry, new AiRuntimeConfig(
            Map.of("dash", new AiRuntimeConfig.ProviderEntry("https://a", "sk-x", true),
                   "vl", new AiRuntimeConfig.ProviderEntry("https://b", "sk-y", true)),
            Map.of("main", new AiRuntimeConfig.SlotEntry("dash", "", true),
                   "multimodal", new AiRuntimeConfig.SlotEntry("vl", "", false))));
        assertSame(registry.getModel("dash"), router.getQueryMultimodalModel());
        assertSame(registry.getModel("vl"), router.getDeepMultimodalModel());
    }

    @Test
    void shouldDeriveMultimodalFromDedicatedSlotsWhenMainNotMultimodal() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = router(registry, new AiRuntimeConfig(
            Map.of("dash", new AiRuntimeConfig.ProviderEntry("https://a", "sk-x", true),
                   "vl", new AiRuntimeConfig.ProviderEntry("https://b", "sk-y", true)),
            Map.of("main", new AiRuntimeConfig.SlotEntry("dash", "", false),
                   "multimodal", new AiRuntimeConfig.SlotEntry("vl", "", false),
                   "deep-analysis", new AiRuntimeConfig.SlotEntry("dash", "", true))));
        assertSame(registry.getModel("vl"), router.getQueryMultimodalModel());
        assertSame(registry.getModel("dash"), router.getDeepMultimodalModel());
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

    @Test
    void shouldFallbackToLegacyModelWhenSlotModelBlank() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = router(registry, new AiRuntimeConfig(
            Map.of("dash", new AiRuntimeConfig.ProviderEntry("https://a", "sk-x", true)),
            Map.of("main", new AiRuntimeConfig.SlotEntry("dash", "", false))));
        injectLegacyModel(router, "qwen-turbo");
        assertNotSame(registry.getModel("dash"), router.getModel("main"));
        assertEquals("qwen-turbo", router.resolveModel("main"));
    }

    @Test
    void shouldReturnBaseModelWhenSlotModelBlankAndNoLegacyFallback() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = router(registry, new AiRuntimeConfig(
            Map.of("dash", new AiRuntimeConfig.ProviderEntry("https://a", "sk-x", true)),
            Map.of("main", new AiRuntimeConfig.SlotEntry("dash", "", false))));
        assertSame(registry.getModel("dash"), router.getModel("main"));
        assertNull(router.resolveModel("main"));
    }

    private static void injectLegacyModel(AiSlotRouter router, String model) {
        try {
            var f = AiSlotRouter.class.getDeclaredField("legacyModel");
            f.setAccessible(true);
            f.set(router, model);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
