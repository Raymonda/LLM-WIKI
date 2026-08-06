package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import java.util.Map;

public enum IngestStep {

    UPLOAD("system", false, 8000),
    ANALYZE("ai", true, 45000),
    WRITE("ai", true, 60000),
    COMPLETE("system", false, 20000);

    private static final Map<String, String> LEGACY_NAME_MAP = Map.ofEntries(
        Map.entry("PARSE_DOCUMENT", "UPLOAD"),
        Map.entry("READ_SOURCE", "UPLOAD"),
        Map.entry("SPLIT_CHUNKS", "UPLOAD"),
        Map.entry("ANALYZE_CHUNKS", "ANALYZE"),
        Map.entry("MERGE_RESULTS", "ANALYZE"),
        Map.entry("EXTRACT_METADATA", "ANALYZE"),
        Map.entry("PLANNING", "WRITE"),
        Map.entry("WRITE_SUMMARY", "WRITE"),
        Map.entry("WRITE_ENTITY_PAGES", "WRITE"),
        Map.entry("UPDATE_RELATED", "WRITE"),
        Map.entry("UPDATE_LINKS", "COMPLETE"),
        Map.entry("PROPOSE_SCHEMA_PATCH", "COMPLETE")
    );

    private final String type;
    private final boolean requiresAi;
    private final long baselineMs;

    IngestStep(String type, boolean requiresAi, long baselineMs) {
        this.type = type;
        this.requiresAi = requiresAi;
        this.baselineMs = baselineMs;
    }

    public String type() { return type; }
    public boolean requiresAi() { return requiresAi; }
    public long baselineMs() { return baselineMs; }

    public static String normalizeStepName(String legacyName) {
        return LEGACY_NAME_MAP.getOrDefault(legacyName, legacyName);
    }

    public static IngestStep[] phase1() {
        return new IngestStep[]{UPLOAD, ANALYZE};
    }

    public static IngestStep[] phase2() {
        return new IngestStep[]{WRITE, COMPLETE};
    }

    public static IngestStep[] all() {
        return values();
    }

    public String stepName() {
        return name();
    }
}