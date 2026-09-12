package org.cn.liuwt.llmwiki.harness;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LintFindingAutoResolveTest {

    @Mock
    private LintFindingMapper lintFindingMapper;

    @InjectMocks
    private LintFindingService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, LintFindingDO.class);
    }

    @Test
    void shouldAutoResolveFindingInFailedStatus() {
        LintFindingDO failed = new LintFindingDO();
        failed.setId(31L);
        failed.setScopeId(1L);
        failed.setStatus("failed");
        when(lintFindingMapper.selectById(31L)).thenReturn(failed);

        service.autoResolve(31L, "ruling_brief");

        verify(lintFindingMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }
}
