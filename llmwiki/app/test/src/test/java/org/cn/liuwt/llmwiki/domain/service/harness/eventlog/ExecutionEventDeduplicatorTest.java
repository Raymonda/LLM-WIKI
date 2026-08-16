package org.cn.liuwt.llmwiki.domain.service.harness.eventlog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEventDeduplicatorTest {

    @Test
    void admit_increasingSeq_admitted() {
        ExecutionEventDeduplicator deduplicator = new ExecutionEventDeduplicator();

        assertTrue(deduplicator.admit("exec-1", 1L));
        assertTrue(deduplicator.admit("exec-1", 2L));
        assertTrue(deduplicator.admit("exec-1", 10L));
        assertEquals(10L, deduplicator.lastSeenSeq("exec-1"));
    }

    @Test
    void admit_duplicateOrOlderSeq_rejected() {
        ExecutionEventDeduplicator deduplicator = new ExecutionEventDeduplicator();
        deduplicator.admit("exec-1", 5L);

        assertFalse(deduplicator.admit("exec-1", 5L));
        assertFalse(deduplicator.admit("exec-1", 4L));
        assertFalse(deduplicator.admit("exec-1", 0L));
        assertEquals(5L, deduplicator.lastSeenSeq("exec-1"));
    }

    @Test
    void admit_parallelExecutions_independent() {
        ExecutionEventDeduplicator deduplicator = new ExecutionEventDeduplicator();
        deduplicator.admit("exec-1", 3L);

        assertTrue(deduplicator.admit("exec-2", 1L));
        assertTrue(deduplicator.admit("exec-2", 2L));
        assertFalse(deduplicator.admit("exec-1", 2L));
    }

    @Test
    void admit_nullOrBlankExecutionIdOrNegativeSeq_rejected() {
        ExecutionEventDeduplicator deduplicator = new ExecutionEventDeduplicator();

        assertFalse(deduplicator.admit(null, 1L));
        assertFalse(deduplicator.admit(" ", 1L));
        assertFalse(deduplicator.admit("exec-1", -1L));
        assertNull(deduplicator.lastSeenSeq(null));
    }

    @Test
    void reset_allowsReplayFromBeginning() {
        ExecutionEventDeduplicator deduplicator = new ExecutionEventDeduplicator();
        deduplicator.admit("exec-1", 7L);

        deduplicator.reset("exec-1");

        assertTrue(deduplicator.admit("exec-1", 1L));
    }
}
