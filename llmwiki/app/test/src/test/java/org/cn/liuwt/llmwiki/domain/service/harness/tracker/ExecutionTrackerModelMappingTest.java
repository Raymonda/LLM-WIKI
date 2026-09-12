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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionTrackerModelMappingTest {

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
    void shouldMapBatchIdIntoExecutionModelWhenExecutionHasBatch() {
        ExecutionDO batchItem = new ExecutionDO();
        batchItem.setId(1L);
        batchItem.setType("ingest");
        batchItem.setStatus("pending");
        batchItem.setScopeId(10L);
        batchItem.setBatchId(9L);
        when(executionMapper.selectById(1L)).thenReturn(batchItem);
        when(executionStepMapper.selectList(any())).thenReturn(List.of());

        ExecutionModel model = tracker.getExecution(1L);

        assertEquals(9L, model.getBatchId());
    }

    @Test
    void shouldLeaveBatchIdNullWhenExecutionIsStandalone() {
        ExecutionDO standalone = new ExecutionDO();
        standalone.setId(2L);
        standalone.setType("ingest");
        standalone.setStatus("pending");
        standalone.setScopeId(10L);
        when(executionMapper.selectById(2L)).thenReturn(standalone);
        when(executionStepMapper.selectList(any())).thenReturn(List.of());

        ExecutionModel model = tracker.getExecution(2L);

        assertNull(model.getBatchId());
    }
}
