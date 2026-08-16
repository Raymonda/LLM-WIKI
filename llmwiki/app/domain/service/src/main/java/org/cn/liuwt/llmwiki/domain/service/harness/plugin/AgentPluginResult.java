package org.cn.liuwt.llmwiki.domain.service.harness.plugin;

import java.util.Map;

public record AgentPluginResult(boolean success, Map<String, Object> output, String message) {

    public static AgentPluginResult ok(Map<String, Object> output) {
        return new AgentPluginResult(true, output == null ? Map.of() : output, null);
    }

    public static AgentPluginResult ok(Map<String, Object> output, String message) {
        return new AgentPluginResult(true, output == null ? Map.of() : output, message);
    }

    public static AgentPluginResult fail(String message) {
        return new AgentPluginResult(false, Map.of(), message);
    }
}
