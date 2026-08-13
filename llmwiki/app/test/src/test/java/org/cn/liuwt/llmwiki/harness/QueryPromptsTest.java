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
}
