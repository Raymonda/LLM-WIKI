package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public record ToolInvocation(String toolName, String methodName, Object[] args, String scopeId, String path) {

    public static ToolInvocation from(String toolName, Method method, Object[] args) {
        String scopeId = null;
        String path = null;
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            Parameter parameter = parameters[i];
            Object arg = args[i];
            if (!(arg instanceof String value) || value.isBlank()) {
                continue;
            }
            String name = parameter.isNamePresent() ? parameter.getName().toLowerCase() : "";
            ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
            String description = toolParam != null && toolParam.description() != null ? toolParam.description() : "";
            if (scopeId == null && (name.contains("scope") || description.contains("范围") || description.contains("scopeId"))) {
                scopeId = value;
            } else if (path == null && (name.contains("path") || description.contains("路径"))) {
                path = value;
            }
        }
        return new ToolInvocation(toolName, method.getName(), args, scopeId, path);
    }

    public boolean hasScope() {
        return scopeId != null && !scopeId.isBlank();
    }

    public Long scopeIdAsLong() {
        try {
            return Long.parseLong(scopeId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
