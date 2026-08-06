package org.cn.liuwt.llmwiki.service.harness.mq;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.HarnessEngine;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionHistoryService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "llmwiki.rocketmq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = LintScheduleTaskMessage.TOPIC,
        consumerGroup = LintScheduleTaskMessage.CONSUMER_GROUP,
        consumeMode = ConsumeMode.CONCURRENTLY,
        namespace = "${rocketmq.consumer.namespace:}"
)
public class LintScheduleConsumer implements RocketMQListener<LintScheduleTaskMessage> {

    private static final Logger log = LoggerFactory.getLogger(LintScheduleConsumer.class);

    @Autowired
    private HarnessEngine harnessEngine;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private ExecutionHistoryService executionHistoryService;

    @Autowired
    private ExecutionNodeRegistry registry;

    @Autowired
    private ExecutionMapper executionMapper;

    @Override
    public void onMessage(LintScheduleTaskMessage msg) {
        Long scopeId = msg.getScopeId();

        // 幂等守卫 1: scope 级原子去重，防止 RocketMQ 重复投递导致同一 scope 并发创建多个 execution
        if (!registry.tryClaimScope(scopeId)) {
            log.info("Scheduled lint skipped: scopeId={} is already being processed on this node", scopeId);
            return;
        }

        try {
            // 幂等守卫 2: DB 级检查（跨节点防重）
            if (executionHistoryService.isTypeExecutionRunning(scopeId, "lint")) {
                log.info("Scheduled lint skipped: scopeId={}, lint already running", scopeId);
                return;
            }

            log.info("Picked up scheduled lint task: scopeId={}, scopeType={}, node={}",
                    scopeId, msg.getScopeType(), registry.getNodeId());

            ExecutionModel execution = executionTracker.createExecution("lint", scopeId, null, null);
            executionTracker.updateExecutionStatus(execution.getId(), "running");

            updateNodeOwnership(execution.getId());

            harnessEngine.executeLintWithExecution(execution.getId(), scopeId, msg.isFullScan());

            log.info("Scheduled lint completed: scopeId={}, executionId={}", scopeId, execution.getId());
        } catch (Exception e) {
            log.error("Scheduled lint failed for scopeId={}: {}", scopeId, e.getMessage(), e);
            // 确保 execution 状态被正确标记为 failed，避免卡死在 running 状态
            markLatestLintFailed(scopeId, e.getMessage());
        } finally {
            registry.releaseScope(scopeId);
        }
    }

    private void markLatestLintFailed(Long scopeId, String errorMessage) {
        try {
            ExecutionDO latest = executionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExecutionDO>()
                    .eq(ExecutionDO::getScopeId, scopeId)
                    .eq(ExecutionDO::getType, "lint")
                    .eq(ExecutionDO::getStatus, "running")
                    .orderByDesc(ExecutionDO::getCreatedAt)
                    .last("LIMIT 1")
            );
            if (latest != null) {
                executionTracker.failExecution(latest.getId(), errorMessage);
            }
        } catch (Exception ex) {
            log.warn("Failed to mark lint execution as failed for scopeId={}: {}", scopeId, ex.getMessage());
        }
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
