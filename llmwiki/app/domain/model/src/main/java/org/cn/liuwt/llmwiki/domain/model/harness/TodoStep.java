package org.cn.liuwt.llmwiki.domain.model.harness;

import java.util.List;

public record TodoStep(String content, String status, List<String> dependencies) {

    public static final String PENDING = "pending";
    public static final String IN_PROGRESS = "in_progress";
    public static final String DONE = "done";

    public TodoStep {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("todo step content 不能为空");
        }
        if (status == null || status.isBlank()) {
            status = PENDING;
        }
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }
}
