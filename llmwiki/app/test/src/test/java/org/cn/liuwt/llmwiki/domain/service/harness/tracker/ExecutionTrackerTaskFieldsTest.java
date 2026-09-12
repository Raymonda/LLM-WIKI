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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionTrackerTaskFieldsTest {

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
    void shouldMapPayloadAndSubmitterWhenLoadingExecution() {
        ExecutionDO executionDO = new ExecutionDO();
        executionDO.setId(9L);
        executionDO.setType("query_save");
        executionDO.setStatus("pending");
        executionDO.setScopeId(1L);
        executionDO.setPayloadJson("{\"question\":\"q\",\"answer\":\"a\"}");
        executionDO.setSubmittedBy(7L);
        when(executionMapper.selectById(9L)).thenReturn(executionDO);
        when(executionStepMapper.selectList(any())).thenReturn(List.of());

        ExecutionModel model = tracker.getExecution(9L);

        assertEquals("{\"question\":\"q\",\"answer\":\"a\"}", model.getPayloadJson());
        assertEquals(7L, model.getSubmittedBy());
    }
}
