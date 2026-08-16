package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionEventDO;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventDeduplicator;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionLogEvent;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/harness/execution-events")
public class ExecutionEventController {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEventController.class);

    @Autowired
    private ExecutionEventLogService eventLogService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private final ConcurrentHashMap<String, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();

    private record Subscriber(SseEmitter emitter, ExecutionEventDeduplicator deduplicator) {}

    @GetMapping("/{executionId}")
    public Result<List<ExecutionEventDO>> listEvents(@PathVariable String executionId,
                                                     @RequestParam(required = false, defaultValue = "0") long afterSeq) {
        jwtTokenProvider.getCurrentScopeId();
        return Result.success(eventLogService.replayAfter(executionId, afterSeq));
    }

    @GetMapping("/{executionId}/stream")
    public SseEmitter streamEvents(@PathVariable String executionId,
                                   @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId,
                                   @RequestParam(required = false, defaultValue = "0") long afterSeq) {
        jwtTokenProvider.getCurrentScopeId();
        long resumeSeq = lastEventId != null ? lastEventId : afterSeq;
        SseEmitter emitter = new SseEmitter(300000L);
        ExecutionEventDeduplicator deduplicator = new ExecutionEventDeduplicator();

        for (ExecutionEventDO event : eventLogService.replayAfter(executionId, resumeSeq)) {
            if (deduplicator.admit(executionId, event.getSeq())) {
                sendQuietly(emitter, event);
            }
        }

        Subscriber subscriber = new Subscriber(emitter, deduplicator);
        subscribers.computeIfAbsent(executionId, id -> ConcurrentHashMap.newKeySet()).add(subscriber);
        emitter.onCompletion(() -> removeSubscriber(executionId, subscriber));
        emitter.onTimeout(() -> removeSubscriber(executionId, subscriber));
        emitter.onError(e -> removeSubscriber(executionId, subscriber));
        return emitter;
    }

    @EventListener
    public void onExecutionLogEvent(ExecutionLogEvent logEvent) {
        ExecutionEventDO event = logEvent.getEvent();
        Set<Subscriber> listeners = subscribers.get(event.getExecutionId());
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (Subscriber subscriber : listeners) {
            if (subscriber.deduplicator().admit(event.getExecutionId(), event.getSeq())) {
                sendQuietly(subscriber.emitter(), event);
            }
        }
    }

    private void sendQuietly(SseEmitter emitter, ExecutionEventDO event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.getSeq()))
                    .name(event.getEventType())
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("Execution event SSE send failed: executionId={}, seq={}, error={}",
                    event.getExecutionId(), event.getSeq(), e.getMessage());
        }
    }

    private void removeSubscriber(String executionId, Subscriber subscriber) {
        Set<Subscriber> listeners = subscribers.get(executionId);
        if (listeners != null) {
            listeners.remove(subscriber);
            if (listeners.isEmpty()) {
                subscribers.remove(executionId, listeners);
            }
        }
    }
}
