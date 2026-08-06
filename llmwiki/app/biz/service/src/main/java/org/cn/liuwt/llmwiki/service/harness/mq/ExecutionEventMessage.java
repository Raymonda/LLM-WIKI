package org.cn.liuwt.llmwiki.service.harness.mq;

import lombok.Data;

@Data
public class ExecutionEventMessage {

    public static final String TOPIC = "llmwiki-execution-event";
    public static final String CONSUMER_GROUP = "cg_llmwiki-execution-event";

    public static final String TYPE_EXECUTION_STATUS = "EXECUTION_STATUS";
    public static final String TYPE_STEP_STATUS = "STEP_STATUS";
    public static final String TYPE_STEP_PROGRESS = "STEP_PROGRESS";

    private String eventType;
    private Long executionId;
    private String executionType;
    private Long stepId;
    private String stepName;
    private String status;
    private String outputData;
    private String complianceViolations;
    private Integer current;
    private Integer total;
    private Long avgMsPerUnit;
    private Integer chunkIndex;
    private String chunkPreview;
    private Long publishedAt;
}
