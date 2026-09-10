package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.domain.service.harness.DocumentStructureAnalyzer;
import org.cn.liuwt.llmwiki.domain.service.harness.ExecutionStrategy;
import org.cn.liuwt.llmwiki.domain.service.harness.GlobalSummaryService;
import org.cn.liuwt.llmwiki.domain.service.harness.DocumentChunker;
import org.cn.liuwt.llmwiki.domain.service.harness.ParallelAnalysisExecutor;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class AnalyzerAgent {

    private static final Logger log = LoggerFactory.getLogger(AnalyzerAgent.class);

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private GlobalSummaryService globalSummaryService;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private LlmConcurrencyBarrier llmBarrier;

    @Autowired
    private StorageProvider storageProvider;

    @Value("${llmwiki.ai.multimodal:false}")
    private boolean multimodalEnabled;

    @Value("${llmwiki.ai.multimodal-max-images:10}")
    private int multimodalMaxImages;

    @Value("${llmwiki.ingest.analysis.pool-size:8}")
    private int analysisPoolSize;

    @Value("${llmwiki.ingest.analysis.single-pass-max-chars:800000}")
    private int singlePassMaxChars;

    private final ExecutorService analysisExecutor = Executors.newFixedThreadPool(8);
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @PostConstruct
    public void initExecutor() {
        if (analysisPoolSize <= 0 || analysisPoolSize == 8) {
            return;
        }
        java.util.concurrent.ThreadPoolExecutor tpe = (java.util.concurrent.ThreadPoolExecutor) analysisExecutor;
        // 先抬高上界再设下界，避免 core > max 触发 IllegalArgumentException
        tpe.setMaximumPoolSize(Math.max(tpe.getMaximumPoolSize(), analysisPoolSize));
        tpe.setCorePoolSize(analysisPoolSize);
        tpe.setMaximumPoolSize(analysisPoolSize);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down analysisExecutor");
        analysisExecutor.shutdown();
        try {
            if (!analysisExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                analysisExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            analysisExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public int analyze(IngestContext context, Long executionId, Long stepId) {
        Long scopeId = context.getScopeId();
        int tokensUsed = 0;

        if (chatClient == null || !chatClient.isAvailable()) {
            context.setMergedAnalysis("AI not available, skipping analysis");
            context.setMetadataJson("{\"title\":\"未命名\",\"summary\":\"AI不可用\",\"category\":\"未分类\",\"tags\":[],\"keywords\":[],\"affectedPages\":[]}");
            return 0;
        }

        String pagesContext = buildPagesContext(scopeId);
        context.setPagesContext(pagesContext);
        populateDeprecatedPagesIndex(context);

        String sourceText = context.getSourceContent();
        List<DocumentChunker.Chunk> chunks = context.getChunks();

        boolean canSinglePass = sourceText != null && sourceText.length() <= singlePassMaxChars;

        if (canSinglePass) {
            log.info("Analysis: single-pass mode (sourceLength={}, threshold={})",
                sourceText.length(), singlePassMaxChars);
            if (stepId != null) {
                executionTracker.updateStepStatus(stepId, "running");
            }
            String prompt = schemaInjector.prependForAnalyzer(scopeId, PromptRegistry.forIngest().analyzeAndExtract(context.getGuidance(), pagesContext));
            String response;
            if (multimodalEnabled && context.hasExtractedImages()) {
                List<LlmClient.MultimodalImageInput> images = loadImagesForMultimodal(scopeId, context);
                response = chatClient.chatMultimodal(prompt, sourceText, images);
            } else {
                response = chatClient.chat(prompt, sourceText);
            }
            tokensUsed += estimateTokens(response);
            parseCombinedResponse(response, context);
        } else {
            log.info("Analysis: chunked mode (sourceLength={}, chunks={}, threshold={})",
                sourceText != null ? sourceText.length() : 0, chunks.size(), singlePassMaxChars);
            final Long fStepId = stepId;
            final Long fExecId = executionId;
            ParallelAnalysisExecutor.ProgressListener listener = (current, total, avgMs, chunkIndex, chunkContent) -> {
                if (fStepId != null) {
                    executionTracker.publishStepProgress(
                        fExecId, fStepId, "ANALYZE", current, total, avgMs, chunkIndex, chunkContent
                    );
                }
            };
            ParallelAnalysisExecutor executor = new ParallelAnalysisExecutor(chatClient, analysisExecutor, 60, context.getGuidance(), schemaInjector.buildSchemaHeader(scopeId), listener, llmBarrier);
            List<ParallelAnalysisExecutor.ChunkAnalysisResult> chunkResults = executor.analyze(chunks);
            for (ParallelAnalysisExecutor.ChunkAnalysisResult cr : chunkResults) {
                tokensUsed += estimateTokens(cr.content());
            }

            List<String> validResults = chunkResults.stream()
                .filter(r -> !r.hasError())
                .map(r -> String.format("## 片段 %d 分析\n%s", r.chunkIndex() + 1, r.content()))
                .collect(java.util.stream.Collectors.toList());
            if (validResults.isEmpty()) {
                context.setMergedAnalysis("所有片段分析均失败");
                context.setMetadataJson("{\"title\":\"未命名\",\"summary\":\"分析失败\",\"category\":\"未分类\",\"tags\":[],\"keywords\":[],\"affectedPages\":[]}");
                return tokensUsed;
            }
            String combined = String.join("\n\n---\n\n", validResults);

            boolean skipMerge = false;
            ExecutionStrategy strategy = context.getStrategy();
            if (strategy != null) {
                skipMerge = strategy.isSkipMerge();
            } else {
                boolean isStructured = context.getDocumentType() == DocumentStructureAnalyzer.DocumentType.STRUCTURED
                    && context.getChapters() != null && context.getChapters().size() >= 3;
                skipMerge = isStructured;
            }

            if (skipMerge) {
                context.setMergedAnalysis(combined);
                String chapterSummary = buildChapterSummary(context.getChapters());
                String prompt = schemaInjector.prependForAnalyzer(scopeId,
                    PromptRegistry.forIngest().extractMetadataFromStructure(chapterSummary, pagesContext));
                String response = chatClient.chat(prompt);
                tokensUsed += estimateTokens(response);
                parseMetadataOnly(response, context);
                log.info("Chunked skipMerge path: {} chunks, saved 1 LLM call", chunks.size());
            } else {
                String prompt = schemaInjector.prependForAnalyzer(scopeId, PromptRegistry.forIngest().mergeAndExtract(pagesContext));
                String response;
                if (multimodalEnabled && context.hasExtractedImages()) {
                    List<LlmClient.MultimodalImageInput> images = loadImagesForMultimodal(scopeId, context);
                    response = chatClient.chatMultimodal(prompt, combined, images);
                } else {
                    response = chatClient.chat(prompt, combined);
                }
                tokensUsed += estimateTokens(response);
                parseCombinedResponse(response, context);
            }
        }

        return tokensUsed;
    }

    private void parseCombinedResponse(String response, IngestContext context) {
        try {
            String cleaned = PromptTemplate.stripMarkdownFences(response);
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(cleaned);
            String mergedAnalysis = root.has("mergedAnalysis") ? root.get("mergedAnalysis").asText() : cleaned;
            String metadataJson = root.has("metadata") ? objectMapper.writeValueAsString(root.get("metadata")) : cleaned;
            context.setMergedAnalysis(mergedAnalysis);
            context.setMetadataJson(metadataJson);
        } catch (Exception e) {
            context.setMergedAnalysis(response);
            String fallbackPrompt = schemaInjector.prependForAnalyzer(context.getScopeId(), PromptRegistry.forIngest().extractMetadata(context.getPagesContext()));
            String metadata = chatClient.chat(fallbackPrompt, response);
            context.setMetadataJson(metadata);
        }
    }

    private List<LlmClient.MultimodalImageInput> loadImagesForMultimodal(Long scopeId, IngestContext context) {
            List<IngestContext.ExtractedImage> extractedImages = context.getExtractedImages();
            if (extractedImages == null || extractedImages.isEmpty()) {
                return List.of();
            }
    
            String scopeIdStr = String.valueOf(scopeId);
            List<LlmClient.MultimodalImageInput> result = new ArrayList<>();
            int loaded = 0;
    
            for (IngestContext.ExtractedImage img : extractedImages) {
                if (loaded >= multimodalMaxImages) break;
                try {
                    String assetPath = "assets/" + img.relativePath();
                    byte[] data = storageProvider.read(scopeIdStr, assetPath);
                    if (data == null || data.length == 0) {
                        log.warn("Multimodal image not found: {}", assetPath);
                        continue;
                    }
                    String mimeType = detectMimeType(img.relativePath());
                    String base64 = Base64.getEncoder().encodeToString(data);
                    result.add(new LlmClient.MultimodalImageInput(mimeType, base64));
                    loaded++;
                } catch (Exception e) {
                    log.warn("Failed to load multimodal image {}: {}", img.relativePath(), e.getMessage());
                }
            }
    
            log.info("Loaded {} multimodal images for scopeId={}", loaded, scopeId);
            return result;
        }
    
    private String detectMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    private static final int CHAPTER_SUMMARY_MAX_CHARS = 4000;

    private String buildChapterSummary(List<DocumentStructureAnalyzer.Chapter> chapters) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chapters.size(); i++) {
            DocumentStructureAnalyzer.Chapter ch = chapters.get(i);
            sb.append("### 第").append(i + 1).append("章: ").append(ch.title()).append("\n");
            String content = ch.sourceContent();
            if (content != null) {
                sb.append(PromptTemplate.sampleSourceContent(content, CHAPTER_SUMMARY_MAX_CHARS)).append("\n");
            }
            if (!ch.subChapters().isEmpty()) {
                sb.append("子章节: ");
                sb.append(ch.subChapters().stream()
                    .map(DocumentStructureAnalyzer.Chapter::title)
                    .collect(java.util.stream.Collectors.joining(", ")));
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void parseMetadataOnly(String response, IngestContext context) {
        try {
            String cleaned = PromptTemplate.stripMarkdownFences(response);
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(cleaned);
            String metadataJson = objectMapper.writeValueAsString(root);
            context.setMetadataJson(metadataJson);
        } catch (Exception e) {
            log.warn("parseMetadataOnly failed, using fallback: {}", e.getMessage());
            context.setMetadataJson("{\"title\":\"未命名\",\"summary\":\"元数据解析失败\",\"category\":\"未分类\",\"tags\":[],\"keywords\":[],\"affectedPages\":[]}");
        }
    }

    private String buildPagesContext(Long scopeId) {
        GlobalSummaryService.GlobalSummary summary = globalSummaryService.build(scopeId);
        return summary.toCompactPrompt();
    }

    private void populateDeprecatedPagesIndex(IngestContext context) {
        Long scopeId = context.getScopeId();
        GlobalSummaryService.GlobalSummary summary = globalSummaryService.build(scopeId);
        var deprecatedList = summary.deprecatedPageList();
        if (deprecatedList == null || deprecatedList.isEmpty()) return;

        java.util.Map<String, IngestContext.DeprecatedPageInfo> index = new java.util.HashMap<>();
        for (var dp : deprecatedList) {
            index.put(dp.title().toLowerCase(), new IngestContext.DeprecatedPageInfo(dp.pageId(), dp.title(), dp.category(), dp.filePath()));
        }
        context.setDeprecatedPagesIndex(index);
        log.info("Populated deprecatedPagesIndex with {} entries for scopeId={}", index.size(), scopeId);
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        int chineseCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') chineseCount++;
        }
        int nonChinese = text.length() - chineseCount;
        return chineseCount / 2 + nonChinese / 4;
    }
}