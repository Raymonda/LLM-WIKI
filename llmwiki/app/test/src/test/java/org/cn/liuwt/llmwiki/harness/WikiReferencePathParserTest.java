package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.query.WikiReferencePathParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WikiReferencePathParserTest {

    @Test
    void shouldExtractSingleBracketPaths() {
        List<String> paths = WikiReferencePathParser.extractPaths(
            "参见 [[日报]](wiki/pages/daily.md) 与 [[概览]](wiki/pages/overview.md)");
        assertEquals(List.of("pages/daily.md", "pages/overview.md"), paths);
    }

    @Test
    void shouldTolerateDoubleBracketPaths() {
        List<String> paths = WikiReferencePathParser.extractPaths(
            "- [[日报]]((wiki/pages/daily.md))\n- [[概览]]((wiki/pages/overview.md))");
        assertEquals(List.of("pages/daily.md", "pages/overview.md"), paths);
    }

    @Test
    void shouldDeduplicateRepeatedPaths() {
        List<String> paths = WikiReferencePathParser.extractPaths(
            "[[日报]](wiki/pages/daily.md) 再次引用 [[日报]](wiki/pages/daily.md)");
        assertEquals(List.of("pages/daily.md"), paths);
    }

    @Test
    void shouldPreserveFirstOccurrenceOrder() {
        List<String> paths = WikiReferencePathParser.extractPaths(
            "[[B]](wiki/pages/b.md) [[A]](wiki/pages/a.md) [[B]](wiki/pages/b.md)");
        assertEquals(List.of("pages/b.md", "pages/a.md"), paths);
    }

    @Test
    void shouldReturnEmptyWhenAnswerBlankOrWithoutLinks() {
        assertEquals(List.of(), WikiReferencePathParser.extractPaths(null));
        assertEquals(List.of(), WikiReferencePathParser.extractPaths(""));
        assertEquals(List.of(), WikiReferencePathParser.extractPaths("无链接的普通文本"));
    }
}
