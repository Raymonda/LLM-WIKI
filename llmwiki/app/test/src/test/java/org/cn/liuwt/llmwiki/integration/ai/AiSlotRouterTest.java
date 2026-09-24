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
    void shouldReturnNullForAllAccessorsWhenSnapshotEmpty() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = new AiSlotRouter(registry, new AiRuntimeConfigHolder());
        assertNull(router.getModel("main"));
        assertNull(router.getEndpoint("main"));
        assertNull(router.resolveModel("main"));
        assertFalse(router.isMultiProviderMode());
    }

    @Test
    void shouldNotBuildModelForDisabledProviderWhenApplyingConfig() {
        AiProviderRegistry registry = new AiProviderRegistry();
        registry.applyConfig(config());
        assertNull(registry.getModel("off"));
        assertNotNull(registry.getProviderEntry("off"));
    }

    @Test
    void shouldReturnBaseModelWhenSlotModelBlank() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = router(registry, new AiRuntimeConfig(
            Map.of("dash", new AiRuntimeConfig.ProviderEntry("https://a", "sk-x", true)),
            Map.of("main", new AiRuntimeConfig.SlotEntry("dash", "", false))));
        assertSame(registry.getModel("dash"), router.getModel("main"));
        assertNull(router.resolveModel("main"));
    }

    @Test
    void shouldResolveStrictSlotModelWithoutMainFallback() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = router(registry, config());
        assertEquals("qwen-vl-ocr", router.resolveSlotModelStrict("ocr"));
        assertNull(router.resolveSlotModelStrict("diagram"));
        assertEquals("qwen-plus", router.resolveModel("diagram"));
    }

    @Test
    void shouldReturnNullStrictWhenSlotModelBlank() {
        AiProviderRegistry registry = new AiProviderRegistry();
        AiSlotRouter router = router(registry, new AiRuntimeConfig(
            Map.of("dash", new AiRuntimeConfig.ProviderEntry("https://a", "sk-x", true)),
            Map.of("main", new AiRuntimeConfig.SlotEntry("dash", "qwen-plus", false),
                   "ocr", new AiRuntimeConfig.SlotEntry("dash", " ", false))));
        assertNull(router.resolveSlotModelStrict("ocr"));
    }

    @Test
    void shouldReturnNullStrictWhenSnapshotEmpty() {
        AiSlotRouter router = new AiSlotRouter(new AiProviderRegistry(), new AiRuntimeConfigHolder());
        assertNull(router.resolveSlotModelStrict("ocr"));
    }
}
