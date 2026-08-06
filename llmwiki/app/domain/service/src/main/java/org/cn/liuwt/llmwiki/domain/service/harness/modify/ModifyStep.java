package org.cn.liuwt.llmwiki.domain.service.harness.modify;

public enum ModifyStep {

    ANALYZE_FEEDBACK("ai", true, 15000),
    WRITE_PAGES("ai", true, 20000),
    UPDATE_LINKS("ai", true, 10000);

    private final String type;
    private final boolean requiresAi;
    private final long baselineMs;

    ModifyStep(String type, boolean requiresAi, long baselineMs) {
        this.type = type;
        this.requiresAi = requiresAi;
        this.baselineMs = baselineMs;
    }

    public String type() { return type; }
    public boolean requiresAi() { return requiresAi; }
    public long baselineMs() { return baselineMs; }
}
