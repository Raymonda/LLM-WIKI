package org.cn.liuwt.llmwiki.domain.service.harness.eventlog;

public final class TestEventLogs {

    public static ExecutionEventLogService disabled() {
        return new ExecutionEventLogService(null, null, new EventLogProperties());
    }

    private TestEventLogs() {
    }
}
