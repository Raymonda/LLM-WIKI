package org.cn.liuwt.llmwiki.domain.service.harness.eventlog;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.List;
import java.util.Map;
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
