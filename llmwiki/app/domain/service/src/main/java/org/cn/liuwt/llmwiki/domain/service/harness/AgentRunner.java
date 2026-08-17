package org.cn.liuwt.llmwiki.domain.service.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.FactBlock;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.config.QueryPrompts;
import org.cn.liuwt.llmwiki.domain.service.harness.query.FactBlockParser;
import org.cn.liuwt.llmwiki.domain.service.harness.query.QueryClarifier;
import org.cn.liuwt.llmwiki.domain.service.harness.query.QuerySseProtocol;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.ReadFileTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.ReadRawSourceTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.GetSourceInfoTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.SearchWikiTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.ListPagesTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.GetRelatedPagesTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.WriteFileTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.UpdateLinksTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.ApprovalService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.RateLimitService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    @Value("${llmwiki.query.fact-block.enabled:true}")
    private boolean factBlockEnabled;

    @Value("${llmwiki.query.clarifier.enabled:true}")
    private boolean clarifierEnabled;

    @Value("${llmwiki.query.narrative.enabled:true}")
    private boolean narrativeEnabled;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired(required = false)
    private ChatModel chatModel;

    @Autowired(required = false)
    private LlmClient LlmClient;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private CompactionService compactionService;

    @Autowired
    private org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService executionEventLog;

    @Autowired
    private QueryClarifier queryClarifier;

    @Autowired
    private ReadFileTool readFileTool;

    @Autowired
    private ReadRawSourceTool readRawSourceTool;

    @Autowired
    private GetSourceInfoTool getSourceInfoTool;

    @Autowired
    private SearchWikiTool searchWikiTool;

    @Autowired
    private ListPagesTool listPagesTool;

    @Autowired
    private GetRelatedPagesTool getRelatedPagesTool;

    @Autowired
    private WriteFileTool writeFileTool;

    @Autowired
    private UpdateLinksTool updateLinksTool;

    @Autowired
    private org.cn.liuwt.llmwiki.domain.service.harness.tool.TodoTool todoTool;

    @Autowired
    private SearchService searchService;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private GlobalSummaryService globalSummaryService;

    @Autowired
    private org.cn.liuwt.llmwiki.integration.storage.StorageProvider storageProvider;

    private ChatClient queryReadOnlyClient;
    private ChatClient queryReadWriteClient;
    private ChatClient noToolsClient;
    private ChatClient deepQueryReadOnlyClient;
    private ChatClient deepQueryReadWriteClient;
    private ChatClient deepNoToolsClient;

    @PostConstruct
    public void init() {
        if (chatModel != null) {
            this.queryReadOnlyClient = ChatClient.builder(chatModel)
                .defaultTools(readFileTool, readRawSourceTool, getSourceInfoTool,
                    searchWikiTool, listPagesTool, getRelatedPagesTool, todoTool)
                .build();
            this.queryReadWriteClient = ChatClient.builder(chatModel)
                .defaultTools(readFileTool, readRawSourceTool, getSourceInfoTool,
                    searchWikiTool, listPagesTool, getRelatedPagesTool, todoTool,
                    writeFileTool, updateLinksTool)
                .build();
            this.noToolsClient = ChatClient.builder(chatModel).build();
            log.info("Query ChatClient initialized: readOnly(7 tools) + readWrite(9 tools) + noToolsClient");

            ChatModel deepModel = LlmClient.getDeepAnalysisChatModel();
            if (deepModel != null && deepModel != chatModel) {
                this.deepQueryReadOnlyClient = ChatClient.builder(deepModel)
                    .defaultTools(readFileTool, readRawSourceTool, getSourceInfoTool,
                        searchWikiTool, listPagesTool, getRelatedPagesTool, todoTool)
                    .build();
                this.deepQueryReadWriteClient = ChatClient.builder(deepModel)
                    .defaultTools(readFileTool, readRawSourceTool, getSourceInfoTool,
                        searchWikiTool, listPagesTool, getRelatedPagesTool, todoTool,
                        writeFileTool, updateLinksTool)
                    .build();
                this.deepNoToolsClient = ChatClient.builder(deepModel).build();
                log.info("Deep analysis ChatClient initialized: readOnly(7 tools) + readWrite(9 tools) + noToolsClient");
            }
        }
    }

    public Flux<String> runQueryAgentStreaming(Long scopeId, String question, String sessionId, boolean deepMode) {
        return runQueryAgentStreaming(scopeId, question, sessionId, deepMode, null);
    }

    public Flux<String> runQueryAgentStreaming(Long scopeId, String question, String sessionId, boolean deepMode, String assumedIntent) {
        TokenUsageContext.set(scopeId, "query");
        recordTurnStart(sessionId, scopeId, question, deepMode, false);
        if (!rateLimitService.checkCallRate(scopeId)) {
            TokenUsageContext.clear();
            return Flux.just("AI 调用频率过高，请稍后再试。");
        }

        if (queryReadOnlyClient == null) {
            TokenUsageContext.clear();
            log.error("ChatClient not available, falling back to simple query streaming");
            return Flux.just(runSimpleQuery(scopeId, question));
        }

        ChatClient activeQueryClient = (deepMode && deepQueryReadOnlyClient != null) ? deepQueryReadOnlyClient : queryReadOnlyClient;

        return Flux.defer(() -> {
            try {
                String effectiveAssumed = assumedIntent;
                if (clarifierEnabled && (effectiveAssumed == null || effectiveAssumed.isBlank())) {
                    ChatClient clarifyClient = (deepMode && deepNoToolsClient != null) ? deepNoToolsClient : noToolsClient;
                    String clarifyPrompt = schemaInjector.prependForQuery(scopeId,
                        PromptRegistry.forQuery().clarificationPrompt(scopeId));
                    QueryClarifier.ClarificationResult clarification = queryClarifier.assess(
                        clarifyClient, clarifyPrompt, question, sessionId);
                    if ("AMBIGUOUS".equals(clarification.clarity())
                        && clarification.clarification() != null
                        && !clarification.clarification().isBlank()
                        && clarification.reason() != null) {
                        String payload = buildClarifyPayload(clarification);
                        return Flux.just(QuerySseProtocol.CLARIFY_PREFIX + payload);
                    }
                    if ("CLEAR".equals(clarification.clarity())
                        && "forced-clear".equals(clarification.reason())
                        && clarification.clarification() != null
                        && !clarification.clarification().isBlank()) {
                        effectiveAssumed = "（澄清次数超限，自动采用）" + clarification.clarification();
                    }
                }
                long phase1Start = System.currentTimeMillis();
                RetrievalContext retrievalContext = retrievalService.preRetrieveLight(scopeId, question);
                long phase1Elapsed = System.currentTimeMillis() - phase1Start;

                String factContext = compactionService.compressField(sessionId, scopeId, "factContext", retrievalContext.toPromptContextLight());
                String deprecatedContext = retrievalContext.formatDeprecatedContext();
                int pageCount = retrievalContext.getPageCount();

                log.info("Pre-retrieve completed: scopeId={} results={} factContextLen={} deprecatedPages={} elapsed={}ms",
                    scopeId, retrievalContext.getSearchResults().size(),
                    factContext.length(), retrievalContext.getDeprecatedPages().size(), phase1Elapsed);

                // Phase 1: Fact Agent (tools, ACTIVE-only, generates Layer 1)
                String factSystemPrompt = factBlockEnabled
                    ? schemaInjector.prependForQuery(scopeId,
                        PromptRegistry.forQuery().factAgentPromptStructured(scopeId, pageCount, factContext))
                    : schemaInjector.prependForQuery(scopeId,
                        PromptRegistry.forQuery().factAgentPrompt(scopeId, pageCount, factContext));

                if (effectiveAssumed != null && !effectiveAssumed.isBlank()) {
                    factSystemPrompt = factSystemPrompt + "\n\n## 已确认的用户意图\n" + effectiveAssumed
                        + "\n检索与回答请聚焦此意图，无需再次澄清。";
                }

                StringBuilder lineBuffer = new StringBuilder();
                StringBuilder layer1Buffer = new StringBuilder();
                List<FactBlock> factBlocks = new ArrayList<>();
                AtomicInteger charCount = new AtomicInteger(0);

                Flux<String> generatingMarker = Flux.just("__STEP__:generating");

                Flux<String> layer1Stream = factBlockEnabled
                    ? activeQueryClient.prompt()
                        .system(factSystemPrompt)
                        .user(question)
                        .toolContext(java.util.Map.of("sessionId", sessionId))
                        .stream()
                        .content()
                        .concatMap(chunk -> {
                            if (chunk == null) return Flux.empty();
                            lineBuffer.append(chunk);
                            List<String> lines = FactBlockParser.extractCompleteLines(lineBuffer);
                            List<String> out = new ArrayList<>();
                            for (String line : lines) {
                                FactBlock block = FactBlockParser.tryParse(line);
                                if (block != null) {
                                    factBlocks.add(block);
                                    out.add(QuerySseProtocol.FACT_PREFIX + line);
                                } else if (!line.isBlank()) {
                                    layer1Buffer.append(line).append('\n');
                                    out.add(line);
                                }
                            }
                            return Flux.fromIterable(out);
                        })
                        .concatWith(Flux.defer(() -> emitPendingLine(lineBuffer, layer1Buffer, factBlocks)))
                    : activeQueryClient.prompt()
                        .system(factSystemPrompt)
                        .user(question)
                        .toolContext(java.util.Map.of("sessionId", sessionId))
                        .stream()
                        .content()
                        .doOnNext(layer1Buffer::append);

                // Phase 2: Synthesis Agent (no tools, uses Layer 1 + DEPRECATED context, generates Layer 2/3)
                Flux<String> layer23Stream = Flux.defer(() -> {
                    log.info("Fact Agent completed: scopeId={} factBlocks={}", scopeId, factBlocks.size());
                    String synthesisUserPrompt = buildSynthesisUserPrompt(scopeId, question,
                        FactBlockParser.toFactInput(factBlocks, layer1Buffer.toString()), deprecatedContext, deepMode);

                    return synthesizeWithImages(scopeId, synthesisUserPrompt, factBlocks, layer1Buffer.toString(), deepMode);
                });

                Flux<String> synthesisMarker = Flux.just("\n\n", "__STEP__:synthesizing");

                return Flux.concat(generatingMarker, layer1Stream, synthesisMarker, layer23Stream)
                    .doOnNext(chunk -> {
                        if (!chunk.startsWith("__STEP__:") && !chunk.startsWith("\n\n__STEP__:")) {
                            charCount.addAndGet(chunk != null ? chunk.length() : 0);
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("Stream failed, FALLBACK to simple query: scopeId={}, error={}", scopeId, e.getMessage(), e);
                        recordError(sessionId, "stream", e.getMessage());
                        return Flux.just(runSimpleQuery(scopeId, question));
                    });
            } catch (Exception e) {
                log.error("Pre-retrieve failed, FALLBACK to simple query: scopeId={}, error={}", scopeId, e.getMessage(), e);
                recordError(sessionId, "pre-retrieve", e.getMessage());
                return Flux.just(runSimpleQuery(scopeId, question));
            }
        }).subscribeOn(Schedulers.boundedElastic())
        .doFinally(signal -> recordTurnEnd(sessionId, signal));
    }

    public Flux<String> runQueryAgentStreamingMultiScope(List<Long> scopeIds, String question, String sessionId, boolean deepMode) {
        return runQueryAgentStreamingMultiScope(scopeIds, question, sessionId, deepMode, null);
    }

    public Flux<String> runQueryAgentStreamingMultiScope(List<Long> scopeIds, String question, String sessionId, boolean deepMode, String assumedIntent) {
        if (scopeIds.size() == 1) {
            return runQueryAgentStreaming(scopeIds.get(0), question, sessionId, deepMode, assumedIntent);
        }
        Long primaryScopeId = scopeIds.get(0);
        TokenUsageContext.set(primaryScopeId, "query");
        recordTurnStart(sessionId, primaryScopeId, question, deepMode, true);
        if (!rateLimitService.checkCallRate(primaryScopeId)) {
            TokenUsageContext.clear();
            return Flux.just("AI 调用频率过高，请稍后再试。");
        }
        if (queryReadOnlyClient == null) {
            TokenUsageContext.clear();
            return Flux.just(runSimpleQuery(primaryScopeId, question));
        }
        ChatClient activeQueryClient = (deepMode && deepQueryReadOnlyClient != null) ? deepQueryReadOnlyClient : queryReadOnlyClient;
        return Flux.defer(() -> {
            try {
                String effectiveAssumed = assumedIntent;
                if (clarifierEnabled && (effectiveAssumed == null || effectiveAssumed.isBlank())) {
                    ChatClient clarifyClient = (deepMode && deepNoToolsClient != null) ? deepNoToolsClient : noToolsClient;
                    String clarifyPrompt = schemaInjector.prependForQuery(primaryScopeId,
                        PromptRegistry.forQuery().clarificationPrompt(primaryScopeId));
                    QueryClarifier.ClarificationResult clarification = queryClarifier.assess(
                        clarifyClient, clarifyPrompt, question, sessionId);
                    if ("AMBIGUOUS".equals(clarification.clarity())
                        && clarification.clarification() != null
                        && !clarification.clarification().isBlank()
                        && clarification.reason() != null) {
                        String payload = buildClarifyPayload(clarification);
                        return Flux.just(QuerySseProtocol.CLARIFY_PREFIX + payload);
                    }
                    if ("CLEAR".equals(clarification.clarity())
                        && "forced-clear".equals(clarification.reason())
                        && clarification.clarification() != null
                        && !clarification.clarification().isBlank()) {
                        effectiveAssumed = "（澄清次数超限，自动采用）" + clarification.clarification();
                    }
                }
                RetrievalContext retrievalContext = retrievalService.preRetrieveMultiScope(scopeIds, question);
                String factContext = compactionService.compressField(sessionId, primaryScopeId, "factContext", retrievalContext.toPromptContextLight());
                int pageCount = retrievalContext.getSearchResults().size();
                String factSystemPrompt = factBlockEnabled
                    ? PromptRegistry.forQuery().factAgentPromptStructured(primaryScopeId, pageCount, factContext)
                    : PromptRegistry.forQuery().factAgentPrompt(primaryScopeId, pageCount, factContext);

                if (effectiveAssumed != null && !effectiveAssumed.isBlank()) {
                    factSystemPrompt = factSystemPrompt + "\n\n## 已确认的用户意图\n" + effectiveAssumed
                        + "\n检索与回答请聚焦此意图，无需再次澄清。";
                }
                StringBuilder lineBuffer = new StringBuilder();
                StringBuilder layer1Buffer = new StringBuilder();
                List<FactBlock> factBlocks = new ArrayList<>();
                Flux<String> generatingMarker = Flux.just("__STEP__:generating");
                Flux<String> layer1Stream = factBlockEnabled
                    ? activeQueryClient.prompt()
                        .system(factSystemPrompt)
                        .user(question)
                        .toolContext(java.util.Map.of("sessionId", sessionId))
                        .stream()
                        .content()
                        .concatMap(chunk -> {
                            if (chunk == null) return Flux.empty();
                            lineBuffer.append(chunk);
                            List<String> lines = FactBlockParser.extractCompleteLines(lineBuffer);
                            List<String> out = new ArrayList<>();
                            for (String line : lines) {
                                FactBlock block = FactBlockParser.tryParse(line);
                                if (block != null) {
                                    factBlocks.add(block);
                                    out.add(QuerySseProtocol.FACT_PREFIX + line);
                                } else if (!line.isBlank()) {
                                    layer1Buffer.append(line).append('\n');
                                    out.add(line);
                                }
                            }
                            return Flux.fromIterable(out);
                        })
                        .concatWith(Flux.defer(() -> emitPendingLine(lineBuffer, layer1Buffer, factBlocks)))
                    : activeQueryClient.prompt()
                        .system(factSystemPrompt)
                        .user(question)
                        .toolContext(java.util.Map.of("sessionId", sessionId))
                        .stream()
                        .content()
                        .doOnNext(layer1Buffer::append);
                Flux<String> layer23Stream = Flux.defer(() -> {
                    log.info("Fact Agent completed: scopeId={} factBlocks={}", primaryScopeId, factBlocks.size());
                    String synthesisUserPrompt = buildSynthesisUserPrompt(primaryScopeId, question,
                        FactBlockParser.toFactInput(factBlocks, layer1Buffer.toString()), "", deepMode);
                    return synthesizeWithImages(primaryScopeId, synthesisUserPrompt, factBlocks, layer1Buffer.toString(), deepMode);
                });
                Flux<String> synthesisMarker = Flux.just("\n\n", "__STEP__:synthesizing");
                return Flux.concat(generatingMarker, layer1Stream, synthesisMarker, layer23Stream)
                    .onErrorResume(e -> {
                        log.error("Multi-scope stream failed: scopeIds={}, error={}", scopeIds, e.getMessage(), e);
                        recordError(sessionId, "multi-scope-stream", e.getMessage());
                        return Flux.just(runSimpleQuery(primaryScopeId, question));
                    });
            } catch (Exception e) {
                log.error("Multi-scope pre-retrieve failed: scopeIds={}, error={}", scopeIds, e.getMessage(), e);
                recordError(sessionId, "multi-scope-pre-retrieve", e.getMessage());
                return Flux.just(runSimpleQuery(primaryScopeId, question));
            }
        }).subscribeOn(Schedulers.boundedElastic())
        .doFinally(signal -> recordTurnEnd(sessionId, signal));
    }

    private void recordTurnStart(String sessionId, Long scopeId, String question, boolean deepMode, boolean multiScope) {
        todoTool.clear(sessionId);
        executionEventLog.append(sessionId, org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventTypes.TURN_START,
                java.util.Map.of(
                        "scopeId", scopeId,
                        "deepMode", deepMode,
                        "multiScope", multiScope,
                        "questionPreview", question == null ? "" : (question.length() > 120 ? question.substring(0, 120) : question)));
    }

    private void recordTurnEnd(String sessionId, reactor.core.publisher.SignalType signal) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("signal", signal.name());
        org.cn.liuwt.llmwiki.domain.service.harness.tool.TodoTool.Progress todoProgress = todoTool.progressOf(sessionId);
        if (todoProgress != null && todoProgress.total() > 0) {
            payload.put("todoDone", todoProgress.done());
            payload.put("todoTotal", todoProgress.total());
        }
        executionEventLog.append(sessionId, org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventTypes.TURN_END,
                payload);
    }

    private void recordError(String sessionId, String stage, String message) {
        executionEventLog.append(sessionId, org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventTypes.ERROR,
                java.util.Map.of("stage", stage, "message", message != null ? message : "unknown"));
    }

    private String runSimpleQuery(Long scopeId, String question) {
        if (LlmClient != null && LlmClient.isAvailable()) {
            String searchContext = retrieveSearchContext(scopeId, question);
            String systemPrompt = schemaInjector.prependForQuery(scopeId,
                PromptRegistry.forQuery().simpleSystemPrompt(scopeId, searchContext));
            String answer = LlmClient.chat(systemPrompt, question);
            return answer;
        }
        return "AI 服务未配置，无法回答问题。请设置 AI_DASHSCOPE_API_KEY 环境变量。";
    }

    public String buildSynthesisUserPrompt(Long scopeId, String question, String factInput, String deprecatedContext, boolean deepMode) {
        QueryPrompts registry = PromptRegistry.forQuery();
        return narrativeEnabled
            ? registry.narrativePrompt(scopeId, question, factInput, deprecatedContext, deepMode)
            : registry.synthesisPrompt(scopeId, question, factInput, deprecatedContext, deepMode);
    }

    private String buildClarifyPayload(QueryClarifier.ClarificationResult clarification) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("question", clarification.clarification());
            payload.put("options", clarification.options() != null ? clarification.options() : List.of());
            payload.put("assumedIntentId", clarification.options() != null && !clarification.options().isEmpty()
                ? clarification.options().get(0) : null);
            payload.put("reason", clarification.reason());
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"question\":\"\"}";
        }
    }

    private Flux<String> emitPendingLine(StringBuilder lineBuffer, StringBuilder layer1Buffer, List<FactBlock> factBlocks) {
        String rest = lineBuffer.toString().trim();
        if (rest.isEmpty()) return Flux.empty();
        FactBlock block = FactBlockParser.tryParse(rest);
        if (block != null) {
            factBlocks.add(block);
            return Flux.just(QuerySseProtocol.FACT_PREFIX + rest);
        }
        layer1Buffer.append(rest).append('\n');
        if (rest.startsWith("{")) return Flux.empty();
        return Flux.just(rest);
    }

    private static final Pattern IMG_EXTRACT_PATTERN = Pattern.compile("!\\[[^\\]]*?\\]\\(([^)]+?)\\)");

    private Flux<String> synthesizeWithImages(Long scopeId, String synthesisPrompt, List<FactBlock> factBlocks, String layer1Text, boolean deepMode) {
        ChatClient activeNoToolsClient = (deepMode && deepNoToolsClient != null) ? deepNoToolsClient : noToolsClient;
        
        if (chatModel == null) {
            return activeNoToolsClient.prompt()
                .system("你是知识分析与综合专家。")
                .user(synthesisPrompt)
                .stream()
                .content();
        }

        java.util.LinkedHashSet<String> mergedPaths = new java.util.LinkedHashSet<>();
        mergedPaths.addAll(extractImagePathsFromFactBlocks(factBlocks));
        mergedPaths.addAll(extractImagePaths(layer1Text));
        List<String> imagePaths = new ArrayList<>(mergedPaths);
        log.info("Image extraction: scopeId={} deepMode={} foundPaths={} factBlockCount={}",
            scopeId, deepMode, imagePaths.size(), factBlocks == null ? 0 : factBlocks.size());

        List<Media> mediaList = loadImagesAsMedia(String.valueOf(scopeId), imagePaths);
        log.info("Image loading: scopeId={} loadedCount={} (extracted={})", 
            scopeId, mediaList.size(), imagePaths.size());

        if (mediaList.isEmpty()) {
            log.info("No images loaded, using text-only synthesis: scopeId={} deepMode={}", scopeId, deepMode);
            return activeNoToolsClient.prompt()
                .system("你是知识分析与综合专家。")
                .user(synthesisPrompt)
                .stream()
                .content();
        }

        ChatModel multimodalModel = deepMode 
            ? LlmClient.getDeepMultimodalChatModel() 
            : LlmClient.getQueryMultimodalChatModel();
        ChatModel fallbackModel = (deepMode && deepNoToolsClient != null) 
            ? LlmClient.getDeepAnalysisChatModel() : chatModel;
        if (fallbackModel == null) fallbackModel = chatModel;
        ChatModel synthesisModel = multimodalModel != null ? multimodalModel : fallbackModel;

        try {
            log.info("Synthesis with multimodal images: scopeId={} imageCount={} deepMode={} multimodalModel={} synthesisModel={}",
                scopeId, mediaList.size(), deepMode,
                multimodalModel != null ? "available" : "null",
                synthesisModel.getClass().getSimpleName());
            Media[] mediaArray = mediaList.toArray(new Media[0]);
            UserMessage userMsg = UserMessage.builder()
                .text(synthesisPrompt)
                .media(mediaArray)
                .build();
            Prompt prompt = new Prompt(new SystemMessage("你是知识分析与综合专家。"), userMsg);

            Flux<ChatResponse> chatStream = synthesisModel.stream(prompt);
            return chatStream
                .filter(resp -> resp.getResult() != null && resp.getResult().getOutput() != null)
                .mapNotNull(resp -> {
                    Object content = resp.getResult().getOutput().getText();
                    if (content == null) return null;
                    String text = String.valueOf(content);
                    return text.isEmpty() ? null : text;
                });
        } catch (Exception e) {
            log.warn("Multimodal synthesis failed, falling back to text-only: scopeId={}, error={}", scopeId, e.getMessage());
            return activeNoToolsClient.prompt()
                .system("你是知识分析与综合专家。")
                .user(synthesisPrompt)
                .stream()
                .content();
        }
    }

    private static final int MAX_QUERY_IMAGES = 10;
    private static final int MAX_SINGLE_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_BYTES = 20 * 1024 * 1024;

    private List<String> extractImagePathsFromFactBlocks(List<FactBlock> factBlocks) {
        List<String> paths = new ArrayList<>();
        if (factBlocks == null) return paths;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (FactBlock block : factBlocks) {
            if (!"image".equals(block.kind())) continue;
            if (block.refs() == null) continue;
            for (FactBlock.FactRef ref : block.refs()) {
                String path = ref.path();
                if (path != null && !path.isBlank() && !path.startsWith("http") && !path.startsWith("data:")) {
                    seen.add(path);
                }
            }
        }
        paths.addAll(seen);
        return paths;
    }

    private List<String> extractImagePaths(String text) {
        List<String> paths = new ArrayList<>();
        if (text == null || !text.contains("![")) return paths;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        Matcher m = IMG_EXTRACT_PATTERN.matcher(text);
        while (m.find() && seen.size() < MAX_QUERY_IMAGES) {
            String path = m.group(1);
            if (path != null && !path.isBlank() && !path.startsWith("http") && !path.startsWith("data:")) {
                seen.add(path);
            }
        }
        paths.addAll(seen);
        return paths;
    }

    private List<Media> loadImagesAsMedia(String scopeId, List<String> imagePaths) {
        List<Media> result = new ArrayList<>();
        long totalBytes = 0;
        for (String refPath : imagePaths) {
            try {
                String storagePath = resolveImageStoragePath(refPath);
                byte[] bytes = storageProvider.read(scopeId, storagePath);
                if (bytes == null || bytes.length == 0) continue;
                if (bytes.length > MAX_SINGLE_IMAGE_BYTES) {
                    log.warn("Skipping oversized image for query: path={} size={}KB limit={}KB", storagePath, bytes.length / 1024, MAX_SINGLE_IMAGE_BYTES / 1024);
                    continue;
                }
                if (totalBytes + bytes.length > MAX_TOTAL_IMAGE_BYTES) {
                    log.warn("Total image payload limit reached for query: loaded={}KB remaining images skipped", totalBytes / 1024);
                    break;
                }
                totalBytes += bytes.length;
                MimeType mimeType = detectMimeType(storagePath);
                Media media = new Media(mimeType, new ByteArrayResource(bytes));
                result.add(media);
                log.debug("Loaded image for query synthesis: path={} size={}KB", storagePath, bytes.length / 1024);
                if (result.size() >= MAX_QUERY_IMAGES) break;
            } catch (Exception e) {
                log.debug("Failed to load image for query: path={}, error={}", refPath, e.getMessage());
            }
        }
        if (!result.isEmpty()) {
            log.info("Query image payload: count={} totalKB={}", result.size(), totalBytes / 1024);
        }
        return result;
    }

    private String resolveImageStoragePath(String refPath) {
        String p = refPath.replace('\\', '/').trim();
        while (p.startsWith("../") || p.startsWith("./")) {
            p = p.substring(p.indexOf('/') + 1);
        }
        if (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    private MimeType detectMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return MimeType.valueOf("image/png");
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MimeType.valueOf("image/jpeg");
        if (lower.endsWith(".gif")) return MimeType.valueOf("image/gif");
        if (lower.endsWith(".webp")) return MimeType.valueOf("image/webp");
        if (lower.endsWith(".svg")) return MimeType.valueOf("image/svg+xml");
        return MimeTypeUtils.APPLICATION_OCTET_STREAM;
    }

    private String retrieveSearchContext(Long scopeId, String question) {
        StringBuilder context = new StringBuilder();
        try {
            List<SearchResultInfo> searchResults = searchService.search(scopeId, question, null);
            int fullReadLimit = Math.min(3, searchResults.size());
            for (int i = 0; i < fullReadLimit; i++) {
                SearchResultInfo r = searchResults.get(i);
                String dbPath = r.getPath();
                String storagePath = dbPath.startsWith("pages/") ? "wiki/" + dbPath : dbPath;
                String fullContent = null;
                try {
                    byte[] bytes = storageProvider.read(String.valueOf(scopeId), storagePath);
                    if (bytes != null) {
                        fullContent = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        if (fullContent.length() > 4000) {
                            fullContent = fullContent.substring(0, 4000) + "\n...（内容截断）";
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to read full content for page {}: {}", dbPath, e.getMessage());
                }

                context.append("### [").append(r.getTitle()).append("] (").append(dbPath).append(", ").append(r.getCategory()).append(")\n");
                if (fullContent != null && !fullContent.isBlank()) {
                    context.append(fullContent).append("\n\n");
                } else {
                    String summary = r.getSummary() != null ? r.getSummary() : "无摘要";
                    context.append("摘要: ").append(summary);
                    if (r.getHighlightedContent() != null && !r.getHighlightedContent().isEmpty()) {
                        context.append("\n匹配片段: ").append(r.getHighlightedContent().get(0));
                    }
                    context.append("\n\n");
                }
            }
            for (int i = fullReadLimit; i < Math.min(5, searchResults.size()); i++) {
                SearchResultInfo r = searchResults.get(i);
                context.append("- **").append(r.getTitle()).append("** (")
                    .append(r.getPath()).append(", ").append(r.getCategory()).append("): ")
                    .append(r.getSummary() != null ? r.getSummary() : "无摘要").append("\n");
            }
            if (searchResults.isEmpty()) {
                context.append("（无匹配页面）\n");
            }
        } catch (Exception e) {
            log.error("Failed to search Wiki for context: {}", e.getMessage(), e);
            context.append("（搜索失败）\n");
        }
        return context.toString();
    }
}
