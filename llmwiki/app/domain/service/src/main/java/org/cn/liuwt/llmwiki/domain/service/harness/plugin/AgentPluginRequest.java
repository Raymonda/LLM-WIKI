package org.cn.liuwt.llmwiki.domain.service.harness.plugin;

import java.util.Map;

public record AgentPluginRequest(Long scopeId, String executionId, Map<String, Object> input, Map<String, Object> metadata) {

    public static AgentPluginRequest of(Long scopeId, Map<String, Object> input) {
        return new AgentPluginRequest(scopeId, null, input == null ? Map.of() : input, Map.of());
    }

    public static AgentPluginRequest of(Long scopeId, String executionId, Map<String, Object> input) {
        return new AgentPluginRequest(scopeId, executionId, input == null ? Map.of() : input, Map.of());
    }
}
