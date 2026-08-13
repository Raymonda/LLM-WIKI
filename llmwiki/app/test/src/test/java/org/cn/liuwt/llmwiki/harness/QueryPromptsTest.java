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
}
