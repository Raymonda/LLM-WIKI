package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;

import java.time.LocalDateTime;
import java.util.List;

public interface ExecutionHistoryService {

    boolean isAnyExecutionRunning(Long scopeId);

    boolean isTypeExecutionRunning(Long scopeId, String type);

    String getRecentActivitySummary(Long scopeId);

    LocalDateTime findLastCompletedAt(Long scopeId, String type);

    ExecutionDO findMostRecentCompleted(Long scopeId, String type);

    ExecutionDO findActiveExecution(Long scopeId, String type);

    long countActiveExecutions(Long scopeId);

    List<ExecutionDO> findStaleExecutions(List<String> statuses);

    List<ExecutionDO> findStaleExecutionsBefore(List<String> statuses, LocalDateTime threshold);
}
