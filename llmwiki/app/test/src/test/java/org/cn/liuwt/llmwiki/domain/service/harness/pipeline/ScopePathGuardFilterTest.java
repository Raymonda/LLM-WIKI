package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopePathGuardFilterTest {

    private final ScopePathGuardFilter filter = new ScopePathGuardFilter();

    private static ToolInvocation invocation(String methodName, String scopeId, String path) {
        return new ToolInvocation(methodName, methodName, new Object[0], scopeId, path);
    }

    @Test
    void preFilter_normalPathAndScope_allowed() {
        assertTrue(filter.preFilter(invocation("readFile", "1", "wiki/pages/a.md")).allowed());
    }

    @Test
    void preFilter_pathTraversal_rejected() {
        FilterVerdict verdict = filter.preFilter(invocation("readFile", "1", "wiki/../../etc/passwd"));

        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("父目录"));
    }

    @Test
    void preFilter_writeToolToRaw_rejected() {
        FilterVerdict verdict = filter.preFilter(invocation("writeFile", "1", "raw/source.pdf"));

        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("raw"));
    }

    @Test
    void preFilter_readToolWithRawPath_allowed() {
        assertTrue(filter.preFilter(invocation("readFile", "1", "raw/source.pdf")).allowed());
    }

    @Test
    void preFilter_updateToolToRaw_rejected() {
        assertFalse(filter.preFilter(invocation("updateLinks", "1", "raw/x")).allowed());
    }

    @Test
    void preFilter_nonNumericScopeId_rejected() {
        FilterVerdict verdict = filter.preFilter(invocation("searchWiki", "abc", null));

        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("scopeId"));
    }

    @Test
    void preFilter_noPathNoScope_allowed() {
        FilterVerdict verdict = filter.preFilter(invocation("extractMetadata", null, null));

        assertTrue(verdict.allowed());
        assertNull(verdict.reason());
    }

    @Test
    void preFilter_blankPath_allowed() {
        assertTrue(filter.preFilter(invocation("searchWiki", "1", " ")).allowed());
    }
}
