package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class GraphData {
    private List<GraphNode> nodes;
    private List<GraphEdge> edges;
    private GraphStats stats;
    private Boolean requiresOverview;

    @Data
    public static class GraphNode {
        private Long id;
        private String title;
        private String path;
        private String category;
        private String healthStatus;
        private String pageType;
        private String lifecycleStatus;
        private Integer sourceCount;
        private int inDegree;
        private int outDegree;
    }

    @Data
    public static class GraphEdge {
        private Long fromId;
        private Long toId;
        private String linkType;
        private String linkContext;
    }

    @Data
    public static class GraphStats {
        private int totalNodes;
        private int totalEdges;
        private int orphanCount;
        private int hubCount;
        private int conflictCount;
        private int needsUpdateCount;
        private int hasProblemsCount;
        private int deprecatedCount;
        private int mergedCount;
        private double linkDensity;
        private List<CategoryCount> categoryCounts;
    }

    @Data
    public static class CategoryCount {
        private String category;
        private int count;
    }
}