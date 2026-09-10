package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestStep;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionStatusEvent;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.service.harness.mq.IngestDispatcher;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Service
public class IngestBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestBatchScheduler.class);

    private static final Set<String> SETTLE_STATUSES =
        Set.of("awaiting_confirmation", "completed", "failed", "cancelled", "paused", "budget_exhausted");

    @Autowired
    private ExecutionMapper executionMapper;

    @Autowired
    private IngestBatchMapper batchMapper;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private IngestDispatcher dispatcher;

    @Autowired
    private IngestService ingestService;

    private final ConcurrentHashMap<Long, ReentrantLock> scopeLocks = new ConcurrentHashMap<>();

    public void kick(Long scopeId) {
        if (scopeId == null) return;
        ReentrantLock lock = scopeLocks.computeIfAbsent(scopeId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            return;
        }
        try {
            Set<Long> skipped = new HashSet<>();
            while (true) {
                if (hasRunningIngest(scopeId)) return;
                ExecutionDO candidate = selectCandidate(scopeId, skipped);
                if (candidate == null) return;
                String expected = candidate.getStatus();
                if (!claimForDispatch(candidate.getId(), expected)) {
                    skipped.add(candidate.getId());
                    continue;
                }
                try {
                    dispatch(candidate, expected);
                } catch (Exception e) {
                    log.error("Ingest dispatch failed: executionId={}", candidate.getId(), e);
                    ingestService.failExecution(candidate.getId(), "分发失败: " + e.getMessage());
                }
                return;
            }
        } catch (Exception e) {
            log.error("Ingest batch kick failed: scopeId={}", scopeId, e);
        } finally {
            lock.unlock();
        }
    }

    @EventListener
    public void onExecutionStatusEvent(ExecutionStatusEvent event) {
        try {
            if (!"ingest".equals(event.getExecutionType())) return;
            ExecutionDO execution = executionMapper.selectById(event.getExecutionId());
            if (execution == null) return;
            if (SETTLE_STATUSES.contains(event.getNewStatus())) {
                kick(execution.getScopeId());
            }
        } catch (Exception e) {
            log.error("Ingest batch settle handling failed: executionId={}", event.getExecutionId(), e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        recoverScan();
    }

    @Scheduled(fixedDelayString = "${llmwiki.ingest.batch.recover-interval-ms:60000}")
    public void recoverScan() {
        try {
            Set<Long> scopeIds = executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
                    .eq(ExecutionDO::getType, "ingest")
                    .in(ExecutionDO::getStatus, "pending", "confirmed"))
                .stream().map(ExecutionDO::getScopeId).collect(Collectors.toSet());
            batchMapper.selectList(new LambdaQueryWrapper<IngestBatchDO>()
                    .eq(IngestBatchDO::getStatus, "active"))
                .forEach(b -> scopeIds.add(b.getScopeId()));
            scopeIds.forEach(this::kick);
        } catch (Exception e) {
            log.error("Ingest batch recovery scan failed", e);
        }
    }

    private boolean hasRunningIngest(Long scopeId) {
        Long count = executionMapper.selectCount(new LambdaQueryWrapper<ExecutionDO>()
            .eq(ExecutionDO::getType, "ingest")
            .eq(ExecutionDO::getScopeId, scopeId)
            .eq(ExecutionDO::getStatus, "running"));
        return count != null && count > 0;
    }

    private ExecutionDO selectCandidate(Long scopeId, Set<Long> skipped) {
        List<ExecutionDO> ordered = executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
            .eq(ExecutionDO::getType, "ingest")
            .eq(ExecutionDO::getScopeId, scopeId)
            .in(ExecutionDO::getStatus, "confirmed", "pending")
            .orderByAsc(ExecutionDO::getCreatedAt)
            .orderByAsc(ExecutionDO::getId));
        ExecutionDO pendingFallback = null;
        for (ExecutionDO execution : ordered) {
            if (skipped.contains(execution.getId())) continue;
            if (!isEligible(execution)) continue;
            if ("confirmed".equals(execution.getStatus())) return execution;
            if (pendingFallback == null) pendingFallback = execution;
        }
        return pendingFallback;
    }

    private boolean isEligible(ExecutionDO execution) {
        if (execution.getBatchId() == null) return true;
        IngestBatchDO batch = batchMapper.selectById(execution.getBatchId());
        return batch != null && "active".equals(batch.getStatus());
    }

    private boolean claimForDispatch(Long executionId, String expectedStatus) {
        ExecutionDO patch = new ExecutionDO();
        patch.setStatus("running");
        patch.setNodeId(dispatcher.getNodeId());
        patch.setStartedAt(LocalDateTime.now());
        int rows = executionMapper.update(patch, new LambdaUpdateWrapper<ExecutionDO>()
            .eq(ExecutionDO::getId, executionId)
            .eq(ExecutionDO::getStatus, expectedStatus));
        return rows == 1;
    }

    private void dispatch(ExecutionDO execution, String expectedStatus) {
        ExecutionModel model = executionTracker.getExecution(execution.getId());
        String taskType = resolveTaskType(model, expectedStatus);
        String guidance = execution.getGuidance();
        dispatcher.dispatch(execution.getId(), execution.getScopeId(), execution.getSourceId(), guidance, taskType,
            () -> dispatcher.submitLocalTask(execution.getId(), () -> runLocal(execution, taskType, guidance)));
    }

    private String resolveTaskType(ExecutionModel model, String expectedStatus) {
        if ("confirmed".equals(expectedStatus)) return PipelineTaskMessage.TYPE_INGEST_EXECUTE;
        boolean hasSteps = model != null && model.getSteps() != null && !model.getSteps().isEmpty();
        return hasSteps ? PipelineTaskMessage.TYPE_INGEST_RESUME : PipelineTaskMessage.TYPE_INGEST_ANALYZE;
    }

    private void runLocal(ExecutionDO execution, String taskType, String guidance) {
        try {
            if (PipelineTaskMessage.TYPE_INGEST_ANALYZE.equals(taskType)) {
                ingestService.runIngestAnalysis(execution.getId(), execution.getScopeId(), execution.getSourceId(), guidance);
            } else if (PipelineTaskMessage.TYPE_INGEST_EXECUTE.equals(taskType)) {
                ingestService.runIngestExecution(execution.getId(), execution.getScopeId(), execution.getSourceId(), guidance);
            } else {
                ExecutionModel current = executionTracker.getExecution(execution.getId());
                boolean phase1Completed = current != null && IngestStep.isPhase1Completed(current.getSteps());
                if (phase1Completed) {
                    ingestService.resumeIngestExecution(execution.getId(), execution.getScopeId(), execution.getSourceId(), guidance);
                } else {
                    ingestService.resumeIngestAnalysis(execution.getId(), execution.getScopeId(), execution.getSourceId(), guidance);
                }
            }
        } catch (Exception e) {
            log.error("Local ingest dispatch failed: executionId={}", execution.getId(), e);
            ExecutionModel current = ingestService.getProgress(execution.getId());
            if (current == null || !"cancelled".equals(current.getStatus())) {
                ingestService.failExecution(execution.getId(), e.getMessage());
            }
        }
    }
}
