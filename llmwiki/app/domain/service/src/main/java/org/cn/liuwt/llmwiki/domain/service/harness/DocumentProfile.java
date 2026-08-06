package org.cn.liuwt.llmwiki.domain.service.harness;

import java.util.List;

public record DocumentProfile(
    long totalLength,
    int chunkCount,
    int h1Count,
    int h2Count,
    int h3Count,
    double ruleDensity,
    double codeBlockRatio,
    double tableRatio,
    boolean hasImages,
    String topHeadings,
    DocumentStructureAnalyzer.DocumentType documentType,
    List<DocumentStructureAnalyzer.Chapter> chapters
) {
    public boolean isCompact() {
        return totalLength < 20_000;
    }

    public boolean hasRichCode() {
        return codeBlockRatio > 0.05;
    }

    public boolean isLargePolicy() {
        return documentType == DocumentStructureAnalyzer.DocumentType.STRUCTURED
            && totalLength > 100_000 && h1Count >= 6;
    }

    public double headingDensity() {
        return totalLength > 0 ? (double)(h1Count + h2Count) / (totalLength / 1000.0) : 0;
    }

    public int chapterCount() {
        return chapters != null ? chapters.size() : 0;
    }
}
