package org.cn.liuwt.llmwiki.domain.service.harness.eventlog;

public final class ExecutionEventTypes {

    public static final String TURN_START = "turn/start";
    public static final String STEP_START = "step/start";
    public static final String TOOL_CALL = "tool/call";
    public static final String TOOL_RESULT = "tool/result";
    public static final String STEP_END = "step/end";
    public static final String TURN_END = "turn/end";
    public static final String ERROR = "error";
    public static final String COMPACTION_TRIGGERED = "compaction/triggered";
    public static final String SPILL_WRITTEN = "spill/written";

    private ExecutionEventTypes() {
    }
}
