package org.cn.liuwt.llmwiki.domain.service.harness.crossref;

public enum LinkType {
    REFERENCE("reference"),
    CONTRADICTION("contradiction"),
    SUPPLEMENT("supplement"),
    DEPENDENCY("dependency"),
    RELATED("related");

    private final String value;

    LinkType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static LinkType fromString(String s) {
        if (s == null || s.isBlank()) return RELATED;
        for (LinkType t : values()) {
            if (t.value.equalsIgnoreCase(s)) return t;
        }
        return RELATED;
    }
}
