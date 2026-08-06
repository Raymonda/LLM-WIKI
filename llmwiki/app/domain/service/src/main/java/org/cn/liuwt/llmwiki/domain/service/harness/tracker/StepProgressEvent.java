package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import org.springframework.context.ApplicationEvent;

public class StepProgressEvent extends ApplicationEvent {

    private final Long executionId;
    private final String executionType;
    private final Long stepId;
    private final String stepName;
    private final Integer current;
    private final Integer total;
    private final Long avgMsPerUnit;
    private final Integer chunkIndex;
    private final String chunkPreview;

    public StepProgressEvent(Object source,
                             Long executionId,
                             String executionType,
                             Long stepId,
                             String stepName,
                             Integer current,
                             Integer total,
                             Long avgMsPerUnit) {
        this(source, executionId, executionType, stepId, stepName, current, total, avgMsPerUnit, null, null);
    }

    public StepProgressEvent(Object source,
                             Long executionId,
                             String executionType,
                             Long stepId,
                             String stepName,
                             Integer current,
                             Integer total,
                             Long avgMsPerUnit,
                             Integer chunkIndex,
                             String chunkPreview) {
        super(source);
        this.executionId = executionId;
        this.executionType = executionType;
        this.stepId = stepId;
        this.stepName = stepName;
        this.current = current;
        this.total = total;
        this.avgMsPerUnit = avgMsPerUnit;
        this.chunkIndex = chunkIndex;
        this.chunkPreview = chunkPreview;
    }

    public Long getExecutionId() { return executionId; }
    public String getExecutionType() { return executionType; }
    public Long getStepId() { return stepId; }
    public String getStepName() { return stepName; }
    public Integer getCurrent() { return current; }
    public Integer getTotal() { return total; }
    public Long getAvgMsPerUnit() { return avgMsPerUnit; }
    public Integer getChunkIndex() { return chunkIndex; }
    public String getChunkPreview() { return chunkPreview; }
}
