package org.cn.liuwt.llmwiki.domain.model.harness;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema Section 6 解析后的结构化 Lint 规则配置。
 * 由 SchemaSection6Parser 从 Schema Markdown 中提取，驱动 Lint Pipeline 行为。
 * 兜底：Schema 不存在或 Section 6 无法解析时，使用 buildDefaults() 返回硬编码默认值（保持向后兼容）。
 */
@Data
public class LintRulesConfig {

    /** 每种诊断类型的规则。key = findingType (orphan/stale/missing_crossref/conflict/gap/action/web_gap) */
    private Map<String, DiagnosticRule> diagnosticRules = new LinkedHashMap<>();

    /** 过时检测滞后天数分级阈值 */
    private StaleThresholds staleThresholds = new StaleThresholds();

    /** 干预等级配置 */
    private InterventionTiers interventionTiers = new InterventionTiers();

    /** 分区采样配置 */
    private PartitionConfig partitionConfig = new PartitionConfig();

    /** 缺失交叉引用检测配置 */
    private CrossrefConfig crossrefConfig = new CrossrefConfig();

    /** 调度规则 */
    private ScheduleConfig scheduleConfig = new ScheduleConfig();

    /** 风险分权重配置 */
    private RiskScoreWeights riskScoreWeights = new RiskScoreWeights();

    /** 诊断标准配置（定义"什么是异常"） */
    private DiagnosticStandard diagnosticStandard = new DiagnosticStandard();

    /** 反馈学习配置（定义"如何从用户行为中学习"） */
    private FeedbackLearningConfig feedbackLearning = new FeedbackLearningConfig();

    /** 矛盾裁决策略配置（定义"如何自动处置内容矛盾"） */
    private ConflictResolutionRules conflictResolutionRules = new ConflictResolutionRules();

    // ---- 内嵌配置类 ----

    @Data
    public static class DiagnosticRule {
        /** 诊断类型: orphan, stale, missing_crossref, conflict, gap, action, web_gap */
        private String findingType;
        /** 默认优先级: high / medium / low */
        private String defaultPriority = "medium";
        /** 默认处置方式: auto_refresh, auto_repair, manual_ingest, ruling_brief, dismiss */
        private String defaultHandlingMethod = "auto_repair";
        /** 是否启用该诊断项的自动修复 (AUTO_FIX_*) */
        private boolean autoFixEnabled = true;
    }

    @Data
    public static class StaleThresholds {
        /** 滞后超过此天数 → high 优先级 */
        private int highPriorityDays = 30;
        /** 滞后超过此天数 → medium 优先级 */
        private int mediumPriorityDays = 7;
        /** 无来源页面年龄超过此天数 → 纳入批量 LLM 检测 */
        private int sourcelessAgeDays = 60;
        /** 批量 stale LLM 检测每批大小 */
        private int batchSize = 100;
        /** 批量 stale LLM 检测最大批次数 */
        private int maxBatches = 3;
        /** 每批页面数（用于构建 pageDigest） */
        private int pageDigestBatchSize = 30;
    }

    @Data
    public static class InterventionTiers {
        /** 风险分 ≤ autoRepairMax → auto_repair */
        private int autoRepairMax = 6;
        /** 风险分 ≤ autoRepairWithNotifyMax → auto_repair_with_notify */
        private int autoRepairWithNotifyMax = 9;
        /** 风险分 > autoRepairWithNotifyMax → ruling_brief */
    }

    @Data
    public static class PartitionConfig {
        /** CHECK_CONFLICTS / CHECK_GAPS 的分区大小 */
        private int partitionSize = 150;
    }

    @Data
    public static class CrossrefConfig {
        /** 最大候选配对数量（防止 C(n,2) 爆炸） */
        private int maxCandidates = 500;
        /** 每次 Lint 最多 LLM 检查的配对数 */
        private int maxLlmChecks = 15;
    }

    @Data
    public static class ScheduleConfig {
        /** 个人知识库调度 cron */
        private String personalCron = "0 0 3 ? * MON";
        /** 团队知识库调度 cron */
        private String teamCron = "0 0 4 * * *";
        /** 跳过窗口：最近一次 Lint 完成后 x 小时内不再重复执行 */
        private int skipWindowHours = 1;
        /** 单次 Lint 执行 token 软上限（仅 warn） */
        private int perExecutionTokenSoftLimit = 200000;
    }

    @Data
    public static class RiskScoreWeights {
        private int reversibilityWeight = 1;
        private int scopeWeight = 1;
        private int schemaComplianceWeight = 1;
        private int infoLossWeight = 1;
        private int subjectivityWeight = 1;
    }

    @Data
    public static class DiagnosticStandard {
        private int orphanMinAgeDays = 7;
        private int staleLagHighDays = 30;
        private int staleLagMediumDays = 7;
        private int staleSourcelessAgeDays = 60;
        private int crossrefMinSharedKeywords = 2;
        private int gapMinCategoryPages = 3;
        private int gapMinAgeDays = 30;
        private boolean probeEnabled = true;
        private int autoFixLimit = 50;
    }

