package org.cn.liuwt.llmwiki.domain.service.harness.plugin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "llmwiki.harness.plugin.echo.enabled", havingValue = "true", matchIfMissing = true)
public class EchoPlugin implements AgentPlugin {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String capability() {
        return "sample.echo";
    }

    @Override
    public AgentPluginResult execute(AgentPluginRequest request) {
        if (request == null) {
            return AgentPluginResult.fail("request 不能为空");
        }
        Map<String, Object> output = new LinkedHashMap<>(request.input());
        output.put("echo.scopeId", request.scopeId());
        return AgentPluginResult.ok(output, "echo");
    }
}
