package org.cn.liuwt.llmwiki.domain.service.harness.eventlog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionEventDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ExecutionEventLogService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEventLogService.class);

    private final ExecutionEventMapper eventMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final EventLogProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();

    @Value("${spring.application.name:local-node}")
    private String nodeId;

    public ExecutionEventLogService(ExecutionEventMapper eventMapper,
                                    ApplicationEventPublisher eventPublisher,
                                    EventLogProperties properties) {
        this.eventMapper = eventMapper;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    public boolean append(String executionId, String eventType, Map<String, ?> payload) {
        if (!properties.isEnabled() || executionId == null || executionId.isBlank()) {
            return false;
        }
        try {
            ExecutionEventDO eventDO = new ExecutionEventDO();
            eventDO.setExecutionId(executionId);
            eventDO.setSeq((int) nextSeq(executionId));
            eventDO.setEventType(eventType);
            eventDO.setPayloadJson(toJson(payload));
            eventDO.setNodeId(nodeId);
            eventDO.setCreatedAt(LocalDateTime.now());
            eventMapper.insert(eventDO);
            eventPublisher.publishEvent(new ExecutionLogEvent(this, eventDO));
            return true;
        } catch (Exception e) {
            log.warn("Execution event append failed (best-effort): executionId={}, type={}, error={}",
                    executionId, eventType, e.getMessage());
            return false;
        }
    }

    public List<ExecutionEventDO> replayAfter(String executionId, long afterSeq) {
        return eventMapper.selectList(new LambdaQueryWrapper<ExecutionEventDO>()
                .eq(ExecutionEventDO::getExecutionId, executionId)
                .gt(ExecutionEventDO::getSeq, afterSeq)
                .orderByAsc(ExecutionEventDO::getSeq)
                .last("LIMIT " + Math.max(1, properties.getReplayLimit())));
    }

    public Map<Long, Map<String, Object>> loadLatestTurnEndPayloads(Collection<Long> executionIds) {
        if (executionIds == null || executionIds.isEmpty()) {
            return Map.of();
        }
        List<String> idStrings = executionIds.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
        if (idStrings.isEmpty()) {
            return Map.of();
        }
        List<ExecutionEventDO> events = eventMapper.selectList(new LambdaQueryWrapper<ExecutionEventDO>()
                .eq(ExecutionEventDO::getEventType, ExecutionEventTypes.TURN_END)
                .in(ExecutionEventDO::getExecutionId, idStrings)
                .orderByAsc(ExecutionEventDO::getSeq));
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (ExecutionEventDO event : events) {
            Long executionId;
            try {
                executionId = Long.valueOf(event.getExecutionId());
            } catch (NumberFormatException e) {
                continue;
            }
            String payloadJson = event.getPayloadJson();
            if (payloadJson == null || payloadJson.isBlank()) {
                continue;
            }
            try {
                Map<String, Object> payload = objectMapper.readValue(payloadJson,
                        new TypeReference<Map<String, Object>>() {});
                result.computeIfAbsent(executionId, k -> new LinkedHashMap<>()).putAll(payload);
            } catch (Exception e) {
                log.warn("Turn-end payload parse failed, skipped: executionId={}, seq={}, error={}",
                        event.getExecutionId(), event.getSeq(), e.getMessage());
            }
        }
        return result;
    }

    private long nextSeq(String executionId) {
        AtomicLong counter = seqCounters.computeIfAbsent(executionId, id -> {
            ExecutionEventDO latest = eventMapper.selectOne(new LambdaQueryWrapper<ExecutionEventDO>()
                    .eq(ExecutionEventDO::getExecutionId, id)
                    .orderByDesc(ExecutionEventDO::getSeq)
                    .last("LIMIT 1"));
            return new AtomicLong(latest != null ? latest.getSeq() : 0L);
        });
        return counter.incrementAndGet();
    }

    private String toJson(Map<String, ?> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Execution event payload serialization failed: {}", e.getMessage());
            return null;
        }
    }
}
