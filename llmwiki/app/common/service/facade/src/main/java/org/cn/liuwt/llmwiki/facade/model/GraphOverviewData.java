package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class GraphOverviewData {
    private List<CategoryNode> categories;
    private List<CategoryEdge> edges;
    private GraphData.GraphStats stats;

    @Data
    public static class CategoryNode {
        private String category;
        private int pageCount;
        private int orphanCount;
        private int conflictCount;
        private int needsUpdateCount;
        private int hasProblemsCount;
        private int hubCount;
        private double avgDegree;
    }

    @Data
    public static class CategoryEdge {
        private String fromCategory;
        private String toCategory;
        private int linkCount;
    }
}
