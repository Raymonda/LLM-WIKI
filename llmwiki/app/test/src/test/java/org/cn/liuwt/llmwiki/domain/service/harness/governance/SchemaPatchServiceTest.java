package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaPatchDO;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaPatchModel.Operation;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaMarkdownRenderer;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaStructuredParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SchemaPatchServiceTest {

    private static final String SCHEMA_WITH_CHANGELOG = String.join("\n",
        "## 1. 领域叙事",
        "内容 A",
        "",
        "## 7. 变更日志",
        "- 2026-09-01 冷启动初版，由 AI 与用户协同生成。",
        "- 2026-09-05 调整分类",
        "",
        "## 8. 附录",
        "尾部");

    private final SchemaPatchService service = new SchemaPatchService();

    private Object invoke(String name, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = SchemaPatchService.class.getDeclaredMethod(name, paramTypes);
        method.setAccessible(true);
        try {
            return method.invoke(service, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private BusinessException expectBusinessException(String name, Class<?>[] paramTypes, Object... args) {
        try {
            invoke(name, paramTypes, args);
            fail("expected BusinessException from " + name);
            return null;
        } catch (BusinessException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("unexpected exception from " + name, e);
        }
    }

    private static SchemaPatchDO patch(Long id, String operation, String before, String after) {
        SchemaPatchDO p = new SchemaPatchDO();
        p.setId(id);
        p.setScopeId(1L);
        p.setSectionTitle("## 2. 分类与命名");
        p.setOperation(operation);
        p.setDiffBefore(before);
        p.setDiffAfter(after);
        return p;
    }

    @Test
    void replaceSingleReplacesExactlyOneOccurrence() throws Exception {
        String result = (String) invoke("replaceSingle",
            new Class<?>[]{String.class, String.class, String.class, String.class},
            "alpha beta gamma", "beta", "BETA", "test");
        assertEquals("alpha BETA gamma", result);
    }

    @Test
    void replaceSingleRejectsWhenBeforeMissing() {
        BusinessException ex = expectBusinessException("replaceSingle",
            new Class<?>[]{String.class, String.class, String.class, String.class},
            "alpha beta", "missing", "x", "test");
        assertEquals(ErrorCode.PATCH_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void replaceSingleRejectsMultipleMatchesToPreventGlobalReplace() {
        BusinessException ex = expectBusinessException("replaceSingle",
            new Class<?>[]{String.class, String.class, String.class, String.class},
            "重复规则\n其他内容\n重复规则", "重复规则", "新规则", "test");
        assertEquals(ErrorCode.PATCH_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void validateRejectsDeleteWithBlankBefore() {
        BusinessException ex = expectBusinessException("validatePatchFields",
            new Class<?>[]{SchemaPatchDO.class, Operation.class},
            patch(1L, "DELETE", "   ", null), Operation.DELETE);
        assertEquals(ErrorCode.PATCH_DIFF_EMPTY_DELETE.getCode(), ex.getCode());
    }

    @Test
    void validateRejectsDeleteWithNullBefore() {
        BusinessException ex = expectBusinessException("validatePatchFields",
            new Class<?>[]{SchemaPatchDO.class, Operation.class},
            patch(1L, "DELETE", null, null), Operation.DELETE);
        assertEquals(ErrorCode.PATCH_DIFF_EMPTY_DELETE.getCode(), ex.getCode());
    }

    @Test
    void validateRejectsModifyWithBlankBefore() {
        BusinessException ex = expectBusinessException("validatePatchFields",
            new Class<?>[]{SchemaPatchDO.class, Operation.class},
            patch(1L, "MODIFY", "", "新规则"), Operation.MODIFY);
        assertEquals(ErrorCode.PATCH_DIFF_EMPTY_MODIFY.getCode(), ex.getCode());
    }

    @Test
    void validateRejectsAddWithNullAfter() {
        BusinessException ex = expectBusinessException("validatePatchFields",
            new Class<?>[]{SchemaPatchDO.class, Operation.class},
            patch(1L, "ADD", null, null), Operation.ADD);
        assertEquals(ErrorCode.PATCH_DIFF_EMPTY_ADD.getCode(), ex.getCode());
    }

    @Test
    void validateAcceptsWellFormedModify() throws Exception {
        invoke("validatePatchFields", new Class<?>[]{SchemaPatchDO.class, Operation.class},
            patch(1L, "MODIFY", "旧规则", "新规则"), Operation.MODIFY);
    }

    @Test
    void extractSection7BodyStopsAtNextHeading() throws Exception {
        String body = (String) invoke("extractSection7Body", new Class<?>[]{String.class}, SCHEMA_WITH_CHANGELOG);
        assertEquals("- 2026-09-01 冷启动初版，由 AI 与用户协同生成。\n- 2026-09-05 调整分类", body);
    }

    @Test
    void extractSection7BodyReturnsEmptyWhenMissing() throws Exception {
        assertEquals("", invoke("extractSection7Body", new Class<?>[]{String.class}, "## 1. 领域叙事\n内容"));
    }

    @Test
    void extractSection7BodyHandlesCrlf() throws Exception {
        String body = (String) invoke("extractSection7Body", new Class<?>[]{String.class},
            "## 7. 变更日志\r\n- 条目一\r\n## 8. 附录\r\n尾巴");
        assertEquals("- 条目一", body);
    }

    @Test
    void appendChangelogKeepsExistingEntriesAndAddsNewOne() throws Exception {
        String body = (String) invoke("appendChangelogEntry", new Class<?>[]{String.class, SchemaPatchDO.class},
            SCHEMA_WITH_CHANGELOG, patch(42L, "ADD", null, "新增规则"));
        assertTrue(body.startsWith("- 2026-09-01 冷启动初版，由 AI 与用户协同生成。\n- 2026-09-05 调整分类"));
        assertTrue(body.endsWith("用户采纳补丁#42：## 2. 分类与命名 ADD"));
    }

    @Test
    void appendChangelogOnSchemaWithoutSection7CreatesEntry() throws Exception {
        String body = (String) invoke("appendChangelogEntry", new Class<?>[]{String.class, SchemaPatchDO.class},
            "## 1. 领域叙事\n内容", patch(7L, "MODIFY", "a", "b"));
        assertTrue(body.contains("用户采纳补丁#7"));
    }

    @Test
    void batchChangelogSummarizesAppliedSections() throws Exception {
        String body = (String) invoke("appendBatchChangelogEntry", new Class<?>[]{String.class, List.class},
            SCHEMA_WITH_CHANGELOG, List.of(patch(9L, "ADD", null, "a"), patch(10L, "MODIFY", "b", "c")));
        assertTrue(body.startsWith("- 2026-09-01 冷启动初版，由 AI 与用户协同生成。"));
        assertTrue(body.endsWith("批量采纳补丁#9 等 2 条：## 2. 分类与命名"));
    }

    @Test
    void conflictsOnlyWhenSameOperationAndOverlappingText() throws Exception {
        Class<?>[] types = new Class<?>[]{SchemaPatchDO.class, SchemaPatchDO.class};
        assertTrue((Boolean) invoke("conflictsWith", types,
            patch(1L, "ADD", null, "规则：禁止使用缩写"), patch(2L, "ADD", null, "规则：禁止使用缩写")));
        assertFalse((Boolean) invoke("conflictsWith", types,
            patch(1L, "ADD", null, "规则：禁止使用缩写"), patch(2L, "DELETE", "规则：禁止使用缩写", null)));
        assertFalse((Boolean) invoke("conflictsWith", types,
            patch(1L, "ADD", null, "规则A：禁止使用缩写"), patch(2L, "ADD", null, "规则B：完全不同的内容")));
        assertFalse((Boolean) invoke("conflictsWith", types,
            patch(3L, "ADD", null, "同一条目"), patch(3L, "ADD", null, "同一条目")));
    }

    @Test
    void conflictDetectionIgnoresWhitespaceDifferences() throws Exception {
        Class<?>[] types = new Class<?>[]{SchemaPatchDO.class, SchemaPatchDO.class};
        assertTrue((Boolean) invoke("conflictsWith", types,
            patch(1L, "MODIFY", "旧 规则", "新 规则"), patch(2L, "MODIFY", "旧\n规则", "新\t规则")));
    }

    private static final Class<?>[] SECTION3_TYPES =
        new Class<?>[]{SchemaStructuredModel.class, SchemaPatchDO.class, Operation.class};

    private SchemaPatchDO section3Patch(Long id, String operation, String before, String after) {
        SchemaPatchDO p = patch(id, operation, before, after);
        p.setSectionTitle("## 3. 页面模板");
        return p;
    }

    @Test
    void section3AddFallsBackToNarrativeRuleWhenNoTemplateMatches() throws Exception {
        SchemaStructuredModel model = new SchemaStructuredModel();
        SchemaPatchDO p = section3Patch(35L, "ADD", null,
            "- **模板选择规则**：每篇文档根据其所属分类选择一个对应模板进行校验，不应同时套用全部模板");
        invoke("applySection3Patch", SECTION3_TYPES, model, p, Operation.ADD);
        assertEquals("- **模板选择规则**：每篇文档根据其所属分类选择一个对应模板进行校验，不应同时套用全部模板",
            model.getTemplates().getNarrative());
        assertTrue(model.getTemplates().getPageTemplates().isEmpty());
    }

    @Test
    void section3AddAppendsNarrativeRuleKeepingExistingNarrative() throws Exception {
        SchemaStructuredModel model = new SchemaStructuredModel();
        model.getTemplates().setNarrative("既有页面契约");
        SchemaPatchDO p = section3Patch(36L, "ADD", null, "- **模板选择规则**：按分类匹配唯一模板");
        invoke("applySection3Patch", SECTION3_TYPES, model, p, Operation.ADD);
        assertEquals("既有页面契约\n- **模板选择规则**：按分类匹配唯一模板",
            model.getTemplates().getNarrative());
    }

    @Test
    void section3AddStillCreatesTemplateWhenDiffHasHeading() throws Exception {
        SchemaStructuredModel model = new SchemaStructuredModel();
        SchemaPatchDO p = section3Patch(37L, "ADD", null,
            "### 市场日报\n\n1. 市场回顾（必需）\n2. 来源引用（必需）");
        invoke("applySection3Patch", SECTION3_TYPES, model, p, Operation.ADD);
        assertEquals(1, model.getTemplates().getPageTemplates().size());
        assertEquals("市场日报", model.getTemplates().getPageTemplates().get(0).getLabel());
        assertEquals(2, model.getTemplates().getPageTemplates().get(0).getSections().size());
        assertEquals(null, model.getTemplates().getNarrative());

        SchemaPatchDO rule = section3Patch(38L, "ADD", null,
            "- **模板选择规则**：规则文本里出现「必需章节」也不得被误判为新模板");
        invoke("applySection3Patch", SECTION3_TYPES, model, rule, Operation.ADD);
        assertEquals(1, model.getTemplates().getPageTemplates().size());
        assertTrue(model.getTemplates().getNarrative().contains("模板选择规则"));
    }

    @Test
    void section3AddAppendsSectionWhenTemplateExists() throws Exception {
        SchemaStructuredModel model = new SchemaStructuredModel();
        SchemaStructuredModel.PageTemplate pt = new SchemaStructuredModel.PageTemplate();
        pt.setType("基金月报");
        pt.setLabel("基金月报");
        model.getTemplates().getPageTemplates().add(pt);
        SchemaPatchDO p = section3Patch(39L, "ADD", null, "- 基金月报：新增风险提示（可选）");
        invoke("applySection3Patch", SECTION3_TYPES, model, p, Operation.ADD);
        assertEquals(1, model.getTemplates().getPageTemplates().size());
        assertEquals(1, pt.getSections().size());
        assertFalse(pt.getSections().get(0).isRequired());
        assertEquals(null, model.getTemplates().getNarrative());
    }

    @Test
    void section3ModifyReplacesMatchingNarrativeRule() throws Exception {
        SchemaStructuredModel model = new SchemaStructuredModel();
        model.getTemplates().setNarrative("- **模板选择规则**：按分类匹配唯一模板");
        SchemaPatchDO p = section3Patch(40L, "MODIFY",
            "- **模板选择规则**：按分类匹配唯一模板",
            "- **模板选择规则**：按分类匹配模板，摘要页不强制套用实体模板");
        invoke("applySection3Patch", SECTION3_TYPES, model, p, Operation.MODIFY);
        assertEquals("- **模板选择规则**：按分类匹配模板，摘要页不强制套用实体模板",
            model.getTemplates().getNarrative());
    }

    @Test
    void section3ModifyRejectsWhenNeitherTemplateNorNarrativeMatches() {
        SchemaStructuredModel model = new SchemaStructuredModel();
        SchemaPatchDO p = section3Patch(41L, "MODIFY", "- 不存在的规则", "- 新规则");
        BusinessException ex = expectBusinessException("applySection3Patch", SECTION3_TYPES,
            model, p, Operation.MODIFY);
        assertEquals(ErrorCode.PATCH_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void section3DeleteRemovesMatchingNarrativeRule() throws Exception {
        SchemaStructuredModel model = new SchemaStructuredModel();
        model.getTemplates().setNarrative("- **模板选择规则**：按分类匹配唯一模板\n- **引用规则**：保留原文");
        SchemaPatchDO p = section3Patch(42L, "DELETE", "- **模板选择规则**：按分类匹配唯一模板", null);
        invoke("applySection3Patch", SECTION3_TYPES, model, p, Operation.DELETE);
        assertEquals("- **引用规则**：保留原文", model.getTemplates().getNarrative());
    }

    @Test
    void templatesNarrativeRuleSurvivesRenderParseRoundTrip() throws Exception {
        SchemaStructuredModel model = new SchemaStructuredModel();
        SchemaPatchDO p = section3Patch(43L, "ADD", null,
            "- **模板选择规则**：每篇文档根据其所属分类选择一个对应模板进行校验");
        invoke("applySection3Patch", SECTION3_TYPES, model, p, Operation.ADD);

        String markdown = new SchemaMarkdownRenderer().render(model);
        assertTrue(markdown.contains("## 3. 页面模板"));
        assertTrue(markdown.contains("- **模板选择规则**：每篇文档根据其所属分类选择一个对应模板进行校验"));

        SchemaStructuredModel parsed = new SchemaStructuredParser().parse(markdown);
        assertEquals("- **模板选择规则**：每篇文档根据其所属分类选择一个对应模板进行校验",
            parsed.getTemplates().getNarrative());
    }

    @Test
    void section3AddWithHeadingMergesIntoSameLabelTemplateInsteadOfDuplicating() throws Exception {
        SchemaStructuredModel model = new SchemaStructuredModel();
        SchemaPatchDO first = section3Patch(44L, "ADD", null,
            "### 基金月报\n\n1. 基金概况（必需）\n2. 来源引用（必需）");
        invoke("applySection3Patch", SECTION3_TYPES, model, first, Operation.ADD);
        assertEquals(1, model.getTemplates().getPageTemplates().size());

        SchemaPatchDO second = section3Patch(45L, "ADD", null,
            "### 基金月报\n\n1. 报告期间（必需）\n2. 来源引用（必需）\n3. 风险提示（可选）");
        invoke("applySection3Patch", SECTION3_TYPES, model, second, Operation.ADD);

        assertEquals(1, model.getTemplates().getPageTemplates().size());
        SchemaStructuredModel.PageTemplate merged = model.getTemplates().getPageTemplates().get(0);
        assertEquals(List.of("基金概况", "来源引用", "报告期间", "风险提示"),
            merged.getSections().stream().map(SchemaStructuredModel.SectionDef::getLabel).toList());
        assertEquals(4, merged.getSections().size());
        assertFalse(merged.getSections().get(3).isRequired());
        assertTrue(model.getTemplates().getNarrative() == null);
    }

    @Test
    void section3AddWithHeadingStillCreatesDistinctTemplate() throws Exception {
        SchemaStructuredModel model = new SchemaStructuredModel();
        SchemaPatchDO first = section3Patch(46L, "ADD", null,
            "### 基金月报\n\n1. 基金概况（必需）");
        invoke("applySection3Patch", SECTION3_TYPES, model, first, Operation.ADD);

        SchemaPatchDO second = section3Patch(47L, "ADD", null,
            "### 市场日报\n\n1. 市场回顾（必需）");
        invoke("applySection3Patch", SECTION3_TYPES, model, second, Operation.ADD);

        assertEquals(2, model.getTemplates().getPageTemplates().size());
        assertEquals("市场日报", model.getTemplates().getPageTemplates().get(1).getLabel());
    }
}
