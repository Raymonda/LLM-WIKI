package org.cn.liuwt.llmwiki.service.harness.task;

import java.util.Map;

public interface BackgroundTaskHandler {

    String taskType();

    default String idempotencyKey(Map<String, Object> payload) {
        return null;
    }

    default void validate(TaskContext ctx) {
    }

    void execute(TaskContext ctx) throws Exception;
}
