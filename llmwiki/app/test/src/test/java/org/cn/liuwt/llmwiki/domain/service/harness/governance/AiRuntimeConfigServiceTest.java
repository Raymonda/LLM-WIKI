package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SystemConfigDO;
import org.cn.liuwt.llmwiki.common.util.ConfigCrypto;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiProviderInput;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiProviderView;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiRuntimeConfigSaveRequest;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiRuntimeConfigView;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiSlotInput;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiSlotView;
import org.cn.liuwt.llmwiki.integration.ai.AiConfigChangedEvent;
import org.cn.liuwt.llmwiki.integration.ai.AiRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiRuntimeConfigServiceTest {

    private final ObjectMapper om = new ObjectMapper();

    private record Harness(AiRuntimeConfigService svc, Map<String, String> db, ApplicationEventPublisher pub) {}

    private Harness harness() {
        SystemConfigService scs = mock(SystemConfigService.class);
        Map<String, String> db = new HashMap<>();
        when(scs.getConfig(eq(0L), eq("ai.runtime"))).thenAnswer(inv -> {
            String v = db.get("ai.runtime");
            if (v == null) return null;
            SystemConfigDO d = new SystemConfigDO();
            d.setConfigValue(v);
            return d;
        });
        when(scs.saveConfig(eq(0L), eq("ai.runtime"), anyString(), any())).thenAnswer(inv -> {
            db.put("ai.runtime", inv.getArgument(2));
            SystemConfigDO d = new SystemConfigDO();
            d.setConfigValue(inv.getArgument(2));
            return d;
        });
        ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
        return new Harness(new AiRuntimeConfigService(scs, "test-secret", pub), db, pub);
    }

    private static AiRuntimeConfigSaveRequest req(String key) {
        return new AiRuntimeConfigSaveRequest(
            List.of(new AiProviderInput("dash", "https://dashscope.aliyuncs.com/compatible-mode", key, true)),
            List.of(new AiSlotInput("main", "dash", "qwen-plus", false)));
    }

    @Test
    void shouldMaskApiKeyWhenBuildingView() {
        Harness h = harness();
        h.svc().save(req("sk-abcdefghij"), 1L);
        AiRuntimeConfigView view = h.svc().getMaskedView();
        assertEquals(1, view.providers().size());
        AiProviderView p = view.providers().get(0);
        assertTrue(p.apiKeyConfigured());
        assertEquals("sk-…ghij", p.apiKeyMasked());
        assertFalse(p.apiKeyMasked().contains("abcdefghij"));
        verify(h.pub()).publishEvent(any(AiConfigChangedEvent.class));
    }

    @Test
    void shouldKeepStoredKeyWhenSavePayloadHasBlankApiKey() throws Exception {
        Harness h = harness();
        h.svc().save(req("sk-real"), 1L);
        h.svc().save(req(""), 1L);
        String json = h.db().get("ai.runtime");
        JsonNode node = om.readTree(json);
        String storedKey = node.get("providers").get(0).get("apiKey").asText();
        assertNotEquals("sk-real", storedKey);
        assertEquals("sk-real", new ConfigCrypto("test-secret").decrypt(storedKey));
    }

    @Test
    void shouldRejectSlotReferencingUnknownProviderWhenSaving() {
        Harness h = harness();
        AiRuntimeConfigSaveRequest bad = new AiRuntimeConfigSaveRequest(
            List.of(new AiProviderInput("dash", "https://x", "sk-y", true)),
            List.of(new AiSlotInput("main", "ghost", "qwen-plus", false)));
        assertThrows(BusinessException.class, () -> h.svc().save(bad, 1L));
    }

    @Test
    void shouldRejectMissingMainSlotWhenSaving() {
        Harness h = harness();
        AiRuntimeConfigSaveRequest bad = new AiRuntimeConfigSaveRequest(
            List.of(new AiProviderInput("dash", "https://x", "sk-y", true)),
            List.of(new AiSlotInput("ocr", "dash", "qwen-vl-ocr", false)));
        assertThrows(BusinessException.class, () -> h.svc().save(bad, 1L));
    }

    @Test
    void shouldRejectBlankModelWhenSlotHasProvider() {
        Harness h = harness();
        AiRuntimeConfigSaveRequest bad = new AiRuntimeConfigSaveRequest(
            List.of(new AiProviderInput("dash", "https://x", "sk-y", true)),
            List.of(new AiSlotInput("main", "dash", "", false)));
        assertThrows(BusinessException.class, () -> h.svc().save(bad, 1L));
    }

    @Test
    void shouldReturnEmptyWhenResolvingEffectiveWithEmptyDb() {
        Harness h = harness();
        assertTrue(h.svc().resolveEffective().isEmpty());
    }

    @Test
    void shouldPersistMultimodalFlagWhenSaving() {
        Harness h = harness();
        h.svc().save(new AiRuntimeConfigSaveRequest(
            List.of(new AiProviderInput("dash", "https://x", "sk-y", true)),
            List.of(new AiSlotInput("main", "dash", "qwen-plus", true),
                new AiSlotInput("ocr", "dash", "qwen-vl-ocr", null))), 1L);
        AiRuntimeConfigView view = h.svc().getMaskedView();
        AiSlotView main = view.slots().stream().filter(s -> "main".equals(s.slot())).findFirst().orElseThrow();
        AiSlotView ocr = view.slots().stream().filter(s -> "ocr".equals(s.slot())).findFirst().orElseThrow();
        assertTrue(main.multimodal());
        assertFalse(ocr.multimodal());
    }

    @Test
    void shouldDropUnknownSlotsWhenReadingStoredConfig() {
        Harness h = harness();
        h.db().put("ai.runtime", "{\"providers\":[{\"name\":\"dash\",\"baseUrl\":\"https://x\",\"enabled\":true}],"
            + "\"slots\":[{\"slot\":\"main\",\"provider\":\"dash\",\"model\":\"qwen-plus\"},"
            + "{\"slot\":\"query-multimodal\",\"provider\":\"dash\",\"model\":\"qwen-vl\"}]}");
        AiRuntimeConfigView view = h.svc().getMaskedView();
        assertEquals(1, view.slots().size());
        assertEquals("main", view.slots().get(0).slot());
    }
}
