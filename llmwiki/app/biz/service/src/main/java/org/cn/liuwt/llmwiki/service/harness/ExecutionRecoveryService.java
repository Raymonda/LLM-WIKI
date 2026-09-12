package org.cn.liuwt.llmwiki.service.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.RateLimitService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionHistoryService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class ExecutionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryService.class);
    private static final Duration NO_STEP_GRACE = Duration.ofMinutes(5);
    private static final Duration NO_HEARTBEAT_GRACE = Duration.ofMinutes(30);

    @Autowired
    private ExecutionHistoryService executionHistoryService;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private RateLimitService rateLimitService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        log.info("ExecutionRecoveryService: scanning for orphaned executions on startup");
        int recovered = recoverZombies("系统重启，任务被中断");
        if (recovered > 0) {
            log.info("ExecutionRecoveryService: recovered {} orphaned executions", recovered);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void scanStaleExecutions() {
        int recovered = recoverZombies("任务长时间无步骤进展，系统自动终止");
        if (recovered > 0) {
            log.info("ExecutionRecoveryService: reclaimed {} zombie executions", recovered);
        }
    }

    private int recoverZombies(String reason) {
        List<ExecutionDO> zombies = executionHistoryService.findZombieExecutions(null, NO_STEP_GRACE, NO_HEARTBEAT_GRACE);
        int recovered = 0;
        for (ExecutionDO exec : zombies) {
            log.warn("Reclaiming zombie execution id={} status={} scopeId={} (no step for {}min or no heartbeat for {}min)",
                exec.getId(), exec.getStatus(), exec.getScopeId(),
                NO_STEP_GRACE.toMinutes(), NO_HEARTBEAT_GRACE.toMinutes());
            executionTracker.failExecution(exec.getId(), reason);
            // 僵尸可能持有过本地信号量许可（pipeline 中途死亡时 finally 不执行）；
            // releaseConcurrent 内部按容量上限收敛，重复 release 不会导致 permit 超发，仅回收泄漏的许可
            rateLimitService.releaseConcurrent(exec.getScopeId());
            recovered++;
        }
        return recovered;
    }
}
