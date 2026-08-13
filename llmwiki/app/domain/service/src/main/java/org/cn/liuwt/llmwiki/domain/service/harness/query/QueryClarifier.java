package org.cn.liuwt.llmwiki.domain.service.harness.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class QueryClarifier {

    private static final Logger log = LoggerFactory.getLogger(QueryClarifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long SESSION_TTL_MILLIS = 30 * 60 * 1000L;

    public record ClarificationResult(String clarity, String clarification, String reason) {}

    private final int maxClarifications;
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    @Autowired
    public QueryClarifier(@Value("${llmwiki.query.clarifier.max-rounds:1}") int maxClarifications) {
        this.maxClarifications = maxClarifications;
    }

    public ClarificationResult assess(ChatClient client, String systemPrompt, String question, String sessionId) {
        String raw = "";
        try {
            raw = client.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();
        } catch (Exception e) {
            log.warn("Clarification LLM call failed, defaulting to CLEAR: {}", e.getMessage());
            return new ClarificationResult("CLEAR", "", "llm-failed");
        }
        return applySessionGuard(sessionId, parseRaw(raw));
    }

    public ClarificationResult assessForTest(String sessionId, String rawJudgment, AtomicBoolean forcedClearFlag) {
        ClarificationResult parsed = parseRaw(rawJudgment);
        return applySessionGuard(sessionId, parsed, forcedClearFlag);
    }

    private ClarificationResult parseRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ClarificationResult("CLEAR", "", "parse-failed");
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            String clarity = "AMBIGUOUS".equalsIgnoreCase(trimmed) ? "AMBIGUOUS" : "CLEAR";
            return new ClarificationResult(clarity, "", trimmed);
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            String clarity = node.has("clarity") ? node.get("clarity").asText() : "CLEAR";
            String clarification = node.has("clarification") ? node.get("clarification").asText() : "";
            String reason = node.has("reason") ? node.get("reason").asText() : "";
            if (!"AMBIGUOUS".equals(clarity)) clarity = "CLEAR";
            return new ClarificationResult(clarity, clarification, reason);
        } catch (Exception e) {
            log.warn("Failed to parse clarification JSON, defaulting to CLEAR: raw={}", raw);
            return new ClarificationResult("CLEAR", "", "parse-failed");
        }
    }

    private ClarificationResult applySessionGuard(String sessionId, ClarificationResult result) {
        return applySessionGuard(sessionId, result, null);
    }

    private ClarificationResult applySessionGuard(String sessionId, ClarificationResult result, AtomicBoolean forcedClearFlag) {
        if (result == null || !"AMBIGUOUS".equals(result.clarity())) return result;
        if (sessionId == null || sessionId.isBlank()) return result;
        long now = System.currentTimeMillis();
        SessionState state = sessionStates.compute(sessionId, (k, v) -> (v == null || v.expired(now)) ? new SessionState(now) : v);
        if (state.tryIncrement(maxClarifications)) {
            return result;
        }
        if (forcedClearFlag != null) forcedClearFlag.set(true);
        return new ClarificationResult("CLEAR", result.clarification(), "forced-clear");
    }

    private static final class SessionState {
        private volatile long lastAccess;
        private final AtomicInteger counter = new AtomicInteger(0);

        SessionState(long now) { this.lastAccess = now; }

        boolean expired(long now) { return now - lastAccess > SESSION_TTL_MILLIS; }

        synchronized boolean tryIncrement(int max) {
            if (counter.get() >= max) return false;
            counter.incrementAndGet();
            lastAccess = System.currentTimeMillis();
            return true;
        }
    }
}