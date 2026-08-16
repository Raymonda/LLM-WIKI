package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.cn.liuwt.llmwiki.common.util.PathGuard;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class ScopePathGuardFilter implements ToolFilter {

    @Override
    public int getOrder() {
        return PRE_ORDER_BASE;
    }

    @Override
    public FilterVerdict preFilter(ToolInvocation invocation) {
        if (invocation.hasScope() && invocation.scopeIdAsLong() == null) {
            return FilterVerdict.reject("ScopePathGuard", "scopeId 必须是数字，当前值: " + invocation.scopeId());
        }
        String path = invocation.path();
        if (path == null || path.isBlank()) {
            return FilterVerdict.allow();
        }
        String normalized = PathGuard.normalize(path);
        if (normalized.contains("..")) {
            return FilterVerdict.reject("ScopePathGuard", "路径包含非法的父目录引用: " + path);
        }
        if (isWriteTool(invocation)) {
            try {
                PathGuard.assertWritable(path);
            } catch (BusinessException e) {
                return FilterVerdict.reject("ScopePathGuard", e.getMessage());
            }
        }
        return FilterVerdict.allow();
    }

    private static boolean isWriteTool(ToolInvocation invocation) {
        String methodName = invocation.methodName().toLowerCase();
        return methodName.startsWith("write") || methodName.startsWith("update");
    }
}
