package org.cn.liuwt.llmwiki.service.query;

import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FunFactServiceTest {

    @Test
    void shouldSetQueryContextInsideAsyncThreadWhenGeneratingFunFacts() throws Exception {
        FunFactService service = new FunFactService();
        LlmClient chatClient = mock(LlmClient.class);
        ReflectionTestUtils.setField(service, "chatClient", chatClient);

        CompletableFuture<Long> capturedScope = new CompletableFuture<>();
        CompletableFuture<String> capturedType = new CompletableFuture<>();
        when(chatClient.chat(anyString())).thenAnswer(inv -> {
            TokenUsageContext.Context ctx = TokenUsageContext.get();
            capturedScope.complete(ctx != null ? ctx.scopeId() : null);
            capturedType.complete(ctx != null ? ctx.operationType() : null);
            return "[]";
        });

        CompletableFuture<List<FunFactService.FunFact>> result = service.generateFunFactsAsync(7L, "债券是什么");

        assertEquals(List.of(), result.get(10, TimeUnit.SECONDS));
        assertEquals(7L, capturedScope.get(10, TimeUnit.SECONDS));
        assertEquals("query", capturedType.get(10, TimeUnit.SECONDS));
    }
}
