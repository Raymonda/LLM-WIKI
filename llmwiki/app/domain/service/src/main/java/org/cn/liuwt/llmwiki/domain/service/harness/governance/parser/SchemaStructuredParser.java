package org.cn.liuwt.llmwiki.domain.service.harness.governance.parser;

import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SchemaStructuredParser {

    private static final Logger log = LoggerFactory.getLogger(SchemaStructuredParser.class);

    private static final Pattern H2_PATTERN = Pattern.compile("^##\\s*(\\d+)[.、\\s](.*)$", Pattern.MULTILINE);
    private static final Pattern H3_PATTERN = Pattern.compile("^###\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern H4_PATTERN = Pattern.compile("^####\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern LIST_ITEM = Pattern.compile("^[\\-\\*\\+]\\s+(.+)$");
    private static final Pattern NUMBERED_ITEM = Pattern.compile("^\\d+[.、]\\s*(.+)$");
    private static final Pattern MAX_LENGTH_PATTERN = Pattern.compile("(\\d+)\\s*[字个字符]");
    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+)\\s*(天|日|day)");
    private static final Pattern HANDLING_METHOD_PATTERN = Pattern.compile(
        "(ruling_brief|auto_fill|auto_refresh|auto_repair|auto_repair_with_notify|dismiss|裁决|自动填充|自动刷新|自动修复)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIORITY_PATTERN = Pattern.compile(
        "(高优先|低优先|\\bhigh\\b|\\bmedium\\b|\\blow\\b)",
        Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> HANDLING_METHOD_CN_MAP = Map.of(
        "裁决", "ruling_brief", "自动填充", "auto_fill", "自动刷新", "auto_refresh", "自动修复", "auto_repair");

    public SchemaStructuredModel parse(String schemaMarkdown) {
        if (schemaMarkdown == null || schemaMarkdown.isBlank()) {
            return null;
        }
        try {
            SchemaStructuredModel model = new SchemaStructuredModel();
            Map<Integer, String> sections = splitSections(schemaMarkdown);

            model.setDomainNarrative(parseNarrative(sections.get(1)));
            model.setTaxonomy(parseTaxonomy(sections.get(2)));
            model.setTemplates(parseTemplates(sections.get(3)));
            model.setNaming(parseNaming(sections.get(4)));
            model.setWorkflow(parseWorkflow(sections.get(5)));
            model.setLintRules(parseLintRules(sections.get(6)));

            return model;
        } catch (Exception e) {
            log.warn("Schema 结构化解析失败，将返回 null: {}", e.getMessage());
            return null;
        }
    }

    public String toJson(SchemaStructuredModel model) {
        if (model == null) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
            mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
            return mapper.writeValueAsString(model);
        } catch (Exception e) {
            log.warn("SchemaStructuredModel 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    public SchemaStructuredModel fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, SchemaStructuredModel.class);
        } catch (Exception e) {
            log.warn("SchemaStructuredModel 反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    Map<Integer, String> splitSections(String markdown) {
        Map<Integer, String> sections = new LinkedHashMap<>();
        String normalized = markdown.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);

        int currentSection = -1;
        StringBuilder buf = new StringBuilder();

        for (String line : lines) {
            Matcher m = H2_PATTERN.matcher(line.trim());
            if (m.find()) {
                if (currentSection > 0) {
                    sections.put(currentSection, buf.toString().trim());
                }
                try {
                    currentSection = Integer.parseInt(m.group(1));
                } catch (NumberFormatException e) {
                    currentSection = -1;
                }
                buf.setLength(0);
                continue;
            }
            if (currentSection > 0) {
                buf.append(line).append('\n');
            }
        }
        if (currentSection > 0) {
            sections.put(currentSection, buf.toString().trim());
        }
        return sections;
    }

    private String parseNarrative(String section) {
        if (section == null || section.isBlank()) return "";
        return section.trim();
    }

    Taxonomy parseTaxonomy(String section) {
        Taxonomy taxonomy = new Taxonomy();
        if (section == null || section.isBlank()) return taxonomy;

        List<String> narrativeLines = new ArrayList<>();
        List<String> treeLines = new ArrayList<>();
        boolean inTree = false;

        for (String line : section.split("\n")) {
            String trimmed = line.trim();
            if (isListItem(trimmed) || isH3(trimmed) || isH4(trimmed)) {
                inTree = true;
                treeLines.add(line);
            } else if (inTree && trimmed.isEmpty()) {
                treeLines.add(line);
            } else if (!inTree) {
                narrativeLines.add(line);
            } else {
                treeLines.add(line);
            }
        }

        taxonomy.setNarrative(String.join("\n", narrativeLines).trim());
        taxonomy.setRoots(buildTaxonomyTree(treeLines));
        return taxonomy;
    }

    private List<TaxonomyNode> buildTaxonomyTree(List<String> lines) {
        List<TaxonomyNode> roots = new ArrayList<>();
        Deque<IndentNode> stack = new ArrayDeque<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (isH3(trimmed)) {
                TaxonomyNode node = new TaxonomyNode();
                String heading = trimmed.replaceFirst("^###\\s+", "").trim();
                node.setId(slugify(heading));
                node.setLabel(heading);
                roots.add(node);
                stack.clear();
                stack.push(new IndentNode(0, node));
                continue;
            }

            if (!isListItem(trimmed)) continue;

            int indent = countIndent(line);
            String content = extractListContent(trimmed);

            TaxonomyNode node = new TaxonomyNode();
            String label = extractCategoryLabel(content);
            node.setId(slugify(label));
            node.setLabel(label);
            node.setDescription(extractCategoryDescription(content));

            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }

            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                TaxonomyNode parent = stack.peek().node;
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(node);
            }
            stack.push(new IndentNode(indent, node));
        }

        return roots;
    }

    private boolean isListItem(String line) {
        return LIST_ITEM.matcher(line).matches();
    }

    private boolean isH3(String line) {
        return line.startsWith("### ");
    }

    private boolean isH4(String line) {
        return line.startsWith("#### ");
    }

    private int countIndent(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else if (c == '\t') count += 2;
            else break;
        }
        return count;
    }

    private String extractListContent(String line) {
        Matcher m = LIST_ITEM.matcher(line);
        return m.matches() ? m.group(1).trim() : line;
    }

    private String extractCategoryLabel(String content) {
        Matcher bracket = Pattern.compile("[【\\[](.+?)[】\\]]").matcher(content);
        if (bracket.find()) return bracket.group(1).trim();

        int colonIdx = content.indexOf('：');
        if (colonIdx < 0) colonIdx = content.indexOf(':');
        if (colonIdx > 0 && colonIdx < 20) return content.substring(0, colonIdx).trim();

        int dashIdx = content.indexOf("——");
        if (dashIdx < 0) dashIdx = content.indexOf(" - ");
        if (dashIdx > 0 && dashIdx < 20) return content.substring(0, dashIdx).trim();

        return content.length() <= 30 ? content : content.substring(0, 30);
    }

    private String extractCategoryDescription(String content) {
        int colonIdx = content.indexOf('：');
        if (colonIdx < 0) colonIdx = content.indexOf(':');
        if (colonIdx > 0 && colonIdx < content.length() - 1) {
            return content.substring(colonIdx + 1).trim();
        }
        int dashIdx = content.indexOf("——");
        if (dashIdx < 0) dashIdx = content.indexOf(" - ");
        if (dashIdx > 0 && dashIdx < content.length() - 1) {
            return content.substring(dashIdx + 2).trim();
        }
        return null;
    }

    private String slugify(String label) {
        if (label == null) return "unknown";
        return label.trim()
            .toLowerCase()
            .replaceAll("\\s+", "_")
            .replaceAll("[^\\w\\u4e00-\\u9fa5_]", "")
            .replaceAll("_+", "_");
    }

    Templates parseTemplates(String section) {
        Templates templates = new Templates();
        if (section == null || section.isBlank()) return templates;

        List<String> narrativeLines = new ArrayList<>();
        String currentType = null;
        String currentLabel = null;
        List<SectionDef> currentSections = new ArrayList<>();
        int sectionOrder = 0;
        boolean inTemplate = false;

        for (String line : section.split("\n")) {
            String trimmed = line.trim();

            if (isH3(trimmed)) {
                if (currentType != null) {
                    PageTemplate pt = new PageTemplate();
                    pt.setType(currentType);
                    pt.setLabel(currentLabel != null ? currentLabel : currentType);
                    pt.setSections(new ArrayList<>(currentSections));
                    templates.getPageTemplates().add(pt);
                }
                String heading = trimmed.replaceFirst("^###\\s+", "").trim();
                currentType = slugify(heading);
                currentLabel = heading;
                currentSections = new ArrayList<>();
                sectionOrder = 0;
                inTemplate = true;
                continue;
            }

            if (isH4(trimmed) && inTemplate) {
                String heading = trimmed.replaceFirst("^####\\s+", "").trim();
                SectionDef sd = new SectionDef();
                sd.setId(slugify(heading));
                sd.setLabel(heading);
                sd.setRequired(true);
                sd.setOrder(++sectionOrder);
                currentSections.add(sd);
                continue;
            }

            if (isListItem(trimmed) && inTemplate) {
                String content = extractListContent(trimmed);
                if (content.contains("章节") || content.contains("section") || content.contains("必须") || content.contains("包含")) {
                    SectionDef sd = new SectionDef();
                    String label = extractCategoryLabel(content);
                    sd.setId(slugify(label));
                    sd.setLabel(label);
                    sd.setRequired(!content.contains("可选") && !content.contains("optional"));
                    sd.setOrder(++sectionOrder);
                    currentSections.add(sd);
                }
                continue;
            }

            if (!inTemplate) {
                narrativeLines.add(line);
            }
        }

        if (currentType != null) {
            PageTemplate pt = new PageTemplate();
            pt.setType(currentType);
            pt.setLabel(currentLabel != null ? currentLabel : currentType);
            pt.setSections(new ArrayList<>(currentSections));
            templates.getPageTemplates().add(pt);
        }

        templates.setNarrative(String.join("\n", narrativeLines).trim());
        return templates;
    }

    Naming parseNaming(String section) {
        Naming naming = new Naming();
        if (section == null || section.isBlank()) return naming;

        List<String> narrativeLines = new ArrayList<>();
        NamingRule currentRule = naming.getEntity();
        boolean inSummary = false;

        for (String line : section.split("\n")) {
            String trimmed = line.trim();

            if (isH3(trimmed)) {
                String heading = trimmed.replaceFirst("^###\\s+", "").trim().toLowerCase();
                if (heading.contains("摘要") || heading.contains("summary")) {
                    inSummary = true;
                    currentRule = naming.getSummary();
                } else if (heading.contains("实体") || heading.contains("entity") || heading.contains("页面")) {
                    inSummary = false;
                    currentRule = naming.getEntity();
                }
                continue;
            }

            if (isListItem(trimmed)) {
                String content = extractListContent(trimmed);
                boolean applied = applyNamingRule(currentRule, content);
                if (!applied) {
                    if (content.length() < 80 && (content.contains("例") || content.contains("如") || content.contains("example"))) {
                        naming.getExamples().add(content);
                    }
                }
                continue;
            }

            narrativeLines.add(line);
        }

        naming.setNarrative(String.join("\n", narrativeLines).trim());
        return naming;
    }

    private boolean applyNamingRule(NamingRule rule, String content) {
        boolean applied = false;
        if (content.contains("中文")) {
            rule.setLanguage("zh-CN");
            applied = true;
        }
        if (content.contains("英文") || content.contains("English")) {
            rule.setLanguage("en");
            applied = true;
        }
        Matcher maxLen = MAX_LENGTH_PATTERN.matcher(content);
        if (maxLen.find()) {
            rule.setMaxLength(Integer.parseInt(maxLen.group(1)));
            applied = true;
        }
        if (content.contains("不超过") || content.contains("长度")) {
            if (!maxLen.find()) {
                maxLen.reset();
                if (maxLen.find()) {
                    rule.setMaxLength(Integer.parseInt(maxLen.group(1)));
                }
            }
            applied = true;
        }
        if (content.contains("禁止") || content.contains("不允许") || content.contains("不得")) {
            String prefix = extractQuotedText(content);
            if (prefix != null) {
                rule.getForbiddenPrefixes().add(prefix);
                applied = true;
            }
        }
        return applied;
    }

    private String extractQuotedText(String content) {
        Matcher m = Pattern.compile("[「『\"'【](.+?)[」』\"'】]").matcher(content);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    Workflow parseWorkflow(String section) {
        Workflow workflow = new Workflow();
        if (section == null || section.isBlank()) return workflow;

        List<String> narrativeLines = new ArrayList<>();

        for (String line : section.split("\n")) {
            String trimmed = line.trim();

            if (isListItem(trimmed)) {
                String content = extractListContent(trimmed);
                if (content.contains("AUTO") || content.contains("自动")) {
                    workflow.setDefaultApproval("AUTO");
                    continue;
                }
                if (content.contains("CONFIRM") || content.contains("确认") || content.contains("审批")) {
                    workflow.setDefaultApproval("CONFIRM");
                    continue;
                }
                if (content.contains("触发") || content.contains("必须确认") || content.contains("需要审批")) {
                    workflow.getConfirmTriggers().add(content);
                    continue;
                }
            }

            narrativeLines.add(line);
        }

        workflow.setNarrative(String.join("\n", narrativeLines).trim());
        return workflow;
    }

    LintRulesConfig parseLintRules(String section) {
        if (section == null || section.isBlank()) {
            return LintRulesConfig.buildDefaults();
        }

        LintRulesConfig config = LintRulesConfig.buildDefaults();

        String sub61 = extractSubSection(section, "6.1");
        String sub62 = extractSubSection(section, "6.2");
        String sub63 = extractSubSection(section, "6.3");
        String sub64 = extractSubSection(section, "6.4");
        String sub65 = extractSubSection(section, "6.5");

        if (sub61 != null) {
            parseDiagnosticRules(config, sub61);
            parseDiagnosticStandards(config, sub61);
        }
        if (sub62 != null) {
            parseInterventionTiers(config, sub62);
        }
        if (sub63 != null) {
            parseFeedbackLearning(config, sub63);
            if (sub64 == null) {
                parseScheduleConfig(config, sub63);
            }
        }
        if (sub64 != null) {
            parseScheduleConfig(config, sub64);
        }
        if (sub65 != null) {
            parseConflictResolutionRules(config, sub65);
        }

        if (sub61 == null && sub62 == null && sub63 == null && sub64 == null && sub65 == null) {
            parseByKeywords(config, section);
        }

        return config;
    }

    private String extractSubSection(String section, String subNum) {
        Pattern p = Pattern.compile(
            "###\\s*6\\." + subNum.replace(".", "\\.") + ".*?\\n(.*?)(?=###\\s|^##\\s|\\Z)",
            Pattern.MULTILINE | Pattern.DOTALL);
        Matcher m = p.matcher(section);
        return m.find() ? m.group(1).trim() : null;
    }

    private void parseDiagnosticRules(LintRulesConfig config, String sub61) {
        String[] lines = sub61.split("\n");
        for (String line : lines) {
            String lowerLine = line.toLowerCase();
            String matchedKey = findDiagKeyInLine(lowerLine, config);
            if (matchedKey == null) continue;

            LintRulesConfig.DiagnosticRule rule = config.getDiagnosticRules().get(matchedKey);

            Matcher hmMatcher = HANDLING_METHOD_PATTERN.matcher(lowerLine);
            if (hmMatcher.find()) {
                String rawMethod = hmMatcher.group(1).toLowerCase();
                String method = HANDLING_METHOD_CN_MAP.getOrDefault(rawMethod, rawMethod);
                rule.setDefaultHandlingMethod(method);
                rule.setAutoFixEnabled(!"ruling_brief".equals(method) && !"dismiss".equals(method));
            }

            Matcher prMatcher = PRIORITY_PATTERN.matcher(lowerLine);
            if (prMatcher.find()) {
                String rawPri = prMatcher.group(1).toLowerCase();
                if (rawPri.contains("高") || "high".equals(rawPri)) {
                    rule.setDefaultPriority("high");
                } else if (rawPri.contains("低") || "low".equals(rawPri)) {
                    rule.setDefaultPriority("low");
                }
            }
        }
    }

    private String findDiagKeyInLine(String lowerLine, LintRulesConfig config) {
        for (var entry : config.getDiagnosticRules().entrySet()) {
            String[] labels = getDiagLabels(entry.getKey());
            for (String label : labels) {
                if (lowerLine.contains(label.toLowerCase())) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private String[] getDiagLabels(String key) {
        return switch (key) {
            case "orphan" -> new String[]{"孤儿", "orphan", "零引用", "零入站", "孤立"};
            case "stale" -> new String[]{"过时", "stale", "滞后", "outdated"};
            case "missing_crossref" -> new String[]{"交叉引用", "crossref", "缺失引用", "missing"};
            case "conflict" -> new String[]{"矛盾", "conflict", "冲突", "不一致"};
            case "gap" -> new String[]{"缺口", "gap", "缺失主题", "缺失页面", "概念缺口"};
            case "action" -> new String[]{"建议", "action", "改进", "suggest"};
            case "web_gap" -> new String[]{"网络缺口", "web_gap", "搜索补充", "公开资料"};
            case "schema_compliance" -> new String[]{"合规", "schema_compliance", "结构合规", "schema compliance"};
            default -> new String[]{key};
        };
    }

    private void parseDiagnosticStandards(LintRulesConfig config, String sub61) {
        LintRulesConfig.DiagnosticStandard ds = config.getDiagnosticStandard();

        Pattern orphanAgePattern = Pattern.compile("孤儿.*?(\\d+)\\s*(天|日|day)", Pattern.CASE_INSENSITIVE);
        Matcher m = orphanAgePattern.matcher(sub61);
        if (m.find()) ds.setOrphanMinAgeDays(Integer.parseInt(m.group(1)));

        Pattern staleHighPattern = Pattern.compile("过时.*?(\\d+)\\s*(天|日|day).*?(高|high)", Pattern.CASE_INSENSITIVE);
        m = staleHighPattern.matcher(sub61);
        if (m.find()) ds.setStaleLagHighDays(Integer.parseInt(m.group(1)));

        Pattern probePattern = Pattern.compile("(?:启用|enable)\\s*(?:探查|probe|AI探查)", Pattern.CASE_INSENSITIVE);
        m = probePattern.matcher(sub61);
        ds.setProbeEnabled(m.find());
    }

    private void parseInterventionTiers(LintRulesConfig config, String sub62) {
        Pattern riskPattern = Pattern.compile(
            "(?:风险分\\s*[≤<=]\\s*(\\d+)|(\\d+)\\s*分?\\s*(?:以下|以内).*?auto_repair(?:_with_notify)?)",
            Pattern.CASE_INSENSITIVE);
        Matcher m = riskPattern.matcher(sub62);
        int tier1 = -1, tier2 = -1;
        while (m.find()) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            int val = g1 != null ? Integer.parseInt(g1) : Integer.parseInt(g2);
            if (m.group().toLowerCase().contains("notify")) {
                tier2 = val;
            } else {
                tier1 = val;
            }
        }
        if (tier1 > 0) config.getInterventionTiers().setAutoRepairMax(tier1);
        if (tier2 > 0) config.getInterventionTiers().setAutoRepairWithNotifyMax(tier2);
    }

    private void parseScheduleConfig(LintRulesConfig config, String sub) {
        Pattern skipPattern = Pattern.compile("(\\d+)\\s*(?:小时|hour|h)\\s*(?:内|已完成).*?跳过", Pattern.CASE_INSENSITIVE);
        Matcher skipMatcher = skipPattern.matcher(sub);
        if (skipMatcher.find()) {
            config.getScheduleConfig().setSkipWindowHours(Integer.parseInt(skipMatcher.group(1)));
        }

        Pattern tokenPattern = Pattern.compile("(\\d{4,})\\s*(?:token|tokens)", Pattern.CASE_INSENSITIVE);
        Matcher tokenMatcher = tokenPattern.matcher(sub);
        if (tokenMatcher.find()) {
            config.getScheduleConfig().setPerExecutionTokenSoftLimit(Integer.parseInt(tokenMatcher.group(1)));
        }
    }

    private void parseFeedbackLearning(LintRulesConfig config, String sub63) {
        LintRulesConfig.FeedbackLearningConfig fl = config.getFeedbackLearning();

        Pattern dismissPattern = Pattern.compile("(\\d+)\\s*次.*?(?:忽略|dismiss).*?(?:降级|降低)", Pattern.CASE_INSENSITIVE);
        Matcher m = dismissPattern.matcher(sub63);
        if (m.find()) fl.setDismissCountToDowngrade(Integer.parseInt(m.group(1)));
    }

    private void parseConflictResolutionRules(LintRulesConfig config, String sub65) {
        LintRulesConfig.ConflictResolutionRules cr = config.getConflictResolutionRules();

        Pattern strategyPattern = Pattern.compile(
            "(?:默认策略|default).*?(source_priority|newer_wins|annotate_both|annotate_and_patch|优先来源|最新来源|标注双方|标注并补丁)",
            Pattern.CASE_INSENSITIVE);
        Matcher m = strategyPattern.matcher(sub65);
        if (m.find()) {
            String raw = m.group(1).toLowerCase();
            Map<String, String> strategyMap = Map.of(
                "优先来源", "source_priority", "最新来源", "newer_wins",
                "标注双方", "annotate_both", "标注并补丁", "annotate_and_patch");
            cr.setDefaultStrategy(strategyMap.getOrDefault(raw, raw));
        }

        Pattern tierPattern = Pattern.compile(
            "(?:层级|tier|优先级).*?(\\d+)\\s*[:：]\\s*(.+?)(?:[,，;；\\n]|$)",
            Pattern.CASE_INSENSITIVE);
        m = tierPattern.matcher(sub65);
        List<LintRulesConfig.SourcePriorityTier> tiers = new ArrayList<>();
        while (m.find()) {
            int tierNum = Integer.parseInt(m.group(1));
            String label = m.group(2).trim();
            if (label.length() > 30) label = label.substring(0, 30);
            LintRulesConfig.SourcePriorityTier tier = new LintRulesConfig.SourcePriorityTier();
            tier.setTier(tierNum);
            tier.setLabel(label);
            tier.setMatchRule(label.replaceAll("[|｜]", "|"));
            tiers.add(tier);
        }
        if (!tiers.isEmpty()) cr.setSourcePriorityTiers(tiers);
    }

    private void parseByKeywords(LintRulesConfig config, String section) {
        String[] lines = section.split("\n");
        for (String line : lines) {
            String lowerLine = line.toLowerCase();
            String matchedKey = findDiagKeyInLine(lowerLine, config);
            if (matchedKey == null) continue;

            LintRulesConfig.DiagnosticRule rule = config.getDiagnosticRules().get(matchedKey);
            Matcher hmMatcher = HANDLING_METHOD_PATTERN.matcher(lowerLine);
            if (hmMatcher.find()) {
                String rawMethod = hmMatcher.group(1).toLowerCase();
                String method = HANDLING_METHOD_CN_MAP.getOrDefault(rawMethod, rawMethod);
                rule.setDefaultHandlingMethod(method);
            }
        }
    }

    private static class IndentNode {
        final int indent;
        final TaxonomyNode node;

        IndentNode(int indent, TaxonomyNode node) {
            this.indent = indent;
            this.node = node;
        }
    }
}
