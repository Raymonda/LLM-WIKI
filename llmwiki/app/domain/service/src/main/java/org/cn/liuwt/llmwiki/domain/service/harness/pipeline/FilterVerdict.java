package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

public record FilterVerdict(boolean allowed, String rejectedBy, String reason) {

    public static FilterVerdict allow() {
        return new FilterVerdict(true, null, null);
    }

    public static FilterVerdict reject(String rejectedBy, String reason) {
        return new FilterVerdict(false, rejectedBy, reason);
    }
}
