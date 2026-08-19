package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeBudgetDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeBudgetMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class TokenUsageMonitor {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageMonitor.class);

    private static final double WARNING_THRESHOLD = 0.8;
    private static final double EXCEEDED_THRESHOLD = 1.0;

    @Autowired
    private ScopeBudgetMapper scopeBudgetMapper;

    @Autowired
    private ScopeMapper scopeMapper;

    @Autowired
    private NotificationService notificationService;

    public void recordUsage(Long scopeId, int tokensUsed) {
        if (tokensUsed <= 0) return;
        getOrCreateBudget(scopeId);
        scopeBudgetMapper.incrementUsedTokens(scopeId, tokensUsed);
        ScopeBudgetDO fresh = scopeBudgetMapper.selectOne(
            new LambdaQueryWrapper<ScopeBudgetDO>().eq(ScopeBudgetDO::getScopeId, scopeId)
        );
        if (fresh == null) return;
        log.info("Scope {} token usage: {} / {} ({}%)",
            scopeId, fresh.getUsedTokens(), fresh.getMonthlyBudget(),
            getUsagePercent(fresh));
        checkAndNotifyBudgetAlert(scopeId, fresh);
    }

    @Scheduled(cron = "${llmwiki.token-usage.reset-cron:0 10 0 * * *}")
    public void resetExpiredMonthlyUsage() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextReset = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();
        int reset = scopeBudgetMapper.resetExpiredMonthlyUsage(now, nextReset);
        if (reset > 0) {
            log.info("Monthly token usage reset for {} scope(s)", reset);
        }
    }

    public int getRemainingBudget(Long scopeId) {
        ScopeBudgetDO budget = getOrCreateBudget(scopeId);
        return budget.getMonthlyBudget() - budget.getUsedTokens();
    }

    public ScopeBudgetDO getBudgetInfo(Long scopeId) {
        return getOrCreateBudget(scopeId);
    }

    public double getUsagePercent(Long scopeId) {
        ScopeBudgetDO budget = getOrCreateBudget(scopeId);
        return getUsagePercent(budget);
    }

    public String getAlertLevel(Long scopeId) {
        ScopeBudgetDO budget = getOrCreateBudget(scopeId);
        double percent = getUsagePercent(budget);
        if (percent >= EXCEEDED_THRESHOLD) return "exceeded";
        if (percent >= WARNING_THRESHOLD) return "warning";
        return "normal";
    }

    private double getUsagePercent(ScopeBudgetDO budget) {
        if (budget.getMonthlyBudget() == null || budget.getMonthlyBudget() == 0) return 0;
        return (double) budget.getUsedTokens() / budget.getMonthlyBudget();
    }

    private void checkAndNotifyBudgetAlert(Long scopeId, ScopeBudgetDO budget) {
        double percent = getUsagePercent(budget);

        if (percent >= EXCEEDED_THRESHOLD && (budget.getExceededNotified() == null || budget.getExceededNotified() == 0)) {
            notificationService.createNotification(
                scopeId,
                "budget_exceeded",
                "Token 用量超额提醒",
                "你的知识库本月 Token 用量已达 " + budget.getUsedTokens() + "，超过月度参考值 " + budget.getMonthlyBudget() + "。操作不受限制，但建议关注用量。",
                scopeId,
                null
            );
            markExceededNotified(scopeId);
            log.info("Sent budget exceeded notification for scope {}", scopeId);
        } else if (percent >= WARNING_THRESHOLD && (budget.getWarningNotified() == null || budget.getWarningNotified() == 0)) {
            notificationService.createNotification(
                scopeId,
                "budget_warning",
                "Token 用量接近参考值",
                "你的知识库本月 Token 用量已达 " + budget.getUsedTokens() + " / " + budget.getMonthlyBudget() + "（" + Math.round(percent * 100) + "%）。操作不受限制，请注意用量趋势。",
                scopeId,
                null
            );
            markWarningNotified(scopeId);
            log.info("Sent budget warning notification for scope {}", scopeId);
        }
    }

    private void markWarningNotified(Long scopeId) {
        scopeBudgetMapper.update(null, new LambdaUpdateWrapper<ScopeBudgetDO>()
            .eq(ScopeBudgetDO::getScopeId, scopeId)
            .set(ScopeBudgetDO::getWarningNotified, 1));
    }

    private void markExceededNotified(Long scopeId) {
        scopeBudgetMapper.update(null, new LambdaUpdateWrapper<ScopeBudgetDO>()
            .eq(ScopeBudgetDO::getScopeId, scopeId)
            .set(ScopeBudgetDO::getExceededNotified, 1));
    }

    private ScopeBudgetDO getOrCreateBudget(Long scopeId) {
        ScopeBudgetDO budget = scopeBudgetMapper.selectOne(
            new LambdaQueryWrapper<ScopeBudgetDO>()
                .eq(ScopeBudgetDO::getScopeId, scopeId)
        );
        if (budget == null) {
            budget = new ScopeBudgetDO();
            budget.setScopeId(scopeId);
            budget.setMonthlyBudget(getScopeMonthlyBudget(scopeId));
            budget.setUsedTokens(0);
            budget.setResetDate(LocalDateTime.now().toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay());
            budget.setWarningNotified(0);
            budget.setExceededNotified(0);
            try {
                scopeBudgetMapper.insert(budget);
            } catch (DuplicateKeyException e) {
                log.debug("Scope budget already created concurrently for scope {}", scopeId);
                budget = scopeBudgetMapper.selectOne(
                    new LambdaQueryWrapper<ScopeBudgetDO>().eq(ScopeBudgetDO::getScopeId, scopeId)
                );
            }
        }
        return budget;
    }

    private int getScopeMonthlyBudget(Long scopeId) {
        ScopeDO scope = scopeMapper.selectById(scopeId);
        if (scope != null && scope.getMonthlyBudget() != null) {
            return scope.getMonthlyBudget();
        }
        return 1000000; // fallback: 100W
    }
}