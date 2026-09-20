package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.springframework.context.ApplicationEvent;

public class IngestAutoConfirmedEvent extends ApplicationEvent {

    private final Long scopeId;
    private final Long executionId;

    public IngestAutoConfirmedEvent(Object source, Long scopeId, Long executionId) {
        super(source);
        this.scopeId = scopeId;
        this.executionId = executionId;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public Long getExecutionId() {
        return executionId;
    }
}
