package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionStepDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionStepMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionTrackerResetRetryTest {

    @Mock
    private ExecutionMapper executionMapper;

    @Mock
    private ExecutionStepMapper executionStepMapper;

    @Mock
    private DistributedEventPublisher eventPublisher;

    @InjectMocks
    private ExecutionTrackerImpl tracker;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
        TableInfoHelper.initTableInfo(assistant, ExecutionStepDO.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldClearErrorMessageAndCompletedAtWhenResetForRetry() {
        ExecutionDO failed = new ExecutionDO();
        failed.setId(9L);
        failed.setType("query_save");
        failed.setStatus("failed");
        failed.setErrorMessage("old error");
        when(executionMapper.selectById(9L)).thenReturn(failed);

        tracker.resetExecutionForRetry(9L);

        ArgumentCaptor<LambdaUpdateWrapper<ExecutionDO>> captor =
            ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(executionMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("status"));
        assertTrue(sqlSet.contains("error_message"));
        assertTrue(sqlSet.contains("completed_at"));
    }
}
