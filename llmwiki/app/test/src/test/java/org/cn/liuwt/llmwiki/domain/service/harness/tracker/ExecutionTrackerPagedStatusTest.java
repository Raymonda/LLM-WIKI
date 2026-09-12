package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionTrackerPagedStatusTest {

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
    void shouldApplyStatusInFilterWhenStatusesProvided() {
        when(executionMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        tracker.listExecutionsPaged(1L, null, List.of("pending", "running", "paused"), 1, 20);

        ArgumentCaptor<Wrapper<ExecutionDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(executionMapper).selectPage(any(), captor.capture());
        LambdaQueryWrapper<ExecutionDO> wrapper = (LambdaQueryWrapper<ExecutionDO>) captor.getValue();
        assertTrue(wrapper.getSqlSegment().toUpperCase().contains("STATUS IN"),
            "paged query must filter by status IN when statuses provided");
        assertTrue(wrapper.getParamNameValuePairs().containsValue("pending"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("running"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("paused"));
    }

    @Test
    void shouldSkipStatusFilterWhenStatusesNull() {
        when(executionMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        tracker.listExecutionsPaged(1L, null, null, 1, 20);

        ArgumentCaptor<Wrapper<ExecutionDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(executionMapper).selectPage(any(), captor.capture());
        LambdaQueryWrapper<ExecutionDO> wrapper = (LambdaQueryWrapper<ExecutionDO>) captor.getValue();
        assertFalse(wrapper.getSqlSegment().toUpperCase().contains("STATUS IN"));
        assertFalse(wrapper.getParamNameValuePairs().containsValue("pending"));
    }
}
