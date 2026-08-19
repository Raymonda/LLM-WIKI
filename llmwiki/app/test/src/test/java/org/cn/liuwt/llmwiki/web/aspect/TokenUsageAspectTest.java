package org.cn.liuwt.llmwiki.web.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.TokenUsageDailyService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.TokenUsageMonitor;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenUsageAspectTest {

    private TokenUsageAspect aspect;
    private TokenUsageMonitor monitor;
    private TokenUsageDailyService dailyService;

    @BeforeEach
    void setUp() {
        aspect = new TokenUsageAspect();
        monitor = mock(TokenUsageMonitor.class);
        dailyService = mock(TokenUsageDailyService.class);
        ReflectionTestUtils.setField(aspect, "tokenUsageMonitor", monitor);
        ReflectionTestUtils.setField(aspect, "dailyService", dailyService);
    }

    @AfterEach
    void tearDown() {
        TokenUsageContext.clear();
        LlmClient.getAndClearLastUsage();
    }

    @Test
    void shouldSkipUsageRecordingWhenResultIsPublisher() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Flux<String> flux = Flux.just("chunk");
        when(pjp.proceed()).thenReturn(flux);
        TokenUsageContext.set(7L, "query");

        Object result = aspect.recordTokenUsage(pjp);

        assertSame(flux, result);
        verify(monitor, never()).recordUsage(anyLong(), anyInt());
        verify(dailyService, never()).recordDaily(anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void shouldRecordUsageWhenSyncChatCompleted() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("content");
        TokenUsageContext.set(7L, "query");
        setLastCallUsage(new LlmClient.TokenCallUsage(100, 50));

        aspect.recordTokenUsage(pjp);

        verify(monitor).recordUsage(7L, 150);
        verify(dailyService).recordDaily(7L, "query", 100, 50);
    }

    @Test
    void shouldDropUsageWhenNoContextOnThread() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("content");
        setLastCallUsage(new LlmClient.TokenCallUsage(100, 50));

        aspect.recordTokenUsage(pjp);

        verify(monitor, never()).recordUsage(anyLong(), anyInt());
        verify(dailyService, never()).recordDaily(anyLong(), anyString(), anyInt(), anyInt());
    }

    @SuppressWarnings("unchecked")
    private void setLastCallUsage(LlmClient.TokenCallUsage usage) {
        ThreadLocal<LlmClient.TokenCallUsage> holder =
            (ThreadLocal<LlmClient.TokenCallUsage>) ReflectionTestUtils.getField(LlmClient.class, "lastCallUsage");
        holder.set(usage);
    }
}
