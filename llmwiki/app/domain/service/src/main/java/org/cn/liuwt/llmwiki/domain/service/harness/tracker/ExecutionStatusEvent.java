package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import org.springframework.context.ApplicationEvent;

public class ExecutionStatusEvent extends ApplicationEvent {

    private final Long executionId;
    private final String executionType;
    private final String newStatus;
    private final String complianceViolations;

    public ExecutionStatusEvent(Object source, Long executionId, String executionType, String newStatus) {
        super(source);
        this.executionId = executionId;
        this.executionType = executionType;
        this.newStatus = newStatus;
        this.complianceViolations = null;
    }

    public ExecutionStatusEvent(Object source, Long executionId, String executionType, String newStatus, String complianceViolations) {
        super(source);
        this.executionId = executionId;
        this.executionType = executionType;
        this.newStatus = newStatus;
        this.complianceViolations = complianceViolations;
    }

    public Long getExecutionId() { return executionId; }
    public String getExecutionType() { return executionType; }
    public String getNewStatus() { return newStatus; }
    public String getComplianceViolations() { return complianceViolations; }
}
