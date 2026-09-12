package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionStepDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionStepMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionTrackerTaskTest {

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
    void shouldPersistPayloadAndSubmitterWhenCreatingTaskExecution() {
        when(executionMapper.insert(any(ExecutionDO.class))).thenAnswer(inv -> {
            ExecutionDO inserted = inv.getArgument(0);
            inserted.setId(100L);
            return 1;
        });

        ExecutionModel model = tracker.createTaskExecution("query_save", 1L, "{\"question\":\"q\"}", 7L);

        ArgumentCaptor<ExecutionDO> captor = ArgumentCaptor.forClass(ExecutionDO.class);
        verify(executionMapper).insert(captor.capture());
        assertEquals("pending", captor.getValue().getStatus());
        assertEquals("query_save", captor.getValue().getType());
        assertEquals("{\"question\":\"q\"}", captor.getValue().getPayloadJson());
        assertEquals(7L, captor.getValue().getSubmittedBy());
        assertEquals(100L, model.getId());
    }

    @Test
    void shouldReturnActiveExecutionsOfSameTypeAndScope() {
        ExecutionDO active = new ExecutionDO();
        active.setId(5L);
        active.setType("conflict_ruling");
        active.setStatus("running");
        active.setScopeId(1L);
        when(executionMapper.selectList(any())).thenReturn(List.of(active));

        List<ExecutionModel> result = tracker.listActiveExecutions(1L, "conflict_ruling");

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).getId());
        verify(executionMapper).selectList(any());
    }

    @Test
    void shouldDeleteAllStepsOfExecution() {
        tracker.deleteSteps(55L);

        verify(executionStepMapper).delete(any());
    }
}
