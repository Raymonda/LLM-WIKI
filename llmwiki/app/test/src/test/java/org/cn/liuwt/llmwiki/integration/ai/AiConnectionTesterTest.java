package org.cn.liuwt.llmwiki.integration.ai;

import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiConnectionTestResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiConnectionTesterTest {

    @Test
    void shouldFailFastWhenEndpointUnreachable() {
        AiConnectionTester tester = new AiConnectionTester();
        long start = System.currentTimeMillis();
        AiConnectionTestResult result = tester.test("http://127.0.0.1:1", "sk-dummy", "qwen-plus");
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.ok());
        assertNotNull(result.message());
        assertTrue(result.latencyMs() >= 0);
        assertTrue(elapsed < 10_000, "unreachable endpoint must fail within the 10s timeout budget");
    }

    @Test
    void shouldProbeModelsEndpointWhenModelBlank() {
        AiConnectionTester tester = new AiConnectionTester();
        long start = System.currentTimeMillis();
        AiConnectionTestResult result = tester.test("http://127.0.0.1:1", "sk-dummy", "");
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.ok());
        assertNotNull(result.message());
        assertTrue(elapsed < 10_000, "unreachable endpoint must fail within the 10s timeout budget");
    }

    @Test
    void shouldConvergeErrorMessageWhenFailing() {
        AiConnectionTester tester = new AiConnectionTester();
        AiConnectionTestResult result = tester.test("http://127.0.0.1:1", "sk-dummy", "qwen-plus");

        assertFalse(result.ok());
        assertTrue(result.message().contains(":"), "message should carry exception simple name");
        assertTrue(result.message().length() < 260, "message must be truncated");
    }
}
