package org.cn.liuwt.llmwiki.domain.service.harness;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 页面级写锁注册表：以 scopeId + 页面路径为粒度串行化同一页面的"读-改-写"。
 * 锁按引用计数回收，空闲条目自动从注册表移除。锁仅在 JVM 内生效；
 * 多节点场景由 IngestBatchScheduler 的写阶段 DB 原子互斥（scope 行锁 + 当前读复查）
 * 保证同 scope 写阶段全局唯一，本锁负责节点内多写并行的页面级串行化。
 */
@Component
public class PageWriteLockRegistry {

    public static class PageLockTimeoutException extends RuntimeException {
        public PageLockTimeoutException(String message) {
            super(message);
        }
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger refCount = new AtomicInteger(1);
    }

    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    public <T> T withPageLock(Long scopeId, String pagePath, long timeoutMs, Supplier<T> action) {
        String key = lockKey(scopeId, pagePath);
        LockEntry entry = locks.compute(key, (k, existing) -> {
            if (existing != null) {
                existing.refCount.incrementAndGet();
                return existing;
            }
            return new LockEntry();
        });

        boolean acquired = false;
        try {
            acquired = entry.lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            releaseRef(key, entry);
            throw new PageLockTimeoutException("page write lock timeout after " + timeoutMs + "ms: " + key);
        }

        try {
            return action.get();
        } finally {
            entry.lock.unlock();
            releaseRef(key, entry);
        }
    }

    int trackedLockCount() {
        return locks.size();
    }

    private void releaseRef(String key, LockEntry entry) {
        locks.computeIfPresent(key, (k, existing) -> {
            if (existing != entry) {
                return existing;
            }
            return entry.refCount.decrementAndGet() <= 0 ? null : existing;
        });
    }

    private String lockKey(Long scopeId, String pagePath) {
        String normalized = pagePath == null ? "" : pagePath.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return scopeId + ":" + normalized;
    }
}
