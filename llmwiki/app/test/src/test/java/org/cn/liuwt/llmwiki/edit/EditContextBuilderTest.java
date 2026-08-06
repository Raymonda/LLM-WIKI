package org.cn.liuwt.llmwiki.edit;

import org.cn.liuwt.llmwiki.domain.service.harness.edit.EditContextBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditContextBuilderTest {

    private static final String DOC = String.join("\n",
        "# Title", "intro line", "## Section A", "body one", "body two", "## Section B", "body three");

    @Test
    void smallDocumentInjectedInFull() {
        EditContextBuilder builder = new EditContextBuilder(100000);
        String context = builder.buildFullContext(DOC, 4, 5);
        assertTrue(context.contains(">> L4| body one"));
        assertTrue(context.contains("L7| body three"));
        assertFalse(context.contains("[大纲骨架]"));
    }

    @Test
    void overBudgetAssemblesSkeletonSectionAndEdges() {
        EditContextBuilder builder = new EditContextBuilder(100);
        String context = builder.buildFullContext(DOC.repeat(200), 400, 401);
        assertTrue(context.contains("[大纲骨架]"));
        assertTrue(context.contains("[选区所在章节"));
        assertTrue(context.contains("[文档开头 5 行]"));
        assertTrue(context.contains("[文档末尾 5 行]"));
        assertTrue(context.length() <= 100);
    }

    @Test
    void focusedWindowWithHeadingPath() {
        EditContextBuilder builder = new EditContextBuilder(100000);
        String context = builder.buildFocusedContext(DOC, 4, 4);
        assertTrue(context.contains("[位置: Title > Section A]"));
        assertTrue(context.contains(">> L4| body one"));
        assertTrue(context.contains("选中: L4-L4"));
    }

    @Test
    void focusedWindowClampedAtDocumentStart() {
        EditContextBuilder builder = new EditContextBuilder(100000);
        String context = builder.buildFocusedContext(DOC, 1, 2);
        assertTrue(context.contains("显示 L1-L7"));
    }

    @Test
    void anchorBeyondDocumentLengthIsClamped() {
        EditContextBuilder builder = new EditContextBuilder(100000);
        String focused = builder.buildFocusedContext(DOC, 10, 12);
        assertTrue(focused.contains("选中: L7-L7"));

        EditContextBuilder tight = new EditContextBuilder(200);
        String full = tight.buildFullContext(DOC.repeat(50), 999, 1000);
        assertTrue(full.contains("选中: L301-L301"));
    }

    @Test
    void tinyBudgetBelowFixedOverheadDoesNotThrow() {
        EditContextBuilder builder = new EditContextBuilder(50);
        String context = builder.buildFullContext(DOC.repeat(10), 4, 5);
        assertTrue(context.length() <= 50);
    }
}
