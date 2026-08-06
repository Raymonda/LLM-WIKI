package org.cn.liuwt.llmwiki.domain.service.harness.baseline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.StepBaselineDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.StepBaselineMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExecutionBaselineService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionBaselineService.class);

    private static final double EWMA_ALPHA = 0.3d;

    @Autowired
    private StepBaselineMapper stepBaselineMapper;

    public Map<String, Long> getProfile(Long scopeId, String docFormat) {
        if (scopeId == null) {
            return Map.of();
        }
        String format = normalizeFormat(docFormat);
        LambdaQueryWrapper<StepBaselineDO> wrapper = new LambdaQueryWrapper<StepBaselineDO>()
            .eq(StepBaselineDO::getScopeId, scopeId)
            .eq(StepBaselineDO::getDocFormat, format);
        List<StepBaselineDO> rows = stepBaselineMapper.selectList(wrapper);
        Map<String, Long> profile = new HashMap<>();
        for (StepBaselineDO row : rows) {
            if (row.getStepName() == null || row.getAvgMs() == null) continue;
            profile.put(row.getStepName(), row.getAvgMs());
        }
        return profile;
    }

    public void recordSample(Long scopeId, String docFormat, String stepName, long durationMs) {
        if (scopeId == null || stepName == null || stepName.isEmpty() || durationMs < 0) {
            return;
        }
        String format = normalizeFormat(docFormat);
        try {
            LambdaQueryWrapper<StepBaselineDO> wrapper = new LambdaQueryWrapper<StepBaselineDO>()
                .eq(StepBaselineDO::getScopeId, scopeId)
                .eq(StepBaselineDO::getStepName, stepName)
                .eq(StepBaselineDO::getDocFormat, format);
            StepBaselineDO existing = stepBaselineMapper.selectOne(wrapper);
            LocalDateTime now = LocalDateTime.now();
            if (existing == null) {
                StepBaselineDO row = new StepBaselineDO();
                row.setScopeId(scopeId);
                row.setStepName(stepName);
                row.setDocFormat(format);
                row.setAvgMs(durationMs);
                row.setP95Ms(durationMs);
                row.setSampleCount(1);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                stepBaselineMapper.insert(row);
            } else {
                int n = existing.getSampleCount() == null ? 0 : existing.getSampleCount();
                long oldAvg = existing.getAvgMs() == null ? 0L : existing.getAvgMs();
                long newAvg = n <= 0 ? durationMs : (oldAvg * n + durationMs) / (n + 1);
                long oldP95 = existing.getP95Ms() == null ? 0L : existing.getP95Ms();
                long newP95 = Math.round(oldP95 * (1 - EWMA_ALPHA) + Math.max(durationMs, oldP95) * EWMA_ALPHA);
                existing.setAvgMs(newAvg);
                existing.setP95Ms(newP95);
                existing.setSampleCount(n + 1);
                existing.setUpdatedAt(now);
                stepBaselineMapper.updateById(existing);
            }
        } catch (Exception e) {
            log.warn("recordSample failed: scope={}, step={}, err={}", scopeId, stepName, e.getMessage());
        }
    }

    private String normalizeFormat(String format) {
        if (format == null || format.isBlank()) return "";
        return format.toLowerCase();
    }
}
