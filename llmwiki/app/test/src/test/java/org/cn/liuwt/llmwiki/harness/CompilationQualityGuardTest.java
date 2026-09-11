package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.quality.CompilationQualityGuard;
import org.cn.liuwt.llmwiki.domain.service.harness.quality.CompilationQualityGuard.GuardResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompilationQualityGuardTest {

    @Test
    void shouldFixLinkedDuplicateNameWhenGuarding() {
        GuardResult r = CompilationQualityGuard.guard("由 [[ 国泰海通 ]]（国泰海通）担任托管人。", null);
        assertEquals("由 [[ 国泰海通 ]]担任托管人。", r.content());
        assertEquals(1, r.fixes().size());
    }

    @Test
    void shouldFixSelfDuplicateNameWithFullAndHalfWidthParensWhenGuarding() {
        GuardResult full = CompilationQualityGuard.guard("托管人为国泰海通证券股份有限公司（国泰海通证券股份有限公司）。", null);
        assertEquals("托管人为国泰海通证券股份有限公司。", full.content());

        GuardResult half = CompilationQualityGuard.guard("托管人为泰康资产管理公司(泰康资产管理公司)。", null);
        assertEquals("托管人为泰康资产管理公司。", half.content());
    }

    @Test
    void shouldKeepLegitimateParensWhenGuarding() {
        String content = "净值数据（来源：月报）（截至 2026-09-11），产品（以下简称\"产品\"）。";
        GuardResult r = CompilationQualityGuard.guard(content, null);
        assertTrue(r.content().contains("（来源：月报）"));
        assertTrue(r.content().contains("（截至 2026-09-11）"));
        assertTrue(r.fixes().isEmpty());
    }

    @Test
    void shouldBeIdempotentWhenGuardingTwice() {
        String content = "托管人为泰康（泰康）。";
        String once = CompilationQualityGuard.guard(content, null).content();
        GuardResult twice = CompilationQualityGuard.guard(once, null);
        assertEquals(once, twice.content());
        assertTrue(twice.fixes().isEmpty());
    }

    @Test
    void shouldRemoveMatchingContentH1WhenTitleProvided() {
        GuardResult r = CompilationQualityGuard.guard("# 华源证券产品信息\n\n## 概述\n内容", "华源证券产品信息");
        assertEquals("## 概述\n内容", r.content());
        assertEquals(1, r.fixes().size());
    }

    @Test
    void shouldKeepH1WhenTitleMissingOrMismatched() {
        GuardResult dormant = CompilationQualityGuard.guard("# 华源证券产品信息\n\n内容", null);
        assertTrue(dormant.content().startsWith("# "));

        GuardResult mismatch = CompilationQualityGuard.guard("# 其他标题\n\n内容", "华源证券产品信息");
        assertTrue(mismatch.content().startsWith("# "));
    }

    @Test
    void shouldWarnOnTruncatedLineAndRepeatedEntityNameWhenGuarding() {
        GuardResult truncated = CompilationQualityGuard.guard("本基金的管理人宣布维持", null);
        assertTrue(truncated.warnings().stream().anyMatch(w -> w.contains("疑似截断行")), truncated.warnings().toString());

        String paragraph = "国泰海通证券股份有限公司担任托管人，国泰海通证券股份有限公司负责清算，"
            + "国泰海通证券股份有限公司收取费用，国泰海通证券股份有限公司发布公告。";
        GuardResult repeated = CompilationQualityGuard.guard(paragraph, null);
        assertTrue(repeated.warnings().stream().anyMatch(w -> w.contains("实体名重复频次异常")), repeated.warnings().toString());
    }
}
