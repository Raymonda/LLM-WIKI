package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.query.QuerySseProtocol;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuerySseProtocolTest {

    @Test
    void shouldMapFactPrefixToFactBlockEvent() {
        QuerySseProtocol.SseEvent event = QuerySseProtocol.mapChunk("__FACT__:{\"id\":\"fb-1\",\"conclusion\":\"x\"}");
        assertEquals("fact-block", event.eventName());
        assertEquals("{\"id\":\"fb-1\",\"conclusion\":\"x\"}", event.payload());
    }

    @Test
    void shouldMapStepPrefixToStepEvent() {
        QuerySseProtocol.SseEvent event = QuerySseProtocol.mapChunk("__STEP__:generating");
        assertEquals("step", event.eventName());
        assertEquals("generating", event.payload());
    }

    @Test
    void shouldMapPlainChunkToAnswerChunkEvent() {
        QuerySseProtocol.SseEvent event = QuerySseProtocol.mapChunk("普通文本");
        assertEquals("answer-chunk", event.eventName());
        assertEquals("普通文本", event.payload());
    }

    @Test
    void shouldDemoteFactBlockToAnswerChunkWhenSwitchOff() {
        QuerySseProtocol.SseEvent event = QuerySseProtocol.mapChunk("__FACT__:{\"id\":\"x\",\"conclusion\":\"y\"}", false);
        assertEquals("answer-chunk", event.eventName());
        assertEquals("{\"id\":\"x\",\"conclusion\":\"y\"}", event.payload());
    }

    @Test
    void shouldMapClarifyPrefixToClarificationEvent() {
        QuerySseProtocol.SseEvent event = QuerySseProtocol.mapChunk("__CLARIFY__:{\"question\":\"追问\"}");
        assertEquals("clarification", event.eventName());
        assertEquals("{\"question\":\"追问\"}", event.payload());
    }
}