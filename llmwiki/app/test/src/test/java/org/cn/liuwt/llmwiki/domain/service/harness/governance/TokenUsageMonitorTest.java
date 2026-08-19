package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeBudgetDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeBudgetMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenUsageMonitorTest {

    private TokenUsageMonitor monitor;
    private ScopeBudgetMapper budgetMapper;
    private ScopeMapper scopeMapper;
    private NotificationService notificationService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ScopeBudgetDO.class);
    }

    @BeforeEach
    void setUp() {
        monitor = new TokenUsageMonitor();
        budgetMapper = mock(ScopeBudgetMapper.class);
        scopeMapper = mock(ScopeMapper.class);
        notificationService = mock(NotificationService.class);
        ReflectionTestUtils.setField(monitor, "scopeBudgetMapper", budgetMapper);
        ReflectionTestUtils.setField(monitor, "scopeMapper", scopeMapper);
        ReflectionTestUtils.setField(monitor, "notificationService", notificationService);
    }

    private ScopeBudgetDO budgetRow(int usedTokens, int warningNotified, int exceededNotified) {
        ScopeBudgetDO row = new ScopeBudgetDO();
        row.setScopeId(7L);
        row.setMonthlyBudget(1000000);
        row.setUsedTokens(usedTokens);
        row.setWarningNotified(warningNotified);
        row.setExceededNotified(exceededNotified);
        row.setResetDate(LocalDateTime.now().plusMonths(1));
        return row;
    }

    @Test
    void shouldUseAtomicIncrementWhenRecordingUsage() {
        when(budgetMapper.selectOne(any())).thenReturn(null, budgetRow(150, 1, 1));

        monitor.recordUsage(7L, 150);

        verify(budgetMapper).incrementUsedTokens(eq(7L), eq(150));
        verify(budgetMapper, never()).updateById(any(ScopeBudgetDO.class));
    }

    @Test
    void shouldRecoverWhenBudgetRowCreatedConcurrently() {
        when(budgetMapper.selectOne(any())).thenReturn(null, budgetRow(0, 0, 0), budgetRow(150, 1, 1));
        when(budgetMapper.insert(any(ScopeBudgetDO.class))).thenThrow(new DuplicateKeyException("dup"));

        assertDoesNotThrow(() -> monitor.recordUsage(7L, 150));

        verify(budgetMapper).incrementUsedTokens(eq(7L), eq(150));
    }

    @Test
    void shouldSkipRecordingWhenTokensNotPositive() {
        monitor.recordUsage(7L, 0);
        monitor.recordUsage(7L, -10);

        verify(budgetMapper, never()).incrementUsedTokens(any(), anyInt());
        verify(budgetMapper, never()).insert(any(ScopeBudgetDO.class));
    }

    @Test
    void shouldMarkNotificationFlagsWithoutRewritingUsageRow() {
        when(budgetMapper.selectOne(any())).thenReturn(null, budgetRow(900000, 0, 0));

        monitor.recordUsage(7L, 100000);

        verify(notificationService, times(1)).createNotification(any(), any(), any(), any(), any(), any());
        verify(budgetMapper).update(eq(null), any());
        verify(budgetMapper, never()).updateById(any(ScopeBudgetDO.class));
    }

    @Test
    void shouldResetExpiredBudgetsWhenScheduled() {
        monitor.resetExpiredMonthlyUsage();

        verify(budgetMapper).resetExpiredMonthlyUsage(any(LocalDateTime.class), any(LocalDateTime.class));
    }
}
