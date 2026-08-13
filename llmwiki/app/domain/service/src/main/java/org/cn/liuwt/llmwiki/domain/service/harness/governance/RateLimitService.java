package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionHistoryService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    @Autowired
    private ScopeMapper scopeMapper;

    @Autowired
    private ExecutionHistoryService executionHistoryService;

    private final ConcurrentHashMap<Long, Semaphore> concurrentSemaphores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastCallTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> activePipelineIds = new ConcurrentHashMap<>();

    private static final long MIN_CALL_INTERVAL_MS = 2000;
    private static final Duration NO_STEP_GRACE = Duration.ofMinutes(5);
    private static final Duration NO_HEARTBEAT_GRACE = Duration.ofMinutes(30);

    @Autowired
    private ExecutionTracker executionTracker;

    public boolean tryAcquireConcurrent(Long scopeId) {
        ScopeDO scope = scopeMapper.selectById(scopeId);
        if (scope == null) {
            return true;
        }
        int maxConcurrent = scope.getMaxConcurrent() != null ? scope.getMaxConcurrent() : getDefaultMaxConcurrent(scope.getType());

        long dbActiveCount = executionHistoryService.countActiveExecutions(scopeId);

        if (dbActiveCount >= maxConcurrent) {
            reclaimZombieExecutions(scopeId);
            dbActiveCount = executionHistoryService.countActiveExecutions(scopeId);
        }

        if (dbActiveCount >= maxConcurrent) {
            log.warn("Scope {} DB concurrent limit reached (active={}, max={}), request rejected",
                scopeId, dbActiveCount, maxConcurrent);
            return false;
        }

        Semaphore semaphore = concurrentSemaphores.computeIfAbsent(scopeId, k -> new Semaphore(maxConcurrent));
        boolean acquired = semaphore.tryAcquire();
        if (!acquired) {
            log.warn("Scope {} local semaphore limit reached (max={}), request rejected", scopeId, maxConcurrent);
            return false;
        }
        return true;
    }

    public void releaseConcurrent(Long scopeId) {
        Semaphore semaphore = concurrentSemaphores.get(scopeId);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    public void releaseAllConcurrent(Long scopeId, int count) {
        Semaphore semaphore = concurrentSemaphores.get(scopeId);
        if (semaphore != null) {
            for (int i = 0; i < count; i++) {
                semaphore.release();
            }
        }
    }

    public boolean checkCallRate(Long scopeId) {
        Long lastCall = lastCallTimestamps.get(scopeId);
        long now = System.currentTimeMillis();
        if (lastCall != null && (now - lastCall) < MIN_CALL_INTERVAL_MS) {
            log.warn("Scope {} AI call rate limited, min interval {}ms not reached", scopeId, MIN_CALL_INTERVAL_MS);
            return false;
        }
        lastCallTimestamps.put(scopeId, now);
        return true;
    }

    public boolean checkCallRateWithinPipeline(Long scopeId, String pipelineKey) {
        String activeKey = activePipelineIds.get(scopeId);
        if (activeKey != null && activeKey.equals(pipelineKey)) {
            return true;
        }
        return checkCallRate(scopeId);
    }

    public void registerPipeline(Long scopeId, String pipelineKey) {
        activePipelineIds.put(scopeId, pipelineKey);
    }

    public void unregisterPipeline(Long scopeId) {
        activePipelineIds.remove(scopeId);
    }

    public boolean checkFileSize(Long scopeId, long fileSizeBytes) {
        ScopeDO scope = scopeMapper.selectById(scopeId);
        if (scope == null) {
            return true;
        }
        int maxFileSizeMB = scope.getMaxFileSize() != null ? scope.getMaxFileSize() : getDefaultMaxFileSize(scope.getType());
        long maxFileSizeBytes = maxFileSizeMB * 1024L * 1024L;
        if (fileSizeBytes > maxFileSizeBytes) {
            log.warn("Scope {} file size {}MB exceeds limit {}MB", scopeId,
                fileSizeBytes / 1024 / 1024, maxFileSizeMB);
            return false;
        }
        return true;
    }

    private int getDefaultMaxConcurrent(String scopeType) {
        if ("personal".equals(scopeType)) return 5;
        if ("team".equals(scopeType)) return 3;
        if ("department".equals(scopeType)) return 5;
        return 2;
    }

    private void reclaimZombieExecutions(Long scopeId) {
        List<ExecutionDO> zombies = executionHistoryService.findZombieExecutions(scopeId, NO_STEP_GRACE, NO_HEARTBEAT_GRACE);
        if (zombies.isEmpty()) {
            return;
        }
        for (ExecutionDO zombie : zombies) {
            log.warn("Scope {} concurrency blocked by zombie execution id={} (status={}), reclaiming",
                scopeId, zombie.getId(), zombie.getStatus());
            executionTracker.failExecution(zombie.getId(), "僵尸执行回收：长时间无步骤进展");
        }
        releaseConcurrent(scopeId);
    }

    private int getDefaultMaxFileSize(String scopeType) {
        if ("personal".equals(scopeType)) return 10;
        if ("team".equals(scopeType)) return 50;
        if ("department".equals(scopeType)) return 100;
        return 20;
    }
}