package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.config.IngestPrompts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IngestPromptsFidelityTest {

    private final IngestPrompts prompts = IngestPrompts.get();

    @Test
    void shouldDefineFaithfulCompilationConstraintBlock() {
        String block = PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT;
        assertTrue(block.contains("忠实编译约束"), "constraint block must be titled");
        assertTrue(block.contains("（来源："), "must require source annotation format");
        assertTrue(block.contains("严禁推断"), "must forbid inference");
        assertTrue(block.contains("并列呈现"), "must require side-by-side conflict presentation");
        assertTrue(block.contains("严禁裁决"), "must forbid conflict adjudication");
    }

    @Test
    void shouldInjectConstraintIntoAllWriterPrompts() {
        String[][] cases = {
            {"writeSummary", prompts.writeSummary("{}")},
            {"writeEntityPage", prompts.writeEntityPage("实体", "组织", "分析", "{}")},
            {"writeSummaryWithPlan", prompts.writeSummaryWithPlan("{}", "{}")},
            {"writeEntityPageWithPlan", prompts.writeEntityPageWithPlan("实体", "组织", "{}", "{}")},
            {"mergeIntoExistingPage", prompts.mergeIntoExistingPage("现有", "源", "分析", "{}", "更新")},
        };
        for (String[] c : cases) {
            assertTrue(c[1].contains(PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT),
                c[0] + " must embed the faithful compilation constraint");
        }
    }

    @Test
    void shouldDescribeSourceCompilationStructureForSummaryPages() {
        String single = prompts.writeSummary("{}");
        assertTrue(single.contains("文档结构地图"), "summary must start with a structure map");
        assertTrue(single.contains("来源未提供的信息"), "summary must allow a missing-info list");
        assertFalse(single.contains("5-8句话"), "self-repeating overview instruction must be gone");
        assertFalse(single.contains("一级标题"), "H1 instruction must be gone");
        assertFalse(single.contains("标注来源文件信息"), "trailing reference-source section must be gone");

        String planned = prompts.writeSummaryWithPlan("{}", "{}");
        assertTrue(planned.contains("文档结构地图"));
        assertFalse(planned.contains("必须用表格呈现"), "metadata table instruction must be gone");
    }

    @Test
    void shouldDescribeEntryAndSourceAnnotationForEntityPages() {
        String serial = prompts.writeEntityPage("实体", "组织", "分析", "{}");
        assertTrue(serial.contains("（来源："), "entity page must require per-entry source annotation");
        assertFalse(serial.contains("一级标题"), "H1 instruction must be gone");
        assertFalse(serial.contains("5. 参考来源"), "trailing reference-source section must be gone");

        String planned = prompts.writeEntityPageWithPlan("实体", "组织", "{}", "{}");
        assertTrue(planned.contains("（来源："));
        assertFalse(planned.contains("必须用表格呈现"));
        assertFalse(planned.contains("5. 参考来源"));
    }

    @Test
    void shouldKeepEntityAspectDimensionsWithAggregationSemantics() {
        String page = prompts.writeEntityPage("实体", "组织", "分析", "{}");
        assertTrue(page.contains("汇集"), "entity page must use aggregation semantics");
        assertTrue(page.contains("组织/公司类"), "entity aspect dimensions must stay");
    }

    @Test
    void shouldForbidAdjudicationStrategyInWritingPlan() {
        String plan = prompts.writingPlan("{}", "上下文");
        assertFalse(plan.contains("termMap"), "term unification table must be gone");
        assertFalse(plan.contains("不使用变体"), "term unification instruction must be gone");
        assertFalse(plan.contains("source_priority"), "source-priority adjudication must be gone");
        assertFalse(plan.contains("newer_wins"), "newer-wins adjudication must be gone");
        assertTrue(plan.contains("annotate_both"), "side-by-side annotation must stay");
    }

    @Test
    void shouldForbidAdjudicationInMergePrompts() {
        String merge = prompts.mergeIntoExistingPage("现有", "源", "分析", "{}", "更新");
        assertFalse(merge.contains("[已更新]"), "auto-adjudication marker must be gone");
        assertTrue(merge.contains("严禁裁决"), "merge must require side-by-side presentation");
    }

    @Test
    void shouldStrengthenReferenceSummaryFidelity() {
        String single = prompts.referenceSummary();
        assertTrue(single.contains("保留原文结构"), "chapter summary must preserve source structure");
        assertTrue(single.contains("blockquote"), "chapter summary must quote original clauses");
        assertTrue(single.contains("最小改写"), "chapter summary must minimize rewriting");

        String batch = prompts.batchReferenceSummaries();
        assertTrue(batch.contains("保留原文结构"), "batch summaries must preserve source structure");
        assertTrue(batch.contains("引用原文"), "batch summaries must reference original clauses");
        assertTrue(batch.contains("最小改写"), "batch summaries must minimize rewriting");
    }

    @Test
    void shouldDefineEntityClaimMergePrompt() {
        String prompt = prompts.mergeEntityClaims(
            "- [0] 总部位于上海（来源：2023 年报）\n- [1] 注册资本 10 亿元（来源：2023 年报）",
            "某公司 2024 年公告：注册资本变更为 12 亿元。");

        assertTrue(prompt.contains(PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT),
            "merge prompt must embed the faithful compilation constraint");
        assertTrue(prompt.contains("duplicate_of:<n>"), "must define duplicate relation format");
        assertTrue(prompt.contains("conflict_with:<n>"), "must define conflict relation format");
        assertTrue(prompt.contains("- [0] 总部位于上海（来源：2023 年报）"), "must embed existing entries listing");
        assertTrue(prompt.contains("某公司 2024 年公告：注册资本变更为 12 亿元。"), "must embed new source material");
    }

    @Test
    void shouldDefineBatchEntityClaimMergePrompt() {
        String prompt = prompts.batchMergeEntityClaims();

        assertTrue(prompt.contains(PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT),
            "batch merge prompt must embed the faithful compilation constraint");
        assertTrue(prompt.contains("pageIndex"), "must define pageIndex routing");
        assertTrue(prompt.contains("duplicate_of:<n>"), "must define duplicate relation format");
        assertTrue(prompt.contains("conflict_with:<n>"), "must define conflict relation format");
        assertTrue(prompt.contains("candidates"), "must define candidates array");
        assertTrue(prompt.contains("严禁裁决"), "must forbid adjudication");
    }
}
