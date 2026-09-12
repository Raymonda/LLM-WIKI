package org.cn.liuwt.llmwiki.service.harness.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

final class TaskPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskPayloads() {
    }

    static Map<String, Object> parse(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid task payload JSON: " + payloadJson, e);
        }
    }

    static String write(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize task payload", e);
        }
    }
}
