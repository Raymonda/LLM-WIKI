package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.TokenUsageDailyDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.TokenUsageDailyMapper;
import org.cn.liuwt.llmwiki.facade.model.TokenUsageTrendItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TokenUsageDailyService {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageDailyService.class);

    @Autowired
    private TokenUsageDailyMapper tokenUsageDailyMapper;

    public void recordDaily(Long scopeId, String operationType, int inputTokens, int outputTokens) {
        if (scopeId == null || (inputTokens == 0 && outputTokens == 0)) return;
        String type = operationType != null ? operationType : "unknown";
        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        try {
            tokenUsageDailyMapper.upsertUsage(scopeId, dateStr, type, inputTokens, outputTokens);
        } catch (Exception e) {
            log.warn("Failed to record daily token usage: scopeId={}, type={}, error={}", scopeId, type, e.getMessage());
        }
    }

    public Map<String, Long> queryByType(Long scopeId, LocalDate startDate, LocalDate endDate) {
        List<TokenUsageDailyDO> records = tokenUsageDailyMapper.selectList(
            new LambdaQueryWrapper<TokenUsageDailyDO>()
                .eq(TokenUsageDailyDO::getScopeId, scopeId)
                .ge(TokenUsageDailyDO::getUsageDate, startDate)
                .le(TokenUsageDailyDO::getUsageDate, endDate)
        );
        return records.stream().collect(Collectors.groupingBy(
            TokenUsageDailyDO::getOperationType,
            Collectors.summingLong(r -> r.getInputTokens() + r.getOutputTokens())
        ));
    }

    public List<TokenUsageTrendItem> queryTrend(Long scopeId, LocalDate startDate, LocalDate endDate) {
        List<TokenUsageDailyDO> records = tokenUsageDailyMapper.selectList(
            new LambdaQueryWrapper<TokenUsageDailyDO>()
                .eq(TokenUsageDailyDO::getScopeId, scopeId)
                .ge(TokenUsageDailyDO::getUsageDate, startDate)
                .le(TokenUsageDailyDO::getUsageDate, endDate)
                .orderByAsc(TokenUsageDailyDO::getUsageDate)
        );

        Map<LocalDate, Map<String, Long>> grouped = new LinkedHashMap<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            grouped.put(d, new HashMap<>());
        }
        for (TokenUsageDailyDO r : records) {
            grouped.computeIfAbsent(r.getUsageDate(), k -> new HashMap<>())
                .merge(r.getOperationType(), r.getInputTokens() + r.getOutputTokens(), Long::sum);
        }

        List<TokenUsageTrendItem> result = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<String, Long>> entry : grouped.entrySet()) {
            Map<String, Long> byType = entry.getValue();
            TokenUsageTrendItem item = new TokenUsageTrendItem();
            item.setDate(entry.getKey().format(DateTimeFormatter.ISO_LOCAL_DATE));
            item.setIngestTokens(byType.getOrDefault("ingest", 0L));
            item.setQueryTokens(byType.getOrDefault("query", 0L));
            item.setLintTokens(byType.getOrDefault("lint", 0L));
            item.setSchemaTokens(byType.getOrDefault("schema", 0L));
            item.setModifyTokens(byType.getOrDefault("modify", 0L));
            result.add(item);
        }
        return result;
    }
}
