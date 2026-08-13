package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.service.harness.query.QuerySseProtocol;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.facade.model.SaveAnswerRequest;
import org.cn.liuwt.llmwiki.facade.model.WikiPageInfo;
import org.cn.liuwt.llmwiki.service.query.FunFactService;
import org.cn.liuwt.llmwiki.service.query.QueryService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    @Autowired
    private QueryService queryService;

    @Autowired
    private FunFactService funFactService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private WikiFileServiceImpl wikiFileService;

    @Autowired
    private ScopeService scopeService;

    @Value("${llmwiki.query.fact-block.enabled:true}")
    private boolean factBlockEnabled;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuery(@RequestParam String question,
                                   @RequestParam(required = false) String sessionId,
                                   @RequestParam(required = false, defaultValue = "quick") String mode,
                                   @RequestParam(required = false) String scopeIds) {
        if (question == null || question.trim().isEmpty()) {
            SseEmitter emitter = new SseEmitter(5000L);
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", "问题不能为空")));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        List<Long> resolvedScopeIds = resolveScopeIds(scopeIds, scopeId);
        String sessionKey = sessionId != null ? sessionId : "query-" + scopeId + "-" + System.currentTimeMillis();
        boolean isDeepMode = "deep".equalsIgnoreCase(mode);

        SseEmitter emitter = new SseEmitter(isDeepMode ? 600000L : 300000L);
        emitters.put(sessionKey, emitter);

        emitter.onCompletion(() -> emitters.remove(sessionKey));
        emitter.onTimeout(() -> emitters.remove(sessionKey));
        emitter.onError(e -> emitters.remove(sessionKey));

        try {
            emitter.send(SseEmitter.event().name("start").data(Map.of("sessionId", sessionKey)));
            emitter.send(SseEmitter.event().name("mode").data(Map.of("mode", "tool-calling")));
            emitter.send(SseEmitter.event().name("step").data(Map.of("step", "retrieving")));
        } catch (Exception e) {
            log.debug("SSE initial send failed: {}", e.getMessage());
            emitter.completeWithError(e);
            return emitter;
        }

        CompletableFuture<List<FunFactService.FunFact>> funFactsFuture = funFactService.generateFunFactsAsync(question);
        funFactsFuture.thenAccept(facts -> {
            if (!facts.isEmpty()) {
                try {
                    List<Map<String, String>> factsData = facts.stream()
                        .map(f -> Map.of("icon", f.icon(), "text", f.text()))
                        .toList();
                    emitter.send(SseEmitter.event().name("fun-facts").data(Map.of("facts", factsData)));
                } catch (Exception e) {
                    log.debug("Failed to send fun-facts event: {}", e.getMessage());
                }
            }
        });

        Flux<String> answerStream = resolvedScopeIds.size() == 1
            ? queryService.queryWikiStreaming(resolvedScopeIds.get(0), question, sessionKey, isDeepMode)
            : queryService.queryWikiStreamingMultiScope(resolvedScopeIds, question, sessionKey, isDeepMode);

        answerStream
            .doOnNext(chunk -> {
                try {
                    QuerySseProtocol.SseEvent event = QuerySseProtocol.mapChunk(chunk, factBlockEnabled);
                    if ("fact-block".equals(event.eventName())) {
                        emitter.send(SseEmitter.event().name("fact-block").data(event.payload()));
                    } else if ("step".equals(event.eventName())) {
                        emitter.send(SseEmitter.event().name("step").data(Map.of("step", event.payload())));
                    } else {
                        emitter.send(SseEmitter.event().name("answer-chunk").data(Map.of("content", event.payload())));
                    }
                } catch (Exception e) {
                    log.debug("SSE send failed: {}", e.getMessage());
                    throw new RuntimeException(e);
                }
            })
            .doOnComplete(() -> {
                try {
                    emitter.send(SseEmitter.event().name("answer-complete").data(Map.of("totalTokens", 0)));
                    emitter.complete();
                } catch (Exception e) {
                    log.error("SSE complete send failed", e);
                    emitter.completeWithError(e);
                }
                emitters.remove(sessionKey);
            })
            .doOnError(e -> {
                log.error("Query SSE stream failed", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message",
                        e.getMessage() != null ? e.getMessage() : "AI 问答暂时不可用")));
                } catch (Exception ignored) {}
                emitters.remove(sessionKey);
            })
            .subscribe();

        return emitter;
    }

    @PostMapping("/save")
    public Result<WikiPageInfo> saveAnswer(@RequestBody SaveAnswerRequest request) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        try {
            WikiPageDO pageDO = queryService.saveAnswerToWiki(scopeId, request.getQuestion(), request.getAnswer(), request.getSessionId());
            WikiPageInfo info = new WikiPageInfo();
            info.setId(pageDO.getId());
            info.setTitle(pageDO.getTitle());
            info.setPath(pageDO.getFilePath());
            info.setCategory(pageDO.getCategory());
            info.setSummary(pageDO.getSummary());
            info.setScopeId(pageDO.getScopeId());
            info.setSourceCount(pageDO.getSourceCount());
            info.setHealthStatus(pageDO.getHealthStatus());
            return Result.success(info);
        } catch (Exception e) {
            log.error("Failed to save answer to wiki", e);
            return Result.failed(ErrorCode.QUERY_SAVE_FAILED, e.getMessage());
        }
    }

    @PostMapping("/resolve-links")
    public Result<Map<String, Long>> resolveLinks(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        if (content == null || content.isEmpty()) {
            return Result.success(Map.of());
        }
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        Map<String, Long> resolution = wikiFileService.resolveWikiLinks(content, scopeId);
        return Result.success(resolution);
    }

    private List<Long> resolveScopeIds(String scopeIdsParam, Long fallbackScopeId) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (scopeIdsParam == null || scopeIdsParam.isBlank()) {
            return List.of(fallbackScopeId);
        }
        List<Long> ids = Arrays.stream(scopeIdsParam.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(Long::parseLong)
            .toList();
        for (Long id : ids) {
            if (!scopeService.canView(id, userId)) {
                throw new BusinessException(ErrorCode.AUTH_ACCESS_DENIED);
            }
        }
        return ids.isEmpty() ? List.of(fallbackScopeId) : ids;
    }
}
