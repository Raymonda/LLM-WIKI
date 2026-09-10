package org.cn.liuwt.llmwiki.service.harness.mq;

import lombok.Data;

import java.util.List;

@Data
public class PipelineTaskMessage {

    public static final String TOPIC = "llmwiki-pipeline-task";
    public static final String CONSUMER_GROUP = "cg_llmwiki-pipeline-task";

    public static final String TYPE_INGEST_START = "INGEST_START";
    public static final String TYPE_INGEST_ANALYZE = "INGEST_ANALYZE";
    public static final String TYPE_INGEST_EXECUTE = "INGEST_EXECUTE";
    public static final String TYPE_INGEST_RESUME = "INGEST_RESUME";
    public static final String TYPE_INGEST_REANALYZE = "INGEST_REANALYZE";

    public static final String TYPE_LINT_START = "LINT_START";

    public static final String TYPE_MERGE = "MERGE";
    public static final String TYPE_PAGE_SAVE_POST = "PAGE_SAVE_POST";
    public static final String TYPE_SCHEMA_POLISH = "SCHEMA_POLISH";

    private Long executionId;
    private Long scopeId;
    private Long sourceId;
    private String guidance;
    private String taskType;
    private boolean fullScan;
    private String nodeId;
    private Long submittedAt;
    private List<Long> originalPageIds;

    // PAGE_SAVE_POST 专用字段
    private Long pageId;
    private String content;
    private String category;
    private String contentHash;
}
