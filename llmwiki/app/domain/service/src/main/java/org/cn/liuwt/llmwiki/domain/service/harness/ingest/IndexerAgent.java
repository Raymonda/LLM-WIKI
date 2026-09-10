package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.domain.service.harness.crossref.CrossRefDomainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class IndexerAgent {

    private static final Logger log = LoggerFactory.getLogger(IndexerAgent.class);

    @Autowired
    private CrossRefDomainService crossRefDomainService;

    public CompletableFuture<Integer> startLinksGeneration(IngestContext context) {
        return startLinksGeneration(context, null);
    }

    public CompletableFuture<Integer> startLinksGeneration(IngestContext context, String pageInventory) {
        Long scopeId = context.getScopeId();

        return crossRefDomainService.generateLinksForIngestAsync(
            context, scopeId, context.getExecutionId());
    }
}
