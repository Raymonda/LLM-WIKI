package org.cn.liuwt.llmwiki.domain.service.harness.governance.validation;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.PageTemplate;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.SectionDef;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.Taxonomy;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.TaxonomyNode;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaStructuredParser;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaComplianceCheckerTest {

    @Mock
    private SchemaManager schemaManager;

    @Mock
    private SchemaStructuredParser schemaStructuredParser;

    @Mock
    private WikiPageMapper wikiPageMapper;

    @InjectMocks
    private SchemaComplianceChecker checker;

    private void stubSchema(SchemaStructuredModel model) {
        SchemaConfigDO schema = new SchemaConfigDO();
        schema.setConfigValue("## 1. 领域定位\n\n内容\n\n## 2. 分类体系\n\n内容\n\n## 3. 页面模板\n\n内容\n");
        when(schemaManager.getSchema(1L, SchemaSkeletonValidator.WIKI_SCHEMA_KEY)).thenReturn(schema);
        when(schemaManager.getStructuredModel(1L)).thenReturn(model);
    }

    private static PageTemplate template(String label, String... requiredSections) {
        PageTemplate pt = new PageTemplate();
        pt.setType(label);
        pt.setLabel(label);
        int order = 1;
        for (String section : requiredSections) {
            SectionDef sd = new SectionDef();
            sd.setId(section);
            sd.setLabel(section);
            sd.setRequired(true);
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

    private static SchemaStructuredModel researchModel() {
        SchemaStructuredModel model = new SchemaStructuredModel();
        Taxonomy taxonomy = new Taxonomy();
        taxonomy.setRoots(List.of(
            node("research", "研究分析",
                node("策略报告", "策略报告"),
                node("公司研究", "公司研究")),
            node("制度文件", "制度文件"),
            node("个人学习库", "个人学习库")
        ));
        model.setTaxonomy(taxonomy);
        model.getTemplates().getPageTemplates().add(
            template("制度文件", "适用范围", "制度正文", "修订记录"));
        model.getTemplates().getPageTemplates().add(
            template("策略报告", "市场回顾", "核心观点", "投资建议"));
        model.getTemplates().getPageTemplates().add(
            template("公司研究", "公司概况", "财务分析"));
        return model;
    }

    private static WikiPageDO page(String category) {
        WikiPageDO page = new WikiPageDO();
        page.setCategory(category);
        return page;
    }

    private static String metadata(String category) {
        return "{\"category\":\"" + category + "\"}";
    }

    @Test
    void pageIsValidatedOnlyAgainstTemplateMatchingItsCategory() {
        SchemaStructuredModel model = researchModel();
        stubSchema(model);
        when(wikiPageMapper.selectOne(any())).thenReturn(page("研究分析/策略报告"));

        Map<String, String> pages = Map.of("pages/债券日报.md",
            "## 市场回顾\n\n内容\n\n## 核心观点\n\n内容\n\n## 投资建议\n\n内容");

        ComplianceResult result = checker.check(1L, metadata("制度文件"), pages);

        assertFalse(result.hasViolations(), result::summarize);
    }

    @Test
    void pageReportsMissingSectionOfMatchedTemplateOnly() {
        SchemaStructuredModel model = researchModel();
        stubSchema(model);
        when(wikiPageMapper.selectOne(any())).thenReturn(page("研究分析/策略报告"));

        Map<String, String> pages = Map.of("pages/债券日报.md", "## 市场回顾\n\n内容\n\n## 核心观点\n\n内容");

        ComplianceResult result = checker.check(1L, metadata("研究分析/策略报告"), pages);

        assertEquals(1, result.violations().size(), result::summarize);
        SchemaComplianceChecker.SchemaViolation violation = result.violations().get(0);
        assertEquals(SchemaComplianceChecker.ViolationType.PAGE_STRUCTURE, violation.violationType());
        assertTrue(violation.description().contains("投资建议"));
        assertTrue(violation.description().contains("策略报告"));
        assertFalse(violation.description().contains("适用范围"));
    }

    @Test
    void pageSkippedWhenNoTemplateMatchesItsCategory() {
        SchemaStructuredModel model = researchModel();
        stubSchema(model);
        when(wikiPageMapper.selectOne(any())).thenReturn(page("未知分类"));

        Map<String, String> pages = Map.of("pages/任意.md", "## 随意章节\n\n内容");

        ComplianceResult result = checker.check(1L, metadata("研究分析/策略报告"), pages);

        assertFalse(result.hasViolations(), result::summarize);
    }

    @Test
    void singlePageWithoutDatabaseRowFallsBackToMetadataCategory() {
        SchemaStructuredModel model = researchModel();
        stubSchema(model);
        when(wikiPageMapper.selectOne(any())).thenReturn(null);

        Map<String, String> pages = Map.of("某标题", "## 市场回顾\n\n内容\n\n## 核心观点\n\n内容\n\n## 投资建议\n\n内容");

        ComplianceResult result = checker.check(1L, metadata("研究分析/策略报告"), pages);

        assertFalse(result.hasViolations(), result::summarize);
    }

    @Test
    void pageCategoryResolvedByTitleLookupTakesPrecedenceOverMetadata() {
        SchemaStructuredModel model = researchModel();
        stubSchema(model);
        when(wikiPageMapper.selectOne(any())).thenReturn(null, page("研究分析/公司研究"));

        Map<String, String> pages = Map.of("弘尚资产月报",
            "## 市场回顾\n\n内容\n\n## 核心观点\n\n内容\n\n## 投资建议\n\n内容");

        ComplianceResult result = checker.check(1L, metadata("研究分析/策略报告"), pages);

        assertEquals(2, result.violations().size(), result::summarize);
        assertTrue(result.violations().stream().allMatch(v -> v.description().contains("公司研究")));
    }

    @Test
    void multiPageWithoutResolvableCategoryIsSkippedInsteadOfUsingMetadataCategory() {
        SchemaStructuredModel model = researchModel();
        stubSchema(model);
        when(wikiPageMapper.selectOne(any())).thenReturn(null);

        Map<String, String> pages = Map.of(
            "pages/a.md", "## 市场回顾\n\n内容",
            "pages/b.md", "## 随意章节\n\n内容");

        ComplianceResult result = checker.check(1L, metadata("研究分析/策略报告"), pages);

        assertFalse(result.hasViolations(), result::summarize);
    }

    @Test
    void planSummaryValidatedAgainstCategoryTemplateOnly() {
        SchemaStructuredModel model = researchModel();
        stubSchema(model);

        String plan = "{\"summaryOutline\":\"## 市场回顾\\n\\n## 核心观点\"}";

        ComplianceResult result = checker.checkPlan(1L, plan, metadata("研究分析/策略报告"));

        assertEquals(1, result.violations().size(), result::summarize);
        SchemaComplianceChecker.SchemaViolation violation = result.violations().get(0);
        assertTrue(violation.description().contains("投资建议"));
        assertFalse(violation.description().contains("适用范围"));
        assertFalse(violation.description().contains("公司概况"));
    }

    @Test
    void planSummarySkippedWhenNoTemplateMatchesMetadataCategory() {
        SchemaStructuredModel model = researchModel();
        stubSchema(model);

        String plan = "{\"summaryOutline\":\"## 随意章节\"}";

        ComplianceResult result = checker.checkPlan(1L, plan, metadata("个人学习库"));

        assertFalse(result.hasViolations(), result::summarize);
    }
}
