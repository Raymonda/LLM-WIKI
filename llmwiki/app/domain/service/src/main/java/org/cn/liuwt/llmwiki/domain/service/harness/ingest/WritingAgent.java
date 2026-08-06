package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WritingAgent {

    private static final Logger log = LoggerFactory.getLogger(WritingAgent.class);

    @Autowired
    private WriterOrchestrator writerOrchestrator;

    public int process(IngestContext context) {
        log.info("WritingAgent started for scopeId={}, sourceId={}", context.getScopeId(), context.getSourceId());
        int tokensUsed = writerOrchestrator.writeWithVerification(context);
        log.info("WritingAgent completed for scopeId={}, tokensUsed={}", context.getScopeId(), tokensUsed);
        return tokensUsed;
    }
}
