package org.cn.liuwt.llmwiki.domain.service.harness.plugin;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.EventLogProperties;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.TestEventLogs;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPluginRegistryTest {

    @SuppressWarnings("unchecked")
    private static AgentPluginRegistry registry(List<AgentPlugin> initialPlugins,
                                                SchemaManager schemaManager,
                                                ExecutionEventLogService eventLog) {
        ObjectProvider<AgentPlugin> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.stream()).thenReturn(Stream.of(initialPlugins.toArray(AgentPlugin[]::new)));
        return new AgentPluginRegistry(provider, schemaManager, eventLog);
    }

    private static ExecutionEventLogService recordingEventLog(List<String> recorded) {
        return new ExecutionEventLogService(null, null, new EventLogProperties()) {
            @Override
            public boolean append(String executionId, String eventType, Map<String, ?> payload) {
                recorded.add(eventType);
                return true;
            }
        };
    }

    private static AgentPlugin plugin(String name, String capability, List<String> constraints) {
        return new AgentPlugin() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String capability() {
                return capability;
            }

            @Override
            public List<String> schemaConstraints() {
                return constraints;
            }

            @Override
            public AgentPluginResult execute(AgentPluginRequest request) {
                return AgentPluginResult.ok(Map.of("name", name));
            }
        };
    }

    @Test
    void init_registersEchoPluginFromProvider() {
        AgentPluginRegistry registry = registry(List.of(new EchoPlugin()),
            Mockito.mock(SchemaManager.class), TestEventLogs.disabled());
        registry.init();

        assertTrue(registry.resolve("sample.echo", null).isPresent());
        assertTrue(registry.listPlugins().stream().anyMatch(p -> "echo".equals(p.get("name"))));
    }

    @Test
    void register_illegalSchemaConstraint_throws() {
        AgentPluginRegistry registry = registry(List.of(),
            Mockito.mock(SchemaManager.class), TestEventLogs.disabled());
        AgentPlugin bad = plugin("bad", "cap.bad", List.of("## 99. 不存在段"));

        assertThrows(IllegalStateException.class, () -> registry.register(bad));
        assertTrue(registry.resolve("cap.bad", null).isEmpty());
    }

    @Test
    void register_duplicateName_throws() {
        AgentPluginRegistry registry = registry(List.of(plugin("dup", "cap.a", List.of())),
            Mockito.mock(SchemaManager.class), TestEventLogs.disabled());
        registry.init();

        assertThrows(IllegalStateException.class, () -> registry.register(plugin("dup", "cap.b", List.of())));
    }

    @Test
    void registerForScope_missingSchemaSection_throws() {
        SchemaManager schemaManager = Mockito.mock(SchemaManager.class);
        SchemaConfigDO schema = new SchemaConfigDO();
        schema.setConfigValue("## 1. 领域定位\n内容");
        Mockito.when(schemaManager.getSchema(Mockito.anyLong(), Mockito.anyString())).thenReturn(schema);
        AgentPluginRegistry registry = registry(List.of(),
            schemaManager, TestEventLogs.disabled());
        AgentPlugin constrained = plugin("constrained", "cap.c", List.of("## 2. 分类体系"));

        assertThrows(IllegalStateException.class, () -> registry.registerForScope(1L, constrained));
        assertTrue(registry.resolve("cap.c", 1L).isEmpty());
    }

    @Test
    void resolve_scopeOverrideTakesPrecedence() {
        AgentPluginRegistry registry = registry(List.of(new EchoPlugin()),
            Mockito.mock(SchemaManager.class), TestEventLogs.disabled());
        registry.init();
        AgentPlugin custom = plugin("echo-custom", "sample.echo", List.of());
        registry.registerForScope(1L, custom);

        assertTrue(registry.resolve("sample.echo", 1L).isPresent());
        assertTrue(registry.listPlugins().stream()
            .anyMatch(p -> "echo-custom".equals(p.get("name"))));
    }

    @Test
    void execute_echoPluginFullChain_emitsStepEvents() {
        List<String> recorded = new ArrayList<>();
        AgentPluginRegistry registry = registry(List.of(new EchoPlugin()),
            Mockito.mock(SchemaManager.class), recordingEventLog(recorded));
        registry.init();

        AgentPluginResult result = registry.execute("sample.echo", 7L,
            AgentPluginRequest.of(7L, "exec-1", Map.of("hello", "world")));

        assertTrue(result.success());
        assertEquals("world", result.output().get("hello"));
        assertTrue(recorded.contains("step/start"));
        assertTrue(recorded.contains("step/end"));
    }

    @Test
    void execute_unknownCapability_returnsFail() {
        AgentPluginRegistry registry = registry(List.of(),
            Mockito.mock(SchemaManager.class), TestEventLogs.disabled());
        registry.init();

        AgentPluginResult result = registry.execute("nope.capability", 1L, AgentPluginRequest.of(1L, Map.of()));

        assertFalse(result.success());
    }

    @Test
    void execute_pluginException_returnsFailAndStepEnd() {
        List<String> recorded = new ArrayList<>();
        AgentPluginRegistry registry = registry(List.of(), Mockito.mock(SchemaManager.class), recordingEventLog(recorded));
        registry.init();
        AgentPlugin boom = new AgentPlugin() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public String capability() {
                return "cap.boom";
            }

            @Override
            public AgentPluginResult execute(AgentPluginRequest request) {
                throw new RuntimeException("爆炸");
            }
        };
        registry.register(boom);

        AgentPluginResult result = registry.execute("cap.boom", 1L, AgentPluginRequest.of(1L, Map.of()));

        assertFalse(result.success());
        assertInstanceOf(String.class, result.message());
        assertTrue(recorded.contains("step/start"));
        assertTrue(recorded.contains("step/end"));
    }
}
