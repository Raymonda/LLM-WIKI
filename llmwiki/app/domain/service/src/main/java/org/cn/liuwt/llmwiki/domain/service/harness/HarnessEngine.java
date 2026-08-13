package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import reactor.core.publisher.Flux;

import java.util.List;

public interface HarnessEngine {
    ExecutionModel executeIngest(Long scopeId, Long sourceId);
    Flux<String> executeQueryStreaming(Long scopeId, String question, String sessionId, boolean deepMode, String assumedIntent);
    Flux<String> executeQueryStreamingMultiScope(List<Long> scopeIds, String question, String sessionId, boolean deepMode, String assumedIntent);
    ExecutionModel executeLint(Long scopeId, boolean fullScan);
    ExecutionModel executeLintWithExecution(Long executionId, Long scopeId, boolean fullScan);
    ExecutionModel getExecution(Long executionId);
    WikiPageDO executeSaveQueryResult(Long scopeId, String question, String answer, String sessionId);
}
