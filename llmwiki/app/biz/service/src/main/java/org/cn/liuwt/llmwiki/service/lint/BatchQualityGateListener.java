package org.cn.liuwt.llmwiki.service.lint;

import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestBatchSettledEvent;
import org.cn.liuwt.llmwiki.domain.service.harness.quality.CompilationQualityScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class BatchQualityGateListener {

    private static final Logger log = LoggerFactory.getLogger(BatchQualityGateListener.class);

    @Autowired
    private CompilationQualityScanner compilationQualityScanner;

    @Autowired
    private LintFindingService lintFindingService;

    @EventListener
    public void onBatchSettled(IngestBatchSettledEvent event) {
        try {
            if (event.getScopeId() == null || event.getPageIds() == null || event.getPageIds().isEmpty()) {
                return;
            }
            CompilationQualityScanner.ScanReport report =
                compilationQualityScanner.scan(event.getScopeId(), event.getPageIds());
            persistFindings(event, report);
        } catch (Exception e) {
            log.error("Batch quality gate failed: batchId={}, scopeId={}", event.getBatchId(), event.getScopeId(), e);
        }
    }

    private void persistFindings(IngestBatchSettledEvent event, CompilationQualityScanner.ScanReport report) {
        Long scopeId = event.getScopeId();
        for (CompilationQualityScanner.PageDefects defect : report.defectivePages()) {
            createQuietly(scopeId, "compilation_defect", "medium",
                "编译质量缺陷：" + displayTitle(defect.title(), defect.pagePath()),
                defectDetail(defect), defect.pagePath(), defect.pageId(),
                withBatch(event, Map.of("fixes", defect.fixes(), "warnings", defect.warnings())));
        }
        for (CompilationQualityScanner.SourceCoverage coverage : report.lowCoveragePages()) {
            createQuietly(scopeId, "low_source_coverage", "high",
                "来源覆盖不足：" + displayTitle(coverage.title(), coverage.pagePath()),
                "共 " + coverage.bulletLines() + " 条陈述，仅 " + coverage.coveredLines()
                    + " 条标注来源（覆盖率 " + percent(coverage.ratio()) + "%）",
                coverage.pagePath(), coverage.pageId(),
                withBatch(event, Map.of("bulletLines", coverage.bulletLines(),
                    "coveredLines", coverage.coveredLines(), "ratio", coverage.ratio())));
        }
        for (CompilationQualityScanner.DuplicatePair pair : report.duplicatePairs()) {
            createQuietly(scopeId, "duplicate_content", "medium",
                "疑似重复页面：" + pair.pageAPath() + " ↔ " + pair.pageBPath(),
                "与页面 " + pair.pageBPath() + " 相似度 " + percent(pair.similarity()) + "%",
                pair.pageAPath(), pair.pageAId(),
                withBatch(event, Map.of("pageBId", pair.pageBId(),
                    "pageBPath", pair.pageBPath(), "similarity", pair.similarity())));
        }
        for (CompilationQualityScanner.CitationRate citation : report.lowCitationPages()) {
            createQuietly(scopeId, "low_citation", "high",
                "引用密度过低：" + displayTitle(citation.title(), citation.pagePath()),
                "正文 " + citation.contentLines() + " 行，原文引用仅 " + citation.quoteLines()
                    + " 行（占比 " + percent(citation.ratio()) + "%）",
                citation.pagePath(), citation.pageId(),
                withBatch(event, Map.of("contentLines", citation.contentLines(),
                    "quoteLines", citation.quoteLines(), "ratio", citation.ratio())));
        }
    }

    private void createQuietly(Long scopeId, String findingType, String priority, String title,
                               String detail, String pagePath, Long pageId, Map<String, Object> extra) {
        try {
            lintFindingService.createFinding(scopeId, null, findingType, priority, title, detail,
                pagePath, pageId, extra);
        } catch (Exception e) {
            log.warn("Failed to persist batch quality finding: type={}, pagePath={}: {}",
                findingType, pagePath, e.getMessage());
        }
    }

    private static String defectDetail(CompilationQualityScanner.PageDefects defect) {
        StringBuilder sb = new StringBuilder();
        sb.append("待修复 ").append(defect.fixes().size()).append(" 条：")
            .append(String.join("；", defect.fixes()));
        if (!defect.warnings().isEmpty()) {
            sb.append("；告警 ").append(defect.warnings().size()).append(" 条：")
                .append(String.join("；", defect.warnings()));
        }
        return sb.toString();
    }

    private static Map<String, Object> withBatch(IngestBatchSettledEvent event, Map<String, Object> extra) {
        Map<String, Object> merged = new HashMap<>(extra);
        merged.put("batchId", event.getBatchId());
        return merged;
    }

    private static String displayTitle(String title, String pagePath) {
        return title != null && !title.isBlank() ? title : String.valueOf(pagePath);
    }

    private static long percent(double ratio) {
        return Math.round(ratio * 100);
    }
}
