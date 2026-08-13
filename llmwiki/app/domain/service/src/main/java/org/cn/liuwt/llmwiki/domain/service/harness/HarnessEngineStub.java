package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import reactor.core.publisher.Flux;

public class HarnessEngineStub implements HarnessEngine {
    @Override
    public ExecutionModel executeIngest(Long scopeId, Long sourceId) {
        throw new UnsupportedOperationException("HarnessEngine not implemented yet");
    }

    @Override
    public Flux<String> executeQueryStreaming(Long scopeId, String question, String sessionId, boolean deepMode, String assumedIntent) {
        throw new UnsupportedOperationException("HarnessEngine not implemented yet");
    }

    @Override
    public Flux<String> executeQueryStreamingMultiScope(java.util.List<Long> scopeIds, String question, String sessionId, boolean deepMode, String assumedIntent) {
        throw new UnsupportedOperationException("HarnessEngine not implemented yet");
    }

    @Override
    public ExecutionModel executeLint(Long scopeId, boolean fullScan) {
        throw new UnsupportedOperationException("HarnessEngine not implemented yet");
    }

    @Override
    public ExecutionModel executeLintWithExecution(Long executionId, Long scopeId, boolean fullScan) {
        throw new UnsupportedOperationException("HarnessEngine not implemented yet");
    }

    @Override
    public ExecutionModel getExecution(Long executionId) {
        throw new UnsupportedOperationException("HarnessEngine not implemented yet");
    }

    @Override
    public WikiPageDO executeSaveQueryResult(Long scopeId, String question, String answer, String sessionId) {
        throw new UnsupportedOperationException("HarnessEngine not implemented yet");
    }
}
