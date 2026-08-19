package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPipeline.class);

    private final List<ToolFilter> filters;
    private final ExecutionEventLogService eventLog;

    public ToolExecutionPipeline(List<ToolFilter> filters, ExecutionEventLogService eventLog) {
        this.filters = new ArrayList<>(filters);
        this.filters.sort(Comparator.comparingInt(ToolFilter::getOrder));
        this.eventLog = eventLog;
    }

    public Object invoke(ToolInvocation invocation, InvocationBody body) throws Throwable {
        for (ToolFilter filter : filters) {
            FilterVerdict verdict = filter.preFilter(invocation);
            if (!verdict.allowed()) {
                log.warn("Tool invocation rejected: tool={}, by={}, reason={}, scopeId={}, path={}",
                        invocation.toolName(), verdict.rejectedBy(), verdict.reason(), invocation.scopeId(), invocation.path());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tool", invocation.toolName());
                payload.put("rejectedBy", verdict.rejectedBy());
                payload.put("reason", verdict.reason());
                eventLog.append(executionIdOf(invocation), ExecutionEventTypes.ERROR, payload);
                throw new ToolInvocationException("工具调用被守卫拦截 [" + verdict.rejectedBy() + "]: " + verdict.reason());
            }
        }

        eventLog.append(executionIdOf(invocation), ExecutionEventTypes.TOOL_CALL, toolCallPayload(invocation));

        ToolResult result = executeAroundChain(invocation, body);

        eventLog.append(executionIdOf(invocation), ExecutionEventTypes.TOOL_RESULT, toolResultPayload(invocation, result));

        for (ToolFilter filter : filters) {
            try {
                filter.postFilter(invocation, result);
            } catch (Exception e) {
                log.warn("Post filter failed after tool invocation: tool={}, filter={}, error={}",
                        invocation.toolName(), filter.getClass().getSimpleName(), e.getMessage());
            }
        }

        if (result.error() != null) {
            Throwable cause = result.error();
            if (cause instanceof ToolInvocationException toolException) {
                throw toolException;
            }
            log.error("Tool invocation failed: tool={}, scopeId={}, error={}",
                    invocation.toolName(), invocation.scopeId(), cause.getMessage(), cause);
            throw new ToolInvocationException("工具 " + invocation.toolName() + " 执行失败: " + cleanMessage(cause), cause);
        }
        return result.value();
    }

    private static String executionIdOf(ToolInvocation invocation) {
        Long scopeId = invocation.scopeIdAsLong();
        return scopeId != null ? "scope-" + scopeId : null;
    }

    private static Map<String, Object> toolCallPayload(ToolInvocation invocation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", invocation.toolName());
        payload.put("scopeId", invocation.scopeId());
        payload.put("path", invocation.path());
        return payload;
    }

    private static Map<String, Object> toolResultPayload(ToolInvocation invocation, ToolResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", invocation.toolName());
        payload.put("success", result.success());
        payload.put("elapsedMs", result.elapsedMs());
        if (result.value() instanceof String text) {
            payload.put("valueBytes", text.getBytes(StandardCharsets.UTF_8).length);
        } else if (result.value() != null) {
            payload.put("valueType", result.value().getClass().getSimpleName());
        }
        if (result.error() != null) {
            payload.put("error", cleanMessage(result.error()));
        }
        return payload;
    }

    private ToolResult executeAroundChain(ToolInvocation invocation, InvocationBody body) {
        ToolFilter.InvocationChain chain = () -> {
            long start = System.nanoTime();
            try {
                return ToolResult.ofValue(body.execute(), elapsedMs(start));
            } catch (Throwable t) {
                return ToolResult.ofError(t, elapsedMs(start));
            }
        };
        List<ToolFilter> reversed = new ArrayList<>(filters);
        for (int i = reversed.size() - 1; i >= 0; i--) {
            ToolFilter filter = reversed.get(i);
            ToolFilter.InvocationChain next = chain;
            chain = () -> filter.aroundInvoke(invocation, next);
        }
        try {
            return chain.proceed();
        } catch (Throwable t) {
            return ToolResult.ofError(t, 0);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String cleanMessage(Throwable t) {
        String message = t.getMessage();
        return message != null && !message.isBlank() ? message : t.getClass().getSimpleName();
    }

    @FunctionalInterface
    public interface InvocationBody {

        Object execute() throws Throwable;
    }
}
