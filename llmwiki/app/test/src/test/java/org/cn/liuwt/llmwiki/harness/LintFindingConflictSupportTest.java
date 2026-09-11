package org.cn.liuwt.llmwiki.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LintFindingConflictSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, LintFindingDO.class);
    }

    @Mock
    private LintFindingMapper lintFindingMapper;

    @Mock
    private WikiPageMapper wikiPageMapper;

    private LintFindingService newService() {
        LintFindingService service = new LintFindingService();
        inject(service, "lintFindingMapper", lintFindingMapper);
        inject(service, "wikiPageMapper", wikiPageMapper);
        return service;
    }

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = LintFindingService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            fail("Failed to inject " + field + ": " + e.getMessage());
        }
    }

    private static LintFindingService.ConflictCard card() {
        return new LintFindingService.ConflictCard(
            "特朗普与拜登对X的看法矛盾",
            "页面A: entities/trump.md\n声明A: 税率是10%\n页面B: entities/biden.md\n声明B: 税率是15%",
            "medium",
            "entities/trump.md", 11L, "特朗普",
            "entities/biden.md", 42L, "拜登",
            "fact_conflict", "税率是10%", "税率是15%", "ingest_fact_conflict");
    }

    private static LintFindingDO candidate(long id, Long assetId, String extra) {
        LintFindingDO f = new LintFindingDO();
        f.setId(id);
        f.setAssetId(assetId);
        f.setPagePath("entities/trump.md");
        f.setStatus("open");
        f.setExtra(extra);
        return f;
    }

    @Test
    void shouldInsertCanonicalFindingWhenNoCandidateMatches() throws Exception {
        when(lintFindingMapper.selectList(any())).thenReturn(List.of());
        when(lintFindingMapper.selectOne(any())).thenReturn(null);
        when(lintFindingMapper.insert(any(LintFindingDO.class))).thenAnswer(inv -> {
            ((LintFindingDO) inv.getArgument(0)).setId(99L);
            return 1;
        });

        Long id = newService().upsertConflictFinding(1L, 100L, card());

        assertEquals(99L, id);
        ArgumentCaptor<LintFindingDO> captor = ArgumentCaptor.forClass(LintFindingDO.class);
        verify(lintFindingMapper).insert(captor.capture());
        LintFindingDO inserted = captor.getValue();
        assertEquals("conflict", inserted.getFindingType());
        assertEquals("open", inserted.getStatus());
        assertEquals(11L, inserted.getAssetId());
        assertEquals("entities/trump.md", inserted.getPagePath());
        Map<String, Object> extra = MAPPER.readValue(inserted.getExtra(), Map.class);
        assertEquals(11L, ((Number) extra.get("fromPageId")).longValue());
        assertEquals("entities/trump.md", extra.get("fromPagePath"));
        assertEquals("特朗普", extra.get("fromPageTitle"));
        assertEquals(42L, ((Number) extra.get("relatedPageId")).longValue());
        assertEquals("entities/biden.md", extra.get("relatedPagePath"));
        assertEquals("fact_conflict", extra.get("conflictType"));
        assertEquals("税率是10%", extra.get("claimA"));
        assertEquals("税率是15%", extra.get("claimB"));
        assertEquals("ingest_fact_conflict", extra.get("source"));
    }

    @Test
    void shouldUpdateExistingFindingWhenSamePagePairReportedAgain() throws Exception {
        LintFindingDO existing = candidate(7L, 11L,
            "{\"relatedPageId\":42,\"claimA\":\"旧A\",\"claimB\":\"旧B\"}");
        when(lintFindingMapper.selectList(any())).thenReturn(List.of(existing));
        when(lintFindingMapper.updateById(any(LintFindingDO.class))).thenReturn(1);

        Long id = newService().upsertConflictFinding(1L, 100L, card());

        assertEquals(7L, id);
        verify(lintFindingMapper, never()).insert(any(LintFindingDO.class));
        ArgumentCaptor<LintFindingDO> captor = ArgumentCaptor.forClass(LintFindingDO.class);
        verify(lintFindingMapper).updateById(captor.capture());
        Map<String, Object> extra = MAPPER.readValue(captor.getValue().getExtra(), Map.class);
        assertEquals("entities/trump.md", extra.get("fromPagePath"));
        assertEquals("税率是10%", extra.get("claimA"));
        assertEquals("税率是15%", extra.get("claimB"));
    }

    @Test
    void shouldMatchReversedPagePairAndRewriteToCurrentDirection() throws Exception {
        LintFindingDO reversed = candidate(8L, 42L,
            "{\"relatedPageId\":11,\"claimA\":\"B主张\",\"claimB\":\"A主张\"}");
        when(lintFindingMapper.selectList(any())).thenReturn(List.of(reversed));
        when(lintFindingMapper.updateById(any(LintFindingDO.class))).thenReturn(1);

        Long id = newService().upsertConflictFinding(1L, 100L, card());

        assertEquals(8L, id);
        ArgumentCaptor<LintFindingDO> captor = ArgumentCaptor.forClass(LintFindingDO.class);
        verify(lintFindingMapper).updateById(captor.capture());
        LintFindingDO updated = captor.getValue();
        assertEquals(11L, updated.getAssetId());
        Map<String, Object> extra = MAPPER.readValue(updated.getExtra(), Map.class);
        assertEquals("税率是10%", extra.get("claimA"));
        assertEquals("税率是15%", extra.get("claimB"));
        verify(lintFindingMapper, never()).insert(any(LintFindingDO.class));
    }

    @Test
    void shouldRepairLegacyFindingMatchedByPath() throws Exception {
        LintFindingDO legacy = new LintFindingDO();
        legacy.setId(9L);
        legacy.setAssetId(null);
        legacy.setPagePath("entities/trump.md");
        legacy.setStatus("open");
        legacy.setExtra("{\"pagePathB\":\"entities/biden.md\",\"claimA\":\"旧A\",\"claimB\":\"旧B\"}");
        when(lintFindingMapper.selectList(any())).thenReturn(List.of(legacy));
        when(lintFindingMapper.updateById(any(LintFindingDO.class))).thenReturn(1);

        Long id = newService().upsertConflictFinding(1L, 100L, card());

        assertEquals(9L, id);
        ArgumentCaptor<LintFindingDO> captor = ArgumentCaptor.forClass(LintFindingDO.class);
        verify(lintFindingMapper).updateById(captor.capture());
        LintFindingDO updated = captor.getValue();
        assertEquals(11L, updated.getAssetId());
        Map<String, Object> extra = MAPPER.readValue(updated.getExtra(), Map.class);
        assertFalse(extra.containsKey("pagePathB"));
        assertEquals(42L, ((Number) extra.get("relatedPageId")).longValue());
        assertEquals("entities/biden.md", extra.get("relatedPagePath"));
    }

    @Test
    void shouldSkipWhenDismissedAndPageUnchanged() {
        when(lintFindingMapper.selectList(any())).thenReturn(List.of());
        LintFindingDO dismissed = new LintFindingDO();
        dismissed.setId(5L);
        dismissed.setAssetId(11L);
        dismissed.setUpdatedAt(LocalDateTime.now().minusDays(3));
        when(lintFindingMapper.selectOne(any())).thenReturn(dismissed);
        when(wikiPageMapper.selectById(11L)).thenReturn(null);

        Long id = newService().upsertConflictFinding(1L, 100L, card());

        assertNull(id);
        verify(lintFindingMapper, never()).insert(any(LintFindingDO.class));
    }

    @Test
    void shouldReviveWhenPageUpdatedAfterDismiss() {
        when(lintFindingMapper.selectList(any())).thenReturn(List.of());
        LintFindingDO dismissed = new LintFindingDO();
        dismissed.setId(5L);
        dismissed.setAssetId(11L);
        dismissed.setUpdatedAt(LocalDateTime.now().minusDays(2));
        when(lintFindingMapper.selectOne(any())).thenReturn(dismissed);
        WikiPageDO page = new WikiPageDO();
        page.setId(11L);
        page.setContentUpdatedAt(LocalDateTime.now().minusDays(1));
        when(wikiPageMapper.selectById(11L)).thenReturn(page);
        when(lintFindingMapper.insert(any(LintFindingDO.class))).thenAnswer(inv -> {
            ((LintFindingDO) inv.getArgument(0)).setId(100L);
            return 1;
        });

        Long id = newService().upsertConflictFinding(1L, 100L, card());

        assertEquals(100L, id);
        verify(lintFindingMapper).insert(any(LintFindingDO.class));
    }

    @Test
    void shouldAutoResolveDeferredFinding() {
        LintFindingDO finding = new LintFindingDO();
        finding.setId(21L);
        finding.setScopeId(1L);
        finding.setStatus("deferred");
        finding.setFindingType("conflict");
        when(lintFindingMapper.selectById(21L)).thenReturn(finding);
        when(lintFindingMapper.update(any(), any())).thenReturn(1);

        newService().autoResolve(21L, "ruling_brief");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<LintFindingDO>> captor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(lintFindingMapper).update(any(), captor.capture());
        captor.getValue().getSqlSegment();
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("auto_resolved"));
    }
}
