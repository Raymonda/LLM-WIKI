package org.cn.liuwt.llmwiki.domain.service.harness.conflict;

public enum ConflictType {
    VALUE_CONFLICT("value_conflict"),
    FACT_CONFLICT("fact_conflict"),
    DEFINITION_CONFLICT("definition_conflict"),
    TEMPORAL_CONFLICT("temporal_conflict");

    private final String value;

    ConflictType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ConflictType fromString(String s) {
        if (s == null || s.isBlank()) return FACT_CONFLICT;
        for (ConflictType t : values()) {
            if (t.value.equalsIgnoreCase(s)) return t;
        }
        return FACT_CONFLICT;
    }
}
