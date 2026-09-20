package org.cn.liuwt.llmwiki.integration.ai;

import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiConnectionTestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class AiConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(AiConnectionTester.class);

    private static final long TIMEOUT_SECONDS = 10;
    private static final int MAX_MESSAGE_LEN = 200;
    private static final RetryTemplate NO_RETRY = RetryTemplate.builder()
        .maxAttempts(1)
        .fixedBackoff(1)
        .build();

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-connection-test");
        t.setDaemon(true);
        return t;
    });

    public AiConnectionTestResult test(String baseUrl, String apiKey, String model) {
        long start = System.currentTimeMillis();
        String normalizedBaseUrl = AiBaseUrlNormalizer.normalize(baseUrl);
        try {
            CompletableFuture.supplyAsync(() -> doCall(normalizedBaseUrl, apiKey, model), executor)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;
            return new AiConnectionTestResult(true, elapsed, "ok");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.debug("AI connection test failed: baseUrl={}, model={}", normalizedBaseUrl, model, e);
            return new AiConnectionTestResult(false, elapsed, converge(e));
        }
    }

    private Boolean doCall(String baseUrl, String apiKey, String model) {
        if (model == null || model.isBlank()) {
            return pingModelsEndpoint(baseUrl, apiKey);
        }
        OpenAiApi api = OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .maxTokens(1)
            .model(model)
            .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .retryTemplate(NO_RETRY)
            .build();
        chatModel.call("ping");
        return Boolean.TRUE;
    }

    private Boolean pingModelsEndpoint(String baseUrl, String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(5));
        factory.setReadTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        RestClient restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
            .requestFactory(factory)
            .build();
        restClient.get().uri("/v1/models").retrieve().toBodilessEntity();
        return Boolean.TRUE;
    }

    private static String converge(Throwable e) {
        Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
        String msg = cause.getMessage() == null ? "" : cause.getMessage();
        if (msg.length() > MAX_MESSAGE_LEN) {
            msg = msg.substring(0, MAX_MESSAGE_LEN);
        }
        return cause.getClass().getSimpleName() + ": " + msg;
    }
}
