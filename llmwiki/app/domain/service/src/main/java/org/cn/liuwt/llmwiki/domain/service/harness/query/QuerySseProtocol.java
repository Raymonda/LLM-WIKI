package org.cn.liuwt.llmwiki.domain.service.harness.query;

public final class QuerySseProtocol {

    public static final String FACT_PREFIX = "__FACT__:";
    public static final String CLARIFY_PREFIX = "__CLARIFY__:";

    private QuerySseProtocol() {}

    public record SseEvent(String eventName, String payload) {}

    public static SseEvent mapChunk(String chunk) {
        return mapChunk(chunk, true);
    }

    public static SseEvent mapChunk(String chunk, boolean factBlockEnabled) {
        if (chunk == null) return new SseEvent("answer-chunk", "");
        if (chunk.startsWith(CLARIFY_PREFIX)) {
            return new SseEvent("clarification", chunk.substring(CLARIFY_PREFIX.length()));
        }
        if (chunk.startsWith(FACT_PREFIX)) {
            String payload = chunk.substring(FACT_PREFIX.length());
            return factBlockEnabled
                ? new SseEvent("fact-block", payload)
                : new SseEvent("answer-chunk", payload);
        }
        if (chunk.startsWith("__STEP__:")) {
            return new SseEvent("step", chunk.substring("__STEP__:".length()));
        }
        return new SseEvent("answer-chunk", chunk);
    }
}