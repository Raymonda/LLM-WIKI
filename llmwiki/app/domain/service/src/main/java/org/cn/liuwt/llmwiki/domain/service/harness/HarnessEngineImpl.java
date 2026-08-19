package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.RateLimitService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class HarnessEngineImpl implements HarnessEngine {

    private static final Logger log = LoggerFactory.getLogger(HarnessEngineImpl.class);

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private PipelineOrchestrator pipelineOrchestrator;

    @Autowired
    private AgentRunner agentRunner;

    @Override
    public ExecutionModel executeIngest(Long scopeId, Long sourceId) {
        log.info("Starting ingest for scopeId={}, sourceId={}", scopeId, sourceId);
        return pipelineOrchestrator.runIngestPipeline(scopeId, sourceId);
    }

    @Override
    public Flux<String> executeQueryStreaming(Long scopeId, String question, String sessionId, boolean deepMode, String assumedIntent) {
        log.info("Starting streaming query for scopeId={}, sessionId={}, deepMode={}, assumedIntent={}", scopeId, sessionId, deepMode, assumedIntent);
        return agentRunner.runQueryAgentStreaming(scopeId, question, sessionId, deepMode, assumedIntent);
    }

    @Override
    public Flux<String> executeQueryStreamingMultiScope(java.util.List<Long> scopeIds, String question, String sessionId, boolean deepMode, String assumedIntent) {
        log.info("Starting multi-scope streaming query for scopeIds={}, sessionId={}, deepMode={}, assumedIntent={}", scopeIds, sessionId, deepMode, assumedIntent);
        return agentRunner.runQueryAgentStreamingMultiScope(scopeIds, question, sessionId, deepMode, assumedIntent);
    }

    @Override
    public ExecutionModel executeLint(Long scopeId, boolean fullScan) {
        log.info("Starting lint for scopeId={}, fullScan={}", scopeId, fullScan);
        return pipelineOrchestrator.runLintPipeline(scopeId, fullScan);
    }

    @Override
    public ExecutionModel executeLintWithExecution(Long executionId, Long scopeId, boolean fullScan) {
        // MQ 路径下此方法缺少并发锁（旧路径 doRunLintPipeline 已有 tryAcquireConcurrent）
        // doRunLintPipelineWithExecution 内部会 releaseConcurrent，此处需匹配 acquire
        if (!rateLimitService.tryAcquireConcurrent(scopeId)) {
            throw new RuntimeException("并发执行数量已达上限，请等待当前任务完成后再试。scopeId=" + scopeId);
        }
        log.info("Starting lint with execution: executionId={}, scopeId={}, fullScan={}", executionId, scopeId, fullScan);
        return pipelineOrchestrator.runLintPipelineWithExecution(executionId, scopeId, fullScan);
    }

    @Override
    public ExecutionModel getExecution(Long executionId) {
        return executionTracker.getExecution(executionId);
    }

    @Override
    public WikiPageDO executeSaveQueryResult(Long scopeId, String question, String answer, String sessionId) {
        log.info("Starting query save for scopeId={}", scopeId);
        return pipelineOrchestrator.runSaveQueryResultPipeline(scopeId, question, answer, sessionId);
    }
}
