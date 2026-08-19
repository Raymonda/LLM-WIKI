package org.cn.liuwt.llmwiki.service.harness.mq;

import org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap.SchemaPolishDispatcher;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@ConditionalOnProperty(name = "llmwiki.rocketmq.enabled", havingValue = "true")
public class MqSchemaPolishDispatcher implements SchemaPolishDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MqSchemaPolishDispatcher.class);

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private MqHealthService mqHealthService;

    @Autowired
    private LocalSchemaPolishDispatcher localFallback;

    @Override
    public void dispatch(Long scopeId) {
        if (rocketMQTemplate == null || !mqHealthService.shouldAttempt()) {
            localFallback.dispatch(scopeId);
            return;
        }
        PipelineTaskMessage msg = new PipelineTaskMessage();
        msg.setTaskType(PipelineTaskMessage.TYPE_SCHEMA_POLISH);
        msg.setScopeId(scopeId);
        msg.setSubmittedAt(System.currentTimeMillis());
        try {
            rocketMQTemplate.convertAndSend(PipelineTaskMessage.TOPIC, msg);
            mqHealthService.markSendSuccess();
            log.info("[MQ] Schema polish task sent: scopeId={}, topic={}", scopeId, PipelineTaskMessage.TOPIC);
        } catch (Exception e) {
            mqHealthService.markSendFailed();
            log.error("[MQ] Failed to send schema polish task, fallback to local: scopeId={}", scopeId, e);
            localFallback.dispatch(scopeId);
        }
    }
}
