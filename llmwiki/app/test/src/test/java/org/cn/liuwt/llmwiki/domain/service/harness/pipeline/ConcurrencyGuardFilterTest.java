package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyGuardFilterTest {

    private static ToolInvocation invocation(String scopeId) {
        return new ToolInvocation("searchWiki", "searchWiki", new Object[0], scopeId, null);
    }

    @Test
    void aroundInvoke_withinLimit_proceeds() throws Throwable {
        ConcurrencyGuardFilter filter = new ConcurrencyGuardFilter(properties(8));

        ToolResult result = filter.aroundInvoke(invocation("1"), () -> ToolResult.ofValue("ok", 1));

        assertTrue(result.success());
    }

    @Test
    void aroundInvoke_limitReached_rejectedWithoutWaiting() throws Throwable {
        ConcurrencyGuardFilter filter = new ConcurrencyGuardFilter(properties(1));
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch holderFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> {
                try {
                    return filter.aroundInvoke(invocation("1"), () -> {
                        holderStarted.countDown();
                        holderFinish.await(10, TimeUnit.SECONDS);
                        return ToolResult.ofValue("held", 1);
                    });
                } catch (Throwable t) {
                    return ToolResult.ofError(t, 0);
                }
            });

            assertTrue(holderStarted.await(5, TimeUnit.SECONDS));

            ToolResult rejected = filter.aroundInvoke(invocation("1"), () -> ToolResult.ofValue("never", 0));

            assertFalse(rejected.success());
            assertTrue(rejected.error() instanceof ToolInvocationException);
            assertTrue(rejected.error().getMessage().contains("并发已达上限"));

            holderFinish.countDown();
            ToolResult holderResult = (ToolResult) holder.get(5, TimeUnit.SECONDS);
            assertTrue(holderResult.success());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aroundInvoke_differentScopes_isolated() throws Throwable {
        ConcurrencyGuardFilter filter = new ConcurrencyGuardFilter(properties(1));
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch holderFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                try {
                    return filter.aroundInvoke(invocation("1"), () -> {
                        holderStarted.countDown();
                        holderFinish.await(10, TimeUnit.SECONDS);
                        return ToolResult.ofValue("held", 1);
                    });
                } catch (Throwable t) {
                    return ToolResult.ofError(t, 0);
                }
            });

            assertTrue(holderStarted.await(5, TimeUnit.SECONDS));

            ToolResult otherScope = filter.aroundInvoke(invocation("2"), () -> ToolResult.ofValue("ok", 1));

            assertTrue(otherScope.success());
            holderFinish.countDown();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aroundInvoke_permitReleasedAfterCompletion() throws Throwable {
        ConcurrencyGuardFilter filter = new ConcurrencyGuardFilter(properties(1));

        filter.aroundInvoke(invocation("1"), () -> ToolResult.ofValue("first", 1));

        ToolResult second = filter.aroundInvoke(invocation("1"), () -> ToolResult.ofValue("second", 1));

        assertTrue(second.success());
    }

    private static ToolPipelineProperties properties(int maxConcurrent) {
        ToolPipelineProperties properties = new ToolPipelineProperties();
        properties.setScopeMaxConcurrentTools(maxConcurrent);
        return properties;
    }
}
