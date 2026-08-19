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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessConfigDocSyncTest {

    private static final Map<String, Class<?>> SECTION_PROPERTIES = Map.of(
        "spill", SpillProperties.class,
        "tool-pipeline", ToolPipelineProperties.class,
        "compaction", CompactionProperties.class,
        "event-log", EventLogProperties.class,
        "loop-hygiene", LoopHygieneProperties.class);

    private static final Set<String> SECTIONS_WITHOUT_PROPERTIES = Set.of("plugin");

    @Test
    @SuppressWarnings("unchecked")
    void harnessYmlSectionsMatchPropertiesClasses() throws Exception {
        Map<String, Object> harness = loadHarnessSection();

        Set<String> expectedSections = new TreeSet<>(SECTION_PROPERTIES.keySet());
        expectedSections.addAll(SECTIONS_WITHOUT_PROPERTIES);
        assertEquals(expectedSections, harness.keySet(),
            "llmwiki.harness 下的配置段与登记表不一致：新增配置段必须同步 Properties 类（或登记到 SECTIONS_WITHOUT_PROPERTIES）与本测试");

        for (Map.Entry<String, Class<?>> entry : SECTION_PROPERTIES.entrySet()) {
            Map<String, Object> section = asMap(harness.get(entry.getKey()), entry.getKey());
            Set<String> ymlKeys = section.keySet();
            Set<String> fieldKeys = propertyFieldNames(entry.getValue());

            assertEquals(fieldKeys, ymlKeys,
                "llmwiki.harness." + entry.getKey() + " 的 yml 键与 "
                    + entry.getValue().getSimpleName() + " 字段不一致（配置漂移）");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void harnessYmlLeavesAreStringScalars() throws Exception {
        Map<String, Object> harness = loadHarnessSection();
        assertLeafScalars(harness, "llmwiki.harness");
    }

    @SuppressWarnings("unchecked")
    private static void assertLeafScalars(Map<String, Object> node, String where) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String path = where + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                assertLeafScalars((Map<String, Object>) child, path);
            } else {
                assertTrue(entry.getValue() instanceof String, path + " 应为字符串标量");
            }
        }
    }

    private static Map<String, Object> loadHarnessSection() throws Exception {
        Path ymlPath = Path.of("..", "bootstrap", "src", "main", "resources", "application.yml");
        assertTrue(Files.exists(ymlPath), "找不到 application.yml: " + ymlPath.toAbsolutePath());
        Map<String, Object> root = new Yaml().load(Files.readString(ymlPath));
        Map<String, Object> llmwiki = asMap(root.get("llmwiki"), "llmwiki");
        return asMap(llmwiki.get("harness"), "llmwiki.harness");
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
