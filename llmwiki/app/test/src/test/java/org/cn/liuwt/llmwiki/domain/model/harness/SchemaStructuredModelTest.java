package org.cn.liuwt.llmwiki.domain.model.harness;

import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.PageTemplate;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.SectionDef;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.Taxonomy;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.TaxonomyNode;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.Templates;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaStructuredModelTest {

    private static PageTemplate template(String label, String... sections) {
        PageTemplate pt = new PageTemplate();
        pt.setType(label.toLowerCase());
        pt.setLabel(label);
        int order = 1;
        for (String section : sections) {
            SectionDef sd = new SectionDef();
            sd.setId(section.toLowerCase());
            sd.setLabel(section);
            sd.setOrder(order++);
            pt.getSections().add(sd);
        }
        return pt;
    }

    private static TaxonomyNode node(String id, String label, TaxonomyNode... children) {
        TaxonomyNode n = new TaxonomyNode();
        n.setId(id);
        n.setLabel(label);
        if (children.length > 0) {
            n.setChildren(List.of(children));
        }
        return n;
    }

    private static Taxonomy taxonomy() {
        Taxonomy tax = new Taxonomy();
        tax.setRoots(List.of(
            node("research", "研究分析",
                node("策略报告", "策略报告"),
                node("公司研究", "公司研究")),
            node("个人学习库", "个人学习库")
        ));
        return tax;
    }

    @Test
    void findByCategoryMatchesLeafLabelExactly() {
        Templates templates = new Templates();
        PageTemplate strategy = template("策略报告");
        PageTemplate compliance = template("制度文件");
        templates.getPageTemplates().addAll(List.of(compliance, strategy));

        assertEquals(List.of(strategy), templates.findByCategory("研究分析/策略报告"));
        assertEquals(List.of(compliance), templates.findByCategory("制度文件"));
    }

    @Test
    void findByCategoryReturnsAllTemplatesSharingSameLabel() {
        Templates templates = new Templates();
        PageTemplate first = template("基金月报", "基金概况");
        PageTemplate second = template("基金月报", "报告期间");
        templates.getPageTemplates().addAll(List.of(first, second));

        assertEquals(List.of(first, second), templates.findByCategory("基金月报"));
    }

    @Test
    void findByCategoryUsesSubstringWhenNoExactSegmentMatches() {
        Templates templates = new Templates();
        PageTemplate monthly = template("投资月报");
        templates.getPageTemplates().add(monthly);

        assertEquals(List.of(monthly), templates.findByCategory("固定收益/月报"));
    }

    @Test
    void findByCategoryReturnsEmptyWhenNothingMatches() {
        Templates templates = new Templates();
        templates.getPageTemplates().add(template("策略报告"));

        assertEquals(List.of(), templates.findByCategory("系统介绍"));
        assertEquals(List.of(), templates.findByCategory(null));
        assertEquals(List.of(), templates.findByCategory(" "));
    }

    @Test
    void isValidPathAcceptsBothIdAndLabelSegments() {
        Taxonomy tax = taxonomy();

        assertTrue(tax.isValidPath("research/策略报告"));
        assertTrue(tax.isValidPath("研究分析/策略报告"));
        assertTrue(tax.isValidPath("研究分析/公司研究"));
        assertTrue(tax.isValidPath("研究分析"));
        assertTrue(tax.isValidPath("个人学习库"));
    }

    @Test
    void isValidPathAllowsExtensionBeyondKnownLeaf() {
        Taxonomy tax = taxonomy();

        assertTrue(tax.isValidPath("研究分析/策略报告/细分主题"));
    }

    @Test
    void isValidPathRejectsUnknownSegments() {
        Taxonomy tax = taxonomy();

        assertFalse(tax.isValidPath("研究分析/概念"));
        assertFalse(tax.isValidPath("不存在的分类"));
        assertFalse(tax.isValidPath(""));
        assertFalse(tax.isValidPath(null));
    }

    @Test
    void flattenLabelPathsRendersHumanReadablePaths() {
        List<String> paths = taxonomy().flattenLabelPaths();

        assertTrue(paths.contains("研究分析/策略报告"));
        assertTrue(paths.contains("研究分析/公司研究"));
        assertFalse(paths.stream().anyMatch(p -> p.startsWith("research")));
    }
}
