package org.cn.liuwt.llmwiki.domain.service.harness.governance.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.*;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaStructuredParser;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SchemaComplianceChecker {

    private static final Logger log = LoggerFactory.getLogger(SchemaComplianceChecker.class);

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private SchemaStructuredParser schemaStructuredParser;

    @Autowired(required = false)
    private LlmClient chatClient;

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Pattern SECTION_PATTERN = Pattern.compile(
        "^##\\s*" + 1 + "[.、\\s].*?\\n(.*?)(?=^##\\s|\\Z)",
        Pattern.MULTILINE | Pattern.DOTALL
    );

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#+\\s+(.+)$");

    private static final Pattern MAX_LENGTH_PATTERN = Pattern.compile("(\\d+)[字|个|字符]");

    private static final Pattern CHANGE_LOG_PATTERN = Pattern.compile(
        "^##\\s*7[.、\\s]*变更日志.*?(?=^##\\s|\\Z)",
        Pattern.MULTILINE | Pattern.DOTALL
    );

    public ComplianceResult check(Long scopeId, String metadataJson, Map<String, String> pageContents) {
        SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
            return ComplianceResult.ok();
        }

        SchemaStructuredModel structuredModel = schemaManager.getStructuredModel(scopeId);

        List<SchemaViolation> violations = new ArrayList<>();

        if (structuredModel != null) {
            violations.addAll(validateCategoryStructured(metadataJson, structuredModel));
            violations.addAll(validatePageStructureStructured(pageContents, structuredModel));
            violations.addAll(validateNamingStructured(metadataJson, structuredModel));
        } else {
            String schemaContent = schema.getConfigValue();
            String categoriesSection = extractSection(schemaContent, 2);
            String templatesSection = extractSection(schemaContent, 3);
            String namingSection = extractSection(schemaContent, 4);
            violations.addAll(validateCategory(scopeId, metadataJson, categoriesSection));
            violations.addAll(validatePageStructure(scopeId, pageContents, templatesSection));
            violations.addAll(validateNaming(scopeId, metadataJson, namingSection));
        }

        if (!violations.isEmpty()) {
            log.warn("Schema compliance check found {} violations for scopeId={}", violations.size(), scopeId);
            for (SchemaViolation v : violations) {
                log.warn("  - {}: {} (severity={})", v.violationType(), v.description(), v.severity());
            }
        }

        return new ComplianceResult(violations);
    }

    public ComplianceResult checkPlan(Long scopeId, String writingPlanJson, String metadataJson) {
        SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
            return ComplianceResult.ok();
        }
        if (writingPlanJson == null || writingPlanJson.isEmpty()) {
            return ComplianceResult.ok();
        }

        SchemaStructuredModel structuredModel = schemaManager.getStructuredModel(scopeId);

        List<SchemaViolation> violations = new ArrayList<>();

        if (structuredModel != null) {
            violations.addAll(validateCategoryStructured(metadataJson, structuredModel));
            violations.addAll(validatePlanEntitiesStructured(writingPlanJson, metadataJson, structuredModel));
            violations.addAll(validatePlanSummaryStructureStructured(writingPlanJson, structuredModel));
        } else {
            String schemaContent = schema.getConfigValue();
            String categoriesSection = extractSection(schemaContent, 2);
            String templatesSection = extractSection(schemaContent, 3);
            String namingSection = extractSection(schemaContent, 4);
            violations.addAll(validateCategory(scopeId, metadataJson, categoriesSection));
            violations.addAll(validatePlanEntities(scopeId, writingPlanJson, metadataJson, categoriesSection, namingSection));
            violations.addAll(validatePlanSummaryStructure(scopeId, writingPlanJson, templatesSection));
        }

        if (!violations.isEmpty()) {
            log.warn("WritingPlan compliance check found {} violations for scopeId={}", violations.size(), scopeId);
            for (SchemaViolation v : violations) {
                log.warn("  - {}: {} (severity={})", v.violationType(), v.description(), v.severity());
            }
        }

        return new ComplianceResult(violations);
    }

    private List<SchemaViolation> validatePlanEntities(Long scopeId, String writingPlanJson, String metadataJson, String categoriesSection, String namingSection) {
        List<SchemaViolation> violations = new ArrayList<>();

        try {
            JsonNode root = OBJECT_MAPPER.readTree(cleanJson(writingPlanJson));
            if (root == null) return violations;

            if (root.has("entityPlans") && root.get("entityPlans").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = root.get("entityPlans").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String entityName = entry.getKey();
                    JsonNode plan = entry.getValue();

                    if (namingSection != null && !namingSection.trim().isEmpty()) {
                        List<String> namingRules = extractNamingRules(namingSection);
                        for (String rule : namingRules) {
                            if (rule.contains("中文") && containsOnlyLatinOrDigits(entityName)) {
                                violations.add(new SchemaViolation(ViolationType.NAMING,
                                    "实体名「" + entityName + "」不符合 Schema 命名约定「" + rule + "」",
                                    Severity.MEDIUM, entityName, "请使用符合 Schema 命名约定的实体名"));
                            }
                            if (rule.contains("不超过") || rule.contains("长度")) {
                                int maxLen = extractMaxLength(rule);
                                if (maxLen > 0 && entityName.length() > maxLen) {
                                    violations.add(new SchemaViolation(ViolationType.NAMING,
                                        "实体名「" + entityName + "」长度(" + entityName.length() + ")超过 Schema 限制(" + maxLen + ")",
                                        Severity.MEDIUM, entityName, "请缩短实体名至" + maxLen + "字以内"));
                                }
                            }
                        }
                    }

                    if (categoriesSection != null && !categoriesSection.trim().isEmpty() && plan.has("category")) {
                        String entityCategory = plan.get("category").asText();
                        Set<String> validCategories = extractValidCategories(categoriesSection);
                        if (!validCategories.isEmpty() && !matchesValidCategory(entityCategory, validCategories)) {
                            violations.add(new SchemaViolation(ViolationType.CATEGORY,
                                "实体「" + entityName + "」的分类「" + entityCategory + "」不在 Schema 分类体系中",
                                Severity.HIGH, entityName, "请选择 Schema 定义的有效分类"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("validatePlanEntities failed: {}", e.getMessage());
        }

        return violations;
    }

    private List<SchemaViolation> validatePlanSummaryStructure(Long scopeId, String writingPlanJson, String templatesSection) {
        if (templatesSection == null || templatesSection.trim().isEmpty()) return List.of();

        List<SchemaViolation> violations = new ArrayList<>();
        List<String> requiredSections = extractRequiredPageSections(templatesSection);
        if (requiredSections.isEmpty()) return violations;

        try {
            JsonNode root = OBJECT_MAPPER.readTree(cleanJson(writingPlanJson));
            if (root == null) return violations;

            if (root.has("summaryOutline")) {
                String outline = root.get("summaryOutline").asText();
                List<String> missing = findMissingSections(outline, requiredSections);
                for (String section : missing) {
                    violations.add(new SchemaViolation(ViolationType.PAGE_STRUCTURE,
                        "摘要页大纲缺少 Schema 定义的必需章节「" + section + "」",
                        Severity.MEDIUM, "summaryOutline", "请在摘要页大纲中补充「" + section + "」章节"));
                }
            }
        } catch (Exception e) {
            log.warn("validatePlanSummaryStructure failed: {}", e.getMessage());
        }

        return violations;
    }

    private String cleanJson(String json) {
        if (json == null || json.isEmpty()) return json;
        int start = json.indexOf("{");
        int end = json.lastIndexOf("}") + 1;
        return (start >= 0 && end > start) ? json.substring(start, end) : json;
    }

    private List<SchemaViolation> validateCategoryStructured(String metadataJson, SchemaStructuredModel model) {
        Taxonomy taxonomy = model.getTaxonomy();
        if (taxonomy == null || taxonomy.getRoots() == null || taxonomy.getRoots().isEmpty()) {
            return List.of();
        }

        String category = extractMetadataField(metadataJson, "category");
        if (category == null || category.isEmpty()) {
            return List.of(new SchemaViolation(ViolationType.CATEGORY,
                "元数据缺少 category 字段", Severity.MEDIUM, "category", "请指定符合 Schema 分类体系的分类路径"));
        }

        if (!taxonomy.isValidPath(category)) {
            List<String> allPaths = taxonomy.flattenPaths();
            String summary = allPaths.size() <= 5
                ? String.join("、", allPaths)
                : String.join("、", allPaths.subList(0, 5)) + "等" + allPaths.size() + "个分类";
            return List.of(new SchemaViolation(ViolationType.CATEGORY,
                "分类「" + category + "」不在 Schema 分类体系中，有效分类：" + summary,
                Severity.HIGH, "category", "请选择 Schema 定义的有效分类，或先申请 Schema 补丁添加新分类"));
        }

        return List.of();
    }

    private List<SchemaViolation> validatePageStructureStructured(Map<String, String> pageContents, SchemaStructuredModel model) {
        if (pageContents == null || pageContents.isEmpty()) return List.of();
        Templates templates = model.getTemplates();
        if (templates == null || templates.getPageTemplates() == null || templates.getPageTemplates().isEmpty()) {
            return List.of();
        }

        List<SchemaViolation> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : pageContents.entrySet()) {
            String pagePath = entry.getKey();
            String content = entry.getValue();
            if (content == null || content.isEmpty()) continue;

            for (PageTemplate pt : templates.getPageTemplates()) {
                List<String> requiredLabels = pt.requiredSectionLabels();
                if (requiredLabels.isEmpty()) continue;

                Set<String> presentSections = new HashSet<>();
                for (String line : content.split("\\r?\\n")) {
                    Matcher m = HEADING_PATTERN.matcher(line.trim());
                    if (m.find()) {
                        presentSections.add(m.group(1).trim().toLowerCase());
                    }
                }

                for (String required : requiredLabels) {
                    boolean found = false;
                    String normalizedRequired = required.toLowerCase();
                    for (String present : presentSections) {
                        if (present.contains(normalizedRequired) || normalizedRequired.contains(present)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        violations.add(new SchemaViolation(ViolationType.PAGE_STRUCTURE,
                            "页面「" + pagePath + "」缺少 Schema 模板定义的必需章节「" + required + "」",
                            Severity.MEDIUM, pagePath, "请在页面中补充「" + required + "」章节"));
                    }
                }
            }
        }
        return violations;
    }

    private List<SchemaViolation> validateNamingStructured(String metadataJson, SchemaStructuredModel model) {
        Naming naming = model.getNaming();
        if (naming == null) return List.of();

        String title = extractMetadataField(metadataJson, "title");
        if (title == null || title.isEmpty()) return List.of();

        NamingRule entityRule = naming.getEntity();
        if (entityRule == null) return List.of();

        NamingValidationResult result = entityRule.validate(title);
        if (result.isValid()) return List.of();

        return List.of(new SchemaViolation(ViolationType.NAMING,
            result.getMessage(), Severity.LOW, "title", "请调整标题以符合 Schema 命名约定"));
    }

    private List<SchemaViolation> validatePlanEntitiesStructured(String writingPlanJson, String metadataJson, SchemaStructuredModel model) {
        List<SchemaViolation> violations = new ArrayList<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(cleanJson(writingPlanJson));
            if (root == null) return violations;

            if (root.has("entityPlans") && root.get("entityPlans").isObject()) {
                Naming naming = model.getNaming();
                NamingRule entityRule = naming != null ? naming.getEntity() : null;
                Taxonomy taxonomy = model.getTaxonomy();

                Iterator<Map.Entry<String, JsonNode>> fields = root.get("entityPlans").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String entityName = entry.getKey();
                    JsonNode plan = entry.getValue();

                    if (entityRule != null) {
                        NamingValidationResult result = entityRule.validate(entityName);
                        if (!result.isValid()) {
                            violations.add(new SchemaViolation(ViolationType.NAMING,
                                "实体名「" + entityName + "」" + result.getMessage(),
                                Severity.MEDIUM, entityName, "请调整实体名以符合 Schema 命名约定"));
                        }
                    }

                    if (taxonomy != null && plan.has("category")) {
                        String entityCategory = plan.get("category").asText();
                        if (!taxonomy.isValidPath(entityCategory)) {
                            violations.add(new SchemaViolation(ViolationType.CATEGORY,
                                "实体「" + entityName + "」的分类「" + entityCategory + "」不在 Schema 分类体系中",
                                Severity.HIGH, entityName, "请选择 Schema 定义的有效分类"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("validatePlanEntitiesStructured failed: {}", e.getMessage());
        }
        return violations;
    }

    private List<SchemaViolation> validatePlanSummaryStructureStructured(String writingPlanJson, SchemaStructuredModel model) {
        Templates templates = model.getTemplates();
        if (templates == null || templates.getPageTemplates() == null || templates.getPageTemplates().isEmpty()) {
            return List.of();
        }

        List<SchemaViolation> violations = new ArrayList<>();
        List<String> requiredLabels = new ArrayList<>();
        for (PageTemplate pt : templates.getPageTemplates()) {
            requiredLabels.addAll(pt.requiredSectionLabels());
        }
        if (requiredLabels.isEmpty()) return violations;

        try {
            JsonNode root = OBJECT_MAPPER.readTree(cleanJson(writingPlanJson));
            if (root == null) return violations;

            if (root.has("summaryOutline")) {
                String outline = root.get("summaryOutline").asText();
                Set<String> presentSections = new HashSet<>();
                for (String line : outline.split("\\r?\\n")) {
                    Matcher m = HEADING_PATTERN.matcher(line.trim());
                    if (m.find()) {
                        presentSections.add(m.group(1).trim().toLowerCase());
                    }
                }

                for (String required : requiredLabels) {
                    boolean found = false;
                    String normalizedRequired = required.toLowerCase();
                    for (String present : presentSections) {
                        if (present.contains(normalizedRequired) || normalizedRequired.contains(present)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        violations.add(new SchemaViolation(ViolationType.PAGE_STRUCTURE,
                            "摘要页大纲缺少 Schema 模板定义的必需章节「" + required + "」",
                            Severity.MEDIUM, "summaryOutline", "请在摘要页大纲中补充「" + required + "」章节"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("validatePlanSummaryStructureStructured failed: {}", e.getMessage());
        }
        return violations;
    }

    private List<SchemaViolation> validateCategory(Long scopeId, String metadataJson, String categoriesSection) {
        if (categoriesSection == null || categoriesSection.trim().isEmpty()) {
            return List.of();
        }
        Set<String> validCategories = extractValidCategories(categoriesSection);
        if (validCategories.isEmpty()) {
            return List.of();
        }

        String category = extractMetadataField(metadataJson, "category");
        if (category == null || category.isEmpty()) {
            return List.of(new SchemaViolation(ViolationType.CATEGORY,
                "元数据缺少 category 字段", Severity.MEDIUM, "category", "请指定符合 Schema 分类体系的分类路径"));
        }

        if (!matchesValidCategory(category, validCategories)) {
            return List.of(new SchemaViolation(ViolationType.CATEGORY,
                "分类「" + category + "」不在 Schema 分类体系中，有效分类：" + summarizeValidCategories(validCategories),
                Severity.HIGH, "category", "请选择 Schema 定义的有效分类，或先申请 Schema 补丁添加新分类"));
        }

        return List.of();
    }

    private List<SchemaViolation> validatePageStructure(Long scopeId, Map<String, String> pageContents, String templatesSection) {
        if (templatesSection == null || templatesSection.trim().isEmpty()) {
            return List.of();
        }
        if (pageContents == null || pageContents.isEmpty()) {
            return List.of();
        }

        List<String> requiredSections = extractRequiredPageSections(templatesSection);
        if (requiredSections.isEmpty()) {
            return List.of();
        }

        List<SchemaViolation> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : pageContents.entrySet()) {
            String pagePath = entry.getKey();
            String content = entry.getValue();
            if (content == null || content.isEmpty()) continue;

            List<String> missing = findMissingSections(content, requiredSections);
            for (String section : missing) {
                violations.add(new SchemaViolation(ViolationType.PAGE_STRUCTURE,
                    "页面「" + pagePath + "」缺少 Schema 定义的必需章节「" + section + "」",
                    Severity.MEDIUM, pagePath, "请在页面中补充「" + section + "」章节"));
            }
        }

        return violations;
    }

    private List<SchemaViolation> validateNaming(Long scopeId, String metadataJson, String namingSection) {
        if (namingSection == null || namingSection.trim().isEmpty()) {
            return List.of();
        }

        List<String> namingRules = extractNamingRules(namingSection);
        if (namingRules.isEmpty()) {
            return List.of();
        }

        String title = extractMetadataField(metadataJson, "title");
        if (title == null || title.isEmpty()) {
            return List.of();
        }

        List<SchemaViolation> violations = new ArrayList<>();
        for (String rule : namingRules) {
            if (rule.contains("中文") && containsOnlyLatinOrDigits(title)) {
                violations.add(new SchemaViolation(ViolationType.NAMING,
                    "标题「" + title + "」不符合 Schema 命名约定「" + rule + "」",
                    Severity.LOW, "title", "请使用符合 Schema 命名约定的标题格式"));
            }
            if (rule.contains("不超过") || rule.contains("长度")) {
                int maxLen = extractMaxLength(rule);
                if (maxLen > 0 && title.length() > maxLen) {
                    violations.add(new SchemaViolation(ViolationType.NAMING,
                        "标题「" + title + "」长度(" + title.length() + ")超过 Schema 限制(" + maxLen + ")",
                        Severity.LOW, "title", "请缩短标题至" + maxLen + "字以内"));
                }
            }
        }

        return violations;
    }

    public String extractSection(String schemaMarkdown, int sectionNumber) {
        if (schemaMarkdown == null || schemaMarkdown.isEmpty()) return null;

        String patternStr = "^##\\s*" + sectionNumber + "[.、\\s].*?\\n(.*?)(?=^##\\s|\\Z)";
        Pattern sectionPattern = Pattern.compile(patternStr, Pattern.MULTILINE | Pattern.DOTALL);

        Matcher matcher = sectionPattern.matcher(schemaMarkdown);
        if (matcher.find()) {
            String content = matcher.group(1);
            return content == null ? null : content.trim();
        }
        return null;
    }

    public String getSchemaSection(Long scopeId, int sectionNumber) {
        SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
            return null;
        }
        String section = extractSection(schema.getConfigValue(), sectionNumber);
        if (section == null || section.trim().isEmpty()) return null;
        if (section.trim().length() < 10) return null;
        return section;
    }

    public boolean hasSchemaPageTemplate(Long scopeId) {
        return getSchemaSection(scopeId, 3) != null;
    }

    private Set<String> extractValidCategories(String categoriesSection) {
        Set<String> categories = new HashSet<>();
        String[] lines = categoriesSection.split("\\r?\\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                String item = trimmed.substring(2).trim();
                for (String name : extractCategoryNames(item)) {
                    categories.add(normalizeCategory(name));
                }
                continue;
            }

            if (trimmed.startsWith("### ")) {
                String heading = trimmed.substring(4).trim();
                categories.add(normalizeCategory(heading));
                continue;
            }

            int slashIdx = trimmed.indexOf("/");
            if (slashIdx > 0 && trimmed.length() < 60) {
                categories.add(normalizeCategory(trimmed));
            }
        }

        return categories;
    }

    private static final Pattern BRACKET_CATEGORY = Pattern.compile("【(.+?)】");

    private List<String> extractCategoryNames(String item) {
        List<String> names = new ArrayList<>();
        Matcher m = BRACKET_CATEGORY.matcher(item);
        while (m.find()) {
            names.add(m.group(1));
        }
        if (!names.isEmpty()) return names;
        if (item.length() > 40) {
            String[] parts = item.split("[;；,，]");
            for (String part : parts) {
                String t = part.trim();
                if (!t.isEmpty() && t.length() < 30) names.add(t);
            }
        }
        if (names.isEmpty()) names.add(item);
        return names;
    }

    private String normalizeCategory(String category) {
        return category.trim().replaceAll("\\s+/\\s+", "/").toLowerCase();
    }

    private boolean matchesValidCategory(String category, Set<String> validCategories) {
        String normalized = normalizeCategory(category);
        if (validCategories.contains(normalized)) return true;

        for (String valid : validCategories) {
            if (normalized.startsWith(valid + "/") || valid.startsWith(normalized + "/")) {
                return true;
            }
            String[] parts = normalized.split("/");
            String[] validParts = valid.split("/");
            if (parts.length > 0 && validParts.length > 0 && parts[0].equals(validParts[0])) {
                return true;
            }
        }

        return validCategories.isEmpty();
    }

    private String summarizeValidCategories(Set<String> validCategories) {
        List<String> sorted = new ArrayList<>(validCategories);
        Collections.sort(sorted);
        if (sorted.size() <= 5) return String.join("、", sorted);
        return String.join("、", sorted.subList(0, 5)) + "等" + sorted.size() + "个分类";
    }

    private List<String> extractRequiredPageSections(String templatesSection) {
        List<String> sections = new ArrayList<>();
        String[] lines = templatesSection.split("\\r?\\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                sections.add(trimmed.substring(4).trim());
            }
            if (trimmed.startsWith("- ") && trimmed.contains("章节") || trimmed.contains("section")) {
                String item = trimmed.substring(2).trim();
                sections.add(item);
            }
        }

        if (sections.isEmpty()) {
            for (String line : lines) {
                Matcher m = HEADING_PATTERN.matcher(line.trim());
                if (m.find()) {
                    sections.add(m.group(1).trim());
                }
            }
        }

        return sections;
    }

    private List<String> findMissingSections(String content, List<String> requiredSections) {
        List<String> missing = new ArrayList<>();
        Set<String> presentSections = new HashSet<>();

        for (String line : content.split("\\r?\\n")) {
            Matcher m = HEADING_PATTERN.matcher(line.trim());
            if (m.find()) {
                presentSections.add(m.group(1).trim().toLowerCase());
            }
        }

        for (String required : requiredSections) {
            boolean found = false;
            String normalizedRequired = required.toLowerCase();
            for (String present : presentSections) {
                if (present.contains(normalizedRequired) || normalizedRequired.contains(present)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                missing.add(required);
            }
        }

        return missing;
    }

    private List<String> extractNamingRules(String namingSection) {
        List<String> rules = new ArrayList<>();
        String[] lines = namingSection.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                rules.add(trimmed.substring(2).trim());
            }
        }
        return rules;
    }

    private String extractMetadataField(String metadataJson, String field) {
        if (metadataJson == null || metadataJson.isEmpty()) return null;
        try {
            int start = metadataJson.indexOf("{");
            int end = metadataJson.lastIndexOf("}") + 1;
            String cleanJson = (start >= 0 && end > start) ? metadataJson.substring(start, end) : metadataJson;
            com.fasterxml.jackson.databind.JsonNode node = OBJECT_MAPPER.readTree(cleanJson);
            if (node.has(field)) return node.get(field).asText();
        } catch (Exception e) {
            log.warn("extractMetadataField failed for '{}': {}", field, e.getMessage());
        }
        return null;
    }

    private boolean containsOnlyLatinOrDigits(String text) {
        return text.matches("[a-zA-Z0-9\\s_-]+");
    }

    private int extractMaxLength(String rule) {
        Matcher m = MAX_LENGTH_PATTERN.matcher(rule);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    public enum ViolationType {
        CATEGORY, PAGE_STRUCTURE, NAMING
    }

    public enum Severity {
        HIGH, MEDIUM, LOW
    }

    public record SchemaViolation(
        ViolationType violationType,
        String description,
        Severity severity,
        String affectedField,
        String suggestion
    ) {}

    public record ViolationGroupSummary(
        ViolationType type,
        int totalCount,
        Severity highestSeverity,
        List<String> affectedPages,
        String overview
    ) {}

    public record ComplianceResult(List<SchemaViolation> violations) {

        public static ComplianceResult ok() {
            return new ComplianceResult(List.of());
        }

        public boolean hasViolations() {
            return violations != null && !violations.isEmpty();
        }

        public boolean requiresReview() {
            if (violations == null) return false;
            return violations.stream()
                .anyMatch(v -> v.severity() == Severity.HIGH || v.severity() == Severity.MEDIUM);
        }

        public List<SchemaViolation> getHighSeverityViolations() {
            if (violations == null) return List.of();
            return violations.stream()
                .filter(v -> v.severity() == Severity.HIGH)
                .toList();
        }

        public List<ViolationGroupSummary> summarizeGrouped() {
            if (violations == null || violations.isEmpty()) return List.of();

            Map<ViolationType, List<SchemaViolation>> grouped = new LinkedHashMap<>();
            for (SchemaViolation v : violations) {
                grouped.computeIfAbsent(v.violationType(), k -> new ArrayList<>()).add(v);
            }

            List<ViolationGroupSummary> result = new ArrayList<>();
            for (Map.Entry<ViolationType, List<SchemaViolation>> entry : grouped.entrySet()) {
                ViolationType type = entry.getKey();
                List<SchemaViolation> group = entry.getValue();
                int count = group.size();
                Severity highest = group.stream()
                    .map(SchemaViolation::severity)
                    .min(Comparator.comparingInt(s -> s.ordinal()))
                    .orElse(Severity.LOW);

                // affectedField 可能是字段名、实体名或页面路径，需要提取人类可读的描述
                List<String> affected = group.stream()
                    .map(v -> extractAffectedLabel(v))
                    .filter(f -> f != null && !f.isBlank())
                    .distinct()
                    .limit(5)
                    .toList();

                String overview = buildGroupOverview(type, count, highest);
                result.add(new ViolationGroupSummary(type, count, highest, affected, overview));
            }

            result.sort(Comparator.comparingInt(g -> g.highestSeverity().ordinal()));
            return result;
        }

        /**
         * 从 SchemaViolation 中提取人类可读的受影响对象标签
         * - CATEGORY/NAMING 类型：显示具体的分类值或实体名，而非字段名
         * - PAGE_STRUCTURE 类型：显示页面路径
         */
        private static String extractAffectedLabel(SchemaViolation violation) {
            String field = violation.affectedField();
            if (field == null || field.isBlank()) return null;

            // 如果是纯字段名（category/title/summaryOutline 等），从 description 中提取实际值
            if (field.equals("category") || field.equals("title") || field.equals("summaryOutline")) {
                // 从 description 中提取引号内的实际值，例如：
                // "分类「组织架构」不在 Schema 分类体系中" → 组织架构
                // "标题「ABC」不符合 Schema 命名约定" → ABC
                Pattern quotePattern = Pattern.compile("「([^」]+)」");
                Matcher matcher = quotePattern.matcher(violation.description());
                if (matcher.find()) {
                    return matcher.group(1);
                }
                // fallback：显示字段名的中文映射
                return switch (field) {
                    case "category" -> "分类";
                    case "title" -> "标题";
                    case "summaryOutline" -> "摘要大纲";
                    default -> field;
                };
            }

            // 其他情况（实体名、页面路径）直接返回
            return field;
        }

        private static String buildGroupOverview(ViolationType type, int count, Severity severity) {
            String severityLabel = switch (severity) {
                case HIGH -> "高优";
                case MEDIUM -> "中优";
                case LOW -> "低优";
            };
            return switch (type) {
                case CATEGORY -> count + " 个分类不在 Schema 分类体系中（" + severityLabel + "）";
                case PAGE_STRUCTURE -> count + " 个页面缺少 Schema 要求的必需章节（" + severityLabel + "）";
                case NAMING -> count + " 个命名不符合 Schema 规范（" + severityLabel + "）";
            };
        }

        public String summarize() {
            if (violations == null || violations.isEmpty()) return "Schema 合规检查通过";
            StringBuilder sb = new StringBuilder();
            sb.append("发现 ").append(violations.size()).append(" 个 Schema 违规：\n");
            for (SchemaViolation v : violations) {
                sb.append("- [").append(v.severity()).append("] ")
                    .append(v.violationType()).append(": ").append(v.description());
                if (v.suggestion() != null && !v.suggestion().isEmpty()) {
                    sb.append(" | ").append(v.suggestion());
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    public List<Map<String, String>> preCheckInstruction(Long scopeId, String pageContent, String instruction) {
        if (chatClient == null) {
            log.info("preCheckInstruction: LlmClient not available, skipping check");
            return List.of();
        }

        SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
            log.info("preCheckInstruction: no Schema for scopeId={}, skipping check", scopeId);
            return List.of();
        }

        String schemaContent = schema.getConfigValue();
        Matcher m = CHANGE_LOG_PATTERN.matcher(schemaContent);
        String slimSchema = m.replaceAll("").trim();

        String pageSummary = pageContent != null && pageContent.length() > 2000
            ? pageContent.substring(0, 2000) + "\n...(页面内容过长，仅展示前 2000 字符)"
            : (pageContent != null ? pageContent : "");

        String prompt = """
            你是 Schema 合规检查器。判断下方的用户修改指令，如果按照这个指令修改页面，是否会违反上方 Schema 骨架中定义的规则。

            Schema 规则存在于以下 section：
            - ## 2. 分类体系（category 定义）
            - ## 3. 页面模板（必需章节定义）
            - ## 4. 命名与引用约定（命名规则）

            重点关注：
            1. 修改指令是否要求改变分类、引入不在分类体系中的概念
            2. 修改指令是否要求删除或跳过 Schema 要求的必需章节
            3. 修改指令是否要求使用不符合命名约定的页面标题或段落标题

            如果指令不违反任何 Schema 规则，返回空数组 []。
            如果违反，返回 JSON 数组，每个元素包含：
            - rule: 违反的规则名称（如 "命名约定"）
            - description: 具体冲突描述
            - severity: HIGH/MEDIUM/LOW
            - section: 对应的 Schema Section 编号（如 "4"）

            Schema 骨架：
            """ + slimSchema + """

            当前页面摘要（前 2000 字符）：
            """ + pageSummary + """

            用户修改指令：
            """ + instruction + """

            请只返回纯 JSON 数组，不要包含任何其他文字、解释或代码围栏（```）。
            """;

        try {
            String response = chatClient.chat(prompt);
            String cleaned = cleanJson(response);
            if (cleaned.startsWith("[")) {
                List<Map<String, String>> conflicts = new ArrayList<>();
                com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(cleaned);
                if (root.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode item : root) {
                        Map<String, String> conflict = new LinkedHashMap<>();
                        conflict.put("rule", item.has("rule") ? item.get("rule").asText() : "");
                        conflict.put("description", item.has("description") ? item.get("description").asText() : "");
                        conflict.put("severity", item.has("severity") ? item.get("severity").asText() : "MEDIUM");
                        conflict.put("section", item.has("section") ? item.get("section").asText() : "");
                        conflicts.add(conflict);
                    }
                }
                return conflicts;
            }
        } catch (Exception e) {
            log.warn("preCheckInstruction: LLM call failed, falling back to no conflicts. error={}", e.getMessage());
        }

        return List.of();
    }
}