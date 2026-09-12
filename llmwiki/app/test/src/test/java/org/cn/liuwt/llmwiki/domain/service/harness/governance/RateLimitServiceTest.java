package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitServiceTest {

    private final RateLimitService service = new RateLimitService();

    @Test
    void shouldAcquireImmediatelyWhenNoRecentCall() {
        long start = System.currentTimeMillis();

        boolean acquired = service.awaitCallRate(9L);

        assertTrue(acquired);
        assertTrue(System.currentTimeMillis() - start < 300, "expected immediate acquisition");
    }

    @Test
    void shouldWaitThenAcquireWhenWithinMinInterval() {
        assertTrue(service.checkCallRate(3L));
        long start = System.currentTimeMillis();

        boolean acquired = service.awaitCallRate(3L);

        assertTrue(acquired);
        assertTrue(System.currentTimeMillis() - start >= 450, "expected wait for min interval");
    }
}
