package org.cn.liuwt.llmwiki.service.harness.mq;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class ExecutionNodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExecutionNodeRegistry.class);

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final String nodeId;
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<Long, Future<?>> executionFutures = new ConcurrentHashMap<>();
    private final Set<Long> claimedExecutions = ConcurrentHashMap.newKeySet();
    private final Set<Long> claimedScopes = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(50),
            Executors.defaultThreadFactory(), new ThreadPoolExecutor.CallerRunsPolicy()
    );
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, ScheduledFuture<?>> syncTimers = new ConcurrentHashMap<>();

    public ExecutionNodeRegistry() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        this.nodeId = host + "-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("ExecutionNodeRegistry initialized, nodeId={}", this.nodeId);
    }

    public String getNodeId() {
        return nodeId;
    }

    public SseEmitter createEmitter(Long executionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(executionId, emitter);
        emitter.onCompletion(() -> emitters.remove(executionId));
        emitter.onTimeout(() -> emitters.remove(executionId));
        emitter.onError(e -> emitters.remove(executionId));
        return emitter;
    }

    public SseEmitter getEmitter(Long executionId) {
        return emitters.get(executionId);
    }

    public SseEmitter removeEmitter(Long executionId) {
        return emitters.remove(executionId);
    }

    public void putFuture(Long executionId, Future<?> future) {
        executionFutures.put(executionId, future);
    }

    public Future<?> getFuture(Long executionId) {
        return executionFutures.get(executionId);
    }

    public Future<?> removeFuture(Long executionId) {
        return executionFutures.remove(executionId);
    }

    public Future<?> cancelAndRemoveFuture(Long executionId) {
        Future<?> future = executionFutures.remove(executionId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        return future;
    }

    public boolean hasLocalExecution(Long executionId) {
        Future<?> future = executionFutures.get(executionId);
        return future != null && !future.isDone();
    }

    /**
     * 原子性地尝试声明对某 executionId 的消费权。
     * 返回 true 表示本次调用是首个消费者，可以继续处理；
     * 返回 false 表示已有另一条消息正在处理该 execution，应跳过（防重复消费）。
     */
    public boolean tryClaimExecution(Long executionId) {
        return claimedExecutions.add(executionId);
    }

    /**
     * 释放对某 executionId 的消费声明，允许后续消息重新消费（如失败重试场景）。
     */
    public void releaseExecution(Long executionId) {
        claimedExecutions.remove(executionId);
    }

    /**
     * 原子性地尝试声明对某 scopeId 的操作权（用于定时 Lint 等场景，
     * 防止同一 scope 的多条重复消息并发创建多个 execution）。
     * 返回 true 表示本次调用是首个消费者；返回 false 表示已有其他线程在处理该 scope。
     */
    public boolean tryClaimScope(Long scopeId) {
        return claimedScopes.add(scopeId);
    }

    /**
     * 释放对某 scopeId 的操作声明。
     */
    public void releaseScope(Long scopeId) {
        claimedScopes.remove(scopeId);
    }

    public Future<?> submitTask(Runnable task) {
        return executor.submit(task);
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public void putSyncTimer(Long executionId, ScheduledFuture<?> timer) {
        ScheduledFuture<?> old = syncTimers.put(executionId, timer);
        if (old != null && !old.isDone()) old.cancel(false);
    }

    public void cancelSyncTimer(Long executionId) {
        ScheduledFuture<?> timer = syncTimers.remove(executionId);
        if (timer != null && !timer.isDone()) timer.cancel(false);
    }

    public void completeAndRemoveEmitter(Long executionId) {
        SseEmitter emitter = emitters.remove(executionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.warn("Failed to complete SSE emitter for executionId={}", executionId);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ExecutionNodeRegistry executor");
        executor.shutdown();
        scheduler.shutdownNow();
        syncTimers.values().forEach(t -> t.cancel(false));
        syncTimers.clear();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
