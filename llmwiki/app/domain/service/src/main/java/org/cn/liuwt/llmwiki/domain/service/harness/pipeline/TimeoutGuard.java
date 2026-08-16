package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class TimeoutGuard implements ToolFilter {

    private static final Logger log = LoggerFactory.getLogger(TimeoutGuard.class);

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "tool-pipeline-exec");
        thread.setDaemon(true);
        return thread;
    });
    private final ToolPipelineProperties properties;

    public TimeoutGuard(ToolPipelineProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return PRE_ORDER_BASE + 200;
    }

    @Override
    public ToolResult aroundInvoke(ToolInvocation invocation, InvocationChain chain) throws Throwable {
        if (properties.getTimeoutMs() <= 0) {
            return chain.proceed();
        }
        TokenUsageContext.Context tokenContext = TokenUsageContext.get();
        CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(() -> {
            if (tokenContext != null) {
                TokenUsageContext.set(tokenContext.scopeId(), tokenContext.operationType());
            }
            try {
                return chain.proceed();
            } catch (Throwable t) {
                throw new CompletionException(t);
            } finally {
                if (tokenContext != null) {
                    TokenUsageContext.clear();
                }
            }
        }, executor);
        try {
            return future.get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Tool invocation timed out: tool={}, scopeId={}, timeoutMs={}",
                    invocation.toolName(), invocation.scopeId(), properties.getTimeoutMs());
            return ToolResult.ofError(new ToolInvocationException(
                    "工具 " + invocation.toolName() + " 执行超时（" + properties.getTimeoutMs() + "ms），已放弃等待", e), 0);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ToolResult.ofError(cause, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.ofError(new ToolInvocationException("工具 " + invocation.toolName() + " 执行被中断", e), 0);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Tool pipeline executor did not quiesce within 10s after shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
