package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WriterQualityVerifier {

    private static final Logger log = LoggerFactory.getLogger(WriterQualityVerifier.class);

    private final Map<String, String> pageContentCache = new HashMap<>();

    public enum Severity { CRITICAL, WARNING, INFO }

    public record QualityIssue(
        Severity severity,
        String category,
        String pagePath,
        String entityName,
        String description
    ) {}

    public record VerificationReport(
        List<QualityIssue> issues,
        int criticalCount,
        int warningCount,
        int infoCount
    ) {
        public boolean hasCriticalIssues() { return criticalCount > 0; }

        public List<QualityIssue> getCriticalIssues() {
            return issues.stream().filter(i -> i.severity() == Severity.CRITICAL).toList();
        }

        public Set<String> getCriticalEntityPaths() {
            Set<String> paths = new HashSet<>();
            for (QualityIssue issue : issues) {
                if (issue.severity() == Severity.CRITICAL && issue.pagePath() != null) {
                    paths.add(issue.pagePath());
                }
            }
            return paths;
        }

        public String summarize() {
            StringBuilder sb = new StringBuilder();
            sb.append("Quality Verification: ");
            sb.append(criticalCount).append(" critical, ");
            sb.append(warningCount).append(" warnings, ");
            sb.append(infoCount).append(" info");
            if (criticalCount > 0) {
                sb.append("\nCritical issues:\n");
                for (QualityIssue issue : getCriticalIssues()) {
                    sb.append("  - [").append(issue.category()).append("] ");
                    if (issue.entityName() != null) sb.append("(").append(issue.entityName()).append(") ");
                    sb.append(issue.description()).append("\n");
                }
            }
            return sb.toString();
        }
    }

    private final StorageProvider storageProvider;

    public WriterQualityVerifier(StorageProvider storageProvider) {
        this.storageProvider = storageProvider;
    }

    public VerificationReport verify(IngestContext context) {
        List<QualityIssue> issues = new ArrayList<>();

        Map<String, EntityDossier> dossiers = context.getEntityDossiers();
        if (dossiers == null || dossiers.isEmpty()) {
            log.info("WriterQualityVerifier: no EntityDossiers available, skipping verification");
            return new VerificationReport(List.of(), 0, 0, 0);
        }

        Long scopeId = context.getScopeId();
        String scopeIdStr = String.valueOf(scopeId);

        for (Map.Entry<String, EntityDossier> entry : dossiers.entrySet()) {
            String entityName = entry.getKey();
            EntityDossier dossier = entry.getValue();

            verifyEntityCoverage(scopeIdStr, entityName, dossier, issues);
        }

        verifySummaryCompleteness(scopeIdStr, context, dossiers, issues);

        verifyCrossReferenceSymmetry(scopeIdStr, context, issues);

        int criticalCount = (int) issues.stream().filter(i -> i.severity() == Severity.CRITICAL).count();
        int warningCount = (int) issues.stream().filter(i -> i.severity() == Severity.WARNING).count();
        int infoCount = (int) issues.stream().filter(i -> i.severity() == Severity.INFO).count();

        VerificationReport report = new VerificationReport(issues, criticalCount, warningCount, infoCount);
        log.info("WriterQualityVerifier: {}", report.summarize());
        return report;
    }

    private void verifyEntityCoverage(
        String scopeIdStr,
        String entityName,
        EntityDossier dossier,
        List<QualityIssue> issues
    ) {
        String entityPageContent = readEntityPageContent(scopeIdStr, entityName);
        if (entityPageContent == null || entityPageContent.isBlank()) {
            if (dossier.totalMentions() >= 3) {
                issues.add(new QualityIssue(
                    Severity.CRITICAL,
                    "MISSING_ENTITY_PAGE",
                    null,
                    entityName,
                    "实体「" + entityName + "」有 " + dossier.totalMentions() + " 处出现但未生成页面"
                ));
            }
            return;
        }

        if (dossier.definitionText() != null && !dossier.definitionText().isBlank()) {
            if (!containsKeyTerms(entityPageContent, dossier.definitionText(), entityName)) {
                issues.add(new QualityIssue(
                    Severity.WARNING,
                    "DEFINITION_NOT_COVERED",
                    null,
                    entityName,
                    "实体「" + entityName + "」的定义信息未在页面中体现"
                ));
            }
        }

        if (dossier.ruleTexts() != null && !dossier.ruleTexts().isEmpty()) {
            int coveredRules = 0;
            for (String rule : dossier.ruleTexts()) {
                if (containsKeyTerms(entityPageContent, rule, entityName)) {
                    coveredRules++;
                }
            }
            double ruleCoverage = (double) coveredRules / dossier.ruleTexts().size();
            if (ruleCoverage < 0.5 && dossier.ruleTexts().size() >= 3) {
                issues.add(new QualityIssue(
                    Severity.WARNING,
                    "LOW_RULE_COVERAGE",
                    null,
                    entityName,
                    "实体「" + entityName + "」的规则条款覆盖率仅 " +
                        String.format("%.0f%%", ruleCoverage * 100) +
                        " (" + coveredRules + "/" + dossier.ruleTexts().size() + ")"
                ));
            }
        }

        if (dossier.containingSections() != null && dossier.containingSections().size() >= 3) {
            int sectionsMentioned = 0;
            for (String section : dossier.containingSections()) {
                if (entityPageContent.contains(section) || entityPageContent.contains(extractShortTitle(section))) {
                    sectionsMentioned++;
                }
            }
            double sectionCoverage = (double) sectionsMentioned / dossier.containingSections().size();
            if (sectionCoverage < 0.3) {
                issues.add(new QualityIssue(
                    Severity.INFO,
                    "LOW_SECTION_COVERAGE",
                    null,
                    entityName,
                    "实体「" + entityName + "」涉及 " + dossier.containingSections().size() +
                        " 个章节但页面仅提及 " + sectionsMentioned + " 个"
                ));
            }
        }
    }

    private void verifySummaryCompleteness(
        String scopeIdStr,
        IngestContext context,
        Map<String, EntityDossier> dossiers,
        List<QualityIssue> issues
    ) {
        WikiPageDO summaryPage = context.getSummaryPage();
        if (summaryPage == null) return;

        String summaryContent = readPageContent(scopeIdStr, summaryPage.getFilePath());
        if (summaryContent == null || summaryContent.isBlank()) return;

        int mentionedEntities = 0;
        int totalCoreEntities = 0;
        for (Map.Entry<String, EntityDossier> entry : dossiers.entrySet()) {
            EntityDossier dossier = entry.getValue();
            if (dossier.totalMentions() >= 3) {
                totalCoreEntities++;
                if (summaryContent.contains(entry.getKey())) {
                    mentionedEntities++;
                }
            }
        }

        if (totalCoreEntities >= 3) {
            double entityCoverage = (double) mentionedEntities / totalCoreEntities;
            if (entityCoverage < 0.4) {
                issues.add(new QualityIssue(
                    Severity.WARNING,
                    "LOW_SUMMARY_ENTITY_COVERAGE",
                    summaryPage.getFilePath(),
                    null,
                    "摘要页仅提及 " + mentionedEntities + "/" + totalCoreEntities +
                        " 个核心实体（覆盖率 " + String.format("%.0f%%", entityCoverage * 100) + "）"
                ));
            }
        }
    }

    private void verifyCrossReferenceSymmetry(
        String scopeIdStr,
        IngestContext context,
        List<QualityIssue> issues
    ) {
        WikiPageDO summaryPage = context.getSummaryPage();
        ConcurrentHashMap<String, WikiPageDO> entityPages = context.getEntityPages();
        if (entityPages == null || entityPages.isEmpty()) return;

        for (Map.Entry<String, WikiPageDO> entry : entityPages.entrySet()) {
            WikiPageDO entityPage = entry.getValue();
            if (entityPage == null) continue;

            String content = readPageContent(scopeIdStr, entityPage.getFilePath());
            if (content == null) continue;

            for (Map.Entry<String, WikiPageDO> otherEntry : entityPages.entrySet()) {
                if (otherEntry.getKey().equals(entry.getKey())) continue;
                WikiPageDO otherPage = otherEntry.getValue();
                if (otherPage == null) continue;

                String otherContent = readPageContent(scopeIdStr, otherPage.getFilePath());
                if (otherContent == null) continue;

                boolean forwardRef = content.contains(otherEntry.getKey()) ||
                    content.contains("[[" + otherEntry.getKey() + "]]");
                boolean backwardRef = otherContent.contains(entry.getKey()) ||
                    otherContent.contains("[[" + entry.getKey() + "]]");

                if (forwardRef && !backwardRef) {
                    issues.add(new QualityIssue(
                        Severity.INFO,
                        "ASYMMETRIC_CROSSREF",
                        otherPage.getFilePath(),
                        entry.getKey(),
                        "「" + entry.getKey() + "」引用了「" + otherEntry.getKey() +
                            "」但反向引用缺失"
                    ));
                }
            }
        }
    }

    private String readEntityPageContent(String scopeIdStr, String entityName) {
        String pagePath = "pages/" + entityName.replace("/", "-").replace("\\", "-") + ".md";
        return readPageContent(scopeIdStr, pagePath);
    }

    private String readPageContent(String scopeIdStr, String filePath) {
        if (filePath == null) return null;
        String cacheKey = scopeIdStr + ":" + filePath;
        String cached = pageContentCache.get(cacheKey);
        if (cached != null) return cached;
        try {
            byte[] bytes = storageProvider.read(scopeIdStr, "wiki/" + filePath);
            if (bytes == null) return null;
            String content = new String(bytes, StandardCharsets.UTF_8);
            pageContentCache.put(cacheKey, content);
            return content;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean containsKeyTerms(String pageContent, String referenceText, String entityName) {
        if (pageContent == null || referenceText == null) return false;

        if (pageContent.contains(entityName)) {
            String[] words = referenceText.split("[\\s,，。、；：]+");
            int matchCount = 0;
            int totalWords = 0;
            for (String word : words) {
                if (word.length() >= 2) {
                    totalWords++;
                    if (pageContent.contains(word)) {
                        matchCount++;
                    }
                }
            }
            return totalWords > 0 && (double) matchCount / totalWords >= 0.3;
        }
        return false;
    }

    private String extractShortTitle(String sectionTitle) {
        if (sectionTitle == null) return "";
        String cleaned = sectionTitle.replaceFirst("^#+\\s*", "").trim();
        if (cleaned.length() > 20) {
            return cleaned.substring(0, 20);
        }
        return cleaned;
    }
}
