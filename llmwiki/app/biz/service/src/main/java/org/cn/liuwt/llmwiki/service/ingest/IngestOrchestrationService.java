package org.cn.liuwt.llmwiki.service.ingest;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.harness.mq.MqHealthService;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Future;

@Service
public class IngestOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(IngestOrchestrationService.class);

    private final IngestService ingestService;
    private final SourceService sourceService;
    private final ExecutionNodeRegistry registry;
    private final MqHealthService mqHealthService;
    private final ExecutionMapper executionMapper;
    private final org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    @Value("${llmwiki.rocketmq.enabled:false}")
    private boolean mqEnabled;

    public IngestOrchestrationService(IngestService ingestService,
                                      SourceService sourceService,
                                      ExecutionNodeRegistry registry,
                                      MqHealthService mqHealthService,
                                      ExecutionMapper executionMapper,
                                      @Autowired(required = false) org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate) {
        this.ingestService = ingestService;
        this.sourceService = sourceService;
        this.registry = registry;
        this.mqHealthService = mqHealthService;
        this.executionMapper = executionMapper;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    public ExecutionModel startIngest(Long scopeId, Long sourceId, String guidance) {
        SourceModel source = sourceService.getSource(sourceId, scopeId);
        if (source == null) {
            throw new IllegalArgumentException("ingest source not found: " + sourceId);
        }
        ExecutionModel execution = ingestService.createExecution(scopeId, sourceId);
        setNodeOwnership(execution.getId());
        dispatchToMqOrLocal(execution.getId(), scopeId, sourceId, guidance,
            PipelineTaskMessage.TYPE_INGEST_START,
            () -> submitLocalTask(execution.getId(), () -> {
                try {
                    ingestService.runIngestPipeline(execution.getId(), scopeId, sourceId, guidance);
                } catch (Exception e) {
                    log.error("Ingest pipeline failed for executionId={}", execution.getId(), e);
                    ExecutionModel current = ingestService.getProgress(execution.getId());
                    if (current == null || !"cancelled".equals(current.getStatus())) {
                        ingestService.failExecution(execution.getId());
                    }
                }
            }));
        return execution;
    }

    private void submitLocalTask(Long executionId, Runnable task) {
        Future<?> future = registry.submitTask(() -> {
            try {
                task.run();
            } finally {
                registry.removeFuture(executionId);
            }
        });
        registry.putFuture(executionId, future);
    }

    private void dispatchToMqOrLocal(Long executionId, Long scopeId, Long sourceId, String guidance,
                                     String taskType, Runnable localFallback) {
        if (rocketMQTemplate == null || !mqEnabled || !mqHealthService.shouldAttempt()) {
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

    private void sendPipelineTask(Long executionId, Long scopeId, Long sourceId, String guidance, String taskType) {
        PipelineTaskMessage msg = new PipelineTaskMessage();
        msg.setExecutionId(executionId);
        msg.setScopeId(scopeId);
        msg.setSourceId(sourceId);
        msg.setGuidance(guidance);
        msg.setTaskType(taskType);
        msg.setNodeId(registry.getNodeId());
        msg.setSubmittedAt(System.currentTimeMillis());
        rocketMQTemplate.convertAndSend(PipelineTaskMessage.TOPIC, msg);
    }

    private void setNodeOwnership(Long executionId) {
        try {
            ExecutionDO update = new ExecutionDO();
            update.setId(executionId);
            update.setNodeId(registry.getNodeId());
            executionMapper.updateById(update);
        } catch (Exception e) {
            log.warn("Failed to set node ownership for executionId={}", executionId, e);
        }
    }
}
