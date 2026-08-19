package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.cn.liuwt.llmwiki.domain.service.harness.SpillProperties;
import org.cn.liuwt.llmwiki.domain.service.harness.SpillService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.TestEventLogs;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.cn.liuwt.llmwiki.integration.storage.LocalStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpillFilterTest {

    @TempDir
    Path tempDir;

    private SpillService spillService;
    private SpillFilter filter;

    @BeforeEach
    void setUp() {
        LocalStorageProvider storageProvider = new LocalStorageProvider();
        storageProvider.setBasePath(tempDir.toString());
        SpillProperties spillProperties = new SpillProperties();
        spillProperties.setMaxInlineBytes(64);
        spillService = new SpillService(storageProvider, spillProperties);
        filter = new SpillFilter(spillService, spillProperties, TestEventLogs.disabled());
    }

    @AfterEach
    void tearDown() {
        TokenUsageContext.clear();
    }

    @Test
    void aroundInvoke_smallOutput_passedThrough() throws Throwable {
        ToolInvocation invocation = invocation("readPage", "5");

        ToolResult result = filter.aroundInvoke(invocation, () -> new ToolResult("short content", null, 5L));

        assertEquals("short content", result.value());
        assertTrue(result.success());
    }

    @Test
    void aroundInvoke_largeOutput_replacedWithLocatorAndReadableBack() throws Throwable {
        String large = "x".repeat(200);
        ToolInvocation invocation = invocation("readPage", "5");

        ToolResult result = filter.aroundInvoke(invocation, () -> new ToolResult(large, null, 5L));

        assertTrue(result.success());
        String value = (String) result.value();
        assertTrue(value.contains("SPILL"));
        assertFalse(value.contains("xxxx"));
        String spillId = extractSpillId(value);
        assertEquals(large, spillService.readSpill(5L, "tool", spillId));
    }

    @Test
    void aroundInvoke_largeOutputWithoutScopeId_passedThrough() throws Throwable {
        String large = "x".repeat(200);
        ToolInvocation invocation = new ToolInvocation("readPage", "readPage",
                new Object[]{null, "a.md"}, null, "a.md");

        ToolResult result = filter.aroundInvoke(invocation, () -> new ToolResult(large, null, 5L));

        assertEquals(large, result.value());
    }

    @Test
    void aroundInvoke_failedResult_passedThrough() throws Throwable {
        ToolInvocation invocation = invocation("readPage", "5");
        ToolResult failed = new ToolResult(null, new IllegalStateException("boom"), 5L);

        ToolResult result = filter.aroundInvoke(invocation, () -> failed);

        assertEquals(failed, result);
    }

    @Test
    void aroundInvoke_nonStringValue_passedThrough() throws Throwable {
        ToolInvocation invocation = invocation("listPages", "5");
        List<String> pages = List.of("a.md", "b.md");

        ToolResult result = filter.aroundInvoke(invocation, () -> new ToolResult(pages, null, 5L));

        assertEquals(pages, result.value());
    }

    @Test
    void aroundInvoke_executionIdTakenFromTokenUsageContext() throws Throwable {
        TokenUsageContext.set(7L, "query");
        String large = "y".repeat(200);
        ToolInvocation invocation = invocation("readPage", "7");

        ToolResult result = filter.aroundInvoke(invocation, () -> new ToolResult(large, null, 5L));

        String spillId = extractSpillId((String) result.value());
        assertEquals(large, spillService.readSpill(7L, "query", spillId));
    }

    private static ToolInvocation invocation(String toolName, String scopeId) {
        return new ToolInvocation(toolName, toolName, new Object[]{scopeId, "a.md"}, scopeId, "a.md");
    }

    private static String extractSpillId(String locator) {
        int start = locator.indexOf("spillId=") + "spillId=".length();
        int end = locator.indexOf(' ', start);
        return locator.substring(start, end);
    }
}
