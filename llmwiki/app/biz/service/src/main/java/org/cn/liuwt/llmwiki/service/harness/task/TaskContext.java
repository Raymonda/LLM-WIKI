package org.cn.liuwt.llmwiki.service.harness.task;

import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;

import java.util.Map;

public record TaskContext(
    Long executionId,
    Long scopeId,
    Long submittedBy,
    Map<String, Object> payload,
    ExecutionModel execution
) {
}
