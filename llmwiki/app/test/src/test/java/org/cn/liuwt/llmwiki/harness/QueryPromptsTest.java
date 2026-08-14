package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.config.QueryPrompts;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryPromptsTest {

    @Test
    void shouldRequireFactBlocksAsJsonLinesWhenPromptingFactAgent() {
        QueryPrompts prompts = PromptRegistry.forQuery();
        String prompt = prompts.factAgentPromptStructured(1L, 10, "ctx");
        assertTrue(prompt.contains("JSON"), "prompt must mandate JSON output");
        assertTrue(prompt.contains("conclusion"), "prompt must define FactBlock fields");
        assertTrue(prompt.contains("维度覆盖表"), "prompt must require dimension coverage self-check");
        assertTrue(prompt.contains("kind"), "prompt must define fact kinds");
    }

    @Test
    void shouldIncludeStopGuardrailsWhenPromptingFactAgent() {
        QueryPrompts prompts = PromptRegistry.forQuery();
        String prompt = prompts.factAgentPromptStructured(1L, 10, "ctx");
        assertTrue(prompt.contains("维度未覆盖"), "prompt must force re-retrieval on uncovered dimensions");
    }

    @Test
    void shouldBuildSynthesisPromptFromFactSummaryView() {
        QueryPrompts prompts = PromptRegistry.forQuery();
        String prompt = prompts.synthesisPrompt(1L, "问题", "[1] 高可信 | 结论（依据：P2）", "（无已过时页面与本次查询相关）", false);
        assertTrue(prompt.contains("事实清单"), "prompt must contain fact list section");
        assertTrue(prompt.contains("[1] 高可信"), "prompt must embed the summary view");
        assertTrue(prompt.contains("Layer 2"), "prompt must keep Layer 2 structure");
        assertTrue(prompt.contains("Layer 3"), "prompt must keep Layer 3 structure");
    }

    @Test
    void shouldRequireFactAnchoringWhenPromptingSynthesis() {
        QueryPrompts prompts = PromptRegistry.forQuery();
        String prompt = prompts.synthesisPrompt(1L, "q", "[1] 高可信 | 结论", "（无已过时页面与本次查询相关）", true);
        assertTrue(prompt.contains("锚定事实编号"), "prompt must require fact anchoring");
        assertTrue(prompt.contains("置信度分级措辞"), "prompt must bind assertion strength to confidence");
        assertTrue(prompt.contains("不做推演"), "prompt must allow explicit no-evidence declaration");
    }

    @Test
    void shouldRequireCoreConclusionFirstWhenPromptingNarrative() {
        QueryPrompts prompts = PromptRegistry.forQuery();
        String prompt = prompts.narrativePrompt(1L, "问题", "[1] 高可信 | 结论", "（无已过时页面）", false);
        assertTrue(prompt.contains("核心结论"), "narrative prompt must open with core conclusion");
        assertTrue(prompt.contains("论证主体"), "narrative prompt must define argument body");
    }

    @Test
    void shouldKeepProspectiveSectionWhenPromptingNarrative() {
        QueryPrompts prompts = PromptRegistry.forQuery();
        String prompt = prompts.narrativePrompt(1L, "q", "[1] 高可信 | 结论", "（无已过时页面）", true);
        assertTrue(prompt.contains("前瞻分析"), "narrative prompt must keep prospective section");
        assertTrue(prompt.contains("仅供参考"), "narrative prompt must keep disclaimer wording");
    }

    @Test
    void shouldForbidVerbatimFactPastingWhenPromptingNarrative() {
        QueryPrompts prompts = PromptRegistry.forQuery();
        String prompt = prompts.narrativePrompt(1L, "q", "[1] 高可信 | 结论", "（无已过时页面）", false);
        assertTrue(prompt.contains("原料"), "narrative prompt must forbid pasting fact list verbatim");
        assertTrue(prompt.contains("叙事语言"), "narrative prompt must require narrative rewriting");
    }

    @Test
    void shouldIncludeRichElementGuidanceForAllModesWhenPromptingNarrative() {
        QueryPrompts prompts = PromptRegistry.forQuery();
        String prompt = prompts.narrativePrompt(1L, "q", "[1] 高可信 | 结论", "（无已过时页面）", false);
        assertTrue(prompt.contains("时间线"), "narrative prompt must include rich element guidance in all modes");
        assertTrue(prompt.contains("编造"), "narrative prompt must forbid fabricating chart data");
    }
}
