package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestOrchestratorBatchContextTest {

    @Test
    void shouldTreatExecutionWithBatchIdAsBatchContext() {
        ExecutionModel execution = new ExecutionModel();
        execution.setBatchId(9L);

        assertTrue(IngestOrchestrator.isBatchContext(execution));
    }

    @Test
    void shouldNotTreatStandaloneExecutionAsBatchContext() {
        ExecutionModel execution = new ExecutionModel();

        assertFalse(IngestOrchestrator.isBatchContext(execution));
    }

    @Test
    void shouldNotTreatNullExecutionAsBatchContext() {
        assertFalse(IngestOrchestrator.isBatchContext(null));
    }
}
