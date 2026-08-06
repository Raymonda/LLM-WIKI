package org.cn.liuwt.llmwiki.service.harness.mq;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.facade.model.ExecutionInfo;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "llmwiki.rocketmq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = ExecutionEventMessage.TOPIC,
        consumerGroup = ExecutionEventMessage.CONSUMER_GROUP,
        messageModel = MessageModel.BROADCASTING,
        namespace = "${rocketmq.consumer.namespace:}"
)
public class SseEventConsumer implements RocketMQListener<ExecutionEventMessage> {

    private static final Logger log = LoggerFactory.getLogger(SseEventConsumer.class);

    @Autowired
    private ExecutionNodeRegistry registry;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private SourceMapper sourceMapper;

    @Override
    public void onMessage(ExecutionEventMessage msg) {
        SseEmitter emitter = registry.getEmitter(msg.getExecutionId());
        if (emitter == null) {
            return;
        }

        try {
            switch (msg.getEventType()) {
                case ExecutionEventMessage.TYPE_EXECUTION_STATUS:
                    handleExecutionStatus(emitter, msg);
                    break;
                case ExecutionEventMessage.TYPE_STEP_STATUS:
                    handleStepStatus(emitter, msg);
                    break;
                case ExecutionEventMessage.TYPE_STEP_PROGRESS:
                    handleStepProgress(emitter, msg);
                    break;
                default:
                    log.warn("Unknown execution event type: {}", msg.getEventType());
            }
        } catch (Exception e) {
            log.error("Failed to send SSE event for executionId={}", msg.getExecutionId(), e);
            registry.removeEmitter(msg.getExecutionId());
        }
    }

    private void handleExecutionStatus(SseEmitter emitter, ExecutionEventMessage msg) throws Exception {
        String status = msg.getStatus();
        if (isTerminalStatus(status)) {
            ExecutionInfo info = buildFullExecutionInfo(msg.getExecutionId());
            if (info != null) {
                emitter.send(SseEmitter.event().name("done").data(info));
            } else {
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("executionId", msg.getExecutionId());
                fallback.put("status", status);
                emitter.send(SseEmitter.event().name("done").data(fallback));
            }
            emitter.complete();
            registry.removeEmitter(msg.getExecutionId());
        } else if ("paused".equals(status)) {
            ExecutionInfo info = buildFullExecutionInfo(msg.getExecutionId());
            if (info != null) {
                emitter.send(SseEmitter.event().name("pause").data(info));
            } else {
                emitter.send(SseEmitter.event().name("pause").data(Map.of(
                        "executionId", msg.getExecutionId(), "status", status)));
            }
        }
    }

    private void handleStepStatus(SseEmitter emitter, ExecutionEventMessage msg) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("stepId", msg.getStepId());
        data.put("stepName", msg.getStepName());
        data.put("status", msg.getStatus());

        String outputData = "";
        if (isStepTerminal(msg.getStatus()) && msg.getStepId() != null) {
            ExecutionModel.ExecutionStepModel stepModel = executionTracker.getStep(msg.getStepId());
            if (stepModel != null && stepModel.getOutputData() != null) {
                outputData = stepModel.getOutputData();
            }
        }
        data.put("outputData", outputData);
        emitter.send(SseEmitter.event().name("step").data(data));

        if (isStepTerminal(msg.getStatus())) {
            ExecutionModel execution = executionTracker.getExecution(msg.getExecutionId());
            if (execution != null && isTerminalStatus(execution.getStatus())) {
                ExecutionInfo info = buildFullExecutionInfo(msg.getExecutionId());
                emitter.send(SseEmitter.event().name("done").data(info != null ? info : Map.of(
                        "executionId", msg.getExecutionId(), "status", execution.getStatus())));
                emitter.complete();
                registry.removeEmitter(msg.getExecutionId());
            }
        } else if ("paused".equals(msg.getStatus())) {
            ExecutionModel execution = executionTracker.getExecution(msg.getExecutionId());
            if (execution != null && "paused".equals(execution.getStatus())) {
                ExecutionInfo info = buildFullExecutionInfo(msg.getExecutionId());
                emitter.send(SseEmitter.event().name("pause").data(info != null ? info : Map.of(
                        "executionId", msg.getExecutionId(), "status", "paused")));
            }
        }
    }

    private boolean isStepTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private void handleStepProgress(SseEmitter emitter, ExecutionEventMessage msg) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("stepId", msg.getStepId());
        data.put("stepName", msg.getStepName());
        data.put("current", msg.getCurrent());
        data.put("total", msg.getTotal());
        data.put("avgMsPerUnit", msg.getAvgMsPerUnit());
        if (msg.getChunkIndex() != null) {
            data.put("chunkIndex", msg.getChunkIndex());
            data.put("chunkPreview", msg.getChunkPreview() != null ? msg.getChunkPreview() : "");
        }
        emitter.send(SseEmitter.event().name("step_progress").data(data));
    }

    private ExecutionInfo buildFullExecutionInfo(Long executionId) {
        ExecutionModel execution = executionTracker.getExecution(executionId);
        if (execution == null) {
            return null;
        }
        ExecutionInfo info = new ExecutionInfo();
        info.setExecutionId(execution.getId());
        info.setOperationType(execution.getType());
        info.setStatus(execution.getStatus());
        info.setScopeId(execution.getScopeId());
        info.setSourceId(execution.getSourceId());
        info.setStartTime(execution.getStartedAt());
        info.setEndTime(execution.getCompletedAt());
        info.setTotalTokens(execution.getTotalTokens());
        info.setErrorMessage(execution.getErrorMessage());
        if (execution.getSourceId() != null) {
            SourceDO source = sourceMapper.selectById(execution.getSourceId());
            if (source != null) {
                info.setSourceName(source.getName());
            }
        }
        return info;
    }

    private boolean isTerminalStatus(String status) {
        return "completed".equals(status) || "failed".equals(status)
                || "cancelled".equals(status) || "budget_exhausted".equals(status);
    }
}
