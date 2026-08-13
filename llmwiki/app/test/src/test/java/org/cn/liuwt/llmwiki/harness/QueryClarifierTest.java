package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.query.QueryClarifier;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class QueryClarifierTest {

    @Test
    void shouldReturnForcedClearOnSecondAmbiguousForSameSession() {
        QueryClarifier clarifier = new QueryClarifier(1);
        AtomicBoolean forced = new AtomicBoolean();
        QueryClarifier.ClarificationResult first = clarifier.assessForTest("s1", "AMBIGUOUS", forced);
        assertEquals("AMBIGUOUS", first.clarity());
        QueryClarifier.ClarificationResult second = clarifier.assessForTest("s1", "AMBIGUOUS", forced);
        assertEquals("CLEAR", second.clarity());
        assertEquals("forced-clear", second.reason());
        assertTrue(forced.get());
    }

    @Test
    void shouldNotForceClearForDifferentSessions() {
        QueryClarifier clarifier = new QueryClarifier(1);
        clarifier.assessForTest("s1", "AMBIGUOUS", new AtomicBoolean());
        QueryClarifier.ClarificationResult second = clarifier.assessForTest("s2", "AMBIGUOUS", new AtomicBoolean());
        assertEquals("AMBIGUOUS", second.clarity());
    }

    @Test
    void shouldParseJsonClarityAndClarification() {
        QueryClarifier clarifier = new QueryClarifier(5);
        QueryClarifier.ClarificationResult result = clarifier.assessForTest(
            "s-json",
            "{\"clarity\":\"AMBIGUOUS\",\"clarification\":\"请补充公司名称\",\"reason\":\"missing-subject\"}",
            new AtomicBoolean());
        assertEquals("AMBIGUOUS", result.clarity());
        assertEquals("请补充公司名称", result.clarification());
    }

    @Test
    void shouldDefaultToClearOnMalformedJson() {
        QueryClarifier clarifier = new QueryClarifier(5);
        QueryClarifier.ClarificationResult result = clarifier.assessForTest("s-bad", "{not json", new AtomicBoolean());
        assertEquals("CLEAR", result.clarity());
        assertEquals("parse-failed", result.reason());
    }

    @Test
    void shouldParseOptionsWhenAmbiguousJudgmentContainsOptions() {
        QueryClarifier clarifier = new QueryClarifier(5);
        QueryClarifier.ClarificationResult result = clarifier.assessForTest(
            "s-opt",
            "{\"clarity\":\"AMBIGUOUS\",\"clarification\":\"请选择目标公司\",\"reason\":\"missing-subject\",\"options\":[\"公司A\",\"公司B\"]}",
            new AtomicBoolean());
        assertEquals("AMBIGUOUS", result.clarity());
        assertEquals(List.of("公司A", "公司B"), result.options());
    }

    @Test
    void shouldDefaultToEmptyOptionsWhenMissing() {
        QueryClarifier clarifier = new QueryClarifier(5);
        QueryClarifier.ClarificationResult result = clarifier.assessForTest(
            "s-noopt",
            "{\"clarity\":\"AMBIGUOUS\",\"clarification\":\"请补充公司名称\",\"reason\":\"missing-subject\"}",
            new AtomicBoolean());
        assertEquals("AMBIGUOUS", result.clarity());
        assertEquals(List.of(), result.options());
    }

    @Test
    void shouldNotExceedMaxClarificationsUnderConcurrency() throws Exception {
        QueryClarifier clarifier = new QueryClarifier(4);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ambiguous = new AtomicInteger();
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(() -> {
                start.await();
                QueryClarifier.ClarificationResult result = clarifier.assessForTest("s-conc", "AMBIGUOUS", null);
                if ("AMBIGUOUS".equals(result.clarity())) ambiguous.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        for (Future<?> future : futures) future.get();
        pool.shutdown();
        assertTrue(ambiguous.get() <= 4);
    }
}