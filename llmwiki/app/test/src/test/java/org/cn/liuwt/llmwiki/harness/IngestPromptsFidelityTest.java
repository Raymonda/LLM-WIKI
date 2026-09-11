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
            {"mergeIntoExistingPageWithPlan", prompts.mergeIntoExistingPageWithPlan("现有", "源", "分析", "{}", "更新", "{}")},
        };
        for (String[] c : cases) {
            assertTrue(c[1].contains(PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT),
                c[0] + " must embed the faithful compilation constraint");
        }
    }
}
