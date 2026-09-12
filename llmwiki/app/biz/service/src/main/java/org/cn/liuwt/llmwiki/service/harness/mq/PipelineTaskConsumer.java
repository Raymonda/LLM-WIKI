package org.cn.liuwt.llmwiki.service.harness.mq;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.HarnessEngine;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap.SchemaPolishService;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestStep;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.service.ingest.IngestService;
import org.cn.liuwt.llmwiki.service.ingest.MergeService;
import org.cn.liuwt.llmwiki.service.wiki.PageSavePostService;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Component
@ConditionalOnProperty(name = "llmwiki.rocketmq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = PipelineTaskMessage.TOPIC,
        consumerGroup = PipelineTaskMessage.CONSUMER_GROUP,
        consumeMode = ConsumeMode.CONCURRENTLY,
        namespace = "${rocketmq.consumer.namespace:}"
)
public class PipelineTaskConsumer implements RocketMQListener<PipelineTaskMessage> {

    private static final Logger log = LoggerFactory.getLogger(PipelineTaskConsumer.class);

    @Autowired
    private IngestService ingestService;

    @Autowired
    private MergeService mergeService;

    @Autowired
    private HarnessEngine harnessEngine;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private ExecutionNodeRegistry registry;

    @Autowired
    private ExecutionMapper executionMapper;

    @Autowired
    private PageSavePostService pageSavePostService;

