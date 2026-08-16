package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.cn.liuwt.llmwiki.domain.service.harness.SpillProperties;
import org.cn.liuwt.llmwiki.domain.service.harness.SpillService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventTypes;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SpillFilter implements ToolFilter {

    private static final Logger log = LoggerFactory.getLogger(SpillFilter.class);

    private final SpillService spillService;
    private final SpillProperties spillProperties;
    private final ExecutionEventLogService eventLog;

    public SpillFilter(SpillService spillService, SpillProperties spillProperties,
                       ExecutionEventLogService eventLog) {
        this.spillService = spillService;
        this.spillProperties = spillProperties;
        this.eventLog = eventLog;
    }

    @Override
    public int getOrder() {
        return PRE_ORDER_BASE + 450;
    }

    @Override
    public ToolResult aroundInvoke(ToolInvocation invocation, InvocationChain chain) throws Throwable {
        ToolResult result = chain.proceed();
        if (!result.success()) {
            return result;
        }
        if (!(result.value() instanceof String text) || text.isEmpty()) {
            return result;
        }
        if (text.getBytes(StandardCharsets.UTF_8).length <= spillProperties.getMaxInlineBytes()) {
            return result;
        }
        Long scopeId = invocation.scopeIdAsLong();
        if (scopeId == null) {
            return result;
        }
        SpillService.SpillResult spill = spillService.spillIfNeeded(scopeId, executionId(), text);
        if (!spill.spilled()) {
            return result;
        }
        int originalBytes = text.getBytes(StandardCharsets.UTF_8).length;
        log.info("Tool output spilled to locator: tool={}, scopeId={}, originalBytes={}, threshold={}",
                invocation.toolName(), scopeId, originalBytes, spillProperties.getMaxInlineBytes());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", invocation.toolName());
        payload.put("scopeId", scopeId);
        payload.put("spillExecutionId", executionId());
        payload.put("spillId", spill.spillId());
        payload.put("bytes", originalBytes);
        eventLog.append("scope-" + scopeId, ExecutionEventTypes.SPILL_WRITTEN, payload);
        return new ToolResult(spill.content(), null, result.elapsedMs());
    }

    private static String executionId() {
        TokenUsageContext.Context context = TokenUsageContext.get();
        return context != null && context.operationType() != null ? context.operationType() : "tool";
    }
}
