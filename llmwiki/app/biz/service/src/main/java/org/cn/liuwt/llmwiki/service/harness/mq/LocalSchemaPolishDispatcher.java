package org.cn.liuwt.llmwiki.service.harness.mq;

import org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap.SchemaPolishDispatcher;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap.SchemaPolishService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@Component
public class LocalSchemaPolishDispatcher implements SchemaPolishDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalSchemaPolishDispatcher.class);

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "schema-polish-local");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    private SchemaPolishService schemaPolishService;

    @Override
    public void dispatch(Long scopeId) {
        try {
            executor.submit(() -> {
                try {
                    schemaPolishService.polishSchema(scopeId);
                } catch (Exception e) {
                    log.warn("Local schema polish failed, keeping deterministic version: scopeId={}, error={}",
                        scopeId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Schema polish skipped: executor shutting down, deterministic schema remains in effect: scopeId={}",
                scopeId);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
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
