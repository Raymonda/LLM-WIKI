package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;

import java.util.ArrayList;
import java.util.List;

public class RetrievalContext {

    public record ParsedSection(Long sourceId, String sourceName, String sectionContent) {}

    public record GraphNeighbor(
        Long pageId, String title, String path, String summary,
        String pageType, String linkType, String linkContext,
        String direction, String healthStatus, String lifecycleStatus,
        String anchorTitle
    ) {}

    private GlobalSummaryService.GlobalSummary globalSummary;
    private int pageCount;
    private List<SearchResultInfo> searchResults;
    private List<ParsedSection> parsedSourceSections;
    private List<GraphNeighbor> graphNeighbors;

    private RetrievalContext() {
        this.searchResults = new ArrayList<>();
        this.parsedSourceSections = new ArrayList<>();
        this.graphNeighbors = new ArrayList<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public GlobalSummaryService.GlobalSummary getGlobalSummary() {
        return globalSummary;
    }

    public int getPageCount() {
        return pageCount;
    }

    public List<SearchResultInfo> getSearchResults() {
        return searchResults;
    }

    public List<ParsedSection> getParsedSourceSections() {
        return parsedSourceSections;
    }

    public List<GraphNeighbor> getGraphNeighbors() {
        return graphNeighbors;
    }

    public String toPromptContextLight() {
        StringBuilder sb = new StringBuilder();
    
        if (globalSummary != null) {
            sb.append(globalSummary.toCompactPrompt());
            sb.append("\n\n");
        }
    
        List<SearchResultInfo> activeResults = searchResults.stream()
            .filter(r -> !"DEPRECATED".equals(r.getLifecycleStatus()))
            .toList();
        if (!activeResults.isEmpty()) {
            sb.append("## ES 搜索结果（标题 + 摘要 + 匹配片段，未读取全文）\n\n");
            sb.append("以下页面按相关度排序。请根据摘要和匹配片段判断哪些页面与问题真正相关，再用 readFile 或 readFileSection 定向读取。\n\n");
    
            int limit = Math.min(15, activeResults.size());
            for (int i = 0; i < limit; i++) {
                SearchResultInfo r = activeResults.get(i);
                sb.append("### ").append(i + 1).append(". ").append(r.getTitle());
                if (r.getCategory() != null) {
                    sb.append(" [").append(r.getCategory()).append("]");
                }
                if (r.getScore() != null) {
                    sb.append(" (score: ").append(String.format("%.2f", r.getScore())).append(")");
                }
                sb.append("\n");
                sb.append("路径: ").append(r.getPath()).append("\n");

                if (r.getPageType() != null && !r.getPageType().isEmpty()) {
                    sb.append("页面类型: ").append(r.getPageType());
                    if ("reference".equals(r.getPageType())) {
                        sb.append(" ★参考页(单源高保真，信息密度最高)");
                    } else if ("entity".equals(r.getPageType())) {
                        sb.append(" (跨源实体页)");
                    } else if ("summary".equals(r.getPageType())) {
                        sb.append(" (摘要页)");
                    }
                    sb.append("\n");
                }
    
                if (r.getSummary() != null && !r.getSummary().isEmpty()) {
                    sb.append("摘要: ").append(r.getSummary()).append("\n");
                }
    
                if (r.getHighlightedContent() != null && !r.getHighlightedContent().isEmpty()) {
                    sb.append("匹配片段: ");
                    int snippetLimit = Math.min(3, r.getHighlightedContent().size());
                    for (int j = 0; j < snippetLimit; j++) {
                        if (j > 0) sb.append(" | ");
                        sb.append(r.getHighlightedContent().get(j));
                    }
                    sb.append("\n");
                }
    
                if (r.getHealthStatus() != null && !"healthy".equals(r.getHealthStatus())) {
                    sb.append("健康状态: ").append(r.getHealthStatus()).append("\n");
                }
                sb.append("\n");
            }
    
            if (activeResults.size() > limit) {
                sb.append("...(还有 ").append(activeResults.size() - limit).append(" 个结果未展示)\n\n");
            }
        } else {
            sb.append("## 搜索结果\n\n（未找到匹配的知识页面）\n\n");
        }

        if (!graphNeighbors.isEmpty()) {
            sb.append("## 知识图谱邻域（搜索结果的 1-hop 关联页面）\n\n");
            sb.append("以下页面通过交叉引用链接与上方搜索结果直接关联。矛盾链接（contradiction）的页面尤其重要——回答时必须同时呈现双方观点。\n\n");
            String currentAnchor = null;
            for (GraphNeighbor gn : graphNeighbors) {
                String nbStatus = gn.lifecycleStatus();
                if ("DEPRECATED".equals(nbStatus) || "MERGED".equals(nbStatus) || "DELETED".equals(nbStatus)) {
                    continue;
                }
                if (!gn.anchorTitle().equals(currentAnchor)) {
                    currentAnchor = gn.anchorTitle();
                    sb.append("\n**「").append(currentAnchor).append("」的关联页面：**\n");
                }
                sb.append("  ");
                sb.append("outgoing".equals(gn.direction()) ? "→" : "←");
                sb.append(" [").append(gn.linkType()).append("] ");
                sb.append(gn.title());
                if (gn.pageType() != null && !gn.pageType().isEmpty()) {
                    sb.append(" (").append(gn.pageType()).append(")");
                }
                if ("contradiction".equals(gn.linkType())) {
                    sb.append(" ⚠️矛盾");
                }
                sb.append("\n");
                if (gn.linkContext() != null && !gn.linkContext().isBlank()) {
                    sb.append("    关联语境: ").append(gn.linkContext()).append("\n");
                }
                if (gn.summary() != null && !gn.summary().isBlank()) {
                    sb.append("    摘要: ").append(gn.summary()).append("\n");
                }
            }
            sb.append("\n");
        }

        if (!parsedSourceSections.isEmpty()) {
            sb.append("## 原始来源预加载（parsed 章节，已从 wiki 页面关联自动提取）\n\n");
            sb.append("以下章节来自知识库 Wiki 页面关联的原始来源文档，已按问题关键词匹配提取相关段落。\n");
            sb.append("当 Wiki 编译页面缺少细节时，直接引用这些原始章节，无需再调用 readRawSource。\n\n");
            for (ParsedSection ps : parsedSourceSections) {
                sb.append("### 📄 ").append(ps.sourceName());
                sb.append(" (sourceId=").append(ps.sourceId()).append(")\n");
                sb.append(ps.sectionContent()).append("\n\n");
            }
        }
    
        return sb.toString();
    }

    public List<SearchResultInfo> getDeprecatedPages() {
        List<SearchResultInfo> deprecated = new ArrayList<>();
        for (SearchResultInfo r : searchResults) {
            if ("DEPRECATED".equals(r.getLifecycleStatus())) {
                deprecated.add(r);
            }
        }
        return deprecated;
    }

    public String formatDeprecatedContext() {
        List<SearchResultInfo> deprecated = getDeprecatedPages();
        if (deprecated.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 已过时页面（以下页面已被用户标记为过时，仅供历史分析参考）\n\n");
        for (SearchResultInfo r : deprecated) {
            sb.append("- **").append(r.getTitle()).append("**");
            if (r.getCategory() != null) sb.append(" [").append(r.getCategory()).append("]");
            sb.append("\n  摘要: ").append(r.getSummary() != null ? r.getSummary() : "(无摘要)");
            sb.append("\n  路径: ").append(r.getPath()).append("\n\n");
        }
        return sb.toString();
    }

    public static class Builder {
        private GlobalSummaryService.GlobalSummary globalSummary;
        private int pageCount;
        private List<SearchResultInfo> searchResults = new ArrayList<>();
        private List<ParsedSection> parsedSourceSections = new ArrayList<>();
        private List<GraphNeighbor> graphNeighbors = new ArrayList<>();

        public Builder globalSummary(GlobalSummaryService.GlobalSummary globalSummary) {
            this.globalSummary = globalSummary;
            return this;
        }

        public Builder pageCount(int pageCount) {
            this.pageCount = pageCount;
            return this;
        }

        public Builder searchResults(List<SearchResultInfo> searchResults) {
            this.searchResults = searchResults != null ? searchResults : new ArrayList<>();
            return this;
        }

        public Builder parsedSourceSections(List<ParsedSection> parsedSourceSections) {
            this.parsedSourceSections = parsedSourceSections != null ? parsedSourceSections : new ArrayList<>();
            return this;
        }

        public Builder graphNeighbors(List<GraphNeighbor> graphNeighbors) {
            this.graphNeighbors = graphNeighbors != null ? graphNeighbors : new ArrayList<>();
            return this;
        }

        public RetrievalContext build() {
            RetrievalContext ctx = new RetrievalContext();
            ctx.globalSummary = this.globalSummary;
            ctx.pageCount = this.pageCount;
            ctx.searchResults = this.searchResults;
            ctx.parsedSourceSections = this.parsedSourceSections;
            ctx.graphNeighbors = this.graphNeighbors;
            return ctx;
        }
    }
}
