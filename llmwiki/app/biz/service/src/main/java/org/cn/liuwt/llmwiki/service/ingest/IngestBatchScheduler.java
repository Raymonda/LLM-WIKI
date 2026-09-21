package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.NotificationDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.NotificationMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestAutoConfirmedEvent;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestBatchSettledEvent;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestStep;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionStatusEvent;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.service.harness.mq.IngestDispatcher;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Service
public class IngestBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestBatchScheduler.class);

    private static final Set<String> SETTLE_STATUSES =
        Set.of("awaiting_confirmation", "awaiting_review", "completed", "failed", "cancelled", "paused", "budget_exhausted");

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

    @Autowired
    private ScopeMapper scopeMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Autowired
    private ExecutionEventLogService executionEventLogService;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Value("${llmwiki.ingest.batch.analyze-concurrency:2}")
    private int analyzeConcurrency = 2;

    @Value("${llmwiki.ingest.batch.write-concurrency:1}")
    private int writeConcurrency = 1;

    @Value("${llmwiki.rocketmq.enabled:false}")
    private boolean mqEnabled;

    private final ConcurrentHashMap<Long, ReentrantLock> scopeLocks = new ConcurrentHashMap<>();

    public void kick(Long scopeId) {
        if (scopeId == null) return;
        ReentrantLock lock = scopeLocks.computeIfAbsent(scopeId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            return;
        }
        try {
            int analyzing = 0;
            int writing = 0;
            for (ExecutionDO running : listRunningIngest(scopeId)) {
                if (isWritePhase(running)) {
                    writing++;
                } else {
                    analyzing++;
                }
            }
            Set<Long> skipped = new HashSet<>();
            while (writing < writeGate() || analyzing < analyzeGate()) {
                DispatchCandidate candidate = selectDispatchableCandidate(scopeId, skipped, analyzing, writing);
                if (candidate == null) return;
                ExecutionDO execution = candidate.execution();
                String expected = execution.getStatus();
                if (!claimForDispatch(execution, expected, candidate.writePhase())) {
                    skipped.add(execution.getId());
                    continue;
                }
                skipped.add(execution.getId());
                if (candidate.writePhase()) {
                    writing++;
                } else {
                    analyzing++;
                }
                try {
                    dispatch(execution, expected);
                } catch (Exception e) {
                    log.error("Ingest dispatch failed: executionId={}", execution.getId(), e);
                    ingestService.failExecution(execution.getId(), "分发失败: " + e.getMessage());
                }
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

    @EventListener
    public void onAutoConfirmed(IngestAutoConfirmedEvent event) {
        try {
            kick(event.getScopeId());
        } catch (Exception e) {
            log.warn("Auto-confirmed kick failed: scopeId={}, executionId={}", event.getScopeId(), event.getExecutionId(), e);
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

    private List<ExecutionDO> listRunningIngest(Long scopeId) {
        return executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
            .eq(ExecutionDO::getType, "ingest")
            .eq(ExecutionDO::getScopeId, scopeId)
            .eq(ExecutionDO::getStatus, "running"));
    }

    private int analyzeGate() {
        return Math.max(1, analyzeConcurrency);
    }

    private int writeGate() {
        return Math.max(1, writeConcurrency);
    }

    private DispatchCandidate selectDispatchableCandidate(Long scopeId, Set<Long> skipped, int analyzing, int writing) {
        List<ExecutionDO> ordered = executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
            .eq(ExecutionDO::getType, "ingest")
            .eq(ExecutionDO::getScopeId, scopeId)
            .in(ExecutionDO::getStatus, "confirmed", "pending")
            .orderByAsc(ExecutionDO::getCreatedAt)
            .orderByAsc(ExecutionDO::getId));
        if (writing < writeGate()) {
            for (ExecutionDO execution : ordered) {
                if (skipped.contains(execution.getId())) continue;
                if (!isEligible(execution)) continue;
                if ("confirmed".equals(execution.getStatus())) return new DispatchCandidate(execution, true);
            }
        }
        for (ExecutionDO execution : ordered) {
            if (skipped.contains(execution.getId())) continue;
            if (!isEligible(execution)) continue;
            if ("confirmed".equals(execution.getStatus())) continue;
            boolean writePhase = isWritePhase(execution);
            if (writePhase ? writing < writeGate() : analyzing < analyzeGate()) {
                return new DispatchCandidate(execution, writePhase);
            }
        }
        return null;
    }

    private boolean isWritePhase(ExecutionDO execution) {
        if ("confirmed".equals(execution.getStatus())) {
            return true;
        }
        ExecutionModel model = executionTracker.getExecution(execution.getId());
        return model != null && IngestStep.isPhase1Completed(model.getSteps());
    }

    private boolean isEligible(ExecutionDO execution) {
        if (execution.getBatchId() == null) return true;
        IngestBatchDO batch = batchMapper.selectById(execution.getBatchId());
        return batch != null && "active".equals(batch.getStatus());
    }

    private boolean claimForDispatch(ExecutionDO execution, String expectedStatus, boolean writePhase) {
        if (writePhase && mqEnabled) {
            Boolean claimed = transactionTemplate.execute(tx -> {
                if (scopeMapper.lockScopeRow(execution.getScopeId()) == null) {
                    log.warn("Write-phase claim rejected: scope row missing, scopeId={}, executionId={}",
                        execution.getScopeId(), execution.getId());
                    return false;
                }
                for (ExecutionDO running : executionMapper.selectRunningIngestForUpdate(execution.getScopeId())) {
                    if (isWritePhase(running)) {
                        log.info("Write-phase claim deferred to running writer: scopeId={}, executionId={}, runningExecutionId={}",
                            execution.getScopeId(), execution.getId(), running.getId());
                        return false;
                    }
                }
                return casSetRunning(execution.getId(), expectedStatus);
            });
            return Boolean.TRUE.equals(claimed);
        }
        return casSetRunning(execution.getId(), expectedStatus);
    }

    private boolean casSetRunning(Long executionId, String expectedStatus) {
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

        long awaiting = items.stream().filter(i ->
            "awaiting_confirmation".equals(i.getStatus()) || "awaiting_review".equals(i.getStatus())).count();
        long failed = items.stream().filter(i ->
            "failed".equals(i.getStatus()) || "budget_exhausted".equals(i.getStatus())).count();
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
            boolean anyAnalyzedOutcome = awaiting > 0 || failed > 0;
            if (anyAnalyzedOutcome) {
                String detail;
                if (batch.getTotalCount() != null && batch.getTotalCount() == 1) {
                    detail = awaiting >= 1 ? "分析完成，待审阅" : "分析失败";
                } else {
                    detail = "本批 " + awaiting + " 份待审阅、" + failed + " 份失败"
                        + (cancelled > 0 ? "、" + cancelled + " 份取消" : "");
                }
                notifyOnce(batch, "ingest_batch_analyzed", "分析完成", detail);
            }
        }
        boolean noActive = items.stream().noneMatch(i ->
            "pending".equals(i.getStatus()) || "running".equals(i.getStatus()) || "confirmed".equals(i.getStatus()));
        if (noActive && awaiting > 0 && !"completed".equals(batch.getStatus())) {
            notifyOnce(batch, "ingest_batch_review_pending", "批次待审阅",
                awaiting + " 篇已完成分析待你审阅，" + completed + " 篇已自动完成");
        }
        boolean allTerminal = items.stream().allMatch(i -> TERMINAL_EXECUTION_STATUSES.contains(i.getStatus()));
        if (allTerminal && !"completed".equals(batch.getStatus()) && !"cancelled".equals(batch.getStatus())) {
            batch.setStatus("completed");
            batch.setCompletedAt(LocalDateTime.now());
            batchMapper.updateById(batch);
            publishBatchSettled(batch, items);
            long autoCompleted;
            try {
                autoCompleted = countAutoCompleted(items);
            } catch (Exception e) {
                log.warn("Auto-completed stats failed, fallback to 0: batchId={}", batch.getId(), e);
                autoCompleted = 0;
            }
            notifyOnce(batch, "ingest_batch_completed", "处理完成",
                "本批 " + batch.getTotalCount() + " 份已结束：" + autoCompleted + " 自动完成，"
                    + (completed - autoCompleted) + " 确认完成，" + failed + " 失败，" + cancelled + " 取消");
            maybeSuspendAutoMode(batch, items);
        }
    }

    private void publishBatchSettled(IngestBatchDO batch, List<ExecutionDO> items) {
        try {
            List<Long> sourceIds = items.stream()
                .filter(i -> "completed".equals(i.getStatus()))
                .map(ExecutionDO::getSourceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
            if (sourceIds.isEmpty()) {
                return;
            }
            List<Long> pageIds = wikiPageSourceMapper.selectList(new LambdaQueryWrapper<WikiPageSourceDO>()
                    .eq(WikiPageSourceDO::getScopeId, batch.getScopeId())
                    .in(WikiPageSourceDO::getSourceId, sourceIds))
                .stream()
                .map(WikiPageSourceDO::getPageId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
            if (pageIds.isEmpty()) {
                return;
            }
            applicationEventPublisher.publishEvent(
                new IngestBatchSettledEvent(this, batch.getId(), batch.getScopeId(), pageIds));
        } catch (Exception e) {
            log.warn("Failed to publish batch settled event: batchId={}", batch.getId(), e);
        }
    }

    private void maybeSuspendAutoMode(IngestBatchDO batch, List<ExecutionDO> items) {
        try {
            if (!"auto".equals(batch.getMode())) {
                return;
            }
            int total = batch.getTotalCount() != null ? batch.getTotalCount() : items.size();
            long failed = items.stream().filter(i ->
                "failed".equals(i.getStatus()) || "budget_exhausted".equals(i.getStatus())).count();
            if (total < 5 || failed < 3 || failed * 10 < total * 3L) {
                return;
            }
            ScopeDO scope = scopeMapper.selectById(batch.getScopeId());
            if (scope == null || Boolean.TRUE.equals(scope.getAutoSuspended())) {
                return;
            }
            long pct = Math.round(failed * 100.0 / total);
            String reason = "批次 #" + batch.getId() + " 失败率 " + pct + "%（" + failed + "/" + total + "），自动确认已熔断";
            scope.setAutoSuspended(true);
            scope.setAutoSuspendedReason(reason);
            scopeMapper.updateById(scope);
            notificationService.createPersonalNotification(scope.getOwnerId(), "auto_mode_suspended",
                "自动确认已熔断", reason + "。如需恢复，请在项目设置中重新选择摄入模式。", batch.getScopeId(), null, null);
        } catch (Exception e) {
            log.warn("maybeSuspendAutoMode failed for batch {}: {}", batch.getId(), e.getMessage());
        }
    }

    private long countAutoCompleted(List<ExecutionDO> items) {
        List<Long> completedIds = items.stream()
            .filter(i -> "completed".equals(i.getStatus()))
            .map(ExecutionDO::getId)
            .toList();
        if (completedIds.isEmpty()) {
            return 0;
        }
        Map<Long, Map<String, Object>> payloads = executionEventLogService.loadLatestTurnEndPayloads(completedIds);
        return completedIds.stream().filter(id -> isAutoApproved(payloads.get(id))).count();
    }

    private static boolean isAutoApproved(Map<String, Object> turnEndPayload) {
        if (turnEndPayload == null) {
            return false;
        }
        Object autoDecision = turnEndPayload.get("autoDecision");
        return autoDecision instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("autoApprove"));
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

    private record DispatchCandidate(ExecutionDO execution, boolean writePhase) {}
}
