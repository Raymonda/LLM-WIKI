package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.AgentRunner;
import org.cn.liuwt.llmwiki.web.controller.QueryController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryNarrativeSwitchTest {

    @Test
    void shouldIncludeNarrativeFlagInStartEventDataWhenSwitchOn() {
        QueryController controller = new QueryController();
        ReflectionTestUtils.setField(controller, "narrativeEnabled", true);
        Map<String, Object> data = controller.buildStartEventData("query-1-123");
        assertEquals("query-1-123", data.get("sessionId"));
        assertEquals(true, data.get("narrative"));
    }

    @Test
    void shouldSetNarrativeFalseWhenSwitchOff() {
        QueryController controller = new QueryController();
        ReflectionTestUtils.setField(controller, "narrativeEnabled", false);
        Map<String, Object> data = controller.buildStartEventData("query-1-456");
        assertEquals("query-1-456", data.get("sessionId"));
        assertEquals(false, data.get("narrative"));
    }

    @Test
    void shouldUseNarrativePromptWhenSwitchOn() {
        AgentRunner runner = new AgentRunner();
        ReflectionTestUtils.setField(runner, "narrativeEnabled", true);
        String prompt = runner.buildSynthesisUserPrompt(1L, "q", "[1] 高可信 | 结论", "（无已过时页面）", false);
        assertTrue(prompt.contains("核心结论"), "switch on must select narrative prompt");
        assertFalse(prompt.contains("Layer 2"), "narrative prompt must not contain Layer 2 structure");
    }

    @Test
    void shouldUseSynthesisPromptWhenSwitchOff() {
        AgentRunner runner = new AgentRunner();
        ReflectionTestUtils.setField(runner, "narrativeEnabled", false);
        String prompt = runner.buildSynthesisUserPrompt(1L, "q", "[1] 高可信 | 结论", "（无已过时页面）", false);
        assertTrue(prompt.contains("Layer 2"), "switch off must select legacy synthesis prompt");
        assertFalse(prompt.contains("核心结论"), "legacy prompt must not contain narrative structure");
    }
}
