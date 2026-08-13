package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.query.QueryClarifier;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
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
}