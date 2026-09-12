package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LintProbeService;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ContentDuplicateDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LintProbeConflictMappingTest {

    @Mock
    private LintFindingService lintFindingService;

    private LintProbeService newService() {
        LintProbeService service = new LintProbeService();
        inject(service, "lintFindingService", lintFindingService);
        return service;
    }

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = LintProbeService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            fail("Failed to inject " + field + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokePrivate(Object target, String methodName, Class<?>[] paramTypes, Object... args) {
        try {
            java.lang.reflect.Method m = LintProbeService.class.getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            return (T) m.invoke(target, args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke " + methodName, e);
        }
    }

    private static Map<String, Object> aiConflictFinding() {
        Map<String, Object> f = new HashMap<>();
        f.put("type", "conflict");
        f.put("pagePath", "entities/trump.md");
        f.put("newClaim", "税率是10%");
        f.put("existingClaim", "税率是15%");
        f.put("conflictType", "value_conflict");
        f.put("relatedPagePath", "entities/biden.md");
        f.put("relatedPageId", 42);
        f.put("relatedPageTitle", "拜登");
        return f;
    }

    @Test
    void shouldMapNewClaimToClaimAAndExistingClaimToClaimB() {
        Map<String, Object> extra = invokePrivate(newService(), "buildExtraFromAiFinding",
            new Class<?>[]{Map.class}, aiConflictFinding());

        assertEquals("税率是10%", extra.get("claimA"));
        assertEquals("税率是15%", extra.get("claimB"));
        assertEquals("value_conflict", extra.get("conflictType"));
        assertEquals("entities/trump.md", extra.get("fromPagePath"));
        assertEquals("lint_probe", extra.get("source"));
        assertEquals("entities/biden.md", extra.get("relatedPagePath"));
        assertEquals(42, extra.get("relatedPageId"));
        assertEquals("拜登", extra.get("relatedPageTitle"));
    }

    @Test
    void shouldNotWriteLegacyClaimKeys() {
        Map<String, Object> extra = invokePrivate(newService(), "buildExtraFromAiFinding",
            new Class<?>[]{Map.class}, aiConflictFinding());

        assertFalse(extra.containsKey("newClaim"));
        assertFalse(extra.containsKey("existingClaim"));
    }

    @Test
    void shouldBuildConflictCardFromMergedFinding() {
        Map<String, Object> extra = new HashMap<>();
        extra.put("relatedPagePath", "entities/biden.md");
        extra.put("relatedPageId", 42);
        extra.put("relatedPageTitle", "拜登");
        extra.put("conflictType", "value_conflict");
        extra.put("claimA", "税率是10%");
        extra.put("claimB", "税率是15%");
        extra.put("source", "lint_probe");
        LintProbeService.MergedFinding mf = new LintProbeService.MergedFinding(
            "conflict", "high", "冲突标题", "冲突详情",
            "entities/trump.md", 11L, extra, true);

        LintFindingService.ConflictCard card = invokePrivate(newService(), "toConflictCard",
            new Class<?>[]{LintProbeService.MergedFinding.class}, mf);

        assertEquals("entities/trump.md", card.fromPagePath());
        assertEquals(11L, card.fromPageId());
        assertEquals("entities/biden.md", card.relatedPagePath());
        assertEquals(42L, card.relatedPageId());
        assertEquals("拜登", card.relatedPageTitle());
        assertEquals("value_conflict", card.conflictType());
        assertEquals("税率是10%", card.claimA());
        assertEquals("税率是15%", card.claimB());
        assertEquals("lint_probe", card.source());
        assertEquals("high", card.priority());
    }

    @Test
    void shouldDefaultMissingConflictKeysWhenBuildingCard() {
        Map<String, Object> extra = new HashMap<>();
        LintProbeService.MergedFinding mf = new LintProbeService.MergedFinding(
            "conflict", "medium", "孤立冲突", "无相关页信息",
            "entities/trump.md", null, extra, false);

        LintFindingService.ConflictCard card = invokePrivate(newService(), "toConflictCard",
            new Class<?>[]{LintProbeService.MergedFinding.class}, mf);

        assertNull(card.relatedPageId());
        assertNull(card.relatedPagePath());
        assertEquals("fact_conflict", card.conflictType());
        assertEquals("", card.claimA());
        assertEquals("", card.claimB());
        assertEquals("lint_probe", card.source());
    }

    @Test
    void shouldBuildContentDuplicationCardWithEmptyClaims() {
        WikiPageDO pageA = new WikiPageDO();
        pageA.setId(11L);
        pageA.setFilePath("entities/trump.md");
        pageA.setTitle("特朗普");
        WikiPageDO pageB = new WikiPageDO();
        pageB.setId(42L);
        pageB.setFilePath("entities/biden.md");
        pageB.setTitle("拜登");

        List<LintProbeService.MergedFinding> merged = new ArrayList<>();
        List<LintProbeService.MergedFinding> result = invokePrivate(newService(), "appendContentDuplicateFindings",
            new Class<?>[]{List.class, List.class}, merged,
            List.of(new ContentDuplicateDetector.DuplicatePair(pageA, pageB, 0.92)));

        assertEquals(1, result.size());
        LintProbeService.MergedFinding dup = result.get(0);
        assertEquals("conflict", dup.type);
        assertEquals("entities/trump.md", dup.extra.get("fromPagePath"));
        assertEquals("entities/biden.md", dup.extra.get("relatedPagePath"));
        assertEquals("content_duplication", dup.extra.get("conflictType"));
        assertEquals("content_duplicate_detector", dup.extra.get("source"));
        assertEquals("", dup.extra.get("claimA"));
        assertEquals("", dup.extra.get("claimB"));
    }

    @Test
    void shouldRouteConflictToUpsertAndOtherTypesToCreateFinding() {
        Map<String, Object> conflictExtra = new HashMap<>();
        conflictExtra.put("relatedPageId", 42);
        conflictExtra.put("relatedPagePath", "entities/biden.md");
        conflictExtra.put("conflictType", "value_conflict");
        conflictExtra.put("claimA", "税率是10%");
        conflictExtra.put("claimB", "税率是15%");
        LintProbeService.MergedFinding conflictMf = new LintProbeService.MergedFinding(
            "conflict", "high", "冲突标题", "冲突详情",
            "entities/trump.md", 11L, conflictExtra, true);
        LintProbeService.MergedFinding orphanMf = new LintProbeService.MergedFinding(
            "orphan", "medium", "孤立页面", "无入站链接",
            "entities/orphan.md", 77L, new HashMap<>(), false);

        when(lintFindingService.upsertConflictFinding(eq(1L), eq(100L), any())).thenReturn(7L);
        when(lintFindingService.createFinding(eq(1L), eq(100L), eq("orphan"),
            any(), any(), any(), any(), any(), any(), any())).thenReturn(8L);

        Set<Long> touched = invokePrivate(newService(), "persistMergedFindings",
            new Class<?>[]{Long.class, Long.class, List.class,
                org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig.class},
            1L, 100L, List.of(conflictMf, orphanMf), null);

        assertEquals(Set.of(7L, 8L), touched);
        verify(lintFindingService).upsertConflictFinding(eq(1L), eq(100L), any());
        verify(lintFindingService).createFinding(eq(1L), eq(100L), eq("orphan"),
            any(), any(), any(), any(), any(), any(), any());
    }
}
