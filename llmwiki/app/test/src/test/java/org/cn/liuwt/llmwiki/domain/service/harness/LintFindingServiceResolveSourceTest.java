package org.cn.liuwt.llmwiki.domain.service.harness;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LintFindingServiceResolveSourceTest {

    @Mock
    private LintFindingMapper lintFindingMapper;

    @Mock
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Mock
    private WikiFileServiceImpl wikiFileService;

    @InjectMocks
    private LintFindingService lintFindingService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, LintFindingDO.class);
        TableInfoHelper.initTableInfo(assistant, WikiPageSourceDO.class);
    }

    @Test
    void shouldAutoResolveFindingsAndRecalcHealthWhenSourceRestored() {
        WikiPageSourceDO link = new WikiPageSourceDO();
        link.setScopeId(10L);
        link.setSourceId(5L);
        link.setPageId(77L);
        when(wikiPageSourceMapper.selectList(any())).thenReturn(List.of(link));
        LintFindingDO finding = new LintFindingDO();
        finding.setId(900L);
        finding.setScopeId(10L);
        finding.setAssetId(77L);
        finding.setStatus("open");
        when(lintFindingMapper.selectList(any())).thenReturn(List.of(finding));

        lintFindingService.resolveSourceFindingsOnUndeprecate(10L, 5L);

        verify(lintFindingMapper).update(any(), any(LambdaUpdateWrapper.class));
        verify(wikiFileService).recalcPageHealthStatus(10L, 77L);
    }

    @Test
    void shouldSkipAllUpdatesWhenSourceHasNoLinkedPages() {
        when(wikiPageSourceMapper.selectList(any())).thenReturn(List.of());

        lintFindingService.resolveSourceFindingsOnUndeprecate(10L, 5L);

        verifyNoInteractions(lintFindingMapper);
        verifyNoInteractions(wikiFileService);
    }

    @Test
    void shouldNotRecalcHealthWhenNoOpenFindingsExist() {
        WikiPageSourceDO link = new WikiPageSourceDO();
        link.setScopeId(10L);
        link.setSourceId(5L);
        link.setPageId(77L);
        when(wikiPageSourceMapper.selectList(any())).thenReturn(List.of(link));
        when(lintFindingMapper.selectList(any())).thenReturn(List.of());

        lintFindingService.resolveSourceFindingsOnUndeprecate(10L, 5L);

        verifyNoInteractions(wikiFileService);
    }
}
