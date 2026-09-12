package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionStepDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionStepMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalServiceImplTest {

    @Mock
    private ExecutionStepMapper executionStepMapper;

    @Mock
    private ExecutionMapper executionMapper;

    @InjectMocks
    private ApprovalServiceImpl approvalService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
        TableInfoHelper.initTableInfo(assistant, ExecutionStepDO.class);
    }

    private static ExecutionStepDO step(long id, long executionId, String status) {
        ExecutionStepDO step = new ExecutionStepDO();
        step.setId(id);
        step.setExecutionId(executionId);
        step.setStatus(status);
        return step;
    }

    private static ExecutionDO execution(long id, long scopeId) {
        ExecutionDO execution = new ExecutionDO();
        execution.setId(id);
        execution.setScopeId(scopeId);
        return execution;
    }

    @Test
    void approveThrowsWhenStepNotFound() {
        when(executionStepMapper.selectById(5L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
            () -> approvalService.approveStep(5L, 7L, 100L));
        assertEquals(ErrorCode.STEP_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void approveThrowsWhenExecutionMissing() {
        when(executionStepMapper.selectById(6L)).thenReturn(step(6L, 30L, "awaiting_approval"));
        when(executionMapper.selectById(30L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
            () -> approvalService.approveStep(6L, 7L, 100L));
        assertEquals(ErrorCode.STEP_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void approveThrowsWhenScopeMismatch() {
        when(executionStepMapper.selectById(6L)).thenReturn(step(6L, 30L, "awaiting_approval"));
        when(executionMapper.selectById(30L)).thenReturn(execution(30L, 999L));
        BusinessException ex = assertThrows(BusinessException.class,
            () -> approvalService.approveStep(6L, 7L, 100L));
        assertEquals(ErrorCode.STEP_SCOPE_MISMATCH.getCode(), ex.getCode());
    }

    @Test
    void approveThrowsWhenStepAlreadyDecided() {
        when(executionStepMapper.selectById(8L)).thenReturn(step(8L, 30L, "approved"));
        when(executionMapper.selectById(30L)).thenReturn(execution(30L, 100L));
        BusinessException ex = assertThrows(BusinessException.class,
            () -> approvalService.approveStep(8L, 7L, 100L));
        assertEquals(ErrorCode.STEP_NOT_AWAITING_DECISION.getCode(), ex.getCode());
    }

    @Test
    void approveUpdatesStatusAndApproverWhenStepAwaiting() {
        when(executionStepMapper.selectById(9L)).thenReturn(step(9L, 30L, "awaiting_approval"));
        when(executionMapper.selectById(30L)).thenReturn(execution(30L, 100L));
        when(executionStepMapper.update(isNull(), any())).thenReturn(1);
        assertTrue(approvalService.approveStep(9L, 7L, 100L));
    }

    @Test
    void approveThrowsWhenCasLosesRace() {
        when(executionStepMapper.selectById(10L)).thenReturn(step(10L, 30L, "pending"));
        when(executionMapper.selectById(30L)).thenReturn(execution(30L, 100L));
        when(executionStepMapper.update(isNull(), any())).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
            () -> approvalService.approveStep(10L, 7L, 100L));
        assertEquals(ErrorCode.STEP_NOT_AWAITING_DECISION.getCode(), ex.getCode());
    }
}
