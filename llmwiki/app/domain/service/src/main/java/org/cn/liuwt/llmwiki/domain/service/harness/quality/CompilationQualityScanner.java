package org.cn.liuwt.llmwiki.domain.service.harness.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ContentDuplicateDetector;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CompilationQualityScanner {

    private static final Logger log = LoggerFactory.getLogger(CompilationQualityScanner.class);

    static final double COVERAGE_WARN_RATIO = 0.5;
    static final double CITATION_WARN_RATIO = 0.02;
    private static final int SCAN_MAX_PAGES = 500;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private ContentDuplicateDetector contentDuplicateDetector;

    public record PageDefects(Long pageId, String pagePath, String title,
                              List<String> fixes, List<String> warnings) {}

    public record SourceCoverage(Long pageId, String pagePath, String title,
                                 int bulletLines, int coveredLines, double ratio) {}

    public record DuplicatePair(Long pageAId, String pageAPath, Long pageBId, String pageBPath,
                                double similarity) {}

    public record CitationRate(Long pageId, String pagePath, String title,
                               int contentLines, int quoteLines, double ratio) {}

    public record ScanReport(Long scopeId, LocalDateTime generatedAt, int pageCount,
                             List<PageDefects> defectivePages,
                             List<SourceCoverage> lowCoveragePages,
                             List<DuplicatePair> duplicatePairs,
                             List<CitationRate> lowCitationPages) {}

    public ScanReport scan(Long scopeId) {
        return scan(scopeId, null);
    }

    public ScanReport scan(Long scopeId, Collection<Long> pageIds) {
        List<WikiPageDO> pages = loadPages(scopeId, pageIds);

        List<PageDefects> defective = new ArrayList<>();
        List<SourceCoverage> lowCoverage = new ArrayList<>();
        List<CitationRate> lowCitation = new ArrayList<>();
        String scopeIdStr = String.valueOf(scopeId);

        for (WikiPageDO page : pages) {
            String content = readContent(scopeIdStr, page);
            if (content == null) continue;

            CompilationQualityGuard.GuardResult guarded = CompilationQualityGuard.guard(content, page.getTitle());
            if (!guarded.fixes().isEmpty() || !guarded.warnings().isEmpty()) {
                defective.add(new PageDefects(page.getId(), page.getFilePath(), page.getTitle(),
                    guarded.fixes(), guarded.warnings()));
            }

            boolean isEntityPage = "entity".equals(page.getPageType());
            boolean isSourcePage = "summary".equals(page.getPageType()) || "reference".equals(page.getPageType());

            if (isEntityPage || isSourcePage) {
                int[] coverage = countBulletCoverage(content);
                if (coverage[0] > 0) {
                    double ratio = (double) coverage[1] / coverage[0];
                    if (ratio < COVERAGE_WARN_RATIO) {
                        lowCoverage.add(new SourceCoverage(page.getId(), page.getFilePath(), page.getTitle(),
                            coverage[0], coverage[1], ratio));
                    }
                }
            }

            if ("reference".equals(page.getPageType())) {
                int[] citation = countCitation(content);
                if (citation[0] > 0) {
                    double ratio = (double) citation[1] / citation[0];
                    if (ratio < CITATION_WARN_RATIO) {
                        lowCitation.add(new CitationRate(page.getId(), page.getFilePath(), page.getTitle(),
                            citation[0], citation[1], ratio));
                    }
                }
            }
        }

        List<DuplicatePair> duplicates;
        if (pageIds == null) {
            duplicates = contentDuplicateDetector.detectAll(scopeId).stream()
                .map(pair -> new DuplicatePair(pair.pageA().getId(), pair.pageA().getFilePath(),
                    pair.pageB().getId(), pair.pageB().getFilePath(), pair.similarity()))
                .toList();
        } else if (pages.isEmpty()) {
            duplicates = List.of();
        } else {
            Set<Long> focusPageIds = pages.stream().map(WikiPageDO::getId).collect(Collectors.toSet());
            duplicates = contentDuplicateDetector.detectForPages(scopeId, focusPageIds).stream()
                .map(pair -> new DuplicatePair(pair.pageA().getId(), pair.pageA().getFilePath(),
                    pair.pageB().getId(), pair.pageB().getFilePath(), pair.similarity()))
                .toList();
        }

        ScanReport report = new ScanReport(scopeId, LocalDateTime.now(), pages.size(),
            defective, lowCoverage, duplicates, lowCitation);
        log.info("CompilationQualityScanner: scopeId={} pages={} defective={} lowCoverage={} duplicates={} lowCitation={}",
            scopeId, pages.size(), defective.size(), lowCoverage.size(), duplicates.size(), lowCitation.size());
        return report;
    }

    private List<WikiPageDO> loadPages(Long scopeId, Collection<Long> pageIds) {
        LambdaQueryWrapper<WikiPageDO> wrapper = new LambdaQueryWrapper<WikiPageDO>()
            .eq(WikiPageDO::getScopeId, scopeId)
            .isNotNull(WikiPageDO::getFilePath)
            .notIn(WikiPageDO::getVisibility, List.of("private"));
        if (pageIds != null) {
            if (pageIds.isEmpty()) {
                return List.of();
            }
            wrapper.in(WikiPageDO::getId, pageIds);
        }
        return wikiPageMapper.selectList(wrapper).stream()
            .filter(p -> p.getLifecycleStatus() == null || "ACTIVE".equals(p.getLifecycleStatus()))
            .limit(SCAN_MAX_PAGES)
            .toList();
    }

    private String readContent(String scopeIdStr, WikiPageDO page) {
        try {
            byte[] bytes = storageProvider.read(scopeIdStr, "wiki/" + page.getFilePath());
            if (bytes == null) return null;
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("CompilationQualityScanner: failed to read page '{}': {}", page.getFilePath(), e.getMessage());
            return null;
        }
    }

    private static int[] countBulletCoverage(String content) {
        int total = 0;
        int covered = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                total++;
                if (trimmed.contains("（来源：")) {
                    covered++;
                }
            }
        }
        return new int[]{total, covered};
    }

    private static int[] countCitation(String content) {
        int contentLines = 0;
        int quoteLines = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            contentLines++;
            if (trimmed.startsWith(">")) quoteLines++;
        }
        return new int[]{contentLines, quoteLines};
    }
}
