package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

public class ToolInvocationException extends RuntimeException {

    public ToolInvocationException(String message) {
        super(message);
    }

    public ToolInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
