package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SchemaConfigMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.PageTemplate;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.SectionDef;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaStructuredParser;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaManagerStructureHealthTest {

    private static final long SCOPE_ID = 1L;

    private static final String MD_TWO_TEMPLATES = String.join("\n",
        "## 1. 领域定位",
        "",
        "投资研究知识库",
        "",
        "## 3. 页面模板",
        "",
        "### 市场日报",
        "",
        "1. 市场回顾（必需）",
        "2. 来源引用（必需）",
        "",
        "### 策略报告",
        "",
        "1. 策略逻辑（必需）",
        "",
        "## 4. 命名与引用约定",
        "",
        "- 语言要求：中文");

    private static final String MD_ONE_TEMPLATE = String.join("\n",
        "## 1. 领域定位",
        "",
        "投资研究知识库",
        "",
        "## 3. 页面模板",
        "",
        "### 市场日报",
        "",
        "1. 市场回顾（必需）",
        "",
        "## 4. 命名与引用约定",
        "",
        "- 语言要求：中文");

    @Mock
    private SchemaConfigMapper schemaConfigMapper;

    private final SchemaStructuredParser parser = new SchemaStructuredParser();

    private static void inject(Object target, String field, Object value) {
        try {
            Field f = SchemaManager.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject " + field, e);
        }
    }

    private SchemaManager newManager() {
        SchemaManager manager = new SchemaManager();
        inject(manager, "schemaConfigMapper", schemaConfigMapper);
        inject(manager, "schemaStructuredParser", parser);
        return manager;
    }

    private void stubSchema(String markdown, SchemaStructuredModel storedModel) {
        SchemaConfigDO config = new SchemaConfigDO();
        config.setScopeId(SCOPE_ID);
        config.setConfigKey(SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        config.setConfigValue(markdown);
        config.setConfigValueStructured(parser.toJson(storedModel));
        when(schemaConfigMapper.selectOne(any())).thenReturn(config);
    }

    private static SchemaStructuredModel modelWithTemplates(int count, boolean withSections) {
        SchemaStructuredModel model = new SchemaStructuredModel();
        for (int i = 1; i <= count; i++) {
            PageTemplate pt = new PageTemplate();
            pt.setType("template-" + i);
            pt.setLabel("模板" + i);
            pt.setSections(new ArrayList<>());
            if (withSections) {
                SectionDef sd = new SectionDef();
                sd.setId("section-" + i);
                sd.setLabel("章节" + i);
                sd.setRequired(true);
                sd.setOrder(1);
                pt.getSections().add(sd);
            }
            model.getTemplates().getPageTemplates().add(pt);
        }
        return model;
    }

    @Test
    void shouldReportHealthyWhenMarkdownAlignedWithStoredModel() {
        stubSchema(MD_TWO_TEMPLATES, modelWithTemplates(2, true));
        SchemaManager manager = newManager();

        SchemaManager.SchemaStructureHealth health = manager.buildStructureHealth(SCOPE_ID);

        assertNotNull(health);
        assertEquals(2, health.templateCount());
        assertEquals(0, health.defectCount());
        assertNull(health.detail());
    }

    @Test
    void shouldReportStaleWhenStoredModelBehindMarkdown() {
        stubSchema(MD_TWO_TEMPLATES, modelWithTemplates(1, true));
        SchemaManager manager = newManager();

        SchemaManager.SchemaStructureHealth health = manager.buildStructureHealth(SCOPE_ID);

        assertNotNull(health);
        assertEquals(1, health.templateCount());
        assertEquals(1, health.staleTemplateCount());
        assertEquals(1, health.defectCount());
        assertTrue(health.detail().contains("落后 1"));
    }

    @Test
    void shouldReportEmptyTemplateWhenStoredTemplateHasNoSections() {
        stubSchema(MD_ONE_TEMPLATE, modelWithTemplates(1, false));
        SchemaManager manager = newManager();

        SchemaManager.SchemaStructureHealth health = manager.buildStructureHealth(SCOPE_ID);

        assertNotNull(health);
        assertEquals(1, health.emptyTemplateCount());
        assertEquals(1, health.defectCount());
        assertTrue(health.detail().contains("空模板 1"));
    }

    @Test
    void shouldReturnNullWhenSchemaMissing() {
        when(schemaConfigMapper.selectOne(any())).thenReturn(null);
        SchemaManager manager = newManager();

        assertNull(manager.buildStructureHealth(SCOPE_ID));
    }
}
