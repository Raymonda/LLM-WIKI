package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.ingest.EntityPageIncrementalApplier;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.EntityPageIncrementalApplier.ApplyResult;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.EntityPageIncrementalApplier.CandidateEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntityPageIncrementalApplierTest {

    @Test
    void shouldAppendSourceWhenDuplicateOf() {
        String existing = "## 基本信息\n\n- 总部位于上海（来源：年报）\n";
        ApplyResult r = EntityPageIncrementalApplier.apply(existing,
            List.of(new CandidateEntry("基本信息", "总部位于上海", "招股说明书", null, "duplicate_of:0")));

        assertEquals(0, r.added());
        assertEquals(1, r.mergedSources());
        assertTrue(r.content().contains("- 总部位于上海（来源：年报；招股说明书）"), r.content());
    }

    @Test
    void shouldInsertNewEntryAfterExistingWhenConflictWith() {
        String existing = "## 基本信息\n\n- 注册资本 10 亿元（来源：年报）\n\n## 关联关系\n\n- 控股股东为甲集团（来源：年报）\n";
        ApplyResult r = EntityPageIncrementalApplier.apply(existing,
            List.of(new CandidateEntry("基本信息", "注册资本 12 亿元", "招股说明书",
                "原文：注册资本为人民币12亿元", "conflict_with:0")));

        assertEquals(1, r.added());
        assertEquals(1, r.conflicts().size());
        assertEquals("注册资本 10 亿元", r.conflicts().get(0).existingClaim());
        assertTrue(r.content().contains("- 注册资本 10 亿元（来源：年报）"), r.content());
        assertTrue(r.content().contains("- 注册资本 12 亿元（来源：招股说明书）"), r.content());
        assertTrue(r.content().contains("> 原文：注册资本为人民币12亿元"), r.content());
        int oldIdx = r.content().indexOf("- 注册资本 10 亿元（来源：年报）");
        int newIdx = r.content().indexOf("- 注册资本 12 亿元（来源：招股说明书）");
        assertTrue(newIdx > oldIdx, "new entry must follow the existing one");
        assertTrue(r.content().contains("- 控股股东为甲集团（来源：年报）"), "untouched lines must stay");
    }

    @Test
    void shouldInsertNewEntryAtSectionEndWhenNew() {
        String existing = "## 基本信息\n\n- 条目一（来源：年报）\n\n## 关联关系\n\n- 条目二（来源：年报）\n";
        ApplyResult r = EntityPageIncrementalApplier.apply(existing,
            List.of(new CandidateEntry("基本信息", "条目三", "招股说明书", null, "new")));

        assertEquals(1, r.added());
        int i1 = r.content().indexOf("- 条目一（来源：年报）");
        int i3 = r.content().indexOf("- 条目三（来源：招股说明书）");
        int i2 = r.content().indexOf("- 条目二（来源：年报）");
        assertTrue(i1 < i3 && i3 < i2, "new entry must land at the end of the target section: " + r.content());
    }

    @Test
    void shouldCreateSectionWhenMissing() {
        String existing = "## 基本信息\n\n- 条目一（来源：年报）\n";
        ApplyResult r = EntityPageIncrementalApplier.apply(existing,
            List.of(new CandidateEntry("补充信息", "新增条目", "招股说明书", null, "new")));

        assertTrue(r.content().contains("## 补充信息"), r.content());
        assertTrue(r.content().contains("- 新增条目（来源：招股说明书）"), r.content());
    }

    @Test
    void shouldSkipWhenIndexOutOfRange() {
        String existing = "## 基本信息\n\n- 条目一（来源：年报）\n";
        ApplyResult r = EntityPageIncrementalApplier.apply(existing,
            List.of(new CandidateEntry("基本信息", "其他", "招股说明书", null, "duplicate_of:9")));

        assertEquals(0, r.mergedSources());
        assertEquals(1, r.skipped().size());
        assertEquals(existing, r.content());
    }

    @Test
    void shouldTreatExactDuplicateNewAsSourceAppend() {
        String existing = "## 基本信息\n\n- 条目一（来源：年报）\n";
        ApplyResult r = EntityPageIncrementalApplier.apply(existing,
            List.of(new CandidateEntry("基本信息", "条目一", "招股说明书", null, "new")));

        assertEquals(0, r.added());
        assertEquals(1, r.mergedSources());
        assertTrue(r.content().contains("- 条目一（来源：年报；招股说明书）"), r.content());
    }

    @Test
    void shouldApplyMultipleCandidatesInOnePass() {
        String existing = "## 基本信息\n\n- 条目一（来源：年报）\n";
        ApplyResult r = EntityPageIncrementalApplier.apply(existing, List.of(
            new CandidateEntry("基本信息", "条目一", "招股说明书", null, "new"),
            new CandidateEntry("基本信息", "条目二", "招股说明书", null, "new"),
            new CandidateEntry("基本信息", "条目三", "招股说明书", null, "conflict_with:0")));

        assertEquals(2, r.added());
        assertEquals(1, r.mergedSources());
        assertEquals(1, r.conflicts().size());
        int i1 = r.content().indexOf("- 条目一（来源：年报；招股说明书）");
        int i3 = r.content().indexOf("- 条目三（来源：招股说明书）");
        int i2 = r.content().indexOf("- 条目二（来源：招股说明书）");
        assertTrue(i1 < i3 && i3 < i2, r.content());
    }
}
