package org.cn.liuwt.llmwiki.service.harness.mq;

import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.service.ingest.IngestService;
import org.cn.liuwt.llmwiki.service.wiki.PageSavePostService;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Pipeline 死信队列消费者。
 *
 * RocketMQ 自动重试（16次指数退避）耗尽后，消息进入 DLQ（Dead Letter Queue）。
 * 本消费者监听 DLQ，根据消息类型执行不同的死信处理：
 * - PAGE_SAVE_POST → 标记 wiki_page.post_save_status='failed'
 * - INGEST/LINT/MERGE → 标记 execution.status='failed' + errorMessage
 *
 * DLQ topic 格式：%DLQ%{consumerGroup}
 */
@Component
@ConditionalOnProperty(name = "llmwiki.rocketmq.enabled", havingValue = "true")
@RocketMQMessageListener(
    topic = "%DLQ%" + PipelineTaskMessage.CONSUMER_GROUP,
    consumerGroup = "%DLQ%" + PipelineTaskMessage.CONSUMER_GROUP,
    consumeMode = ConsumeMode.CONCURRENTLY,
    namespace = "${rocketmq.consumer.namespace:}"
)
public class PipelineTaskDeadLetterConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(PipelineTaskDeadLetterConsumer.class);

    @Autowired
    private PageSavePostService pageSavePostService;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private IngestService ingestService;

    @Override
    public void onMessage(MessageExt messageExt) {
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        int retries = messageExt.getReconsumeTimes();
        log.error("Dead letter received: msgId={}, reconsumeTimes={}, body={}",
            messageExt.getMsgId(), retries, body);

        String taskType = extractStringField(body, "taskType");
        if (taskType == null) {
            log.error("Dead letter missing taskType, cannot process: msgId={}", messageExt.getMsgId());
            return;
        }

        String dlqError = String.format("MQ死信: 重试%d次后仍失败, msgId=%s", retries, messageExt.getMsgId());

        switch (taskType) {
            case PipelineTaskMessage.TYPE_PAGE_SAVE_POST:
                handlePageSaveDeadLetter(body, dlqError);
                break;
            case PipelineTaskMessage.TYPE_INGEST_START:
            case PipelineTaskMessage.TYPE_INGEST_ANALYZE:
            case PipelineTaskMessage.TYPE_INGEST_EXECUTE:
            case PipelineTaskMessage.TYPE_INGEST_RESUME:
                handleIngestDeadLetter(body, dlqError);
                break;
            case PipelineTaskMessage.TYPE_LINT_START:
                handleLintDeadLetter(body, dlqError);
                break;
            case PipelineTaskMessage.TYPE_MERGE:
                handleMergeDeadLetter(body, dlqError);
                break;
            case PipelineTaskMessage.TYPE_SCHEMA_POLISH:
                log.warn("SCHEMA_POLISH dead letter ignored, deterministic schema remains in effect: msgId={}",
                    messageExt.getMsgId());
                break;
            default:
                log.warn("Dead letter with unknown taskType '{}', ignoring: msgId={}", taskType, messageExt.getMsgId());
        }
    }

    private void handlePageSaveDeadLetter(String body, String dlqError) {
        Long scopeId = extractLongField(body, "scopeId");
        Long pageId = extractLongField(body, "pageId");

        if (scopeId == null || pageId == null) {
            log.error("PAGE_SAVE_POST dead letter missing scopeId or pageId");
            return;
        }

        pageSavePostService.markPageFailed(scopeId, pageId, dlqError);
        log.error("PAGE_SAVE_POST dead letter processed: scopeId={}, pageId={}", scopeId, pageId);
    }

    private void handleIngestDeadLetter(String body, String dlqError) {
        Long executionId = extractLongField(body, "executionId");
        if (executionId == null) {
            log.error("INGEST dead letter missing executionId");
            return;
        }
        ingestService.failExecution(executionId, dlqError);
        log.error("INGEST dead letter processed: executionId={}", executionId);
    }

    private void handleLintDeadLetter(String body, String dlqError) {
        Long executionId = extractLongField(body, "executionId");
        if (executionId == null) {
            log.error("LINT dead letter missing executionId");
            return;
        }
        executionTracker.failExecution(executionId, dlqError);
        log.error("LINT dead letter processed: executionId={}", executionId);
    }

    private void handleMergeDeadLetter(String body, String dlqError) {
        Long executionId = extractLongField(body, "executionId");
        if (executionId == null) {
            log.error("MERGE dead letter missing executionId");
            return;
        }
        executionTracker.failExecution(executionId, dlqError);
        log.error("MERGE dead letter processed: executionId={}", executionId);
    }

    // --- JSON 字段提取（避免引入额外 JSON 库依赖）---

    private Long extractLongField(String json, String fieldName) {
        String value = extractRawValue(json, fieldName);
        if (value == null) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractStringField(String json, String fieldName) {
        String key = "\"" + fieldName + "\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private String extractRawValue(String json, String fieldName) {
        String key = "\"" + fieldName + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        // 如果是字符串值
        if (json.charAt(start) == '"') {
            int end = json.indexOf("\"", start + 1);
            return end > start ? json.substring(start + 1, end) : null;
        }
        // 数字或布尔值
        int end = start;
        while (end < json.length() && !",}".contains(String.valueOf(json.charAt(end)))) end++;
        return json.substring(start, end);
    }
}
