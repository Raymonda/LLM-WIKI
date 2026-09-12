package org.cn.liuwt.llmwiki.domain.service.harness.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class QueryToolProgress {

    public static final String CONTEXT_KEY = "toolProgressSink";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QueryToolProgress() {}

    public static void emit(ToolContext toolContext, String tool, String target, Integer count) {
        Consumer<String> sink = sinkOf(toolContext);
        if (sink == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", tool);
        if (target != null && !target.isBlank()) {
            payload.put("target", target);
        }
        if (count != null) {
            payload.put("count", count);
        }
        try {
            sink.accept(QuerySseProtocol.TOOL_PREFIX + MAPPER.writeValueAsString(payload));
        } catch (Exception ignored) {
        }
    }

    public static String displayName(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.replace('\\', '/').trim();
        int idx = normalized.lastIndexOf('/');
        String name = idx >= 0 ? normalized.substring(idx + 1) : normalized;
        return name.length() > 80 ? name.substring(0, 80) + "…" : name;
    }

    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.length() > maxChars ? stripped.substring(0, maxChars) + "…" : stripped;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String> sinkOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(CONTEXT_KEY);
        if (value instanceof Consumer<?> consumer) {
            return (Consumer<String>) consumer;
        }
        return null;
    }
}
