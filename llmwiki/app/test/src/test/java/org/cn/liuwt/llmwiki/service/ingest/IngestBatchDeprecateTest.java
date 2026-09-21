package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.model.wiki.WikiPageModel;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.web.controller.WikiController;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestBatchDeprecateTest {

    @Mock private ExecutionMapper executionMapper;
    @Mock private IngestBatchMapper batchMapper;
    @Mock private WikiPageSourceMapper wikiPageSourceMapper;
    @Mock private WikiPageMapper wikiPageMapper;
    @Mock private WikiFileServiceImpl wikiFileService;
    @Mock private NotificationService notificationService;
    @InjectMocks private IngestBatchService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
        TableInfoHelper.initTableInfo(assistant, IngestBatchDO.class);
        TableInfoHelper.initTableInfo(assistant, WikiPageSourceDO.class);
        TableInfoHelper.initTableInfo(assistant, WikiPageDO.class);
    }

    @Test
    void shouldDeprecateOnlyPagesOfBatchSources() {
        IngestBatchDO batch = batch();
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO item1 = item(1L, "completed", 101L);
        ExecutionDO item2 = item(2L, "failed", 102L);
        when(executionMapper.selectList(any())).thenReturn(List.of(item1, item2));
        WikiPageSourceDO link1 = link(201L, 101L);
        WikiPageSourceDO link2 = link(202L, 102L);
        WikiPageSourceDO linkDup = link(201L, 102L);
        when(wikiPageSourceMapper.selectList(any())).thenReturn(List.of(link1, link2, linkDup));
        when(wikiPageMapper.selectById(201L)).thenReturn(page(201L, 10L, "ACTIVE"));
        when(wikiPageMapper.selectById(202L)).thenReturn(page(202L, 10L, "ACTIVE"));

        Map<String, Object> result = service.deprecateBatchOutputs(9L, 7L);

        assertThat(result).containsEntry("deprecatedPages", 2).containsEntry("skippedPages", 0);
        verify(wikiFileService).deprecatePage(eq(201L), eq(10L), contains("9"));
        verify(wikiFileService).deprecatePage(eq(202L), eq(10L), contains("9"));
        ArgumentCaptor<LambdaQueryWrapper<WikiPageSourceDO>> captor = captorForSourceQuery();
        verify(wikiPageSourceMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("scope_id");
        verify(notificationService).createPersonalNotification(eq(7L), eq("batch_outputs_deprecated"),
            any(), contains("已标记废弃"), eq(10L), isNull(), isNull());
    }

    @Test
    void shouldSkipAlreadyDeprecatedPages() {
        IngestBatchDO batch = batch();
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO item1 = item(1L, "completed", 101L);
        when(executionMapper.selectList(any())).thenReturn(List.of(item1));
        WikiPageSourceDO link1 = link(201L, 101L);
        WikiPageSourceDO link2 = link(202L, 101L);
        when(wikiPageSourceMapper.selectList(any())).thenReturn(List.of(link1, link2));
        when(wikiPageMapper.selectById(201L)).thenReturn(page(201L, 10L, "DEPRECATED"));
        when(wikiPageMapper.selectById(202L)).thenReturn(page(202L, 10L, "ACTIVE"));

        Map<String, Object> result = service.deprecateBatchOutputs(9L, 7L);

        assertThat(result).containsEntry("deprecatedPages", 1).containsEntry("skippedPages", 1);
        verify(wikiFileService, never()).deprecatePage(eq(201L), any(), any());
        verify(wikiFileService).deprecatePage(eq(202L), eq(10L), any());
    }

    @Test
    void shouldNotTouchPagesOutsideScope() {
        IngestBatchDO batch = batch();
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO item1 = item(1L, "completed", 101L);
        when(executionMapper.selectList(any())).thenReturn(List.of(item1));
        WikiPageSourceDO link1 = link(201L, 101L);
        when(wikiPageSourceMapper.selectList(any())).thenReturn(List.of(link1));
        when(wikiPageMapper.selectById(201L)).thenReturn(page(201L, 99L, "ACTIVE"));

        Map<String, Object> result = service.deprecateBatchOutputs(9L, 7L);

        assertThat(result).containsEntry("deprecatedPages", 0).containsEntry("skippedPages", 1);
        verify(wikiFileService, never()).deprecatePage(any(), any(), any());
    }

    @Nested
    class ReportIssueTests {

        @Mock private JwtTokenProvider jwtTokenProvider;
        @Mock private WikiFileServiceImpl wikiFileService;
        @Mock private LintFindingService lintFindingService;
        @InjectMocks private WikiController wikiController;

        @Test
        void shouldCreateUserReportFindingAndRecalcHealth() {
            WikiPageModel page = new WikiPageModel();
            page.setId(201L);
            page.setTitle("故障排查指南");
            page.setPath("pages/troubleshooting.md");
            when(jwtTokenProvider.getCurrentScopeId()).thenReturn(10L);
            when(jwtTokenProvider.getCurrentUserId()).thenReturn(7L);
            when(wikiFileService.readPageById(201L, 10L)).thenReturn(page);

            Result<Void> result = wikiController.reportIssue(201L, Map.of("description", "第三步命令有误"));

            assertThat(result.isSuccess()).isTrue();
            verify(lintFindingService).createFinding(eq(10L), isNull(), eq("user_report"), eq("medium"),
                contains("故障排查指南"), eq("第三步命令有误"), eq("pages/troubleshooting.md"), eq(201L),
                argThat(extra -> extra != null && "7".equals(String.valueOf(extra.get("reportedBy")))));
            verify(wikiFileService).recalcPageHealthStatus(10L, 201L);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<LambdaQueryWrapper<WikiPageSourceDO>> captorForSourceQuery() {
        return ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
    }

    private IngestBatchDO batch() {
        IngestBatchDO batch = new IngestBatchDO();
        batch.setId(9L);
        batch.setScopeId(10L);
        batch.setUserId(7L);
        batch.setStatus("completed");
        batch.setTotalCount(2);
        return batch;
    }

    private ExecutionDO item(Long id, String status, Long sourceId) {
        ExecutionDO execution = new ExecutionDO();
        execution.setId(id);
        execution.setType("ingest");
        execution.setScopeId(10L);
        execution.setStatus(status);
        execution.setBatchId(9L);
        execution.setSourceId(sourceId);
        return execution;
    }

    private WikiPageSourceDO link(Long pageId, Long sourceId) {
        WikiPageSourceDO link = new WikiPageSourceDO();
        link.setScopeId(10L);
        link.setPageId(pageId);
        link.setSourceId(sourceId);
        return link;
    }

    private WikiPageDO page(Long id, Long scopeId, String lifecycleStatus) {
        WikiPageDO page = new WikiPageDO();
        page.setId(id);
        page.setScopeId(scopeId);
        page.setLifecycleStatus(lifecycleStatus);
        page.setFilePath("pages/p" + id + ".md");
        page.setTitle("Page " + id);
        return page;
    }
}
