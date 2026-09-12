package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.query.QuerySseProtocol;
import org.cn.liuwt.llmwiki.domain.service.harness.query.QueryToolProgress;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class QueryToolProgressTest {

    private final List<String> emitted = new ArrayList<>();

    private ToolContext contextWithSink() {
        return new ToolContext(Map.of(QueryToolProgress.CONTEXT_KEY, (Consumer<String>) emitted::add));
    }

    @Test
    void shouldEmitToolProgressEventWhenSinkPresent() {
        QueryToolProgress.emit(contextWithSink(), "readFile", "久期风险.md", null);

        assertEquals(1, emitted.size());
        String event = emitted.get(0);
        assertTrue(event.startsWith(QuerySseProtocol.TOOL_PREFIX));
        assertTrue(event.contains("\"tool\":\"readFile\""));
        assertTrue(event.contains("\"target\":\"久期风险.md\""));
        assertFalse(event.contains("\"count\""));
    }

    @Test
    void shouldIncludeCountWhenProvided() {
        QueryToolProgress.emit(contextWithSink(), "searchWiki", "久期", 5);

        String event = emitted.get(0);
        assertTrue(event.contains("\"count\":5"));
    }

    @Test
    void shouldOmitTargetWhenBlank() {
        QueryToolProgress.emit(contextWithSink(), "listPages", "  ", null);

        String event = emitted.get(0);
        assertTrue(event.contains("\"tool\":\"listPages\""));
        assertFalse(event.contains("\"target\""));
    }

    @Test
    void shouldStaySilentWhenToolContextMissing() {
        assertDoesNotThrow(() -> QueryToolProgress.emit(null, "readFile", "a.md", null));
        assertEquals(0, emitted.size());
    }

    @Test
    void shouldStaySilentWhenSinkMissing() {
        ToolContext context = new ToolContext(Map.of("sessionId", "s1"));

        assertDoesNotThrow(() -> QueryToolProgress.emit(context, "readFile", "a.md", null));
        assertEquals(0, emitted.size());
    }

    @Test
    void shouldExtractDisplayNameFromPath() {
        assertEquals("久期风险.md", QueryToolProgress.displayName("pages/债券/久期风险.md"));
        assertEquals("久期风险.md", QueryToolProgress.displayName("pages\\债券\\久期风险.md"));
        assertNull(QueryToolProgress.displayName("  "));
        assertNull(QueryToolProgress.displayName(null));
    }

    @Test
    void shouldTruncateDisplayNameOverEightyChars() {
        String longName = "a".repeat(100) + ".md";

        String name = QueryToolProgress.displayName(longName);

        assertEquals(81, name.length());
        assertTrue(name.endsWith("…"));
    }

    @Test
    void shouldTruncateTextOverLimit() {
        assertEquals("abc…", QueryToolProgress.truncate("abcdef", 3));
        assertEquals("ab", QueryToolProgress.truncate("ab", 10));
        assertNull(QueryToolProgress.truncate(null, 5));
    }
}
