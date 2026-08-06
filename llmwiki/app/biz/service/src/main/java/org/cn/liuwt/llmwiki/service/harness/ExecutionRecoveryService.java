package org.cn.liuwt.llmwiki.service.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.RateLimitService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionHistoryService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

@Service
public class ExecutionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryService.class);
    private static final long STALE_THRESHOLD_MINUTES = 30;

    @Autowired
    private ExecutionHistoryService executionHistoryService;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private ExecutionNodeRegistry registry;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        log.info("ExecutionRecoveryService: scanning for stale executions on startup, nodeId={}...", registry.getNodeId());
        List<String> staleStatuses = Arrays.asList("running", "awaiting_confirmation");
        List<ExecutionDO> stale = executionHistoryService.findStaleExecutions(staleStatuses);
        int recovered = 0;
        for (ExecutionDO exec : stale) {
            if (exec.getNodeId() != null && !exec.getNodeId().equals(registry.getNodeId())) {
                log.info("Skipping execution id={} owned by node {} (this node: {})",
                        exec.getId(), exec.getNodeId(), registry.getNodeId());
                continue;
            }
            log.warn("Recovering stale execution id={} status={} scopeId={} nodeId={}",
                    exec.getId(), exec.getStatus(), exec.getScopeId(), exec.getNodeId());
            executionTracker.failExecution(exec.getId(), "系统重启，任务被中断");
            recovered++;
        }
        if (recovered > 0) {
            log.info("ExecutionRecoveryService: recovered {} stale executions", recovered);
        }
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void scanStaleExecutions() {
        LocalDateTime threshold = LocalDateTime.now().minus(STALE_THRESHOLD_MINUTES, ChronoUnit.MINUTES);
        List<String> staleStatuses = Arrays.asList("running", "awaiting_confirmation");
        List<ExecutionDO> stale = executionHistoryService.findStaleExecutionsBefore(staleStatuses, threshold);
        for (ExecutionDO exec : stale) {
            if (exec.getNodeId() != null && !exec.getNodeId().equals(registry.getNodeId())) {
                continue;
            }
            log.warn("Scan: marking stale execution id={} as failed (startedAt={} exceeds {}min threshold, nodeId={})",
                exec.getId(), exec.getStartedAt(), STALE_THRESHOLD_MINUTES, exec.getNodeId());
            executionTracker.failExecution(exec.getId(), "任务超时未完成，系统自动终止（超过" + STALE_THRESHOLD_MINUTES + "分钟）");
        }
    }
}
