package org.cn.liuwt.llmwiki.domain.service.harness.governance.validation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaSection6Parser;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Schema 一致性扫描器（AGENTS.md《Schema 共治宪法·规则 7》）。
 *
 * <p>独立于 Wiki Lint，由 {@link SchemaLintScheduler} 调度，每周产出"Schema 体检报告"：
 * <ul>
 *   <li><b>一致性扫描</b>：规则两两冲突检查（LLM 逐对检查是否有逻辑冲突）</li>
 *   <li><b>事实偏离扫描</b>：Schema Section 6 断言与 lint_finding 实际分布对比</li>
 *   <li><b>膨胀检查</b>：Section 超阈值触发 Compaction 建议</li>
 *   <li><b>僵尸规则</b>：30 天未被任何 op 匹配/引用的规则标 deprecated</li>
 * </ul>
 */
@Service
public class SchemaConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(SchemaConsistencyChecker.class);

    /** Section 膨胀阈值（字符数），超此值建议压缩 */
    private static final int SECTION_BLOAT_THRESHOLD = 8000;
    /** 僵尸规则判定天数 */
    private static final int ZOMBIE_RULE_DAYS = 30;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private LintFindingMapper lintFindingMapper;

    @Autowired
    private SchemaSection6Parser schemaSection6Parser;

    @Autowired(required = false)
    private LlmClient chatClient;

    /**
     * 对指定 scope 执行完整 Schema 一致性扫描，返回体检报告。
     *
     * @return 体检报告文本（Markdown 格式），若 Schema 不存在则返回 null
     */
    public String runConsistencyCheck(Long scopeId) {
        SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
            log.debug("SchemaConsistencyCheck scopeId={}: Schema 不存在，跳过", scopeId);
            return null;
        }

        String schemaContent = schema.getConfigValue();
        StringBuilder report = new StringBuilder();
        report.append("# Schema 体检报告 (scopeId=").append(scopeId).append(")\n\n");

        // 1. 膨胀检查
        String bloatResult = checkBloat(schemaContent);
        report.append("## 膨胀检查\n").append(bloatResult).append("\n\n");

        // 2. 事实偏离扫描
        String deviationResult = checkFactDeviation(scopeId);
        report.append("## 事实偏离扫描\n").append(deviationResult).append("\n\n");

        // 3. 规则冲突检查（需要 LLM）
        if (chatClient != null && chatClient.isAvailable()) {
            String conflictResult = checkRuleConflicts(scopeId, schemaContent);
            report.append("## 规则冲突检查\n").append(conflictResult).append("\n\n");
        } else {
            report.append("## 规则冲突检查\nAI 服务未配置，跳过\n\n");
        }

        // 4. 僵尸规则
        String zombieResult = checkZombieRules(scopeId);
        report.append("## 僵尸规则\n").append(zombieResult).append("\n");

        log.info("SchemaConsistencyCheck scopeId={} 完成，报告长度={}", scopeId, report.length());
        return report.toString();
    }

    // ==================== 膨胀检查 ====================

    private static final java.util.regex.Pattern H2_SECTION_PATTERN = java.util.regex.Pattern.compile(
        "^##\\s+(\\S.*?)(?=\\n)(.*?)(?=^##\\s|\\Z)",
        java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.DOTALL
    );

    /**
     * 检查各 Section 是否超过膨胀阈值，超阈值时建议 Compaction。
     * 使用正则匹配 H2 section 边界，避免 split 在 section 内部 H3/H4 处错误拆分。
     */
    String checkBloat(String schemaContent) {
        StringBuilder result = new StringBuilder();
        java.util.regex.Matcher matcher = H2_SECTION_PATTERN.matcher(schemaContent);
        int bloatedCount = 0;

        while (matcher.find()) {
            String sectionTitle = matcher.group(1).trim();
            String sectionBody = matcher.group(2) != null ? matcher.group(2) : "";
            int charCount = sectionTitle.length() + sectionBody.length();

            if (charCount > SECTION_BLOAT_THRESHOLD) {
                bloatedCount++;
                result.append("- ⚠️ **").append(sectionTitle).append("**：")
                    .append(charCount).append(" 字符（阈值 ").append(SECTION_BLOAT_THRESHOLD).append("）")
                    .append(" — 建议压缩或拆分\n");
            }
        }

        if (bloatedCount == 0) {
            result.append("✅ 所有 Section 均在膨胀阈值内\n");
        }
        return result.toString();
    }

    // ==================== 事实偏离扫描 ====================

    /**
     * 将 Schema Section 6 的诊断规则作为断言，与 lint_finding 表实际分布对比。
     */
    String checkFactDeviation(Long scopeId) {
        StringBuilder result = new StringBuilder();
        LintRulesConfig rulesConfig = schemaSection6Parser.parse(scopeId);

        // 查询当前 scope 的 lint_finding 分布
        List<LintFindingDO> allFindings = lintFindingMapper.selectList(
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getScopeId, scopeId)
                .eq(LintFindingDO::getStatus, "open")
        );

        if (allFindings.isEmpty()) {
            result.append("✅ 当前无活跃的 lint 诊断项，无需偏离检查\n");
            return result.toString();
        }

        // 按类型统计
        Map<String, Long> typeCounts = allFindings.stream()
            .collect(Collectors.groupingBy(
                f -> f.getFindingType() != null ? f.getFindingType() : "unknown",
                Collectors.counting()
            ));

        // 按优先级统计
        Map<String, Long> priorityCounts = allFindings.stream()
            .collect(Collectors.groupingBy(
                f -> f.getPriority() != null ? f.getPriority() : "unknown",
                Collectors.counting()
            ));

        result.append("**当前活跃诊断项分布：**\n");
        result.append("| 诊断类型 | 数量 |\n|---|---|\n");
        for (Map.Entry<String, Long> entry : typeCounts.entrySet()) {
            result.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
        }

        result.append("\n**优先级分布：**\n");
        result.append("| 优先级 | 数量 |\n|---|---|\n");
        for (Map.Entry<String, Long> entry : priorityCounts.entrySet()) {
            result.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
        }

        // 检查 Schema 中定义但实际未出现的诊断类型（可能是过时规则）
        Map<String, LintRulesConfig.DiagnosticRule> rules = rulesConfig.getDiagnosticRules();
        if (rules != null && !rules.isEmpty()) {
            Set<String> definedTypes = rules.keySet();
            Set<String> actualTypes = typeCounts.keySet();

            List<String> unusedRules = new ArrayList<>();
            for (String defined : definedTypes) {
                boolean matched = actualTypes.stream().anyMatch(a -> a.equalsIgnoreCase(defined));
                if (!matched) {
                    unusedRules.add(defined);
                }
            }

            if (!unusedRules.isEmpty()) {
                result.append("\n⚠️ **Schema 定义了但当前无活跃诊断项的类型：**\n");
                for (String rule : unusedRules) {
                    result.append("- `").append(rule).append("` — 可能是过时规则，建议检查是否仍需保留\n");
                }
            }
        }

        return result.toString();
    }

    // ==================== 规则冲突检查（LLM） ====================

    /**
     * 提取 Section 6 诊断规则，通过 LLM 逐对检查是否存在逻辑冲突。
     */
    String checkRuleConflicts(Long scopeId, String schemaContent) {
        String section6 = extractSection6(schemaContent);
        if (section6 == null || section6.isBlank()) {
            return "✅ Section 6 不存在或为空，跳过冲突检查\n";
        }

        String prompt = buildConflictCheckPrompt(section6);
        try {
            String llmResult = chatClient.chat(prompt);
            return llmResult != null && !llmResult.isBlank() ? llmResult : "LLM 返回空结果\n";
        } catch (Exception e) {
            log.warn("Schema 规则冲突检查 LLM 调用失败 scopeId={}: {}", scopeId, e.getMessage());
            return "⚠️ LLM 调用失败：" + e.getMessage() + "\n";
        }
    }

    private String extractSection6(String schemaContent) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "^##\\s*6[.、\\s]*健康检查规则.*?\\n(.*?)(?=^##\\s|\\Z)",
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.DOTALL
        ).matcher(schemaContent);
        return m.find() ? m.group(1).trim() : null;
    }

    private String buildConflictCheckPrompt(String section6) {
        return """
            请检查以下 Schema 健康检查规则中是否存在逻辑冲突（如：两条规则对同一场景给出矛盾指令）。

            判定标准：
            - 两条规则的目标状态互斥（同时满足 A 和 B 会导致矛盾行为）
            - 阈值设置不合理（如"孤儿页面 ≥5 天自动删除"与"孤儿页面保留至少 30 天"冲突）
            - 优先级覆盖范围重叠但处置方向相反

            请逐条列出发现的冲突，格式：
            - **规则 A**（…摘要…）与 **规则 B**（…摘要…）冲突：…具体矛盾描述…
            - 如果没有发现冲突，回复"✅ 未发现规则冲突"。

            Schema Section 6 内容：\n""" + section6;
    }

    // ==================== 僵尸规则检查 ====================

    /**
     * 检查超过 30 天未被任何 op 引用/匹配的规则，标记为 deprecated。
     */
    String checkZombieRules(Long scopeId) {
        StringBuilder result = new StringBuilder();

        LintRulesConfig rulesConfig = schemaSection6Parser.parse(scopeId);
        Map<String, LintRulesConfig.DiagnosticRule> rules = rulesConfig.getDiagnosticRules();
        if (rules == null || rules.isEmpty()) {
            result.append("✅ 无诊断规则定义\n");
            return result.toString();
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(ZOMBIE_RULE_DAYS);

        // 查询最近 ZOMBIE_RULE_DAYS 天内仍然活跃的 finding types（排除已处置的 dismissed/auto_resolved/resolved/rolled_back）
        List<LintFindingDO> recentFindings = lintFindingMapper.selectList(
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getScopeId, scopeId)
                .ge(LintFindingDO::getCreatedAt, cutoff)
                .notIn(LintFindingDO::getStatus, "dismissed", "auto_resolved", "resolved", "rolled_back")
        );

        Set<String> activeTypes = recentFindings.stream()
            .map(LintFindingDO::getFindingType)
            .filter(Objects::nonNull)
            .map(String::toLowerCase)
            .collect(Collectors.toSet());

        List<String> zombieRules = new ArrayList<>();
        for (Map.Entry<String, LintRulesConfig.DiagnosticRule> entry : rules.entrySet()) {
            if (!activeTypes.contains(entry.getKey().toLowerCase())) {
                zombieRules.add(entry.getKey());
            }
        }

        if (zombieRules.isEmpty()) {
            result.append("✅ 所有诊断规则在最近 ").append(ZOMBIE_RULE_DAYS).append(" 天内均有活跃诊断项\n");
        } else {
            result.append("⚠️ 以下规则超过 ").append(ZOMBIE_RULE_DAYS)
                .append(" 天未被引用，建议标记为 deprecated：\n");
            for (String rule : zombieRules) {
                result.append("- `").append(rule).append("`\n");
            }
        }

        return result.toString();
    }
}