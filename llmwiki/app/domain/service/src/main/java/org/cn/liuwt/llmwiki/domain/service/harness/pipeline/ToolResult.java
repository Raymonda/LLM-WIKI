package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

public record ToolResult(Object value, Throwable error, long elapsedMs) {

    public boolean success() {
        return error == null;
    }

    public static ToolResult ofValue(Object value, long elapsedMs) {
        return new ToolResult(value, null, elapsedMs);
    }

    public static ToolResult ofError(Throwable error, long elapsedMs) {
        return new ToolResult(null, error, elapsedMs);
    }
}
