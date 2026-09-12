package org.cn.liuwt.llmwiki.domain.service.harness.governance.parser;

import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.PageTemplate;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.SectionDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaStructuredParserTest {

    private final SchemaStructuredParser parser = new SchemaStructuredParser();

    private static String section3(String body) {
        return "## 3. 页面模板\n\n" + body + "\n";
    }

    private static SectionDef sectionDef(String label, boolean required, int order) {
        SectionDef sd = new SectionDef();
        sd.setId(label);
        sd.setLabel(label);
        sd.setRequired(required);
        sd.setOrder(order);
        return sd;
    }

    @Test
    void shouldParseNumberedSectionsWhenRendererFormat() {
        String markdown = section3(String.join("\n",
            "### 市场日报",
            "",
            "1. 市场回顾（必需）",
            "2. 来源引用（必需）",
            "3. 风险提示（可选）"));

        SchemaStructuredModel model = parser.parse(markdown);

        List<PageTemplate> templates = model.getTemplates().getPageTemplates();
        assertEquals(1, templates.size());
        PageTemplate pt = templates.get(0);
        assertEquals("市场日报", pt.getLabel());
        assertEquals(List.of("市场回顾", "来源引用", "风险提示"),
            pt.getSections().stream().map(SectionDef::getLabel).toList());
        assertTrue(pt.getSections().get(0).isRequired());
        assertTrue(pt.getSections().get(1).isRequired());
        assertFalse(pt.getSections().get(2).isRequired());
        assertEquals(List.of(1, 2, 3),
            pt.getSections().stream().map(SectionDef::getOrder).toList());
    }

    @Test
    void shouldStripRequiredMarkerFromLabelWhenNumberedFormat() {
        String markdown = section3(String.join("\n",
            "### API 文档",
            "",
            "1. 接口概述（必填）",
            "2. 来源引用（必需）"));

        SchemaStructuredModel model = parser.parse(markdown);
        PageTemplate pt = model.getTemplates().getPageTemplates().get(0);
        assertEquals("接口概述", pt.getSections().get(0).getLabel());
        assertEquals("接口概述", pt.getSections().get(0).getId());
    }

    @Test
    void shouldStillParseLegacyUnorderedListSections() {
        String markdown = section3(String.join("\n",
            "### 基金月报",
            "",
            "- 基金概况章节：产品基本信息（必需）",
            "- 风险提示（可选）"));

        SchemaStructuredModel model = parser.parse(markdown);
        PageTemplate pt = model.getTemplates().getPageTemplates().get(0);
        assertEquals(2, pt.getSections().size());
        assertEquals("基金概况章节", pt.getSections().get(0).getLabel());
        assertTrue(pt.getSections().get(0).isRequired());
        assertEquals("风险提示", pt.getSections().get(1).getLabel());
        assertFalse(pt.getSections().get(1).isRequired());
    }

    @Test
    void shouldSkipNumberedLinesWithoutRequiredMarkerOrKeyword() {
        String markdown = section3(String.join("\n",
            "### 市场日报",
            "",
            "1. 先看盘面",
            "2. 再看个股"));

        SchemaStructuredModel model = parser.parse(markdown);
        PageTemplate pt = model.getTemplates().getPageTemplates().get(0);
        assertTrue(pt.getSections().isEmpty());
    }

    @Test
    void shouldRoundTripThroughRendererWithoutLabelDrift() {
        SchemaStructuredModel model = new SchemaStructuredModel();
        PageTemplate pt = new PageTemplate();
        pt.setType("市场日报");
        pt.setLabel("市场日报");
        pt.setSections(new ArrayList<>(List.of(
            sectionDef("市场回顾", true, 1),
            sectionDef("风险提示", false, 2))));
        model.getTemplates().getPageTemplates().add(pt);

        String markdown = new SchemaMarkdownRenderer().render(model);
        SchemaStructuredModel parsed = parser.parse(markdown);

        List<PageTemplate> templates = parsed.getTemplates().getPageTemplates();
        assertEquals(1, templates.size());
        assertEquals("市场日报", templates.get(0).getLabel());
        assertEquals(List.of("市场回顾", "风险提示"),
            templates.get(0).getSections().stream().map(SectionDef::getLabel).toList());
        assertTrue(templates.get(0).getSections().get(0).isRequired());
        assertFalse(templates.get(0).getSections().get(1).isRequired());
    }

    @Test
    void shouldKeepMultipleTemplatesDistinctWhenNumberedFormat() {
        String markdown = section3(String.join("\n",
            "### 策略报告",
            "",
            "1. 市场回顾（必需）",
            "",
            "### 读书笔记",
            "",
            "1. 书籍信息（必需）"));

        SchemaStructuredModel model = parser.parse(markdown);
        List<PageTemplate> templates = model.getTemplates().getPageTemplates();
        assertEquals(2, templates.size());
        assertEquals("策略报告", templates.get(0).getLabel());
        assertEquals("读书笔记", templates.get(1).getLabel());
    }
}
