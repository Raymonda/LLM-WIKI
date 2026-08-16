package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.TestEventLogs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionPipelineTest {

    private static ToolInvocation invocation(String toolName) {
        return new ToolInvocation(toolName, toolName, new Object[0], "1", null);
    }

    @Test
    void invoke_success_returnsValue() throws Throwable {
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(List.of(), TestEventLogs.disabled());

        Object result = pipeline.invoke(invocation("searchWiki"), () -> "ok");

        assertEquals("ok", result);
    }

    @Test
    void invoke_preFilterReject_throwsWithGuardNameAndReason() {
        ToolFilter rejecting = new ToolFilter() {
            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public FilterVerdict preFilter(ToolInvocation invocation) {
                return FilterVerdict.reject("TestGuard", "路径非法");
            }
        };
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(List.of(rejecting), TestEventLogs.disabled());

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> pipeline.invoke(invocation("writeFile"), () -> "never"));

        assertTrue(ex.getMessage().contains("TestGuard"));
        assertTrue(ex.getMessage().contains("路径非法"));
    }

    @Test
    void invoke_preRejected_bodyNeverExecuted() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolFilter rejecting = new ToolFilter() {
            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public FilterVerdict preFilter(ToolInvocation invocation) {
                return FilterVerdict.reject("G", "no");
            }
        };
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(List.of(rejecting), TestEventLogs.disabled());

        assertThrows(ToolInvocationException.class,
                () -> pipeline.invoke(invocation("t"), () -> {
                    executed.set(true);
                    return null;
                }));

        assertFalse(executed.get());
    }

    @Test
    void invoke_toolThrows_normalizedToToolInvocationException() {
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(List.of(), TestEventLogs.disabled());

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> pipeline.invoke(invocation("searchWiki"), () -> {
                    throw new IllegalStateException("ES 连接失败");
                }));

        assertTrue(ex.getMessage().contains("searchWiki"));
        assertTrue(ex.getMessage().contains("ES 连接失败"));
        assertEquals(IllegalStateException.class, ex.getCause().getClass());
    }

    @Test
    void invoke_filtersSortedByOrder_regardlessOfInjectionOrder() throws Throwable {
        StringBuilder order = new StringBuilder();
        ToolFilter outer = chainFilter("outer", 100, order);
        ToolFilter inner = chainFilter("inner", 300, order);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(List.of(inner, outer), TestEventLogs.disabled());

        pipeline.invoke(invocation("t"), () -> "v");

        assertEquals("outer>inner>", order.toString());
    }

    @Test
    void invoke_postFilterSeesValueAndElapsed() throws Throwable {
        AtomicReference<ToolResult> seen = new AtomicReference<>();
        ToolFilter observer = new ToolFilter() {
            @Override
            public int getOrder() {
                return 100;
            }

            @Override
            public void postFilter(ToolInvocation invocation, ToolResult result) {
                seen.set(result);
            }
        };
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(List.of(observer), TestEventLogs.disabled());

        pipeline.invoke(invocation("t"), () -> 42);

        assertEquals(42, seen.get().value());
        assertTrue(seen.get().success());
    }

    @Test
    void invoke_postFilterFailure_doesNotBreakResult() throws Throwable {
        ToolFilter broken = new ToolFilter() {
            @Override
            public int getOrder() {
                return 100;
            }

            @Override
            public void postFilter(ToolInvocation invocation, ToolResult result) {
                throw new IllegalStateException("post broken");
            }
        };
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(List.of(broken), TestEventLogs.disabled());

        assertEquals("v", pipeline.invoke(invocation("t"), () -> "v"));
    }

    private static ToolFilter chainFilter(String tag, int order, StringBuilder recorder) {
        return new ToolFilter() {
            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public ToolResult aroundInvoke(ToolInvocation invocation, InvocationChain chain) throws Throwable {
                recorder.append(tag).append(">");
                return chain.proceed();
            }
        };
    }
}
