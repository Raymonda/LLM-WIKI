package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WriterOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WriterOrchestrator.class);

    @Autowired
    private WriterAgent writerAgent;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired(required = false)
    private ConsistencyReconciler consistencyReconciler;

    @Autowired
    private org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper lintFindingMapper;

    @Value("${llmwiki.ingest.reconciler.async:true}")
    private boolean reconcilerAsync;

    private ExecutorService asyncExecutor;

    @PostConstruct
    public void init() {
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "writer-async-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.asyncExecutor = Executors.newFixedThreadPool(2, tf);
    }

    @PreDestroy
    public void shutdown() {
        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public int writeWithVerification(IngestContext context) {
        int tokens = writerAgent.write(context);

        runComplianceCheck(context);

        runQualityVerification(context);

        if (reconcilerAsync) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                Set<String> fixedPaths = runConsistencyReconciliation(context);
                reSyncIndex(context, fixedPaths);
            }, asyncExecutor);
            context.setReconcilerFuture(future);
        } else {
            Set<String> fixedPaths = runConsistencyReconciliation(context);
            reSyncIndex(context, fixedPaths);
        }

        context.setWriterPostChecksDone(true);

        return tokens;
    }

    public int write(IngestContext context) {
        return writerAgent.write(context);
    }

    private void runComplianceCheck(IngestContext context) {
        try {
            if (context.getWritingPlanJson() == null || context.getWritingPlanJson().isBlank()) {
                return;
            }

            WritingPlanComplianceChecker checker = new WritingPlanComplianceChecker(storageProvider);
            WritingPlanComplianceChecker.ComplianceReport report = checker.check(context);

            if (report.hasIssues()) {
                log.info("WriterOrchestrator: compliance check: {}. scopeId={}",
                    report.summarize(), context.getScopeId());
            }
        } catch (Exception e) {
            log.warn("WriterOrchestrator: compliance check failed (non-blocking): {}", e.getMessage());
        }
    }

    private Set<String> runConsistencyReconciliation(IngestContext context) {
        try {
            if (consistencyReconciler == null) {
                log.debug("WriterOrchestrator: ConsistencyReconciler not available, skipping");
                return Set.of();
            }

            int totalPages = countTotalPages(context);
            if (totalPages < 2) {
                return Set.of();
            }

            ConsistencyReconciler.ConsistencyReport report = consistencyReconciler.reconcile(context);

            if (report.hasIssues()) {
                log.info("WriterOrchestrator: consistency reconciliation: {}. scopeId={}",
                    report.summarize(), context.getScopeId());
            } else {
                log.debug("WriterOrchestrator: consistency reconciliation passed. scopeId={}", context.getScopeId());
            }

            persistFactConflicts(context, report);

            return report.fixedPaths() != null ? report.fixedPaths() : Set.of();
        } catch (Exception e) {
            log.warn("WriterOrchestrator: consistency reconciliation failed (non-blocking): {}", e.getMessage());
            return Set.of();
        }
    }

    private void persistFactConflicts(IngestContext context, ConsistencyReconciler.ConsistencyReport report) {
        if (report.factConflicts() == null || report.factConflicts().isEmpty()) {
            return;
        }
        int persisted = 0;
        int deduplicated = 0;
        for (ConsistencyReconciler.FactConflict fc : report.factConflicts()) {
            try {
                String detail = "页面A: " + fc.pageA() + "\n声明A: " + fc.claimA()
                    + "\n页面B: " + fc.pageB() + "\n声明B: " + fc.claimB();
                int existing = lintFindingMapper.countOpenConflictByPageAndDetailPrefix(
                    context.getScopeId(), fc.pageA(), detail);
                if (existing > 0) {
                    deduplicated++;
                    continue;
                }

                org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO finding = new org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO();
                finding.setScopeId(context.getScopeId());
                finding.setPagePath(fc.pageA());
                finding.setFindingType("conflict");
                finding.setPriority("medium");
                finding.setTitle(fc.description() != null && fc.description().length() > 256
                    ? fc.description().substring(0, 256) : fc.description());
                finding.setDetail(detail);
                finding.setExtra("{\"pagePathB\":\"" + fc.pageB()
                    + "\",\"claimA\":\"" + escapeJson(fc.claimA())
                    + "\",\"claimB\":\"" + escapeJson(fc.claimB()) + "\"}");
                finding.setStatus("open");
                finding.setExecutionId(context.getExecutionId());
                lintFindingMapper.insert(finding);
                persisted++;
            } catch (Exception e) {
                log.warn("WriterOrchestrator: failed to persist fact conflict (non-blocking): pageA={}, error={}",
                    fc.pageA(), e.getMessage());
            }
        }
        if (persisted > 0 || deduplicated > 0) {
            log.info("WriterOrchestrator: persisted {} fact conflicts ({} deduplicated). scopeId={}, executionId={}",
                persisted, deduplicated, context.getScopeId(), context.getExecutionId());
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private void runQualityVerification(IngestContext context) {
        try {
            if (context.getEntityDossiers() == null || context.getEntityDossiers().isEmpty()) {
                log.debug("WriterOrchestrator: no EntityDossiers, skipping quality verification");
                return;
            }

            WriterQualityVerifier verifier = new WriterQualityVerifier(storageProvider);
            WriterQualityVerifier.VerificationReport report = verifier.verify(context);

            context.setVerificationReport(report);

            if (report.hasCriticalIssues()) {
                log.warn("WriterOrchestrator: quality verification found {} critical issues. scopeId={}, executionId={}\n{}",
                    report.criticalCount(), context.getScopeId(), context.getExecutionId(), report.summarize());
            } else if (report.warningCount() > 0) {
                log.info("WriterOrchestrator: quality verification found {} warnings. scopeId={}",
                    report.warningCount(), context.getScopeId());
            } else {
                log.info("WriterOrchestrator: quality verification passed. scopeId={}", context.getScopeId());
            }
        } catch (Exception e) {
            log.warn("WriterOrchestrator: quality verification failed (non-blocking): {}", e.getMessage());
        }
    }

    private void reSyncIndex(IngestContext context, Set<String> fixedPaths) {
        if (fixedPaths == null || fixedPaths.isEmpty()) {
            return;
        }
        try {
            writerAgent.reSyncSpecificPages(context, fixedPaths);
        } catch (Exception e) {
            log.warn("WriterOrchestrator: post-fix re-index failed (non-blocking): {}", e.getMessage());
        }
    }

    private int countTotalPages(IngestContext context) {
        int count = 0;
        if (context.getSummaryPage() != null) count++;
        if (context.getEntityPages() != null) count += context.getEntityPages().size();
        if (context.getChapterPages() != null) count += context.getChapterPages().size();
        if (context.getUpdatedPages() != null) count += context.getUpdatedPages().size();
        return count;
    }
}
