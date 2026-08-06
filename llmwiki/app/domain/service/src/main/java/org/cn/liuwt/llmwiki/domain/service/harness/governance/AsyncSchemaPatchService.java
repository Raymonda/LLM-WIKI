package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import org.cn.liuwt.llmwiki.domain.model.harness.SchemaPatchModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AsyncSchemaPatchService {

    private static final Logger log = LoggerFactory.getLogger(AsyncSchemaPatchService.class);

    @Autowired
    private SchemaPatchProposer schemaPatchProposer;

    @Async("schemaPatchExecutor")
    public void proposeAsync(Long scopeId, Long executionId, SchemaPatchModel.SourceType sourceType, String patchSummary) {
        try {
            int count = schemaPatchProposer.propose(scopeId, executionId, sourceType, patchSummary);
            log.info("Async Schema patch proposal completed: scopeId={}, executionId={}, patches={}", scopeId, executionId, count);
        } catch (Exception e) {
            log.error("Async Schema patch proposal failed: scopeId={}, executionId={}, error={}", scopeId, executionId, e.getMessage(), e);
        }
    }
}