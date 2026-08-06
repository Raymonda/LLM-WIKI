package org.cn.liuwt.llmwiki.domain.service.harness.governance.parser;

import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SchemaSection6Parser {

    private static final Logger log = LoggerFactory.getLogger(SchemaSection6Parser.class);

    @Autowired
    private SchemaManager schemaManager;

    private final ConcurrentMap<Long, LintRulesConfig> cache = new ConcurrentHashMap<>();

    private static final Pattern SECTION_6_PATTERN = Pattern.compile(
        "^##\\s*6[.、\\s]*健康检查规则.*?\\n(.*?)(?=^##\\s|\\Z)",
        Pattern.MULTILINE | Pattern.DOTALL
    );

    private static final Pattern HANDLING_METHOD_PATTERN = Pattern.compile(
        "(ruling_brief|auto_fill|auto_refresh|auto_repair|dismiss|裁决|自动填充|自动刷新|自动修复)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PRIORITY_PATTERN = Pattern.compile(
        "(高优先|低优先|\\bhigh\\b|\\blow\\b)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Map<String, String> HANDLING_METHOD_CN_MAP = Map.of(
        "裁决", "ruling_brief",
        "自动填充", "auto_fill",
        "自动刷新", "auto_refresh",
        "自动修复", "auto_repair"
    );

    public LintRulesConfig parse(Long scopeId) {
        if (scopeId == null) {
            return LintRulesConfig.buildDefaults();
        }
        return cache.computeIfAbsent(scopeId, this::doParse);
    }

    public void invalidate(Long scopeId) {
        if (scopeId != null) {
            cache.remove(scopeId);
        }
    }

    @Autowired
    private SchemaStructuredParser schemaStructuredParser;

    private LintRulesConfig doParse(Long scopeId) {
        try {
            SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
            if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
                log.debug("scopeId={} Schema 不存在或为空，使用默认 LintRulesConfig", scopeId);
                return LintRulesConfig.buildDefaults();
            }

            if (schema.getConfigValueStructured() != null && !schema.getConfigValueStructured().isBlank()) {
                try {
                    SchemaStructuredModel model = schemaStructuredParser.fromJson(schema.getConfigValueStructured());
                    if (model != null && model.getLintRules() != null) {
                        log.debug("scopeId={} 从结构化 JSON 直接加载 LintRulesConfig", scopeId);
                        return model.getLintRules();
                    }
                } catch (Exception e) {
                    log.warn("scopeId={} 结构化 JSON 反序列化失败，回退到 Markdown 解析: {}", scopeId, e.getMessage());
                }
            }

            String section6 = extractSection6(schema.getConfigValue());
            if (section6 == null || section6.isBlank()) {
                log.debug("scopeId={} Schema Section 6 不存在或为空，使用默认 LintRulesConfig", scopeId);
                return LintRulesConfig.buildDefaults();
            }

            LintRulesConfig config = LintRulesConfig.buildDefaults();
            applyStructuredRules(config, section6);
            return config;
        } catch (Exception e) {
            log.warn("Schema Section 6 解析失败 scopeId={}, 使用默认配置: {}", scopeId, e.getMessage());
            return LintRulesConfig.buildDefaults();
        }
    }

    private String extractSection6(String schemaMarkdown) {
        Matcher m = SECTION_6_PATTERN.matcher(schemaMarkdown);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private void applyStructuredRules(LintRulesConfig config, String section6) {
        String sub61 = extractSubSection(section6, "6.1");
        if (sub61 != null) {
            parseDiagnosticRules(config, sub61);
            parseDiagnosticStandards(config, sub61);
        }

        String sub62 = extractSubSection(section6, "6.2");
        if (sub62 != null) {
            parseInterventionTiers(config, sub62);
        }

        String sub63 = extractSubSection(section6, "6.3");
        String sub64 = extractSubSection(section6, "6.4");
        String sub65 = extractSubSection(section6, "6.5");

        boolean legacySchema = sub64 == null && sub63 != null;
        if (sub63 != null) {
            parseFeedbackLearning(config, sub63);
            if (legacySchema) {
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
            parseByKeywords(config, section6);
        }
    }

    private String extractSubSection(String section6, String subNum) {
        Pattern p = Pattern.compile(
            "###\\s*6\\." + subNum.replace(".", "\\.") + ".*?\\n(.*?)(?=###\\s|^##\\s|\\Z)",
            Pattern.MULTILINE | Pattern.DOTALL
        );
        Matcher m = p.matcher(section6);
        return m.find() ? m.group(1).trim() : null;
    }

    private void parseDiagnosticRules(LintRulesConfig config, String sub61) {
        String[] lines = sub61.split("\\n");
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

        String staleKeywords = "过时|stale|滞后|outdated";
        Pattern staleDaysPattern = Pattern.compile(
            "(" + staleKeywords + ".*?)(\\d+)\\s*(天|日|day)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher dm = staleDaysPattern.matcher(sub61);
        while (dm.find()) {
            int days = Integer.parseInt(dm.group(2));
            String context = dm.group(1).toLowerCase();
            if (context.contains("high") || context.contains("高") || days >= 20) {
                config.getStaleThresholds().setHighPriorityDays(days);
            } else if (days >= 5) {
                config.getStaleThresholds().setMediumPriorityDays(days);
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
            case "orphan" -> new String[]{"孤儿", "orphan", "零引用", "零入站"};
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

    private void parseInterventionTiers(LintRulesConfig config, String sub62) {
        Pattern riskPattern = Pattern.compile(
            "(?:风险分\\s*[≤<=]\\s*(\\d+)|(\\d+)\\s*分?\\s*(?:以下|以内).*?auto_repair(?:_with_notify)?)",
            Pattern.CASE_INSENSITIVE
        );
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

    private void parseScheduleConfig(LintRulesConfig config, String sub63) {
        Pattern skipPattern = Pattern.compile("(\\d+)\\s*(?:小时|hour|h)\\s*(?:内|已完成).*?跳过", Pattern.CASE_INSENSITIVE);
        Matcher skipMatcher = skipPattern.matcher(sub63);
        if (skipMatcher.find()) {
            config.getScheduleConfig().setSkipWindowHours(Integer.parseInt(skipMatcher.group(1)));
        }

        Pattern tokenPattern = Pattern.compile("(\\d{4,})\\s*(?:token|tokens)", Pattern.CASE_INSENSITIVE);
        Matcher tokenMatcher = tokenPattern.matcher(sub63);
        if (tokenMatcher.find()) {
            config.getScheduleConfig().setPerExecutionTokenSoftLimit(Integer.parseInt(tokenMatcher.group(1)));
        }
    }

    private void parseDiagnosticStandards(LintRulesConfig config, String sub61) {
        LintRulesConfig.DiagnosticStandard ds = config.getDiagnosticStandard();

        Pattern orphanAgePattern = Pattern.compile(
            "孤儿.*?(\\d+)\\s*(天|日|day)", Pattern.CASE_INSENSITIVE);
        Matcher m = orphanAgePattern.matcher(sub61);
        if (m.find()) ds.setOrphanMinAgeDays(Integer.parseInt(m.group(1)));

        Pattern staleHighPattern = Pattern.compile(
            "过时.*?(\\d+)\\s*(天|日|day).*?(高|high)", Pattern.CASE_INSENSITIVE);
        m = staleHighPattern.matcher(sub61);
        if (m.find()) ds.setStaleLagHighDays(Integer.parseInt(m.group(1)));

        Pattern staleMedPattern = Pattern.compile(
            "过时.*?(\\d+)\\s*(天|日|day).*?(中|medium)", Pattern.CASE_INSENSITIVE);
        m = staleMedPattern.matcher(sub61);
        if (m.find()) ds.setStaleLagMediumDays(Integer.parseInt(m.group(1)));

        Pattern crossrefPattern = Pattern.compile(
            "交叉引用.*?(\\d+)\\s*(个|关键词|keyword)", Pattern.CASE_INSENSITIVE);
        m = crossrefPattern.matcher(sub61);
        if (m.find()) ds.setCrossrefMinSharedKeywords(Integer.parseInt(m.group(1)));

        Pattern gapPagesPattern = Pattern.compile(
            "缺口.*?(\\d+)\\s*(个|页面|page)", Pattern.CASE_INSENSITIVE);
        m = gapPagesPattern.matcher(sub61);
        if (m.find()) ds.setGapMinCategoryPages(Integer.parseInt(m.group(1)));

        Pattern probePattern = Pattern.compile(
            "(?:启用|enable)\\s*(?:探查|probe|AI探查)", Pattern.CASE_INSENSITIVE);
        m = probePattern.matcher(sub61);
        ds.setProbeEnabled(m.find());
    }

    private void parseFeedbackLearning(LintRulesConfig config, String sub63) {
        LintRulesConfig.FeedbackLearningConfig fl = config.getFeedbackLearning();

        Pattern dismissPattern = Pattern.compile(
            "(\\d+)\\s*次.*?(?:忽略|dismiss).*?(?:降级|降低)", Pattern.CASE_INSENSITIVE);
        Matcher m = dismissPattern.matcher(sub63);
        if (m.find()) fl.setDismissCountToDowngrade(Integer.parseInt(m.group(1)));

        Pattern downgradePattern = Pattern.compile(
            "降级.*?(\\d+)\\s*(级|level)", Pattern.CASE_INSENSITIVE);
        m = downgradePattern.matcher(sub63);
        if (m.find()) fl.setDowngradePriorityLevels(Integer.parseInt(m.group(1)));

        Pattern trackAcceptPattern = Pattern.compile(
            "(?:记录|track).*?(?:接受|accept).*?(?:修改|modify)", Pattern.CASE_INSENSITIVE);
        m = trackAcceptPattern.matcher(sub63);
        fl.setTrackAcceptModifications(m.find());

        Pattern trackDismissPattern = Pattern.compile(
            "(?:记录|track).*?(?:忽略|dismiss).*?(?:模式|pattern)", Pattern.CASE_INSENSITIVE);
        m = trackDismissPattern.matcher(sub63);
        fl.setTrackDismissPatterns(m.find());
    }

    private void parseByKeywords(LintRulesConfig config, String section6) {
        String[] lines = section6.split("\\n");
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

        Pattern daysPattern = Pattern.compile("(\\d+)\\s*(?:天|日|day)", Pattern.CASE_INSENSITIVE);
        Matcher dm = daysPattern.matcher(section6);
        int minDays = Integer.MAX_VALUE;
        int maxDays = 0;
        while (dm.find()) {
            int d = Integer.parseInt(dm.group(1));
            if (d < minDays) minDays = d;
            if (d > maxDays) maxDays = d;
        }
        if (maxDays > 0 && maxDays >= 20) {
            config.getStaleThresholds().setHighPriorityDays(maxDays);
        }
        if (minDays < Integer.MAX_VALUE && minDays >= 3) {
            config.getStaleThresholds().setMediumPriorityDays(minDays);
        }
    }

    private void parseConflictResolutionRules(LintRulesConfig config, String sub65) {
        LintRulesConfig.ConflictResolutionRules cr = config.getConflictResolutionRules();

        Pattern strategyPattern = Pattern.compile(
            "(?:默认策略|default).*?(source_priority|newer_wins|annotate_both|annotate_and_patch|adjudicate|优先来源|最新来源|标注双方|标注并补丁|裁决)",
            Pattern.CASE_INSENSITIVE);
        Matcher m = strategyPattern.matcher(sub65);
        if (m.find()) {
            String raw = m.group(1).toLowerCase();
            Map<String, String> strategyMap = Map.of(
                "优先来源", "source_priority", "最新来源", "newer_wins",
                "标注双方", "annotate_both", "标注并补丁", "annotate_and_patch",
                "裁决", "adjudicate"
            );
            cr.setDefaultStrategy(strategyMap.getOrDefault(raw, raw));
        }

        Pattern autoLevelPattern = Pattern.compile(
            "(?:默认自动级别|defaultAutoLevel|default\\s*auto\\s*level).*?(AUTO|REVIEW|DEFER)",
            Pattern.CASE_INSENSITIVE);
        Matcher alMatcher = autoLevelPattern.matcher(sub65);
        if (alMatcher.find()) {
            cr.setDefaultAutoLevel(alMatcher.group(1).toUpperCase());
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

        parseCategoryStrategies(cr, sub65);
    }

    private void parseCategoryStrategies(LintRulesConfig.ConflictResolutionRules cr, String sub65) {
        Pattern catStrategyPattern = Pattern.compile(
            "[-*•]?\\s*(.+?)\\s*(?:→|->|：|:)\\s*(source_priority|newer_wins|annotate_both|annotate_and_patch|adjudicate|优先来源|最新来源|标注双方|标注并补丁|裁决)(?:[（(]\\s*(AUTO|REVIEW|DEFER)\\s*[）)])?",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher cm = catStrategyPattern.matcher(sub65);
        Map<String, String> strategyMap = Map.of(
            "优先来源", "source_priority", "最新来源", "newer_wins",
            "标注双方", "annotate_both", "标注并补丁", "annotate_and_patch",
            "裁决", "adjudicate"
        );
        while (cm.find()) {
            String category = cm.group(1).trim();
            if (category.startsWith("默认") || category.toLowerCase().startsWith("default")) continue;
            if (category.length() > 50) continue;

            String rawStrategy = cm.group(2).toLowerCase();
            String strategy = strategyMap.getOrDefault(rawStrategy, rawStrategy);
            cr.getCategoryStrategies().put(category, strategy);

            String autoLevel = cm.group(3);
            if (autoLevel != null && !autoLevel.isBlank()) {
                cr.getCategoryAutoLevels().put(category, autoLevel.toUpperCase());
            }
        }
    }
}