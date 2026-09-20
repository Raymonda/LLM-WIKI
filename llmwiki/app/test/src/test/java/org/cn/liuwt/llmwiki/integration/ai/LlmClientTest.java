package org.cn.liuwt.llmwiki.integration.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmClientTest {

    @Test
    void shouldFailFastWithGuidanceWhenMainModelNotConfigured() {
        LlmClient client = new LlmClient();
        inject(client, "chatClient", mock(ChatClient.class));
        AiSlotRouter router = mock(AiSlotRouter.class);
        when(router.resolveModel("main")).thenReturn("");
        inject(client, "slotRouter", router);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> client.chat("s", "u"));
        assertTrue(ex.getMessage().contains("系统设置"));
    }

    @Test
    void shouldFailFastWithGuidanceWhenMultimodalModelNotConfigured() {
        LlmClient client = new LlmClient();
        inject(client, "chatClient", mock(ChatClient.class));
        inject(client, "apiKeyValid", true);
        AiSlotRouter router = mock(AiSlotRouter.class);
        when(router.getEndpoint("multimodal"))
            .thenReturn(new AiSlotRouter.Endpoint("https://x", "sk-y", ""));
        inject(client, "slotRouter", router);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> client.chatMultimodal("s", "u", List.of(new LlmClient.MultimodalImageInput("image/png", "AAAA"))));
        assertTrue(ex.getMessage().contains("multimodal"));
    }

    private static void inject(Object target, String field, Object value) {
        try {
            var f = LlmClient.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
