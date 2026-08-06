package org.cn.liuwt.llmwiki.integration.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final ThreadLocal<TokenCallUsage> lastCallUsage = new ThreadLocal<>();

    public interface StreamUsageRecorder {
        void record(Long scopeId, String operationType, int estimatedOutputTokens);
    }
    private static volatile StreamUsageRecorder streamUsageRecorder;

    public static void setStreamUsageRecorder(StreamUsageRecorder recorder) {
        streamUsageRecorder = recorder;
    }

    public record TokenCallUsage(int inputTokens, int outputTokens) {
        public int total() { return inputTokens + outputTokens; }
    }

    public static TokenCallUsage getAndClearLastUsage() {
        TokenCallUsage u = lastCallUsage.get();
        lastCallUsage.remove();
        return u;
    }

    public static void setEstimatedUsage(int estimatedOutputTokens) {
        lastCallUsage.set(new TokenCallUsage(0, estimatedOutputTokens));
    }

    private ChatClient chatClient;

    private static final String PLACEHOLDER_KEY = "placeholder-not-configured";

    private boolean apiKeyValid;

    @Value("${llmwiki.ai.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${llmwiki.ai.retry.base-delay-ms:2000}")
    private long retryBaseDelayMs;

    @Value("${llmwiki.ai.timeout-ms:60000}")
    private long timeoutMs;

    @Value("${llmwiki.llm.executor.core-size:8}")
    private int executorCoreSize;

    @Value("${llmwiki.llm.executor.max-size:12}")
    private int executorMaxSize;

    private final ExecutorService llmExecutor = new ThreadPoolExecutor(
        8, 12, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(100),
        new LlmThreadFactory("llm-call")
    );

    @Autowired
    private AiSlotRouter slotRouter;

    @Autowired(required = false)
    private ContextBudgetManager contextBudgetManager;

    @Autowired(required = false)
    public void setChatModel(ChatModel chatModel) {
        if (chatModel != null) {
            this.legacyChatModel = chatModel;
        }
    }

    private String apiKey;

    @Value("${spring.ai.openai.api-key:}")
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        this.apiKeyValid = StringUtils.hasText(apiKey) && !PLACEHOLDER_KEY.equals(apiKey);
    }

    @Value("${spring.ai.openai.chat.options.model:}")
    private String modelName;

    @Value("${llmwiki.ai.multimodal-model:${spring.ai.openai.chat.options.model:}}")
    private String multimodalModelName;

    @Value("${llmwiki.ai.query-multimodal-model:${llmwiki.ai.multimodal-model:${spring.ai.openai.chat.options.model:}}}")
    private String queryMultimodalModelName;

    @Value("${llmwiki.ai.deep-analysis-model:${spring.ai.openai.chat.options.model:}}")
    private String deepAnalysisModelName;

    @Value("${llmwiki.ai.deep-multimodal-model:${llmwiki.ai.multimodal-model:${spring.ai.openai.chat.options.model:}}}")
    private String deepMultimodalModelName;

    @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String baseUrl;

    private ChatModel legacyChatModel;
    private ChatModel queryMultimodalChatModel;
    private ChatModel deepAnalysisChatModel;
    private ChatModel deepMultimodalChatModel;

    @PostConstruct
    public void init() {
        if (executorCoreSize != 8 || executorMaxSize != 12) {
            ((ThreadPoolExecutor) llmExecutor).setCorePoolSize(executorCoreSize);
            ((ThreadPoolExecutor) llmExecutor).setMaximumPoolSize(executorMaxSize);
        }

        if (slotRouter.isMultiProviderMode()) {
            initMultiProvider();
        } else {
            initLegacy();
        }
    }

    private void initMultiProvider() {
        ChatModel mainModel = slotRouter.getModel("main");
        if (mainModel != null) {
            this.chatClient = ChatClient.builder(mainModel).build();
            this.apiKeyValid = true;
            log.info("LlmClient initialized in multi-provider mode, main slot active");
        } else {
            log.warn("Multi-provider mode enabled but 'main' slot has no available provider");
        }

        this.queryMultimodalChatModel = slotRouter.getModel("query-multimodal");
        this.deepAnalysisChatModel = slotRouter.getModel("deep-analysis");
        this.deepMultimodalChatModel = slotRouter.getModel("deep-multimodal");

        if (queryMultimodalChatModel != null) log.info("Slot 'query-multimodal' active");
        if (deepAnalysisChatModel != null) log.info("Slot 'deep-analysis' active");
        if (deepMultimodalChatModel != null) log.info("Slot 'deep-multimodal' active");
    }

    private void initLegacy() {
        if (legacyChatModel != null) {
            this.chatClient = ChatClient.builder(legacyChatModel).build();
        }

        if (legacyChatModel instanceof OpenAiChatModel openAiModel) {
            if (queryMultimodalModelName != null && !queryMultimodalModelName.equals(modelName)) {
                this.queryMultimodalChatModel = openAiModel.mutate()
                    .defaultOptions(OpenAiChatOptions.builder().model(queryMultimodalModelName).build())
                    .build();
                log.info("Query multimodal ChatModel created: model={}", queryMultimodalModelName);
            }
            if (deepAnalysisModelName != null && !deepAnalysisModelName.equals(modelName)) {
                this.deepAnalysisChatModel = openAiModel.mutate()
                    .defaultOptions(OpenAiChatOptions.builder().model(deepAnalysisModelName).build())
                    .build();
                log.info("Deep analysis ChatModel created: model={}", deepAnalysisModelName);
            }
            if (deepMultimodalModelName != null && !deepMultimodalModelName.equals(modelName)) {
                this.deepMultimodalChatModel = openAiModel.mutate()
                    .defaultOptions(OpenAiChatOptions.builder().model(deepMultimodalModelName).build())
                    .build();
                log.info("Deep multimodal ChatModel created: model={}", deepMultimodalModelName);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down llmExecutor");
        llmExecutor.shutdown();
        try {
            if (!llmExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                llmExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            llmExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isAvailable() {
        return chatClient != null && apiKeyValid;
    }

    public ChatModel getQueryMultimodalChatModel() {
        return queryMultimodalChatModel;
    }

    public ChatModel getDeepAnalysisChatModel() {
        return deepAnalysisChatModel;
    }

    public ChatModel getDeepMultimodalChatModel() {
        return deepMultimodalChatModel;
    }

    public AiSlotRouter getSlotRouter() {
        return slotRouter;
    }

    public String chat(String systemPrompt, String userMessage) {
        return chatWithRetry(systemPrompt, userMessage);
    }

    public String chat(String userMessage) {
        return chatWithRetry(null, userMessage);
    }

    public Flux<String> streamChat(String systemPrompt, String userMessage) {
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient not available");
        }
        java.util.concurrent.atomic.AtomicInteger charCount = new java.util.concurrent.atomic.AtomicInteger(0);
        TokenUsageContext.Context ctx = TokenUsageContext.get();
        return chatClient.prompt()
            .system(systemPrompt)
            .user(userMessage)
            .stream()
            .content()
            .doOnNext(chunk -> charCount.addAndGet(chunk != null ? chunk.length() : 0))
            .doOnComplete(() -> {
                int est = Math.max(1, charCount.get() / 2);
                setEstimatedUsage(est);
                if (streamUsageRecorder != null && ctx != null && ctx.scopeId() != null) {
                    streamUsageRecorder.record(ctx.scopeId(), ctx.operationType(), est);
                }
            });
    }

    public Flux<String> streamChat(String userMessage) {
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient not available");
        }
        java.util.concurrent.atomic.AtomicInteger charCount = new java.util.concurrent.atomic.AtomicInteger(0);
        TokenUsageContext.Context ctx = TokenUsageContext.get();
        return chatClient.prompt()
            .user(userMessage)
            .stream()
            .content()
            .doOnNext(chunk -> charCount.addAndGet(chunk != null ? chunk.length() : 0))
            .doOnComplete(() -> {
                int est = Math.max(1, charCount.get() / 2);
                setEstimatedUsage(est);
                if (streamUsageRecorder != null && ctx != null && ctx.scopeId() != null) {
                    streamUsageRecorder.record(ctx.scopeId(), ctx.operationType(), est);
                }
            });
    }

    public String chatMultimodal(String systemPrompt, String userText, List<MultimodalImageInput> images) {
        return chatWithRetryMultimodal(systemPrompt, userText, images);
    }

    public String chatMultimodal(String userText, List<MultimodalImageInput> images) {
        return chatWithRetryMultimodal(null, userText, images);
    }

    private record ChatCallResult(String content, int inputTokens, int outputTokens) {}

    private ContextBudgetManager.ContextBudgetResult applyBudgetSafely(String systemPrompt, String userMessage) {
        if (contextBudgetManager == null) return null;
        try {
            int maxTokens = contextBudgetManager.getMaxTokensForCurrentContext();
            return contextBudgetManager.enforceBudget(systemPrompt, userMessage, maxTokens);
        } catch (Exception e) {
            log.warn("ContextBudgetManager check failed (non-blocking), using original prompts: {}", e.getMessage());
            return null;
        }
    }

    private String chatWithRetry(String systemPrompt, String userMessage) {
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient not available");
        }

        ContextBudgetManager.ContextBudgetResult budgetResult = applyBudgetSafely(systemPrompt, userMessage);
        String effectiveSystemPrompt = budgetResult != null ? budgetResult.systemPrompt() : systemPrompt;
        String effectiveUserMessage = budgetResult != null ? budgetResult.userMessage() : userMessage;

        Throwable lastException = null;
        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            CompletableFuture<ChatCallResult> future = null;
            try {
                future = CompletableFuture.supplyAsync(() -> {
                    ChatClient.CallResponseSpec callResp;
                    if (effectiveSystemPrompt != null) {
                        callResp = chatClient.prompt()
                            .system(effectiveSystemPrompt)
                            .user(effectiveUserMessage)
                            .call();
                    } else {
                        callResp = chatClient.prompt()
                            .user(effectiveUserMessage)
                            .call();
                    }
                    String content = callResp.content();
                    int inTokens = 0;
                    int outTokens = 0;
                    try {
                        ChatResponse chatResponse = callResp.chatResponse();
                        if (chatResponse != null && chatResponse.getMetadata() != null) {
                            Usage usage = chatResponse.getMetadata().getUsage();
                            if (usage != null) {
                                inTokens = (int) usage.getPromptTokens();
                                outTokens = (int) usage.getCompletionTokens();
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Failed to extract usage from ChatResponse: {}", e.getMessage());
                    }
                    return new ChatCallResult(content, inTokens, outTokens);
                }, llmExecutor);

                ChatCallResult callResult = future.get(timeoutMs, TimeUnit.MILLISECONDS);

                if (callResult != null && callResult.content() != null && !callResult.content().isEmpty()) {
                    int inTokens = callResult.inputTokens();
                    int outTokens = callResult.outputTokens();
                    if (inTokens == 0 && outTokens == 0) {
                        outTokens = Math.max(1, callResult.content().length() / 2);
                        String promptText = (effectiveSystemPrompt != null ? effectiveSystemPrompt : "") + (effectiveUserMessage != null ? effectiveUserMessage : "");
                        inTokens = Math.max(1, promptText.length() / 2);
                        log.debug("Usage fallback estimate: in={}, out={} (real usage not available from API)", inTokens, outTokens);
                    }
                    lastCallUsage.set(new TokenCallUsage(inTokens, outTokens));
                    return callResult.content();
                }

                lastException = new RuntimeException("LLM returned empty response on attempt " + attempt);
            } catch (TimeoutException e) {
                cancelFuture(future);
                lastException = e;
                log.warn("LLM call timed out (attempt {}/{}, timeout={}ms): {}",
                    attempt, maxRetryAttempts, timeoutMs, e.getMessage());
            } catch (ExecutionException e) {
                lastException = e.getCause() != null ? e.getCause() : e;
                if (isRetryable(lastException)) {
                    long delay = retryBaseDelayMs * (1L << (attempt - 1));
                    log.warn("LLM call failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt, maxRetryAttempts, delay, lastException.getMessage());
                    try {
                        CompletableFuture<Void> delayFuture = CompletableFuture.runAsync(
                            () -> {
                                try {
                                    TimeUnit.MILLISECONDS.sleep(delay);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }, llmExecutor
                        );
                        delayFuture.get(delay + 5000, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    } catch (TimeoutException | ExecutionException de) {
                        log.warn("Retry delay execution failed, proceeding immediately: {}", de.getMessage());
                    }
                } else {
                    throw new RuntimeException("LLM call failed", lastException);
                }
            } catch (InterruptedException e) {
                cancelFuture(future);
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM call interrupted", e);
            }
        }

        throw new RuntimeException("LLM call failed after " + maxRetryAttempts + " attempts", lastException);
    }

    private boolean isRetryable(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) return false;

        if (msg.contains("429") || msg.contains("rate") || msg.contains("limit")
            || msg.contains("503") || msg.contains("502")
            || msg.contains("timeout") || msg.contains("Timeout")
            || msg.contains("connection") || msg.contains("Connection")
            || msg.contains("SocketTimeoutException") || msg.contains("ConnectException")
            || msg.contains("ConnectionPoolTimeoutException")) {
            return true;
        }

        if (msg.contains("401") || msg.contains("403") || msg.contains("Unauthorized")
            || msg.contains("Forbidden") || msg.contains("Authentication")
            || msg.contains("IllegalArgument") || msg.contains("invalid_request")
            || msg.contains("Empty response")) {
            return false;
        }

        return false;
    }

    private void cancelFuture(CompletableFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private String chatWithRetryMultimodal(String systemPrompt, String userText, List<MultimodalImageInput> images) {
        if (!isAvailable()) {
            throw new IllegalStateException("ChatClient not available or API key invalid");
        }

        ContextBudgetManager.ContextBudgetResult budgetResult = applyBudgetSafely(systemPrompt, userText);
        String effectiveSystemPrompt = budgetResult != null ? budgetResult.systemPrompt() : systemPrompt;
        String effectiveUserText = budgetResult != null ? budgetResult.userMessage() : userText;

        AiSlotRouter.Endpoint endpoint = slotRouter.getEndpoint("multimodal");
        String resolvedApiKey = endpoint.apiKey();
        String chatUrl = endpoint.baseUrl() + "/v1/chat/completions";
        String resolvedModel = StringUtils.hasText(endpoint.model()) ? endpoint.model() : multimodalModelName;

        Throwable lastException = null;
        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            CompletableFuture<ChatCallResult> future = null;
            try {
                future = CompletableFuture.supplyAsync(() -> {
                    try {
                        StringBuilder contentArray = new StringBuilder();
                        contentArray.append("\"content\":[");
                        contentArray.append("{\"type\":\"text\",\"text\":").append(jsonEscape(effectiveUserText)).append("}");
                        for (MultimodalImageInput img : images) {
                            contentArray.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:")
                                .append(img.mimeType()).append(";base64,").append(img.base64DataUrl()).append("\"}}");
                        }
                        contentArray.append("]");

                        StringBuilder messages = new StringBuilder("[");
                        if (effectiveSystemPrompt != null) {
                            messages.append("{\"role\":\"system\",\"content\":").append(jsonEscape(effectiveSystemPrompt)).append("},");
                        }
                        messages.append("{\"role\":\"user\",").append(contentArray.toString()).append("}");
                        messages.append("]");

                        String body = "{\"model\":\"" + resolvedModel + "\",\"messages\":" + messages.toString()
                            + ",\"max_tokens\":4096}";

                        HttpURLConnection conn = (HttpURLConnection) URI.create(chatUrl).toURL().openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setConnectTimeout((int) Math.min(timeoutMs, 30000));
                        conn.setReadTimeout((int) timeoutMs);
                        conn.setRequestProperty("Authorization", "Bearer " + resolvedApiKey);
                        conn.setRequestProperty("Content-Type", "application/json");

                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(body.getBytes(StandardCharsets.UTF_8));
                        }

                        int status = conn.getResponseCode();
                        java.io.InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                        String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                        if (status >= 400) {
                            throw new RuntimeException("LLM multimodal HTTP " + status + ": "
                                + (response.length() > 500 ? response.substring(0, 500) : response));
                        }

                        return extractCallResultFromResponse(response);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException("LLM multimodal call failed: " + e.getMessage(), e);
                    }
                }, llmExecutor);

                ChatCallResult callResult = future.get(timeoutMs, TimeUnit.MILLISECONDS);

                if (callResult != null && callResult.content() != null && !callResult.content().isEmpty()) {
                    int inTokens = callResult.inputTokens();
                    int outTokens = callResult.outputTokens();
                    if (inTokens == 0 && outTokens == 0) {
                        outTokens = Math.max(1, callResult.content().length() / 2);
                        String promptText = (systemPrompt != null ? systemPrompt : "") + (userText != null ? userText : "");
                        inTokens = Math.max(1, promptText.length() / 2);
                        log.debug("Multimodal usage fallback estimate: in={}, out={}", inTokens, outTokens);
                    }
                    lastCallUsage.set(new TokenCallUsage(inTokens, outTokens));
                    return callResult.content();
                }

                lastException = new RuntimeException("LLM returned empty multimodal response on attempt " + attempt);
            } catch (TimeoutException e) {
                cancelFuture(future);
                lastException = e;
                log.warn("LLM multimodal call timed out (attempt {}/{}, timeout={}ms)", attempt, maxRetryAttempts, timeoutMs);
            } catch (ExecutionException e) {
                lastException = e.getCause() != null ? e.getCause() : e;
                if (isRetryable(lastException)) {
                    long delay = retryBaseDelayMs * (1L << (attempt - 1));
                    log.warn("LLM multimodal call failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt, maxRetryAttempts, delay, lastException.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                } else {
                    throw new RuntimeException("LLM multimodal call failed", lastException);
                }
            } catch (InterruptedException e) {
                cancelFuture(future);
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM multimodal call interrupted", e);
            }
        }

        throw new RuntimeException("LLM multimodal call failed after " + maxRetryAttempts + " attempts", lastException);
    }

    private String jsonEscape(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private ChatCallResult extractCallResultFromResponse(String response) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response);
            String content = root.path("choices").get(0).path("message").path("content").asText();
            int inTokens = 0;
            int outTokens = 0;
            com.fasterxml.jackson.databind.JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode()) {
                inTokens = usageNode.path("prompt_tokens").asInt(0);
                outTokens = usageNode.path("completion_tokens").asInt(0);
            }
            return new ChatCallResult(content, inTokens, outTokens);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse multimodal response: " + e.getMessage(), e);
        }
    }

    public record MultimodalImageInput(String mimeType, String base64DataUrl) {}

    static class LlmThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String prefix;

        LlmThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(false);
            t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("Uncaught exception in thread {}: {}", thread.getName(), ex.getMessage(), ex)
            );
            return t;
        }
    }
}
