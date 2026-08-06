package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.integration.ai.ContextBudgetManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextBudgetManagerTest {

    private ContextBudgetManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContextBudgetManager();
    }

    @Test
    void estimateTokens_allEnglish() {
        int tokens = manager.estimateTokens("Hello world this is a test");
        assertEquals(6, tokens);
    }

    @Test
    void estimateTokens_allChinese() {
        int tokens = manager.estimateTokens("你好世界这是一个测试");
        assertEquals(5, tokens);
    }

    @Test
    void estimateTokens_mixed() {
        int tokens = manager.estimateTokens("Hello 你好 world 世界");
        assertTrue(tokens > 0 && tokens < 10);
    }

    @Test
    void estimateTokens_null() {
        assertEquals(0, manager.estimateTokens(null));
    }

    @Test
    void estimateTokens_empty() {
        assertEquals(0, manager.estimateTokens(""));
    }

    @Test
    void enforceBudget_withinBudget_noTruncation() {
        String system = "You are an assistant.";
        String user = "What is 2+2?";
        ContextBudgetManager.ContextBudgetResult result = manager.enforceBudget(system, user, 10000);
        assertFalse(result.truncated());
        assertEquals(system, result.systemPrompt());
        assertEquals(user, result.userMessage());
    }

    @Test
    void enforceBudget_exceedsBudget_truncatesUserMessage() {
        String system = "System prompt";
        StringBuilder largeUser = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            largeUser.append("word ");
        }
        ContextBudgetManager.ContextBudgetResult result = manager.enforceBudget(system, largeUser.toString(), 100);
        assertTrue(result.truncated());
        assertEquals(system, result.systemPrompt());
        assertTrue(result.userMessage().contains("已截断"));
        assertTrue(result.estimatedTokens() <= 200);
    }

    @Test
    void enforceBudget_systemPromptPreserved() {
        String system = "Important system instructions that must not be truncated.";
        StringBuilder largeUser = new StringBuilder();
        for (int i = 0; i < 50000; i++) {
            largeUser.append("data ");
        }
        ContextBudgetManager.ContextBudgetResult result = manager.enforceBudget(system, largeUser.toString(), 200);
        assertEquals(system, result.systemPrompt());
        assertTrue(result.truncated());
    }

    @Test
    void truncateToTokens_shortText() {
        String text = "Short text";
        String result = manager.truncateToTokens(text, 10000);
        assertEquals(text, result);
    }

    @Test
    void truncateToTokens_longText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("word ");
        }
        String result = manager.truncateToTokens(sb.toString(), 10);
        assertTrue(result.contains("已截断"));
        assertTrue(manager.estimateTokens(result) <= 20);
    }
}
