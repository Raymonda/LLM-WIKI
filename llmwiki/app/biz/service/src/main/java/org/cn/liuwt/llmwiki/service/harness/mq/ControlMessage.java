package org.cn.liuwt.llmwiki.service.harness.mq;

import lombok.Data;

@Data
public class ControlMessage {

    public static final String TOPIC = "llmwiki-execution-ctrl";
    public static final String CONSUMER_GROUP = "cg_llmwiki-execution-ctrl";

    public static final String ACTION_CANCEL = "CANCEL";
    public static final String ACTION_PAUSE = "PAUSE";

    private Long executionId;
    private String action;
    private String reason;
    private String issuedBy;
    private Long issuedAt;
}
