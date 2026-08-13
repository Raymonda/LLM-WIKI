package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.query.QuerySseProtocol;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryPipelineRegressionTest {

    @Test
    void shouldDemoteFactBlockWhenSwitchOffAndKeepOtherEvents() {
        QuerySseProtocol.SseEvent factEvent = QuerySseProtocol.mapChunk("__FACT__:{}", false);
        QuerySseProtocol.SseEvent clarifyEvent = QuerySseProtocol.mapChunk("__CLARIFY__:{}", false);
        QuerySseProtocol.SseEvent stepEvent = QuerySseProtocol.mapChunk("__STEP__:generating", false);
        QuerySseProtocol.SseEvent plainEvent = QuerySseProtocol.mapChunk("text", false);
        assertEquals("answer-chunk", factEvent.eventName());
        assertEquals("clarification", clarifyEvent.eventName());
        assertEquals("step", stepEvent.eventName());
        assertEquals("answer-chunk", plainEvent.eventName());
    }

    @Test
    void shouldKeepAllEventsWithSwitchesOn() {
        assertEquals("fact-block", QuerySseProtocol.mapChunk("__FACT__:{}", true).eventName());
        assertEquals("clarification", QuerySseProtocol.mapChunk("__CLARIFY__:{}", true).eventName());
    }
}