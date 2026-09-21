package org.cn.liuwt.llmwiki.harness;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaSection6Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LintFindingDedupTest {

    @Mock
    private LintFindingMapper lintFindingMapper;

    @Mock
    private SchemaSection6Parser schemaSection6Parser;

    @InjectMocks
    private LintFindingService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, LintFindingDO.class);
    }

    @Test
    void shouldMatchPagePathWhenAssetIdNullAndPagePathPresent() {
        when(schemaSection6Parser.parse(any())).thenReturn(new LintRulesConfig());

        service.createFinding(10L, 501L, "ingest_quality", "high",
            "title", "detail", "pages/Gamma.md", null, null);

        ArgumentCaptor<LambdaQueryWrapper<LintFindingDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(lintFindingMapper, atLeastOnce()).selectOne(captor.capture());
        boolean hasPagePathCondition = captor.getAllValues().stream()
            .anyMatch(w -> w.getSqlSegment() != null && w.getSqlSegment().contains("page_path"));
        assertTrue(hasPagePathCondition);
    }

    @Test
    void shouldNotMatchPagePathWhenAssetIdPresent() {
        when(schemaSection6Parser.parse(any())).thenReturn(new LintRulesConfig());

        service.createFinding(10L, 501L, "ingest_quality", "high",
            "title", "detail", "pages/Gamma.md", 1001L, null);

        ArgumentCaptor<LambdaQueryWrapper<LintFindingDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(lintFindingMapper, atLeastOnce()).selectOne(captor.capture());
        boolean hasPagePathCondition = captor.getAllValues().stream()
            .anyMatch(w -> w.getSqlSegment() != null && w.getSqlSegment().contains("page_path"));
        assertFalse(hasPagePathCondition);
    }

    @Test
    void shouldNotMatchPagePathWhenBlank() {
        when(schemaSection6Parser.parse(any())).thenReturn(new LintRulesConfig());

        service.createFinding(10L, 501L, "ingest_quality", "high",
            "title", "detail", "  ", null, null);

        ArgumentCaptor<LambdaQueryWrapper<LintFindingDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(lintFindingMapper, atLeastOnce()).selectOne(captor.capture());
        boolean hasPagePathCondition = captor.getAllValues().stream()
            .anyMatch(w -> w.getSqlSegment() != null && w.getSqlSegment().contains("page_path"));
        assertFalse(hasPagePathCondition);
    }
}
