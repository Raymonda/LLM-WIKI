package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionStepDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionStepMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ExecutionTrackerImpl implements ExecutionTracker {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTrackerImpl.class);

    @Autowired
    private ExecutionMapper executionMapper;

    @Autowired
    private ExecutionStepMapper executionStepMapper;

    @Autowired
    private DistributedEventPublisher eventPublisher;

    /**
     * 启动清扫：上次进程退出（重启/崩溃）会留下 running/pending 状态的孤儿执行记录，
     * 前端会一直转圈。启动时统一标记为 failed 并中断其 running 步骤。
     */
    @jakarta.annotation.PostConstruct
    public void sweepOrphanedExecutions() {
        List<ExecutionDO> orphaned = executionMapper.selectList(
            new LambdaQueryWrapper<ExecutionDO>()
                .in(ExecutionDO::getStatus, "running", "pending")
        );
        if (orphaned.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ExecutionDO executionDO : orphaned) {
            executionDO.setStatus("failed");
            executionDO.setErrorMessage("服务重启，执行中断");
            executionDO.setCompletedAt(now);
            executionMapper.updateById(executionDO);
        }
        executionStepMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ExecutionStepDO>()
                .in(ExecutionStepDO::getStatus, "running", "pending")
                .set(ExecutionStepDO::getStatus, "failed")
        );
        log.warn("Startup sweep: marked {} orphaned execution(s) as failed", orphaned.size());
    }

    @Override
    public ExecutionModel createExecution(String type, Long scopeId, Long sourceId, Long schemaConfigId) {
        ExecutionDO executionDO = new ExecutionDO();
        executionDO.setType(type);
        executionDO.setStatus("pending");
        executionDO.setScopeId(scopeId);
        executionDO.setSourceId(sourceId);
        executionDO.setSchemaConfigId(schemaConfigId);
        executionDO.setStartedAt(LocalDateTime.now());
        executionDO.setTotalTokens(0);
        executionDO.setTotalCost(java.math.BigDecimal.ZERO);
        executionMapper.insert(executionDO);
        return toExecutionModel(executionDO);
    }

    @Override
    public ExecutionStepModel createStep(Long executionId, String stepName, Integer stepOrder, String approvalLevel) {
        ExecutionStepDO stepDO = new ExecutionStepDO();
        stepDO.setExecutionId(executionId);
        stepDO.setStepName(stepName);
        stepDO.setStepOrder(stepOrder);
        stepDO.setStatus("pending");
        stepDO.setApprovalLevel(approvalLevel);
        stepDO.setTokensUsed(0);
        stepDO.setDurationMs(0);
        executionStepMapper.insert(stepDO);
        return toStepModel(stepDO);
    }

    @Override
    public void updateExecutionStatus(Long executionId, String status) {
        updateExecutionStatus(executionId, status, null);
    }

    @Override
    public void updateExecutionStatus(Long executionId, String status, String complianceViolations) {
        ExecutionDO executionDO = executionMapper.selectById(executionId);
        if (executionDO != null) {
            executionDO.setStatus(status);
            if ("running".equals(status)) {
                executionDO.setStartedAt(LocalDateTime.now());
            }
            executionMapper.updateById(executionDO);
            eventPublisher.publishExecutionStatus(executionId, executionDO.getType(), status, complianceViolations);
        }
    }

    @Override
    public void failExecution(Long executionId, String errorMessage) {
        ExecutionDO executionDO = executionMapper.selectById(executionId);
        if (executionDO != null) {
            String current = executionDO.getStatus();
            if ("completed".equals(current) || "cancelled".equals(current) || "failed".equals(current) || "paused".equals(current)) {
                log.info("Execution {} already in state {}, skipping failExecution", executionId, current);
                return;
            }
            executionDO.setStatus("failed");
            executionDO.setErrorMessage(errorMessage);
            executionDO.setCompletedAt(LocalDateTime.now());
            executionMapper.updateById(executionDO);
            eventPublisher.publishExecutionStatus(executionId, executionDO.getType(), "failed", null);
        }
    }

    @Override
    public void cancelExecution(Long executionId, String reason) {
        ExecutionDO executionDO = executionMapper.selectById(executionId);
        if (executionDO != null) {
            executionDO.setStatus("cancelled");
            executionDO.setErrorMessage(reason);
            executionDO.setCompletedAt(LocalDateTime.now());
            executionMapper.updateById(executionDO);
            eventPublisher.publishExecutionStatus(executionId, executionDO.getType(), "cancelled", null);
        }
    }

    @Override
    public void pauseExecution(Long executionId, String reason) {
        ExecutionDO executionDO = executionMapper.selectById(executionId);
        if (executionDO != null) {
            executionDO.setStatus("paused");
            executionDO.setErrorMessage(reason);
            executionMapper.updateById(executionDO);
            eventPublisher.publishExecutionStatus(executionId, executionDO.getType(), "paused", null);
        }
    }

    @Override
    public void updateStepStatus(Long stepId, String status) {
        ExecutionStepDO stepDO = executionStepMapper.selectById(stepId);
        if (stepDO != null) {
            stepDO.setStatus(status);
            if ("running".equals(status)) {
                stepDO.setStartedAt(LocalDateTime.now());
            }
            executionStepMapper.updateById(stepDO);
            String executionType = getExecutionType(stepDO.getExecutionId());
            eventPublisher.publishStepStatus(stepDO.getExecutionId(), executionType, stepId, stepDO.getStepName(), status, null);
        }
    }

    @Override
    public void updateStepOutputData(Long stepId, String outputData) {
        ExecutionStepDO stepDO = executionStepMapper.selectById(stepId);
        if (stepDO != null) {
            stepDO.setOutputData(outputData);
            executionStepMapper.updateById(stepDO);
        }
    }

    @Override
    public void completeStep(Long stepId, String outputData, Integer tokensUsed, Integer durationMs) {
        ExecutionStepDO stepDO = executionStepMapper.selectById(stepId);
        if (stepDO != null) {
            stepDO.setStatus("completed");
            stepDO.setOutputData(outputData);
            stepDO.setTokensUsed(tokensUsed);
            stepDO.setDurationMs(durationMs);
            stepDO.setCompletedAt(LocalDateTime.now());
            executionStepMapper.updateById(stepDO);
            String executionType = getExecutionType(stepDO.getExecutionId());
            eventPublisher.publishStepStatus(stepDO.getExecutionId(), executionType, stepId, stepDO.getStepName(), "completed", outputData);
        }
    }

    @Override
    public void publishStepProgress(Long executionId, Long stepId, String stepName, Integer current, Integer total, Long avgMsPerUnit) {
        publishStepProgress(executionId, stepId, stepName, current, total, avgMsPerUnit, null, null);
    }

    @Override
    public void publishStepProgress(Long executionId, Long stepId, String stepName, Integer current, Integer total, Long avgMsPerUnit, Integer chunkIndex, String chunkPreview) {
        String executionType = getExecutionType(executionId);
        eventPublisher.publishStepProgress(executionId, executionType, stepId, stepName, current, total, avgMsPerUnit, chunkIndex, chunkPreview);
    }

    private String getExecutionType(Long executionId) {
        ExecutionDO executionDO = executionMapper.selectById(executionId);
        return executionDO != null ? executionDO.getType() : null;
    }

    @Override
    public void completeExecution(Long executionId, Integer totalTokens) {
        ExecutionDO executionDO = executionMapper.selectById(executionId);
        if (executionDO != null) {
            String current = executionDO.getStatus();
            if ("completed".equals(current) || "failed".equals(current) || "cancelled".equals(current)) {
                log.info("Execution {} already in terminal state {}, skipping completeExecution", executionId, current);
                return;
            }
            executionDO.setStatus("completed");
            executionDO.setTotalTokens(totalTokens);
            executionDO.setCompletedAt(LocalDateTime.now());
            executionMapper.updateById(executionDO);
            eventPublisher.publishExecutionStatus(executionId, executionDO.getType(), "completed", null);
        }
    }

    @Override
    public ExecutionModel getExecution(Long executionId) {
        ExecutionDO executionDO = executionMapper.selectById(executionId);
        if (executionDO == null) {
            return null;
        }
        ExecutionModel model = toExecutionModel(executionDO);
        model.setSteps(listSteps(executionId));
        return model;
    }

    @Override
    public List<ExecutionModel> listExecutions(Long scopeId, String type) {
        LambdaQueryWrapper<ExecutionDO> wrapper = new LambdaQueryWrapper<ExecutionDO>()
            .eq(ExecutionDO::getScopeId, scopeId);
        if (type != null) {
            wrapper.eq(ExecutionDO::getType, type);
        }
        wrapper.orderByDesc(ExecutionDO::getCreatedAt);
        wrapper.last("LIMIT 100");
        List<ExecutionDO> executionDOs = executionMapper.selectList(wrapper);
        return executionDOs.stream()
            .map(this::toExecutionModel)
            .collect(Collectors.toList());
    }

    @Override
    public IPage<ExecutionModel> listExecutionsPaged(Long scopeId, String type, int page, int size) {
        LambdaQueryWrapper<ExecutionDO> wrapper = new LambdaQueryWrapper<ExecutionDO>()
            .eq(ExecutionDO::getScopeId, scopeId);
        if (type != null && !type.isBlank()) {
            wrapper.eq(ExecutionDO::getType, type);
        }
        wrapper.orderByDesc(ExecutionDO::getCreatedAt);
        wrapper.select(ExecutionDO::getId, ExecutionDO::getType, ExecutionDO::getStatus,
            ExecutionDO::getScopeId, ExecutionDO::getSourceId, ExecutionDO::getSchemaConfigId,
            ExecutionDO::getStartedAt, ExecutionDO::getCompletedAt, ExecutionDO::getTotalTokens,
            ExecutionDO::getTotalCost, ExecutionDO::getCreatedAt);
        Page<ExecutionDO> pageParam = new Page<>(page, size);
        IPage<ExecutionDO> doPage = executionMapper.selectPage(pageParam, wrapper);
        IPage<ExecutionModel> modelPage = new Page<>(doPage.getCurrent(), doPage.getSize(), doPage.getTotal());
        modelPage.setRecords(doPage.getRecords().stream()
            .map(this::toExecutionModel)
            .collect(Collectors.toList()));
        return modelPage;
    }

    @Override
    public ExecutionStepModel getStep(Long stepId) {
        ExecutionStepDO stepDO = executionStepMapper.selectById(stepId);
        return stepDO != null ? toStepModel(stepDO) : null;
    }

    @Override
    public List<ExecutionStepModel> listSteps(Long executionId) {
        LambdaQueryWrapper<ExecutionStepDO> wrapper = new LambdaQueryWrapper<ExecutionStepDO>()
            .eq(ExecutionStepDO::getExecutionId, executionId)
            .orderByAsc(ExecutionStepDO::getStepOrder);
        List<ExecutionStepDO> stepDOs = executionStepMapper.selectList(wrapper);
        return stepDOs.stream()
            .map(this::toStepModel)
            .collect(Collectors.toList());
    }

    private ExecutionModel toExecutionModel(ExecutionDO executionDO) {
        ExecutionModel model = new ExecutionModel();
        model.setId(executionDO.getId());
        model.setType(executionDO.getType());
        model.setStatus(executionDO.getStatus());
        model.setScopeId(executionDO.getScopeId());
        model.setSourceId(executionDO.getSourceId());
        model.setSchemaConfigId(executionDO.getSchemaConfigId());
        model.setStartedAt(executionDO.getStartedAt());
        model.setCompletedAt(executionDO.getCompletedAt());
        model.setCreatedAt(executionDO.getCreatedAt());
        model.setTotalTokens(executionDO.getTotalTokens());
        model.setErrorMessage(executionDO.getErrorMessage());
        model.setNodeId(executionDO.getNodeId());
        return model;
    }

    private ExecutionStepModel toStepModel(ExecutionStepDO stepDO) {
        ExecutionStepModel model = new ExecutionStepModel();
        model.setId(stepDO.getId());
        model.setExecutionId(stepDO.getExecutionId());
        model.setStepName(stepDO.getStepName());
        model.setStepOrder(stepDO.getStepOrder());
        model.setStatus(stepDO.getStatus());
        model.setInputData(stepDO.getInputData());
        model.setOutputData(stepDO.getOutputData());
        model.setTokensUsed(stepDO.getTokensUsed());
        model.setDurationMs(stepDO.getDurationMs());
        model.setApprovalLevel(stepDO.getApprovalLevel());
        model.setApprovedBy(stepDO.getApprovedBy());
        model.setStartedAt(stepDO.getStartedAt());
        model.setCompletedAt(stepDO.getCompletedAt());
        return model;
    }

    @Override
    public void resetStepForRetry(Long stepId) {
        ExecutionStepDO stepDO = executionStepMapper.selectById(stepId);
        if (stepDO != null) {
            stepDO.setStatus("pending");
            stepDO.setOutputData(null);
            stepDO.setStartedAt(null);
            stepDO.setCompletedAt(null);
            executionStepMapper.updateById(stepDO);
            ExecutionDO executionDO = executionMapper.selectById(stepDO.getExecutionId());
            String executionType = executionDO != null ? executionDO.getType() : null;
            eventPublisher.publishStepStatus(stepDO.getExecutionId(), executionType, stepId, stepDO.getStepName(), "pending", null);
        }
    }

    @Override
    public void resetExecutionForRetry(Long executionId) {
        ExecutionDO executionDO = executionMapper.selectById(executionId);
        if (executionDO != null) {
            executionDO.setStatus("running");
            executionDO.setErrorMessage(null);
            executionDO.setCompletedAt(null);
            executionMapper.updateById(executionDO);
            eventPublisher.publishExecutionStatus(executionId, executionDO.getType(), "running", null);
        }
    }

    @Override
    public void deleteExecution(Long executionId) {
        executionStepMapper.delete(
            new LambdaQueryWrapper<ExecutionStepDO>()
                .eq(ExecutionStepDO::getExecutionId, executionId)
        );
        executionMapper.deleteById(executionId);
    }
}