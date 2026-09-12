package org.cn.liuwt.llmwiki.domain.service.harness;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PageWriteLockRegistryTest {

    private final PageWriteLockRegistry registry = new PageWriteLockRegistry();

    @Test
    void shouldSerializeWritesOnSamePage() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch firstInside = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger maxConcurrent = new AtomicInteger();

            Future<String> first = pool.submit(() -> registry.withPageLock(1L, "pages/a.md", 5000, () -> {
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                firstInside.countDown();
                awaitQuietly(releaseFirst, 5);
                concurrent.decrementAndGet();
                return "first";
            }));
            assertTrue(firstInside.await(5, TimeUnit.SECONDS));

            Future<String> second = pool.submit(() -> registry.withPageLock(1L, "pages/a.md", 5000, () -> {
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                concurrent.decrementAndGet();
                return "second";
            }));

            assertFalse(second.isDone(), "same-page writer must wait while the lock is held");
            releaseFirst.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
            assertEquals(1, maxConcurrent.get(), "same-page writes must never overlap");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldRunInParallelWhenPageOrScopeDiffers() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch firstInside = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);

            Future<String> first = pool.submit(() -> registry.withPageLock(1L, "pages/a.md", 5000, () -> {
                firstInside.countDown();
                awaitQuietly(releaseFirst, 5);
                return "first";
            }));
            assertTrue(firstInside.await(5, TimeUnit.SECONDS));

            Future<String> otherPage = pool.submit(() -> registry.withPageLock(1L, "pages/b.md", 5000, () -> "otherPage"));
            Future<String> otherScope = pool.submit(() -> registry.withPageLock(2L, "pages/a.md", 5000, () -> "otherScope"));

            assertEquals("otherPage", otherPage.get(2, TimeUnit.SECONDS));
            assertEquals("otherScope", otherScope.get(2, TimeUnit.SECONDS));
            releaseFirst.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldAllowReentrantLockOnSameThread() {
        String result = registry.withPageLock(1L, "pages/a.md", 5000,
            () -> registry.withPageLock(1L, "pages/a.md", 5000, () -> "nested"));

        assertEquals("nested", result);
        assertEquals(0, registry.trackedLockCount(), "entries must be recycled after release");
    }

    @Test
    void shouldRecycleEntryWhenIdle() {
        registry.withPageLock(1L, "pages/a.md", 5000, () -> null);

        assertEquals(0, registry.trackedLockCount());
    }

    @Test
    void shouldTimeoutWhenPageLockHeldBeyondTimeout() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = pool.submit(() -> registry.withPageLock(1L, "pages/a.md", 5000, () -> {
                held.countDown();
                awaitQuietly(release, 5);
                return null;
            }));
            assertTrue(held.await(5, TimeUnit.SECONDS));

            assertThrows(PageWriteLockRegistry.PageLockTimeoutException.class,
                () -> registry.withPageLock(1L, "pages/a.md", 100, () -> "never"));

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertEquals(0, registry.trackedLockCount(), "entry must survive the timeout and be recycled once idle");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch, long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
