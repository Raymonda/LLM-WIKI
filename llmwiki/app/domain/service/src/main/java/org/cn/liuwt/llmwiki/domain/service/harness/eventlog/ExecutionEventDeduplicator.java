package org.cn.liuwt.llmwiki.domain.service.harness.eventlog;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExecutionEventDeduplicator {

    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();

    public boolean admit(String executionId, long seq) {
        if (executionId == null || executionId.isBlank() || seq < 0) {
            return false;
        }
        boolean[] admitted = {false};
        lastSeen.compute(executionId, (id, prev) -> {
            if (prev == null || seq > prev) {
                admitted[0] = true;
                return seq;
            }
            return prev;
        });
        return admitted[0];
    }

    public Long lastSeenSeq(String executionId) {
        return executionId == null ? null : lastSeen.get(executionId);
    }

    public void reset(String executionId) {
        if (executionId != null) {
            lastSeen.remove(executionId);
        }
    }
}
