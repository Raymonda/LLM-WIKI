package org.cn.liuwt.llmwiki.domain.service.harness.plugin;

import java.util.List;

public interface AgentPlugin {

    String name();

    String capability();

    default List<String> schemaConstraints() {
        return List.of();
    }

    AgentPluginResult execute(AgentPluginRequest request);
}
