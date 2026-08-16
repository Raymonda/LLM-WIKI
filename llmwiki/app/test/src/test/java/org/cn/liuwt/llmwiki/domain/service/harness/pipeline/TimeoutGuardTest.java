package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeoutGuardTest {

    private static ToolInvocation invocation() {
        return new ToolInvocation("searchWiki", "searchWiki", new Object[0], "1", null);
    }

    @Test
    void aroundInvoke_fastTool_returnsValue() throws Throwable {
        TimeoutGuard guard = new TimeoutGuard(properties(5000));

        ToolResult result = guard.aroundInvoke(invocation(), () -> ToolResult.ofValue("ok", 1));

        assertTrue(result.success());
        assertTrue(result.value() instanceof String s && "ok".equals(s));
    }

    @Test
    void aroundInvoke_slowTool_timesOut() throws Throwable {
        TimeoutGuard guard = new TimeoutGuard(properties(100));

        long start = System.currentTimeMillis();
        ToolResult result = guard.aroundInvoke(invocation(), () -> {
            Thread.sleep(3000);
            return ToolResult.ofValue("late", 0);
        });
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 2000, "超时守卫未在预期时间内返回，耗时 " + elapsed + "ms");
        assertFalseSafe(result);
    }

    @Test
    void aroundInvoke_timeoutDisabled_proceedsDirectly() throws Throwable {
        TimeoutGuard guard = new TimeoutGuard(properties(0));

        ToolResult result = guard.aroundInvoke(invocation(), () -> ToolResult.ofValue("ok", 1));

        assertTrue(result.success());
    }

    @Test
    void aroundInvoke_toolError_propagatedAsResultError() throws Throwable {
        TimeoutGuard guard = new TimeoutGuard(properties(5000));

        ToolResult result = guard.aroundInvoke(invocation(), () -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(result.error() instanceof IllegalStateException);
    }

    private static void assertFalseSafe(ToolResult result) {
        assertTrue(result.error() instanceof ToolInvocationException);
        assertTrue(result.error().getMessage().contains("超时"));
    }

    private static ToolPipelineProperties properties(long timeoutMs) {
        ToolPipelineProperties properties = new ToolPipelineProperties();
        properties.setTimeoutMs(timeoutMs);
        return properties;
    }
}
