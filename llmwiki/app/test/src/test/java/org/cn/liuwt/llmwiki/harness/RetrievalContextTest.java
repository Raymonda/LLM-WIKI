package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.RetrievalContext;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalContextTest {

    private static SearchResultInfo searchResult(String title, String path) {
        SearchResultInfo result = new SearchResultInfo();
        result.setTitle(title);
        result.setPath(path);
        result.setPageType("reference");
        return result;
    }

    private static RetrievalContext.GraphNeighbor neighbor(String anchorTitle) {
        return new RetrievalContext.GraphNeighbor(
            2L, "关联页", "pages/关联页.md", "摘要", "entity", "related",
            "关联语境", "outgoing", "healthy", "ACTIVE", anchorTitle);
    }

    @Test
    void shouldRenderPageExcerptsBlockWhenExcerptsPresent() {
        RetrievalContext ctx = RetrievalContext.builder()
            .searchResults(List.of(searchResult("久期风险", "pages/久期风险.md")))
            .pageExcerpts(List.of(new RetrievalContext.PageExcerpt(
                1L, "久期风险", "pages/久期风险.md", "久期是债券价格对利率变化的敏感度指标。")))
            .build();

        String prompt = ctx.toPromptContextLight();

        assertTrue(prompt.contains("## 页面正文节选"), "excerpt block must be rendered");
        assertTrue(prompt.contains("久期是债券价格对利率变化的敏感度指标。"), "excerpt body must be embedded");
        assertFalse(prompt.contains("预算上限被截断"), "small context must not be truncated");
    }

    @Test
    void shouldAppendLaterBlocksWhenBudgetAllows() {
        RetrievalContext ctx = RetrievalContext.builder()
            .searchResults(List.of(searchResult("页面A", "pages/a.md")))
            .pageExcerpts(List.of(new RetrievalContext.PageExcerpt(1L, "页面A", "pages/a.md", "短节选")))
            .graphNeighbors(List.of(neighbor("页面A")))
            .build();

        String prompt = ctx.toPromptContextLight();

        assertTrue(prompt.contains("## 页面正文节选"));
        assertTrue(prompt.contains("## 知识图谱邻域"), "graph block must follow excerpts when within budget");
        assertFalse(prompt.contains("预算上限被截断"));
    }

    @Test
    void shouldTruncateWithinBudgetWhenExcerptsExceedLimit() {
        String hugeExcerpt = "久期".repeat(40000);
        RetrievalContext ctx = RetrievalContext.builder()
            .searchResults(List.of(searchResult("大页面", "pages/大页面.md")))
            .pageExcerpts(List.of(new RetrievalContext.PageExcerpt(1L, "大页面", "pages/大页面.md", hugeExcerpt)))
            .graphNeighbors(List.of(neighbor("大页面")))
            .build();

        String prompt = ctx.toPromptContextLight();

        assertTrue(prompt.contains("预检索内容已达预算上限被截断"), "oversized block must be truncated with notice");
        assertFalse(prompt.contains("## 知识图谱邻域"), "later blocks must be skipped once budget exhausted");
        assertTrue(prompt.length() < 33000, "prompt must stay near the total budget");
    }
}
