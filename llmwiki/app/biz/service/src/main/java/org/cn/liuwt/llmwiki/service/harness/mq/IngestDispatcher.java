package org.cn.liuwt.llmwiki.service.harness.mq;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class IngestDispatcher {

    private static final Logger log = LoggerFactory.getLogger(IngestDispatcher.class);

    @Autowired
    private ExecutionNodeRegistry registry;

    @Autowired
    private MqHealthService mqHealthService;

    @Autowired(required = false)
    private org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    @Autowired
    private ExecutionMapper executionMapper;

    @Value("${llmwiki.rocketmq.enabled:false}")
    private boolean mqEnabled;

    public boolean isMqAvailable() {
        return rocketMQTemplate != null && mqEnabled;
    }

    public void dispatch(Long executionId, Long scopeId, Long sourceId, String guidance,
                         String taskType, Runnable localFallback) {
        if (!isMqAvailable() || !mqHealthService.shouldAttempt()) {
            localFallback.run();
            return;
        }
        try {
            sendPipelineTask(executionId, scopeId, sourceId, guidance, taskType);
            mqHealthService.markSendSuccess();
        } catch (Exception e) {
            mqHealthService.markSendFailed();
            log.error("Failed to send pipeline task to RocketMQ (type={}, executionId={}), falling back to local execution",
                taskType, executionId, e);
            localFallback.run();
        }
    }

    public void submitLocalTask(Long executionId, Runnable task) {
        AtomicReference<Future<?>> futureRef = new AtomicReference<>();
        Future<?> future = registry.submitTask(() -> {
            try {
                task.run();
            } finally {
                Future<?> self = futureRef.get();
                if (self != null) {
                    registry.removeFutureIfSame(executionId, self);
                }
            }
        });
        futureRef.set(future);
        registry.putFuture(executionId, future);
        if (future.isDone()) {
            registry.removeFutureIfSame(executionId, future);
        }
    }

    public void sendControl(Long executionId, String action, String reason) {
        ControlMessage msg = new ControlMessage();
        msg.setExecutionId(executionId);
        msg.setAction(action);
        msg.setReason(reason);
        msg.setIssuedBy(registry.getNodeId());
        msg.setIssuedAt(System.currentTimeMillis());
        rocketMQTemplate.convertAndSend(ControlMessage.TOPIC, msg);
    }

    public void bindNodeOwnership(Long executionId) {
        try {
            ExecutionDO update = new ExecutionDO();
            update.setId(executionId);
            update.setNodeId(registry.getNodeId());
            executionMapper.updateById(update);
        } catch (Exception e) {
            log.warn("Failed to set node ownership for executionId={}", executionId, e);
        }
    }

    public String getNodeId() {
        return registry.getNodeId();
    }

    private void sendPipelineTask(Long executionId, Long scopeId, Long sourceId, String guidance, String taskType) {
        PipelineTaskMessage msg = new PipelineTaskMessage();
        msg.setExecutionId(executionId);
        msg.setScopeId(scopeId);
        msg.setSourceId(sourceId);
        msg.setGuidance(guidance);
        msg.setTaskType(taskType);
        msg.setNodeId(registry.getNodeId());
        msg.setSubmittedAt(System.currentTimeMillis());
        try {
            rocketMQTemplate.convertAndSend(PipelineTaskMessage.TOPIC, msg);
            log.info("[MQ] Pipeline task sent: executionId={}, taskType={}, topic={}", executionId, taskType, PipelineTaskMessage.TOPIC);
        } catch (Exception e) {
            log.error("[MQ] Failed to send pipeline task: executionId={}, topic={}", executionId, PipelineTaskMessage.TOPIC, e);
            throw new RuntimeException("Failed to submit pipeline task", e);
        }
    }
}
