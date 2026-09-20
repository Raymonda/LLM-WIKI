package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.domain.service.harness.ingest.AutoConfirmPolicy.AutoDecision;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.AutoConfirmPolicy.Signals;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoConfirmPolicyTest {

    private final AutoConfirmPolicy policy = new AutoConfirmPolicy(3, 3, 0.5, 0.5);

    @Test
    void shouldAutoApproveWhenNoSignals() {
        AutoDecision d = policy.decide(new Signals(false, 0, 1.0, 0.0, false, false));
        assertTrue(d.autoApprove());
        assertFalse(d.hardBlocked());
        assertEquals(0, d.softScore());
        assertTrue(d.reasons().isEmpty());
    }

    @Test
    void shouldManualReviewWhenSchemaViolation() {
        AutoDecision d = policy.decide(new Signals(true, 0, 1.0, 0.0, false, false));
        assertFalse(d.autoApprove());
        assertTrue(d.hardBlocked());
        assertTrue(d.reasons().contains("schema_precheck_violation"));
    }

    @Test
    void shouldManualReviewWhenConflictsExceedThreshold() {
        AutoDecision d = policy.decide(new Signals(false, 4, 1.0, 0.0, false, false));
        assertFalse(d.autoApprove());
        assertTrue(d.hardBlocked());
        assertTrue(d.reasons().contains("conflict_count:4"));
    }

    @Test
    void shouldAutoApproveWhenConflictsAtThreshold() {
        AutoDecision d = policy.decide(new Signals(false, 3, 1.0, 0.0, false, false));
        assertTrue(d.autoApprove());
        assertFalse(d.hardBlocked());
        assertEquals(0, d.softScore());
    }

    @Test
    void shouldManualReviewWhenSoftScoreReachesThreshold() {
        AutoDecision d = policy.decide(new Signals(false, 0, 0.32, 0.0, true, false));
        assertFalse(d.autoApprove());
        assertFalse(d.hardBlocked());
        assertEquals(3, d.softScore());
        assertTrue(d.reasons().contains("low_completeness:0.32"));
        assertTrue(d.reasons().contains("schema_gap_hints"));
    }

    @Test
    void shouldAutoApproveWhenSoftScoreBelowThreshold() {
        AutoDecision d = policy.decide(new Signals(false, 0, 1.0, 0.0, true, true));
        assertTrue(d.autoApprove());
        assertFalse(d.hardBlocked());
        assertEquals(2, d.softScore());
        assertTrue(d.reasons().contains("schema_gap_hints"));
        assertTrue(d.reasons().contains("parse_degraded"));
    }

    @Test
    void shouldHardBlockTakePrecedenceOverSoftSignals() {
        AutoDecision d = policy.decide(new Signals(true, 5, 0.32, 0.9, true, true));
        assertFalse(d.autoApprove());
        assertTrue(d.hardBlocked());
        assertEquals(0, d.softScore());
        assertFalse(d.reasons().stream().anyMatch(r -> r.startsWith("low_completeness")));
        assertFalse(d.reasons().stream().anyMatch(r -> r.startsWith("high_update_ratio")));
    }
}
