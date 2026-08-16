package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

public interface ToolFilter {

    int PRE_ORDER_BASE = 100;

    int getOrder();

    default FilterVerdict preFilter(ToolInvocation invocation) {
        return FilterVerdict.allow();
    }

    default ToolResult aroundInvoke(ToolInvocation invocation, InvocationChain chain) throws Throwable {
        return chain.proceed();
    }

    default void postFilter(ToolInvocation invocation, ToolResult result) {
    }

    @FunctionalInterface
    interface InvocationChain {

        ToolResult proceed() throws Throwable;
    }
}
