package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.model.harness.FactBlock;
import org.cn.liuwt.llmwiki.domain.service.harness.query.FactBlockParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FactBlockParserTest {

    @Test
    void shouldParseValidFactLine() {
        String line = "{\"id\":\"fb-1\",\"conclusion\":\"收益率下行\",\"evidence\":\"P2\",\"refs\":[{\"path\":\"wiki/pages/a.md\",\"title\":\"日报\"}],\"confidence\":\"high\",\"kind\":\"fact\"}";
        FactBlock block = FactBlockParser.tryParse(line);
        assertNotNull(block);
        assertEquals("fb-1", block.id());
        assertEquals("high", block.confidence());
        assertEquals(1, block.refs().size());
        assertEquals("wiki/pages/a.md", block.refs().get(0).path());
    }

    @Test
    void shouldReturnNullWhenLineIsNotJson() {
        assertNull(FactBlockParser.tryParse("这是普通文本"));
        assertNull(FactBlockParser.tryParse("{\"foo\":\"bar\"}"));
    }

    @Test
    void shouldDefaultMissingConfidenceToMedium() {
        String line = "{\"id\":\"fb-2\",\"conclusion\":\"结论\",\"kind\":\"fact\"}";
        FactBlock block = FactBlockParser.tryParse(line);
        assertNotNull(block);
        assertEquals("medium", block.confidence());
    }

    @Test
    void shouldExtractCompleteLinesAndKeepPartialInBuffer() {
        StringBuilder buffer = new StringBuilder("{\"id\":\"a\",\"conclusion\":\"x\"}\n{\"id\":\"b\",\"concl");
        List<String> lines = FactBlockParser.extractCompleteLines(buffer);
        assertEquals(1, lines.size());
        assertEquals("{\"id\":\"b\",\"concl", buffer.toString());
    }

    @Test
    void shouldRenderSummaryViewSortedByConfidenceWithNumbering() {
        List<FactBlock> blocks = List.of(
            new FactBlock("1", "中可信结论", "e1", List.of(), "medium", "fact"),
            new FactBlock("2", "高可信结论", "e2", List.of(), "high", "fact"),
            new FactBlock("3", "矛盾观点", "e3", List.of(), "high", "contrast"));
        String view = FactBlockParser.toSummaryView(blocks);
        assertTrue(view.indexOf("[1]") < view.indexOf("高可信结论"));
        assertTrue(view.contains("矛盾条目"));
    }

    @Test
    void shouldFallbackToRawTextWhenNoFactBlocks() {
        assertEquals("原始长文", FactBlockParser.toFactInput(List.of(), "原始长文"));
        String summary = FactBlockParser.toFactInput(
            List.of(new FactBlock("1", "结论", "e", List.of(), "high", "fact")), "原始长文");
        assertTrue(summary.contains("[1]"));
        assertFalse(summary.contains("原始长文"));
    }
}
