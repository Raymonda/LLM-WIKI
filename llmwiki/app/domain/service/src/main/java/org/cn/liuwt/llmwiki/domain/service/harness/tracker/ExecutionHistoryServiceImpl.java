package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@Service
public class ExecutionHistoryServiceImpl implements ExecutionHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionHistoryServiceImpl.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final List<String> ACTIVE_STATUSES = Arrays.asList("running", "pending", "awaiting_confirmation");

    @Autowired
    private ExecutionMapper executionMapper;

    @Override
    public boolean isAnyExecutionRunning(Long scopeId) {
        long count = executionMapper.selectCount(
            new LambdaQueryWrapper<ExecutionDO>()
                .eq(ExecutionDO::getScopeId, scopeId)
                .in(ExecutionDO::getStatus, ACTIVE_STATUSES)
        );
        return count > 0;
    }

    @Override
    public boolean isTypeExecutionRunning(Long scopeId, String type) {
        long count = executionMapper.selectCount(
            new LambdaQueryWrapper<ExecutionDO>()
                .eq(ExecutionDO::getScopeId, scopeId)
                .eq(ExecutionDO::getType, type)
                .in(ExecutionDO::getStatus, ACTIVE_STATUSES)
        );
        return count > 0;
    }

    @Override
    public String getRecentActivitySummary(Long scopeId) {
        try {
            List<ExecutionDO> executions = executionMapper.selectList(
                new LambdaQueryWrapper<ExecutionDO>()
                    .eq(ExecutionDO::getScopeId, scopeId)
                    .orderByDesc(ExecutionDO::getCreatedAt)
                    .last("LIMIT 20")
            );

            if (executions.isEmpty()) {
                return "(暂无操作记录)";
            }

            StringBuilder sb = new StringBuilder();
            for (ExecutionDO exec : executions) {
                String date = exec.getCreatedAt() != null
                    ? exec.getCreatedAt().format(DATE_FMT)
                    : "unknown";
                String type = exec.getType() != null ? exec.getType() : "unknown";
                String status = exec.getStatus() != null ? exec.getStatus() : "";
                sb.append("## [").append(date).append("] ").append(type);
                if (!status.isEmpty() && !"completed".equals(status)) {
                    sb.append(" (").append(status).append(")");
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to extract recent activity for scopeId={}: {}", scopeId, e.getMessage());
            return "(无法获取最近活动)";
        }
    }

    @Override
    public LocalDateTime findLastCompletedAt(Long scopeId, String type) {
        try {
            List<ExecutionDO> results = executionMapper.selectList(
                new LambdaQueryWrapper<ExecutionDO>()
                    .eq(ExecutionDO::getScopeId, scopeId)
                    .eq(ExecutionDO::getType, type)
                    .eq(ExecutionDO::getStatus, "completed")
                    .orderByDesc(ExecutionDO::getCompletedAt)
                    .last("LIMIT 1")
            );
            return results.isEmpty() ? null : results.get(0).getCompletedAt();
        } catch (Exception e) {
            log.debug("Failed to find last completed time for scopeId={}, type={}: {}", scopeId, type, e.getMessage());
            return null;
        }
    }

    @Override
    public ExecutionDO findMostRecentCompleted(Long scopeId, String type) {
        return executionMapper.selectOne(
            new LambdaQueryWrapper<ExecutionDO>()
                .eq(ExecutionDO::getScopeId, scopeId)
                .eq(ExecutionDO::getType, type)
                .eq(ExecutionDO::getStatus, "completed")
                .orderByDesc(ExecutionDO::getCompletedAt)
                .last("LIMIT 1")
        );
    }

    @Override
    public ExecutionDO findActiveExecution(Long scopeId, String type) {
        return executionMapper.selectOne(
            new LambdaQueryWrapper<ExecutionDO>()
                .eq(ExecutionDO::getScopeId, scopeId)
                .eq(ExecutionDO::getType, type)
                .in(ExecutionDO::getStatus, ACTIVE_STATUSES)
                .orderByDesc(ExecutionDO::getStartedAt)
                .last("LIMIT 1")
        );
    }

    @Override
    public long countActiveExecutions(Long scopeId) {
        return executionMapper.selectCount(
            new LambdaQueryWrapper<ExecutionDO>()
                .eq(ExecutionDO::getScopeId, scopeId)
                .in(ExecutionDO::getStatus, ACTIVE_STATUSES)
        );
    }

    @Override
    public List<ExecutionDO> findStaleExecutions(List<String> statuses) {
        return executionMapper.selectList(
            new LambdaQueryWrapper<ExecutionDO>()
                .in(ExecutionDO::getStatus, statuses)
        );
    }

    @Override
    public List<ExecutionDO> findStaleExecutionsBefore(List<String> statuses, LocalDateTime threshold) {
        return executionMapper.selectList(
            new LambdaQueryWrapper<ExecutionDO>()
                .in(ExecutionDO::getStatus, statuses)
                .lt(ExecutionDO::getStartedAt, threshold)
        );
    }
}
