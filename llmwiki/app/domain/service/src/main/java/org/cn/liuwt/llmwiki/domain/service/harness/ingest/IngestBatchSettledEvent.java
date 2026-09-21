package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.springframework.context.ApplicationEvent;

import java.util.List;

public class IngestBatchSettledEvent extends ApplicationEvent {

    private final Long batchId;
    private final Long scopeId;
    private final List<Long> pageIds;

    public IngestBatchSettledEvent(Object source, Long batchId, Long scopeId, List<Long> pageIds) {
        super(source);
        this.batchId = batchId;
        this.scopeId = scopeId;
        this.pageIds = pageIds == null ? List.of() : List.copyOf(pageIds);
    }

    public Long getBatchId() {
        return batchId;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public List<Long> getPageIds() {
        return pageIds;
    }
}
