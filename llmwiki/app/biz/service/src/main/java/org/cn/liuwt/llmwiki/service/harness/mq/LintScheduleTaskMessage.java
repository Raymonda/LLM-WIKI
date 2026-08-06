package org.cn.liuwt.llmwiki.service.harness.mq;

import lombok.Data;

@Data
public class LintScheduleTaskMessage {

    public static final String TOPIC = "llmwiki-lint-schedule";
    public static final String CONSUMER_GROUP = "cg_llmwiki-lint-schedule";

    private Long scopeId;
    private String scopeType;
    private boolean fullScan;
    private String triggeredByNodeId;
    private Long submittedAt;
}
