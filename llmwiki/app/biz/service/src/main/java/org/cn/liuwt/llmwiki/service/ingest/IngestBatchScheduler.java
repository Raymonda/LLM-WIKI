package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.NotificationDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.NotificationMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestStep;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionStatusEvent;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
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

    private static final Set<String> TERMINAL_EXECUTION_STATUSES =
        Set.of("completed", "failed", "cancelled", "budget_exhausted");

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

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private NotificationService notificationService;

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
                if (execution.getBatchId() != null) {
                    try {
                        handleBatchSettlement(execution);
                    } catch (Exception e) {
                        log.warn("Batch settlement handling failed: batchId={}", execution.getBatchId(), e);
                    }
                }
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
            List<IngestBatchDO> activeBatches = batchMapper.selectList(new LambdaQueryWrapper<IngestBatchDO>()
                .eq(IngestBatchDO::getStatus, "active"));
            Set<Long> scopeIds = executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
                    .eq(ExecutionDO::getType, "ingest")
                    .in(ExecutionDO::getStatus, "pending", "confirmed"))
                .stream().map(ExecutionDO::getScopeId).collect(Collectors.toSet());
            activeBatches.forEach(b -> scopeIds.add(b.getScopeId()));
            activeBatches.forEach(batch -> {
                try {
                    handleBatchSettlement(batch);
                } catch (Exception e) {
                    log.warn("Batch settle recovery failed: batchId={}", batch.getId(), e);
                }
            });
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

    public void handleBatchSettlement(ExecutionDO execution) {
        if (execution.getBatchId() == null) return;
        IngestBatchDO batch = batchMapper.selectById(execution.getBatchId());
        if (batch == null) return;
        handleBatchSettlement(batch);
    }

    private void handleBatchSettlement(IngestBatchDO batch) {
        List<ExecutionDO> items = listBatchItems(batch.getId(), batch.getScopeId());
        if (items.isEmpty()) return;

        long awaiting = items.stream().filter(i -> "awaiting_confirmation".equals(i.getStatus())).count();
        long failed = items.stream().filter(i -> "failed".equals(i.getStatus())).count();
        long cancelled = items.stream().filter(i -> "cancelled".equals(i.getStatus())).count();
        long completed = items.stream().filter(i -> "completed".equals(i.getStatus())).count();

        if ("cancelled".equals(batch.getStatus())) return;

        if (awaiting >= 1 && batch.getTotalCount() != null && batch.getTotalCount() > 1) {
            notifyOnce(batch, "ingest_batch_awaiting", "可以开始审阅了",
                "本批已有 " + awaiting + " 份分析完成，可前往审阅收件箱集中确认");
        }
        boolean analysisDone = items.stream().noneMatch(i ->
            "pending".equals(i.getStatus()) || "running".equals(i.getStatus()) || "paused".equals(i.getStatus()));
        if (analysisDone) {
            String detail = batch.getTotalCount() != null && batch.getTotalCount() == 1
                ? (awaiting >= 1 ? "分析完成，待审阅" : "分析失败")
                : "本批 " + awaiting + " 份待审阅、" + failed + " 份失败";
            notifyOnce(batch, "ingest_batch_analyzed", "分析完成", detail);
        }
        boolean allTerminal = items.stream().allMatch(i -> TERMINAL_EXECUTION_STATUSES.contains(i.getStatus()));
        if (allTerminal && !"completed".equals(batch.getStatus()) && !"cancelled".equals(batch.getStatus())) {
            batch.setStatus("completed");
            batch.setCompletedAt(LocalDateTime.now());
            batchMapper.updateById(batch);
            notifyOnce(batch, "ingest_batch_completed", "处理完成",
                "本批 " + batch.getTotalCount() + " 份已全部结束（" + completed + " 成功 / " + failed + " 失败 / " + cancelled + " 取消）");
        }
    }

    private List<ExecutionDO> listBatchItems(Long batchId, Long scopeId) {
        return executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
            .eq(ExecutionDO::getBatchId, batchId)
            .eq(ExecutionDO::getScopeId, scopeId));
    }

    private void notifyOnce(IngestBatchDO batch, String type, String title, String content) {
        Long existing = notificationMapper.selectCount(new LambdaQueryWrapper<NotificationDO>()
            .eq(NotificationDO::getBatchId, batch.getId())
            .eq(NotificationDO::getScopeId, batch.getScopeId())
            .eq(NotificationDO::getType, type));
        if (existing != null && existing > 0) return;
        notificationService.createNotification(batch.getUserId(), type, title, content, batch.getScopeId(), null, null, batch.getId());
    }
}