    @Data
    public static class FeedbackLearningConfig {
        private int dismissCountToDowngrade = 3;
        private int downgradePriorityLevels = 1;
        private boolean trackAcceptModifications = true;
        private boolean trackDismissPatterns = true;
    }

    @Data
    public static class ConflictResolutionRules {
        private String defaultStrategy = "annotate_both";
        private String defaultAutoLevel = "REVIEW";
        private List<SourcePriorityTier> sourcePriorityTiers = new ArrayList<>();
        private List<StrategyDefinition> strategies = new ArrayList<>();
        // 分类级策略覆盖：key=分类名, value=策略key（如 "newer_wins"）
        private Map<String, String> categoryStrategies = new HashMap<>();
        // 分类级自动级别：key=分类名, value=autoLevel（如 "AUTO"）
        private Map<String, String> categoryAutoLevels = new HashMap<>();
    }

    @Data
    public static class SourcePriorityTier {
        private int tier;
        private String label;
        private String matchRule;
    }

    @Data
    public static class StrategyDefinition {
        private String name;
        private String description;
        private String behavior;
        private boolean allowAutoFix;
        private boolean triggerSchemaPatch;
    }

    // ---- 工厂方法 ----

    /** 返回与当前硬编码行为一致的默认配置（向后兼容） */
    public static LintRulesConfig buildDefaults() {
        LintRulesConfig config = new LintRulesConfig();

        config.diagnosticRules.put("orphan", buildRule("orphan", "medium", "auto_repair", true));
        config.diagnosticRules.put("stale", buildRule("stale", "low", "auto_refresh", true));
        config.diagnosticRules.put("missing_crossref", buildRule("missing_crossref", "low", "auto_repair", true));
        config.diagnosticRules.put("conflict", buildRule("conflict", "high", "ruling_brief", false));
        config.diagnosticRules.put("schema_violation", buildRule("schema_violation", "medium", "manual_ingest", false));
        config.diagnosticRules.put("schema_compliance", buildRule("schema_compliance", "medium", "manual_edit", false));
        config.diagnosticRules.put("duplicate_orphan", buildRule("duplicate_orphan", "medium", "manual_merge", false));
        config.diagnosticRules.put("content_thin", buildRule("content_thin", "low", "manual_ingest", false));

        ConflictResolutionRules cr = new ConflictResolutionRules();
        cr.setDefaultStrategy("annotate_both");
        List<SourcePriorityTier> tiers = new ArrayList<>();
        tiers.add(createTier(1, "学术论文", "学术|论文|journal|conference|peer.?review"));
        tiers.add(createTier(2, "官方报告与标准", "官方|标准|白皮书|RFC|国标|GB/"));
        tiers.add(createTier(3, "行业分析报告", "报告|分析|行业|财报|年报"));
        tiers.add(createTier(4, "技术博客与媒体", "博客|blog|媒体|新闻|访谈"));
        tiers.add(createTier(5, "个人笔记与观点", "笔记|观点|个人|记录|手记"));
        cr.setSourcePriorityTiers(tiers);
        List<StrategyDefinition> strategies = new ArrayList<>();
        strategies.add(createStrategy("source_priority", "高优先级来源胜出", "merge_as_primary", true, false));
        strategies.add(createStrategy("newer_wins", "最新来源胜出", "merge_as_primary", true, false));
        strategies.add(createStrategy("annotate_both", "保留双方并排标注", "append_dissent_section", true, false));
        strategies.add(createStrategy("annotate_and_patch", "标注+建议Schema补丁", "append_dissent_section", false, true));
        cr.setStrategies(strategies);
        config.setConflictResolutionRules(cr);

        return config;
    }

    private static DiagnosticRule buildRule(String type, String priority, String handling, boolean autoFix) {
        DiagnosticRule rule = new DiagnosticRule();
        rule.setFindingType(type);
        rule.setDefaultPriority(priority);
        rule.setDefaultHandlingMethod(handling);
        rule.setAutoFixEnabled(autoFix);
        return rule;
    }

    private static SourcePriorityTier createTier(int tier, String label, String matchRule) {
        SourcePriorityTier t = new SourcePriorityTier();
        t.setTier(tier);
        t.setLabel(label);
        t.setMatchRule(matchRule);
        return t;
    }

    private static StrategyDefinition createStrategy(String name, String description, String behavior, boolean allowAutoFix, boolean triggerSchemaPatch) {
        StrategyDefinition s = new StrategyDefinition();
        s.setName(name);
        s.setDescription(description);
        s.setBehavior(behavior);
        s.setAllowAutoFix(allowAutoFix);
        s.setTriggerSchemaPatch(triggerSchemaPatch);
        return s;
    }
}