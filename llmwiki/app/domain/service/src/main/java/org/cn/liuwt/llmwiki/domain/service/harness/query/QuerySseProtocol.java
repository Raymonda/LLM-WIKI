package org.cn.liuwt.llmwiki.domain.service.harness.query;

public final class QuerySseProtocol {

    public static final String FACT_PREFIX = "__FACT__:";

    private QuerySseProtocol() {}

    public record SseEvent(String eventName, String payload) {}

    public static SseEvent mapChunk(String chunk) {
        if (chunk == null) return new SseEvent("answer-chunk", "");
        if (chunk.startsWith(FACT_PREFIX)) {
            return new SseEvent("fact-block", chunk.substring(FACT_PREFIX.length()));
        }
        if (chunk.startsWith("__STEP__:")) {
            return new SseEvent("step", chunk.substring("__STEP__:".length()));
        }
        return new SseEvent("answer-chunk", chunk);
    }
}