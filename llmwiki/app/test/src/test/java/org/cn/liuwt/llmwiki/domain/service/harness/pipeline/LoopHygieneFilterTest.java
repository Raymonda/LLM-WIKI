package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopHygieneFilterTest {

    private static ToolInvocation invocation(String path) {
        return new ToolInvocation("readFile", "readFile",
            new Object[]{"1", path}, "1", path);
    }

    private static ToolResult success() {
        return ToolResult.ofValue("ok", 1L);
    }

    private static ToolResult failure() {
        return ToolResult.ofError(new RuntimeException("error"), 1L);
    }

    private static LoopHygieneFilter enabledFilter(int maxRepeats, int maxCalls) {
        LoopHygieneProperties properties = new LoopHygieneProperties();
        properties.setEnabled(true);
        properties.setMaxRepeats(maxRepeats);
        properties.setMaxCallsPerExecution(maxCalls);
        return new LoopHygieneFilter(properties);
    }

    @Test
    void disabled_allowsRepeatedCalls() {
        LoopHygieneProperties properties = new LoopHygieneProperties();
        LoopHygieneFilter filter = new LoopHygieneFilter(properties);

        for (int i = 0; i < 10; i++) {
            assertTrue(filter.preFilter(invocation("pages/a.md")).allowed());
        }
    }

    @Test
    void repeatedSameArgs_blockedAfterMaxRepeats() {
        LoopHygieneFilter filter = enabledFilter(2, 100);
        ToolInvocation call = invocation("pages/a.md");

        for (int i = 0; i < 2; i++) {
            assertTrue(filter.preFilter(call).allowed());
            filter.postFilter(call, success());
        }

        FilterVerdict verdict = filter.preFilter(call);
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("readFile"));
    }

    @Test
    void differentArgs_notBlocked() {
        LoopHygieneFilter filter = enabledFilter(1, 100);

        assertTrue(filter.preFilter(invocation("pages/a.md")).allowed());
        filter.postFilter(invocation("pages/a.md"), success());

        assertTrue(filter.preFilter(invocation("pages/b.md")).allowed());
    }

    @Test
    void totalAttempts_blockedAtLimit() {
        LoopHygieneFilter filter = enabledFilter(100, 3);

        for (int i = 0; i < 3; i++) {
            assertTrue(filter.preFilter(invocation("pages/p" + i)).allowed());
        }
        FilterVerdict verdict = filter.preFilter(invocation("pages/another.md"));
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("上限"));
    }

    @Test
    void failedExecutions_notCountedAsRepeats() {
        LoopHygieneFilter filter = enabledFilter(1, 100);
        ToolInvocation call = invocation("pages/a.md");

        for (int i = 0; i < 3; i++) {
            filter.preFilter(call);
            filter.postFilter(call, failure());
        }
        assertTrue(filter.preFilter(call).allowed());
    }

    @Test
    void scopesAreIsolated() {
        LoopHygieneFilter filter = enabledFilter(1, 100);
        ToolInvocation scopeA = new ToolInvocation("readFile", "readFile",
            new Object[]{"1", "pages/a.md"}, "1", "pages/a.md");
        ToolInvocation scopeB = new ToolInvocation("readFile", "readFile",
            new Object[]{"2", "pages/a.md"}, "2", "pages/a.md");

        assertTrue(filter.preFilter(scopeA).allowed());
        filter.postFilter(scopeA, success());
        assertTrue(filter.preFilter(scopeB).allowed());
        assertFalse(filter.preFilter(scopeA).allowed());
    }

    @Test
    void reset_clearsCounters() {
        LoopHygieneFilter filter = enabledFilter(1, 1);
        ToolInvocation call = invocation("pages/a.md");

        assertTrue(filter.preFilter(call).allowed());
        filter.postFilter(call, success());
        assertFalse(filter.preFilter(call).allowed());

        filter.reset("1");
        assertTrue(filter.preFilter(call).allowed());
    }

    @Test
    void nonStringArgs_doNotBreakFingerprinting() {
        LoopHygieneFilter filter = enabledFilter(1, 100);
        ToolInvocation withObject = new ToolInvocation("todoWrite", "todoWrite",
            new Object[]{"[]", new Object()}, null, null);

        FilterVerdict verdict = filter.preFilter(withObject);
        assertTrue(verdict.allowed());
        assertNull(verdict.rejectedBy());
        filter.postFilter(withObject, success());
        assertFalse(filter.preFilter(withObject).allowed());
    }
}
