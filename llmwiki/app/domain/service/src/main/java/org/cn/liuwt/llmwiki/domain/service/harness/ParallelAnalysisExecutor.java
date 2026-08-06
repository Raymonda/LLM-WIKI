package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParallelAnalysisExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParallelAnalysisExecutor.class);

    private final LlmClient chatClient;
    private final ExecutorService executor;
    private final long timeoutSeconds;
    private final String guidance;
    private final String schemaHeader;
    private final ProgressListener progressListener;
    private final LlmConcurrencyBarrier llmBarrier;

    public ParallelAnalysisExecutor(LlmClient chatClient, ExecutorService executor, long timeoutSeconds, String guidance) {
        this(chatClient, executor, timeoutSeconds, guidance, "", null, null);
    }

    public ParallelAnalysisExecutor(LlmClient chatClient,
                                    ExecutorService executor,
                                    long timeoutSeconds,
                                    String guidance,
                                    ProgressListener progressListener) {
        this(chatClient, executor, timeoutSeconds, guidance, "", progressListener, null);
    }

    public ParallelAnalysisExecutor(LlmClient chatClient,
                                    ExecutorService executor,
                                    long timeoutSeconds,
                                    String guidance,
                                    String schemaHeader,
                                    ProgressListener progressListener) {
        this(chatClient, executor, timeoutSeconds, guidance, schemaHeader, progressListener, null);
    }

    public ParallelAnalysisExecutor(LlmClient chatClient,
                                    ExecutorService executor,
                                    long timeoutSeconds,
                                    String guidance,
                                    String schemaHeader,
                                    ProgressListener progressListener,
                                    LlmConcurrencyBarrier llmBarrier) {
        this.chatClient = chatClient;
        this.executor = executor;
        this.timeoutSeconds = timeoutSeconds;
        this.guidance = guidance;
        this.schemaHeader = schemaHeader == null ? "" : schemaHeader;
        this.progressListener = progressListener;
        this.llmBarrier = llmBarrier;
    }

    public List<ChunkAnalysisResult> analyze(List<DocumentChunker.Chunk> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        TokenUsageContext.Context parentCtx = TokenUsageContext.get();
        int total = chunks.size();
        AtomicInteger completed = new AtomicInteger(0);
        AtomicLong totalElapsedMs = new AtomicLong(0);
        long startMs = System.currentTimeMillis();

        long totalTimeoutSeconds = Math.max(timeoutSeconds * 2, timeoutSeconds + total * 30L);

        if (progressListener != null) {
            safeNotify(0, total, 0L, -1, "");
        }

        List<CompletableFuture<ChunkAnalysisResult>> futures = new ArrayList<>();
        for (DocumentChunker.Chunk chunk : chunks) {
            DocumentChunker.Chunk finalChunk = chunk;
            CompletableFuture<ChunkAnalysisResult> future = CompletableFuture.supplyAsync(
                () -> {
                    if (parentCtx != null) TokenUsageContext.set(parentCtx.scopeId(), parentCtx.operationType());
                    try {
                        return analyzeChunk(finalChunk);
                    } finally {
                        TokenUsageContext.clear();
                    }
                }, executor
            );
            future.whenComplete((res, ex) -> {
                int done = completed.incrementAndGet();
                long elapsed = System.currentTimeMillis() - startMs;
                totalElapsedMs.set(elapsed);
                long avg = done > 0 ? elapsed / done : 0L;
                int ci = res != null ? res.chunkIndex() : chunk.index();
                String preview = res != null && !res.hasError() ? extractEntityNames(res.content()) : "";
                safeNotify(done, total, avg, ci, preview);
            });
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(totalTimeoutSeconds, TimeUnit.SECONDS)
                .get();
        } catch (ExecutionException e) {
            log.warn("Parallel analysis overall error ({}s timeout): {} chunks, {} completed: {}",
                totalTimeoutSeconds, total, completed.get(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Parallel analysis interrupted: {} chunks, {} completed",
                total, completed.get());
        }

        List<ChunkAnalysisResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            int chunkIndex = i;
            try {
                ChunkAnalysisResult r = futures.get(chunkIndex)
                    .completeOnTimeout(new ChunkAnalysisResult(chunkIndex, "分析超时（总超时限制）", true), 5, TimeUnit.SECONDS)
                    .join();
                results.add(r);
            } catch (Exception e) {
                log.warn("Chunk {} result collection failed: {}", chunkIndex, e.getMessage());
                results.add(new ChunkAnalysisResult(chunkIndex, "分析超时或中断", true));
            }
        }

        return results;
    }

    private void safeNotify(int current, int total, long avgMs, int chunkIndex, String chunkPreview) {
        if (progressListener == null) return;
        try {
            progressListener.onProgress(current, total, avgMs, chunkIndex, chunkPreview);
        } catch (Exception ex) {
            log.warn("progressListener failed: {}", ex.getMessage());
        }
    }

    private ChunkAnalysisResult analyzeChunk(DocumentChunker.Chunk chunk) {
        if (llmBarrier != null) {
            if (!llmBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.ANALYZE, 120_000)) {
                return new ChunkAnalysisResult(chunk.index(), "并发许可获取超时，分析被跳过", true);
            }
            try {
                String prompt = schemaHeader + PromptRegistry.forIngest().analyzeChunkWithGuidance(guidance);
                String analysis = chatClient.chat(prompt, chunk.content());
                return new ChunkAnalysisResult(chunk.index(), analysis, false);
            } finally {
                llmBarrier.release(LlmConcurrencyBarrier.Bucket.ANALYZE);
            }
        }
        String prompt = schemaHeader + PromptRegistry.forIngest().analyzeChunkWithGuidance(guidance);
        String analysis = chatClient.chat(prompt, chunk.content());
        return new ChunkAnalysisResult(chunk.index(), analysis, false);
    }

    public record ChunkAnalysisResult(int chunkIndex, String content, boolean hasError) {}

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int current, int total, long avgMsPerUnit, int chunkIndex, String chunkContent);
    }

    private static final Pattern ENTITY_SECTION_PATTERN = Pattern.compile(
        "(?:###?\\s*1[.、]\\s*主要实体[列表]?|主要实体[列表]?)[\\s\\S]*?(?=###?\\s*2|###?\\s*[二2])",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ENTITY_ITEM_PATTERN = Pattern.compile("^\\s*[-*•]\\s*(.+?)$", Pattern.MULTILINE);

    private String extractEntityNames(String content) {
        if (content == null || content.isEmpty()) return "";
        try {
            Matcher sectionMatcher = ENTITY_SECTION_PATTERN.matcher(content);
            if (!sectionMatcher.find()) return "";
            String section = sectionMatcher.group();
            Matcher itemMatcher = ENTITY_ITEM_PATTERN.matcher(section);
            List<String> entities = new ArrayList<>();
            while (itemMatcher.find()) {
                String entity = itemMatcher.group(1).trim();
                if (!entity.isEmpty() && entities.size() < 5) {
                    entities.add(entity);
                }
            }
            return String.join(",", entities);
        } catch (Exception e) {
            log.debug("Failed to extract entity names: {}", e.getMessage());
            return "";
        }
    }
}