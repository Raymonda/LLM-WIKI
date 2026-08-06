package org.cn.liuwt.llmwiki.edit;

import org.cn.liuwt.llmwiki.domain.service.harness.edit.SearchReplaceEngine;
import org.cn.liuwt.llmwiki.domain.service.harness.edit.SelectionAnchorer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SelectionAnchorerTest {

    private static final String DOC = "# Title\nfirst paragraph\nduplicate line\nfourth row\nduplicate line";

    private final SelectionAnchorer anchorer = new SelectionAnchorer(new SearchReplaceEngine());

    @Test
    void uniqueTextMatchReturnsExact() {
        SelectionAnchorer.AnchorResult r = anchorer.anchor(DOC, "L4", "fourth row");
        assertEquals(4, r.startLine());
        assertEquals(4, r.endLine());
        assertEquals("exact", r.status());
    }

    @Test
    void multiLineSelectionComputesEndLine() {
        SelectionAnchorer.AnchorResult r = anchorer.anchor(DOC, "", "first paragraph\nduplicate line");
        assertEquals(2, r.startLine());
        assertEquals(3, r.endLine());
        assertEquals("exact", r.status());
    }

    @Test
    void ambiguousTextPicksNearestToLineHint() {
        SelectionAnchorer.AnchorResult r = anchorer.anchor(DOC, "L5", "duplicate line");
        assertEquals(5, r.startLine());
        assertEquals(5, r.endLine());
        assertEquals("fuzzy", r.status());
    }

    @Test
    void ambiguousTextWithoutHintFallsBackToLines() {
        SelectionAnchorer.AnchorResult r = anchorer.anchor(DOC, "L3", "not present in doc");
        assertEquals(3, r.startLine());
        assertEquals(3, r.endLine());
        assertEquals("fallback-line", r.status());
    }

    @Test
    void lineRangeClampedToDocument() {
        SelectionAnchorer.AnchorResult r = anchorer.anchor(DOC, "L3-L99", null);
        assertEquals(3, r.startLine());
        assertEquals(5, r.endLine());
        assertEquals("fallback-line", r.status());
    }

    @Test
    void blankTextWithLineRangeFallsBackToLines() {
        SelectionAnchorer.AnchorResult r = anchorer.anchor(DOC, "L2", "   ");
        assertEquals(2, r.startLine());
        assertEquals(2, r.endLine());
        assertEquals("fallback-line", r.status());
    }

    @Test
    void noTextNoLinesFallsBackToFull() {
        SelectionAnchorer.AnchorResult r = anchorer.anchor(DOC, null, null);
        assertEquals(1, r.startLine());
        assertEquals(5, r.endLine());
        assertEquals("fallback-full", r.status());
    }

    @Test
    void crlfSelectionAnchorsEndLineCorrectly() {
        SelectionAnchorer.AnchorResult r = anchorer.anchor("a\r\nb\r\nc", "", "a\nb\nc");
        assertEquals(1, r.startLine());
        assertEquals(3, r.endLine());
        assertEquals("exact", r.status());
    }

    @Test
    void oversizedLineNumberDoesNotThrow() {
        SelectionAnchorer.AnchorResult r = anchorer.anchor(DOC, "L99999999999", null);
        assertEquals("fallback-full", r.status());
    }

    @Test
    void fuzzyCrlfSelectionAnchorsEndLineOfNonFirstMatch() {
        SelectionAnchorer.AnchorResult r = anchorer.anchor("x\r\ny\r\nz\r\nx\r\ny\r\nz", "L4", "x\ny\nz");
        assertEquals(4, r.startLine());
        assertEquals(6, r.endLine());
        assertEquals("fuzzy", r.status());
    }
}
