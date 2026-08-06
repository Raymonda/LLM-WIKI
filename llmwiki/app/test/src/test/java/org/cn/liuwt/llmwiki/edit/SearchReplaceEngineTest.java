package org.cn.liuwt.llmwiki.edit;

import org.cn.liuwt.llmwiki.domain.service.harness.edit.SearchReplaceEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchReplaceEngineTest {

    private final SearchReplaceEngine engine = new SearchReplaceEngine();

    @Test
    void locateAllFindsEveryOccurrence() {
        String doc = "alpha beta\ngamma\nalpha beta\n";
        List<Integer> positions = engine.fuzzyLocateAll(doc, "alpha beta");
        assertEquals(List.of(0, 17), positions);
    }

    @Test
    void locateAllWhitespaceNormalized() {
        String doc = "hello   world\nfoo";
        List<Integer> positions = engine.fuzzyLocateAll(doc, "hello world");
        assertEquals(1, positions.size());
        assertEquals(0, positions.get(0));
    }

    @Test
    void locateAllEmptyWhenNotFound() {
        assertTrue(engine.fuzzyLocateAll("abc", "xyz").isEmpty());
    }

    @Test
    void applyUniqueMatch() {
        SearchReplaceEngine.ApplyResult r = engine.applyBlocks("aaa\nbbb\nccc",
            List.of(new SearchReplaceEngine.SearchReplaceBlock("bbb", "BBB")));
        assertEquals("aaa\nBBB\nccc", r.content());
        assertEquals(1, r.appliedCount());
        assertTrue(r.failedBlocks().isEmpty());
    }

    @Test
    void applyAmbiguousRejected() {
        SearchReplaceEngine.ApplyResult r = engine.applyBlocks("dup\ntext\ndup\n",
            List.of(new SearchReplaceEngine.SearchReplaceBlock("dup", "DUP")));
        assertEquals("dup\ntext\ndup\n", r.content());
        assertEquals(0, r.appliedCount());
        assertEquals(1, r.failedBlocks().size());
        assertEquals("AMBIGUOUS", r.failedBlocks().get(0).getReason());
        assertEquals(Integer.valueOf(2), r.failedBlocks().get(0).getMatchCount());
        assertEquals(List.of(1, 3), r.failedBlocks().get(0).getMatchLines());
    }

    @Test
    void applyNotFoundReported() {
        SearchReplaceEngine.ApplyResult r = engine.applyBlocks("aaa\nbbb",
            List.of(new SearchReplaceEngine.SearchReplaceBlock("zzz", "ZZZ")));
        assertEquals("aaa\nbbb", r.content());
        assertEquals("NOT_FOUND", r.failedBlocks().get(0).getReason());
        assertEquals("zzz", r.failedBlocks().get(0).getSearchPreview());
    }

    @Test
    void applyMultipleBlocksSequentially() {
        SearchReplaceEngine.ApplyResult r = engine.applyBlocks("one\ntwo\nthree",
            List.of(
                new SearchReplaceEngine.SearchReplaceBlock("one", "ONE"),
                new SearchReplaceEngine.SearchReplaceBlock("three", "THREE")));
        assertEquals("ONE\ntwo\nTHREE", r.content());
        assertEquals(2, r.appliedCount());
    }

    @Test
    void applyWhitespaceNormalizedSearchWithExtraSpacesInSearch() {
        SearchReplaceEngine.ApplyResult r = engine.applyBlocks("a b",
            List.of(new SearchReplaceEngine.SearchReplaceBlock("a\t\tb", "X")));
        assertEquals("X", r.content());
        assertEquals(1, r.appliedCount());
        assertTrue(r.failedBlocks().isEmpty());
    }

    @Test
    void applyWhitespaceNormalizedDocWithExtraSpaces() {
        SearchReplaceEngine.ApplyResult r = engine.applyBlocks("pre a\t\tb post",
            List.of(new SearchReplaceEngine.SearchReplaceBlock("a b", "X")));
        assertEquals("pre X post", r.content());
        assertEquals(1, r.appliedCount());
    }

    @Test
    void applyCrlfDocumentWithLfSearch() {
        SearchReplaceEngine.ApplyResult r = engine.applyBlocks("a\r\n\r\nb",
            List.of(new SearchReplaceEngine.SearchReplaceBlock("a\n\nb", "X")));
        assertEquals("X", r.content());
        assertEquals(1, r.appliedCount());
        assertTrue(r.failedBlocks().isEmpty());
    }

    @Test
    void pureWhitespaceSearchDoesNotLoop() {
        SearchReplaceEngine.ApplyResult r = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            engine.applyBlocks("aaa",
                List.of(new SearchReplaceEngine.SearchReplaceBlock(" \t", "X"))));
        assertEquals("aaa", r.content());
        assertEquals("NOT_FOUND", r.failedBlocks().get(0).getReason());
    }

    @Test
    void matchEndOfNormalizedMatch() {
        assertEquals(4, engine.matchEndOf("a\r\nb", "a\nb", 0));
    }

    @Test
    void matchEndOfExactMatch() {
        assertEquals(3, engine.matchEndOf("abcdef", "abc", 0));
    }

    @Test
    void matchEndOfNonFirstNormalizedOccurrence() {
        String doc = "x\r\ny\r\nz\r\nx\r\ny\r\nz";
        assertEquals(16, engine.matchEndOf(doc, "x\ny\nz", 9));
    }
}