    @Autowired
    private SchemaPolishService schemaPolishService;

    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "cancelled", "budget_exhausted", "failed");
    private static final Set<String> RESUME_ONLY_STATUSES = Set.of("paused");

    @Override
    public void onMessage(PipelineTaskMessage msg) {
        // SCHEMA_POLISH 与 PAGE_SAVE_POST 一样无 executionId，同步执行让异常传播触发 RocketMQ 自动重试
        if (PipelineTaskMessage.TYPE_SCHEMA_POLISH.equals(msg.getTaskType())) {
            log.info("Picked up SCHEMA_POLISH: scopeId={}, node={}", msg.getScopeId(), registry.getNodeId());
            schemaPolishService.polishSchema(msg.getScopeId());
            return;
        }

        // PAGE_SAVE_POST 不需要 executionId，独立处理
        if (PipelineTaskMessage.TYPE_PAGE_SAVE_POST.equals(msg.getTaskType())) {
            handlePageSavePost(msg);
            return;
        }

        Long executionId = msg.getExecutionId();

        ExecutionModel execution = executionTracker.getExecution(executionId);
        if (execution == null) {
            log.warn("Execution not found for executionId={}, skipping", executionId);
            return;
        }

        String status = execution.getStatus();
        boolean isResume = PipelineTaskMessage.TYPE_INGEST_RESUME.equals(msg.getTaskType());

        if (TERMINAL_STATUSES.contains(status)) {
            // RESUME 任务允许从 failed 状态续传，不应被终端守卫拦截
            if (!isResume) {
                log.info("Execution {} already in terminal status '{}', skipping taskType={}", executionId, status, msg.getTaskType());
                return;
            }
        } else if (RESUME_ONLY_STATUSES.contains(status) && !isResume) {
            // paused 状态只能通过 RESUME 任务续传，其他 taskType 的消息应被忽略（可能是 pause 前的旧消息回推）
            log.info("Execution {} is '{}', only RESUME task allowed, skipping taskType={}", executionId, status, msg.getTaskType());
            return;
        }

        // 幂等守卫：同一 executionId 在本节点只允许一个活跃消费线程，防止 RocketMQ 重复投递导致并发执行
        if (!registry.tryClaimExecution(executionId)) {
            log.warn("Execution {} is already being processed on this node, skipping duplicate message (taskType={})", executionId, msg.getTaskType());
            return;
        }

        log.info("Picked up pipeline task: executionId={}, taskType={}, node={}",
                executionId, msg.getTaskType(), registry.getNodeId());

        updateNodeOwnership(executionId);

        // 同步执行任务，让异常能传播到 onMessage() 调用方，触发 RocketMQ 自动重试
        Future<?> future = registry.submitTask(() -> executeTask(msg));
        registry.putFuture(executionId, future);

        try {
            future.get();
        } catch (CancellationException ce) {
            // 外部取消（如用户取消），不重试
            log.info("Execution {} was cancelled externally", executionId);
        } catch (ExecutionException ee) {
            // 任务内部异常 → 抛出让 RocketMQ 重试
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            log.error("Pipeline task failed for executionId={}, will be retried by RocketMQ", executionId, cause);

            // 内部 orchestrator 可能已将 execution 状态设为 "failed"，
            // 必须重置为 "running"，否则重试会被终端状态守卫拦截
            try {
                ExecutionModel current = executionTracker.getExecution(executionId);
                if (current != null && TERMINAL_STATUSES.contains(current.getStatus())
                        && !"cancelled".equals(current.getStatus())) {
                    executionTracker.updateExecutionStatus(executionId, "running");
                    log.info("Reset execution {} from '{}' to 'running' for MQ retry", executionId, current.getStatus());
                }
            } catch (Exception resetErr) {
                log.warn("Failed to reset execution status for retry: executionId={}", executionId, resetErr);
            }

            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("Pipeline task failed: executionId=" + executionId, cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Execution {} interrupted", executionId);
        } finally {
            registry.removeFuture(executionId);
            registry.releaseExecution(executionId);
        }
    }

    private void executeTask(PipelineTaskMessage msg) {
        switch (msg.getTaskType()) {
            case PipelineTaskMessage.TYPE_INGEST_START:
                ingestService.runIngestPipeline(msg.getExecutionId(), msg.getScopeId(), msg.getSourceId(), msg.getGuidance());
                break;
            case PipelineTaskMessage.TYPE_INGEST_ANALYZE:
                ingestService.runIngestAnalysis(msg.getExecutionId(), msg.getScopeId(), msg.getSourceId(), msg.getGuidance());
                break;
            case PipelineTaskMessage.TYPE_INGEST_EXECUTE:
                ingestService.runIngestExecution(msg.getExecutionId(), msg.getScopeId(), msg.getSourceId(), msg.getGuidance());
                break;
            case PipelineTaskMessage.TYPE_INGEST_REANALYZE:
                ingestService.reanalyzeIngest(msg.getExecutionId(), msg.getScopeId(), msg.getSourceId(), msg.getGuidance());
                break;
            case PipelineTaskMessage.TYPE_INGEST_RESUME:
                ExecutionModel execution = executionTracker.getExecution(msg.getExecutionId());
                if (execution != null) {
                    boolean phase1Completed = IngestStep.isPhase1Completed(execution.getSteps());
                    if (phase1Completed) {
                        ingestService.resumeIngestExecution(msg.getExecutionId(), msg.getScopeId(), msg.getSourceId(), msg.getGuidance());
                    } else {
                        ingestService.resumeIngestAnalysis(msg.getExecutionId(), msg.getScopeId(), msg.getSourceId(), msg.getGuidance());
                    }
                }
                break;
            case PipelineTaskMessage.TYPE_LINT_START:
                harnessEngine.executeLintWithExecution(msg.getExecutionId(), msg.getScopeId(), msg.isFullScan());
                break;
            case PipelineTaskMessage.TYPE_MERGE:
                mergeService.runMergePipeline(msg.getExecutionId(), msg.getScopeId(),
                        msg.getSourceId(), msg.getGuidance(), msg.getOriginalPageIds());
                break;
            default:
                log.error("Unknown task type: {}", msg.getTaskType());
        }
    }

    private void handlePageSavePost(PipelineTaskMessage msg) {
        log.info("Picked up PAGE_SAVE_POST: scopeId={}, pageId={}, node={}",
            msg.getScopeId(), msg.getPageId(), registry.getNodeId());
        // 直接抛出异常，由 RocketMQ 自动重试（16次指数退避）
        // 重试耗尽后消息进入死信队列（DLQ），由 PipelineTaskDeadLetterConsumer 处理
        pageSavePostService.executePostSave(msg);
    }

    private void updateNodeOwnership(Long executionId) {
        try {
            ExecutionDO update = new ExecutionDO();
            update.setId(executionId);
            update.setNodeId(registry.getNodeId());
            executionMapper.updateById(update);
        } catch (Exception e) {
            log.warn("Failed to update node ownership for executionId={}", executionId, e);
        }
    }
}
