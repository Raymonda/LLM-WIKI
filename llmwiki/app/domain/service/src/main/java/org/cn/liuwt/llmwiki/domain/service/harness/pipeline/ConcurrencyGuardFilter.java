package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Component
public class ConcurrencyGuardFilter implements ToolFilter {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyGuardFilter.class);

    private static final String GLOBAL_KEY = "_global";

    private final ToolPipelineProperties properties;

    private final ConcurrentHashMap<String, Semaphore> scopeSemaphores = new ConcurrentHashMap<>();

    public ConcurrencyGuardFilter(ToolPipelineProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return PRE_ORDER_BASE + 100;
    }

    @Override
    public ToolResult aroundInvoke(ToolInvocation invocation, InvocationChain chain) throws Throwable {
        String key = invocation.hasScope() ? invocation.scopeId() : GLOBAL_KEY;
        Semaphore semaphore = scopeSemaphores.computeIfAbsent(key,
                k -> new Semaphore(Math.max(1, properties.getScopeMaxConcurrentTools())));
        if (!semaphore.tryAcquire()) {
            log.warn("Tool concurrency limit reached: tool={}, scopeId={}, max={}",
                    invocation.toolName(), key, properties.getScopeMaxConcurrentTools());
            return ToolResult.ofError(new ToolInvocationException(
                    "scope " + key + " 的工具并发已达上限 " + properties.getScopeMaxConcurrentTools() + "，请等待当前工具调用完成后再试"), 0);
        }
        try {
            return chain.proceed();
        } finally {
            semaphore.release();
        }
    }
}
