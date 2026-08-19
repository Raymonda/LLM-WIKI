package org.cn.liuwt.llmwiki.domain.service.harness.plugin;

import jakarta.annotation.PostConstruct;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventTypes;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentPluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentPluginRegistry.class);

    private final ObjectProvider<AgentPlugin> pluginProvider;
    private final SchemaManager schemaManager;
    private final ExecutionEventLogService eventLog;

    private final Map<String, AgentPlugin> pluginsByName = new ConcurrentHashMap<>();
    private final Map<String, List<AgentPlugin>> pluginsByCapability = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, AgentPlugin>> scopeOverrides = new ConcurrentHashMap<>();

    public AgentPluginRegistry(ObjectProvider<AgentPlugin> pluginProvider,
                               SchemaManager schemaManager,
                               ExecutionEventLogService eventLog) {
        this.pluginProvider = pluginProvider;
        this.schemaManager = schemaManager;
        this.eventLog = eventLog;
    }

    @PostConstruct
    void init() {
        pluginProvider.stream().forEach(this::register);
        log.info("AgentPluginRegistry initialized: {} plugin(s)", pluginsByName.size());
    }

    public synchronized void register(AgentPlugin plugin) {
        validateBasics(plugin);
        for (String constraint : plugin.schemaConstraints()) {
            if (!SchemaSkeletonValidator.REQUIRED_SECTIONS.contains(constraint)) {
                throw new IllegalStateException("插件 " + plugin.name()
                    + " 的 schemaConstraint 非法（不在 Schema 骨架七段之内）: " + constraint);
            }
        }
        if (pluginsByName.putIfAbsent(plugin.name(), plugin) != null) {
            throw new IllegalStateException("插件名冲突: " + plugin.name());
        }
        pluginsByCapability.computeIfAbsent(plugin.capability(), k -> new ArrayList<>()).add(plugin);
        log.info("AgentPlugin registered: name={} capability={} schemaConstraints={}",
            plugin.name(), plugin.capability(), plugin.schemaConstraints());
    }

    public synchronized void registerForScope(Long scopeId, AgentPlugin plugin) {
        if (scopeId == null) {
            throw new IllegalArgumentException("scopeId 不能为空");
        }
        register(validateScopeConstraints(scopeId, plugin));
        scopeOverrides.computeIfAbsent(scopeId, k -> new ConcurrentHashMap<>())
            .put(plugin.capability(), plugin);
        log.info("AgentPlugin scope-override registered: scopeId={} name={} capability={}",
            scopeId, plugin.name(), plugin.capability());
    }

    private AgentPlugin validateScopeConstraints(Long scopeId, AgentPlugin plugin) {
        if (plugin.schemaConstraints().isEmpty()) {
            return plugin;
        }
        SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
            return plugin;
        }
        List<String> missing = plugin.schemaConstraints().stream()
            .filter(constraint -> !schema.getConfigValue().contains(constraint))
            .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("插件 " + plugin.name() + " 依赖的 Schema section 在 scopeId="
                + scopeId + " 不存在: " + String.join("、", missing));
        }
        return plugin;
    }

    private void validateBasics(AgentPlugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin 不能为空");
        }
        if (plugin.name() == null || plugin.name().isBlank()) {
            throw new IllegalArgumentException("插件 name 不能为空");
        }
        if (plugin.capability() == null || plugin.capability().isBlank()) {
            throw new IllegalArgumentException("插件 capability 不能为空: " + plugin.name());
        }
    }

    public Optional<AgentPlugin> resolve(String capability, Long scopeId) {
        if (capability == null || capability.isBlank()) {
            return Optional.empty();
        }
        if (scopeId != null) {
            AgentPlugin override = scopeOverrides.getOrDefault(scopeId, Map.of()).get(capability);
            if (override != null) {
                return Optional.of(override);
            }
        }
        return pluginsByCapability.getOrDefault(capability, List.of()).stream()
            .min(Comparator.comparing(AgentPlugin::name));
    }

    public AgentPluginResult execute(String capability, Long scopeId, AgentPluginRequest request) {
        Optional<AgentPlugin> candidate = resolve(capability, scopeId);
        if (candidate.isEmpty()) {
            return AgentPluginResult.fail("无可用的插件: capability=" + capability);
        }
        AgentPlugin plugin = candidate.get();
        String executionId = request != null && request.executionId() != null && !request.executionId().isBlank()
            ? request.executionId()
            : (scopeId != null ? "scope-" + scopeId : null);
        eventLog.append(executionId, ExecutionEventTypes.STEP_START,
            Map.of("plugin", plugin.name(), "capability", capability, "scopeId", scopeId == null ? "" : scopeId));
        long start = System.currentTimeMillis();
        AgentPluginResult result;
        try {
            result = plugin.execute(request);
        } catch (Exception e) {
            log.warn("AgentPlugin execution failed: name={} capability={}", plugin.name(), capability, e);
            result = AgentPluginResult.fail("插件执行异常: " + e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;
        eventLog.append(executionId, ExecutionEventTypes.STEP_END, Map.of(
            "plugin", plugin.name(),
            "capability", capability,
            "success", result.success(),
            "elapsedMs", elapsed));
        return result;
    }

    public List<Map<String, Object>> listPlugins() {
        return pluginsByName.values().stream()
            .map(plugin -> Map.<String, Object>of(
                "name", plugin.name(),
                "capability", plugin.capability(),
                "schemaConstraints", plugin.schemaConstraints()))
            .toList();
    }
}
