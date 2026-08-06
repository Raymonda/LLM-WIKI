package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WritingPlanComplianceChecker {

    private static final Logger log = LoggerFactory.getLogger(WritingPlanComplianceChecker.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ComplianceIssue(
        String type,
        String pagePath,
        String description
    ) {}

    public record ComplianceReport(
        List<ComplianceIssue> issues,
        int termFixesApplied,
        int forbiddenPhraseViolations,
        int structureViolations
    ) {
        public boolean hasIssues() {
            return !issues.isEmpty();
        }

        public String summarize() {
            StringBuilder sb = new StringBuilder();
            sb.append("WritingPlan Compliance: ").append(issues.size()).append(" issues, ");
            sb.append(termFixesApplied).append(" term fixes, ");
            sb.append(forbiddenPhraseViolations).append(" forbidden phrases, ");
            sb.append(structureViolations).append(" structure violations");
            return sb.toString();
        }
    }

    private final StorageProvider storageProvider;

    public WritingPlanComplianceChecker(StorageProvider storageProvider) {
        this.storageProvider = storageProvider;
    }

    public ComplianceReport check(IngestContext context) {
        String writingPlanJson = context.getWritingPlanJson();
        if (writingPlanJson == null || writingPlanJson.isBlank()) {
            return new ComplianceReport(List.of(), 0, 0, 0);
        }

        Map<String, List<String>> termMap = extractTermMap(writingPlanJson);
        List<String> forbiddenPhrases = extractForbiddenPhrases(writingPlanJson);
        Map<String, List<String>> requiredStructure = extractRequiredStructure(writingPlanJson);

        Map<String, String> allContents = collectPageContents(context);
        if (allContents.isEmpty()) {
            return new ComplianceReport(List.of(), 0, 0, 0);
        }

        List<ComplianceIssue> issues = new ArrayList<>();
        int termFixes = 0;
        int forbiddenViolations = 0;
        int structureViolations = 0;

        if (!termMap.isEmpty()) {
            termFixes = applyTermMap(context, allContents, termMap, issues);
        }

        if (!forbiddenPhrases.isEmpty()) {
            forbiddenViolations = checkForbiddenPhrases(allContents, forbiddenPhrases, issues);
        }

        if (!requiredStructure.isEmpty()) {
            structureViolations = checkRequiredStructure(allContents, requiredStructure, issues);
        }

        log.info("WritingPlanComplianceChecker: {} term fixes, {} forbidden violations, {} structure violations. scopeId={}",
            termFixes, forbiddenViolations, structureViolations, context.getScopeId());

        return new ComplianceReport(issues, termFixes, forbiddenViolations, structureViolations);
    }

    private Map<String, List<String>> extractTermMap(String writingPlanJson) {
        try {
            JsonNode root = MAPPER.readTree(writingPlanJson);
            JsonNode rules = root.get("consistencyRules");
            if (rules == null || !rules.isObject()) return Map.of();

            JsonNode termMapNode = rules.get("termMap");
            if (termMapNode == null || !termMapNode.isObject()) return Map.of();

            Map<String, List<String>> termMap = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = termMapNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String standardTerm = field.getKey();
                List<String> variants = new ArrayList<>();
                JsonNode variantsNode = field.getValue();
                if (variantsNode.isArray()) {
                    for (JsonNode v : variantsNode) {
                        String variant = v.asText();
                        if (variant != null && !variant.isBlank()) {
                            variants.add(variant);
                        }
                    }
                }
                if (!variants.isEmpty()) {
                    termMap.put(standardTerm, variants);
                }
            }
            return termMap;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> extractForbiddenPhrases(String writingPlanJson) {
        try {
            JsonNode root = MAPPER.readTree(writingPlanJson);
            JsonNode rules = root.get("consistencyRules");
            if (rules == null || !rules.isObject()) return List.of();

            JsonNode forbidden = rules.get("forbiddenPhrases");
            if (forbidden == null || !forbidden.isArray()) return List.of();

            List<String> phrases = new ArrayList<>();
            for (JsonNode p : forbidden) {
                String phrase = p.asText();
                if (phrase != null && !phrase.isBlank()) {
                    phrases.add(phrase);
                }
            }
            return phrases;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, List<String>> extractRequiredStructure(String writingPlanJson) {
        try {
            JsonNode root = MAPPER.readTree(writingPlanJson);
            JsonNode rules = root.get("consistencyRules");
            if (rules == null || !rules.isObject()) return Map.of();

            JsonNode structure = rules.get("requiredStructure");
            if (structure == null || !structure.isObject()) return Map.of();

            Map<String, List<String>> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = structure.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String pattern = field.getKey();
                List<String> sections = new ArrayList<>();
                JsonNode sectionsNode = field.getValue();
                if (sectionsNode.isArray()) {
                    for (JsonNode s : sectionsNode) {
                        sections.add(s.asText());
                    }
                }
                if (!sections.isEmpty()) {
                    result.put(pattern, sections);
                }
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, String> collectPageContents(IngestContext context) {
        Map<String, String> contents = new LinkedHashMap<>();
        String scopeIdStr = String.valueOf(context.getScopeId());

        ConcurrentHashMap<String, String> pageContents = context.getPageContents();
        if (pageContents != null) {
            for (Map.Entry<String, String> entry : pageContents.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    contents.put(entry.getKey(), entry.getValue());
                }
            }
        }

        if (contents.isEmpty()) {
            collectFromPages(scopeIdStr, context.getSummaryPage(), contents);
            collectFromPages(scopeIdStr, context.getEntityPages(), contents);
            collectFromPages(scopeIdStr, context.getChapterPages(), contents);
            collectFromPages(scopeIdStr, context.getUpdatedPages(), contents);
        }

        return contents;
    }

    private void collectFromPages(String scopeIdStr, org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO page, Map<String, String> contents) {
        if (page == null || page.getFilePath() == null) return;
        try {
            byte[] bytes = storageProvider.read(scopeIdStr, "wiki/" + page.getFilePath());
            if (bytes != null) {
                contents.put(page.getFilePath(), new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // skip
        }
    }

    private void collectFromPages(String scopeIdStr, ConcurrentHashMap<String, org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO> pages, Map<String, String> contents) {
        if (pages == null) return;
        for (Map.Entry<String, org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO> entry : pages.entrySet()) {
            collectFromPages(scopeIdStr, entry.getValue(), contents);
        }
    }

    private int applyTermMap(IngestContext context, Map<String, String> allContents,
                              Map<String, List<String>> termMap, List<ComplianceIssue> issues) {
        int fixCount = 0;
        String scopeIdStr = String.valueOf(context.getScopeId());

        for (Map.Entry<String, List<String>> entry : termMap.entrySet()) {
            String standardTerm = entry.getKey();
            List<String> variants = entry.getValue();

            for (String variant : variants) {
                if (variant.length() < 2) continue;
                if (standardTerm.contains(variant)) continue;

                for (Map.Entry<String, String> pageEntry : allContents.entrySet()) {
                    String path = pageEntry.getKey();
                    String content = pageEntry.getValue();
                    if (content == null || !content.contains(variant)) continue;

                    String fixed = safeReplaceVariant(content, variant, standardTerm);
                    if (fixed.equals(content)) continue;
                    pageEntry.setValue(fixed);
                    fixCount++;

                    issues.add(new ComplianceIssue(
                        "TERM_VARIANT_FIXED",
                        path,
                        "「" + variant + "」→「" + standardTerm + "」"
                    ));

                    try {
                        storageProvider.write(scopeIdStr, "wiki/" + path, fixed.getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        log.warn("WritingPlanComplianceChecker: failed to write term fix to {}: {}", path, e.getMessage());
                    }

                    ConcurrentHashMap<String, String> pageContents = context.getPageContents();
                    if (pageContents != null) {
                        pageContents.put(path, fixed);
                    }
                }
            }
        }

        return fixCount;
    }

    static String safeReplaceVariant(String content, String variant, String standardTerm) {
        if (!standardTerm.contains(variant)) {
            return content.replace(variant, standardTerm);
        }
        String placeholder = "\u0000TERM_GUARD_" + System.identityHashCode(content) + "\u0000";
        String guarded = content.replace(standardTerm, placeholder);
        String replaced = guarded.replace(variant, standardTerm);
        return replaced.replace(placeholder, standardTerm);
    }

    private int checkForbiddenPhrases(Map<String, String> allContents,
                                       List<String> forbiddenPhrases, List<ComplianceIssue> issues) {
        int violationCount = 0;

        for (Map.Entry<String, String> pageEntry : allContents.entrySet()) {
            String path = pageEntry.getKey();
            String content = pageEntry.getValue();
            if (content == null) continue;

            for (String phrase : forbiddenPhrases) {
                if (content.contains(phrase)) {
                    violationCount++;
                    issues.add(new ComplianceIssue(
                        "FORBIDDEN_PHRASE",
                        path,
                        "包含禁用短语「" + phrase + "」"
                    ));
                }
            }
        }

        return violationCount;
    }

    private int checkRequiredStructure(Map<String, String> allContents,
                                        Map<String, List<String>> requiredStructure, List<ComplianceIssue> issues) {
        int violationCount = 0;

        for (Map.Entry<String, String> pageEntry : allContents.entrySet()) {
            String path = pageEntry.getKey();
            String content = pageEntry.getValue();
            if (content == null) continue;

            for (Map.Entry<String, List<String>> entry : requiredStructure.entrySet()) {
                String pattern = entry.getKey();
                List<String> requiredSections = entry.getValue();

                if (!matchesPagePattern(path, pattern)) continue;

                for (String section : requiredSections) {
                    if (!content.contains("## " + section) && !content.contains("### " + section)) {
                        violationCount++;
                        issues.add(new ComplianceIssue(
                            "MISSING_SECTION",
                            path,
                            "缺少必需章节「" + section + "」"
                        ));
                    }
                }
            }
        }

        return violationCount;
    }

    private boolean matchesPagePattern(String pagePath, String pattern) {
        if (pattern.startsWith("entity_")) {
            return pagePath.contains("pages/") && !pagePath.contains("summary");
        }
        if (pattern.equals("summary")) {
            return pagePath.contains("summary") || pagePath.contains("pages/");
        }
        return pagePath.matches(pattern.replace("*", ".*"));
    }
}
