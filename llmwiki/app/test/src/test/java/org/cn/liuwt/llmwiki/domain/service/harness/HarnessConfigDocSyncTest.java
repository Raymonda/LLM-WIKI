package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.EventLogProperties;
import org.cn.liuwt.llmwiki.domain.service.harness.pipeline.LoopHygieneProperties;
import org.cn.liuwt.llmwiki.domain.service.harness.pipeline.ToolPipelineProperties;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessConfigDocSyncTest {

    private static final Map<String, Class<?>> SECTION_PROPERTIES = Map.of(
        "spill", SpillProperties.class,
        "tool-pipeline", ToolPipelineProperties.class,
        "compaction", CompactionProperties.class,
        "event-log", EventLogProperties.class,
        "loop-hygiene", LoopHygieneProperties.class);

    private static final Set<String> SECTIONS_WITHOUT_PROPERTIES = Set.of("plugin");

    private static final List<String> PROFILE_FILES = List.of(
        "application.yml", "application-local.yml", "application-dev.yml", "application-prod.yml");

    @Test
    void harnessYmlSectionsAreRegistered() throws Exception {
        Set<String> registered = new TreeSet<>(SECTION_PROPERTIES.keySet());
        registered.addAll(SECTIONS_WITHOUT_PROPERTIES);

        for (Map.Entry<String, Map<String, Object>> entry : loadHarnessSections().entrySet()) {
            Set<String> unregistered = new TreeSet<>(entry.getValue().keySet());
            unregistered.removeAll(registered);
            assertTrue(unregistered.isEmpty(),
                entry.getKey() + " 出现未登记的 llmwiki.harness 配置段 " + unregistered
                    + "：新增配置段必须同步 Properties 类（或登记到 SECTIONS_WITHOUT_PROPERTIES）与本测试");
        }
    }

    @Test
    void harnessYmlKeysSubsetOfPropertiesFields() throws Exception {
        for (Map.Entry<String, Map<String, Object>> fileEntry : loadHarnessSections().entrySet()) {
            Map<String, Object> harness = fileEntry.getValue();
            for (Map.Entry<String, Class<?>> sectionEntry : SECTION_PROPERTIES.entrySet()) {
                if (!harness.containsKey(sectionEntry.getKey())) {
                    continue;
                }
                Map<String, Object> section = asMap(harness.get(sectionEntry.getKey()),
                    fileEntry.getKey() + " llmwiki.harness." + sectionEntry.getKey());
                Set<String> unknownKeys = new TreeSet<>(section.keySet());
                unknownKeys.removeAll(propertyFieldNames(sectionEntry.getValue()));
                assertTrue(unknownKeys.isEmpty(),
                    fileEntry.getKey() + " 的 llmwiki.harness." + sectionEntry.getKey() + " 存在 "
                        + sectionEntry.getValue().getSimpleName() + " 未定义的键 " + unknownKeys
                        + "（配置漂移：yml 仅做局部覆盖，全量参数清单见 docs/CONFIG-REFERENCE.md）");
            }
        }
    }

    @Test
    void harnessYmlLeavesAreScalars() throws Exception {
        for (Map.Entry<String, Map<String, Object>> entry : loadHarnessSections().entrySet()) {
            assertLeafScalars(entry.getValue(), entry.getKey() + " llmwiki.harness");
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertLeafScalars(Map<String, Object> node, String where) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String path = where + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                assertLeafScalars((Map<String, Object>) child, path);
            } else {
                assertTrue(isScalar(entry.getValue()), path + " 应为标量（字符串/布尔/数字）");
            }
        }
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Boolean || value instanceof Number;
    }

    private static Map<String, Map<String, Object>> loadHarnessSections() throws Exception {
        Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
        for (String fileName : PROFILE_FILES) {
            Map<String, Object> harness = loadHarnessSection(fileName);
            if (harness != null) {
                sections.put(fileName, harness);
            }
        }
        assertFalse(sections.isEmpty(),
            "至少一个 profile yml 应包含 llmwiki.harness 配置段（如 application-local.yml 的实验特性开关）");
        return sections;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadHarnessSection(String fileName) throws Exception {
        Path ymlPath = Path.of("..", "bootstrap", "src", "main", "resources", fileName);
        if (!Files.exists(ymlPath)) {
            return null;
        }
        Map<String, Object> root = new Yaml().load(Files.readString(ymlPath));
        if (root == null || !(root.get("llmwiki") instanceof Map<?, ?> llmwiki)) {
            return null;
        }
        if (!(llmwiki.get("harness") instanceof Map<?, ?> harness)) {
            return null;
        }
        return (Map<String, Object>) harness;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String where) {
        assertTrue(value instanceof Map, where + " 应为映射节点");
        return (Map<String, Object>) value;
    }

    private static Set<String> propertyFieldNames(Class<?> propertiesClass) {
        List<String> names = java.util.Arrays.stream(propertiesClass.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .map(Field::getName)
            .map(HarnessConfigDocSyncTest::toKebabCase)
            .collect(Collectors.toList());
        return new TreeSet<>(names);
    }

    private static String toKebabCase(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }
}
