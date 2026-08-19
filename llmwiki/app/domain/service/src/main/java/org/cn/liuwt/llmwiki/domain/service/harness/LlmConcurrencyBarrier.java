package org.cn.liuwt.llmwiki.domain.service.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class LlmConcurrencyBarrier {

    private static final Logger log = LoggerFactory.getLogger(LlmConcurrencyBarrier.class);

    public enum Bucket {
        PLAN, SUMMARY, ENTITY, ANALYZE, LINT, CHAPTER, RECONCILE, CROSSREF
    }

    @Value("${llmwiki.llm.concurrency.plan:4}")
    private int planPermits;

    @Value("${llmwiki.llm.concurrency.summary:4}")
    private int summaryPermits;

    @Value("${llmwiki.llm.concurrency.entity:8}")
    private int entityPermits;

    @Value("${llmwiki.llm.concurrency.analyze:12}")
    private int analyzePermits;

    @Value("${llmwiki.llm.concurrency.lint:4}")
    private int lintPermits;

    @Value("${llmwiki.llm.concurrency.chapter:12}")
    private int chapterPermits;

    @Value("${llmwiki.llm.concurrency.reconcile:2}")
    private int reconcilePermits;

    @Value("${llmwiki.llm.concurrency.crossref:6}")
    private int crossrefPermits;

    private final Map<Bucket, Semaphore> buckets = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        buckets.put(Bucket.PLAN, new Semaphore(planPermits));
        buckets.put(Bucket.SUMMARY, new Semaphore(summaryPermits));
        buckets.put(Bucket.ENTITY, new Semaphore(entityPermits));
        buckets.put(Bucket.ANALYZE, new Semaphore(analyzePermits));
        buckets.put(Bucket.LINT, new Semaphore(lintPermits));
        buckets.put(Bucket.CHAPTER, new Semaphore(chapterPermits));
        buckets.put(Bucket.RECONCILE, new Semaphore(reconcilePermits));
        buckets.put(Bucket.CROSSREF, new Semaphore(crossrefPermits));
        log.info("LlmConcurrencyBarrier initialized: plan={}, summary={}, entity={}, analyze={}, lint={}, chapter={}, reconcile={}, crossref={}",
            planPermits, summaryPermits, entityPermits, analyzePermits, lintPermits, chapterPermits, reconcilePermits, crossrefPermits);
    }

    public boolean tryAcquire(Bucket bucket, long timeoutMs) {
        Semaphore sem = buckets.get(bucket);
        if (sem == null) return false;
        try {
            boolean acquired = sem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("LLM concurrency barrier timeout: bucket={}, timeoutMs={}, available={}",
                    bucket, timeoutMs, sem.availablePermits());
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release(Bucket bucket) {
        Semaphore sem = buckets.get(bucket);
        if (sem != null) {
            sem.release();
        }
    }

    public int availablePermits(Bucket bucket) {
        Semaphore sem = buckets.get(bucket);
        return sem != null ? sem.availablePermits() : 0;
    }

    public int totalPermits() {
        return buckets.values().stream().mapToInt(Semaphore::availablePermits).sum();
    }
}