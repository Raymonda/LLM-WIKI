package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import org.springframework.context.ApplicationEvent;

public class StepStatusEvent extends ApplicationEvent {

    private final Long executionId;
    private final String executionType;
    private final Long stepId;
    private final String stepName;
    private final String status;
    private final String outputData;

    public StepStatusEvent(Object source, Long executionId, String executionType, Long stepId, String stepName, String status, String outputData) {
        super(source);
        this.executionId = executionId;
        this.executionType = executionType;
        this.stepId = stepId;
        this.stepName = stepName;
        this.status = status;
        this.outputData = outputData;
    }

    public Long getExecutionId() { return executionId; }
    public String getExecutionType() { return executionType; }
    public Long getStepId() { return stepId; }
    public String getStepName() { return stepName; }
    public String getStatus() { return status; }
    public String getOutputData() { return outputData; }
}