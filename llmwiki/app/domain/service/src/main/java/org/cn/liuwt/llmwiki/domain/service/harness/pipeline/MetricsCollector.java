package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MetricsCollector implements ToolFilter {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final ToolPipelineProperties properties;

    public MetricsCollector(ToolPipelineProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return PRE_ORDER_BASE + 300;
    }

    @Override
    public void postFilter(ToolInvocation invocation, ToolResult result) {
        long elapsedMs = result.elapsedMs();
        if (!result.success()) {
            log.info("Tool metrics: tool={}, scopeId={}, elapsedMs={}, ok=false, error={}",
                    invocation.toolName(), invocation.scopeId(), elapsedMs, result.error().getMessage());
            return;
        }
        if (elapsedMs >= properties.getSlowToolWarnMs()) {
            log.warn("Tool metrics: slow call tool={}, scopeId={}, elapsedMs={}, slowWarnMs={}",
                    invocation.toolName(), invocation.scopeId(), elapsedMs, properties.getSlowToolWarnMs());
            return;
        }
        log.debug("Tool metrics: tool={}, scopeId={}, elapsedMs={}, ok=true",
                invocation.toolName(), invocation.scopeId(), elapsedMs);
    }
}
