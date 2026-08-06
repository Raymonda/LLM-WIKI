package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SystemConfigDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SystemConfigMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SystemConfigService {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    @Autowired
    @Lazy
    private ExecutionTracker executionTracker;

    public SystemConfigDO getConfig(Long scopeId, String configKey) {
        return systemConfigMapper.selectOne(
            new LambdaQueryWrapper<SystemConfigDO>()
                .eq(SystemConfigDO::getScopeId, scopeId)
                .eq(SystemConfigDO::getConfigKey, configKey)
        );
    }

    public SystemConfigDO saveConfig(Long scopeId, String configKey, String configValue, Long userId) {
        SystemConfigDO existing = getConfig(scopeId, configKey);
        String oldValue = existing != null ? existing.getConfigValue() : null;

        if (existing != null) {
            existing.setConfigValue(configValue);
            existing.setUpdatedBy(userId);
            existing.setUpdatedAt(LocalDateTime.now());
            systemConfigMapper.updateById(existing);
        } else {
            existing = new SystemConfigDO();
            existing.setScopeId(scopeId);
            existing.setConfigKey(configKey);
            existing.setConfigValue(configValue);
            existing.setUpdatedBy(userId);
            existing.setUpdatedAt(LocalDateTime.now());
            systemConfigMapper.insert(existing);
        }

        recordConfigChangeExecution(scopeId, configKey, oldValue, configValue, userId);
        return existing;
    }

    private void recordConfigChangeExecution(Long scopeId, String configKey,
                                              String oldValue, String newValue, Long userId) {
        if (executionTracker == null) {
            return;
        }
        try {
            String summary = String.format(
                "{\"configKey\":\"%s\",\"oldValue\":\"%s\",\"newValue\":\"%s\"}",
                configKey,
                oldValue != null ? oldValue.replace("\"", "\\\"") : "",
                newValue != null ? newValue.replace("\"", "\\\"") : ""
            );
            ExecutionModel exec = executionTracker.createExecution("config_change", scopeId, null, null);
            executionTracker.updateExecutionStatus(exec.getId(), "running");
            ExecutionModel.ExecutionStepModel step = executionTracker.createStep(
                exec.getId(), "CONFIG_CHANGE", 1, "auto");
            executionTracker.updateStepStatus(step.getId(), "running");
            executionTracker.completeStep(step.getId(), summary, 0, 0);
            executionTracker.completeExecution(exec.getId(), 0);
        } catch (Exception e) {
            log.warn("记录系统配置变更执行记录失败 scope={} key={}", scopeId, configKey, e);
        }
    }
}