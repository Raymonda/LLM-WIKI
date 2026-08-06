package org.cn.liuwt.llmwiki.domain.service.harness.governance.parser;

import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class SchemaMarkdownRenderer {

    public String render(SchemaStructuredModel model) {
        if (model == null) return null;
        StringBuilder sb = new StringBuilder();

        renderSection1(sb, model);
        renderSection2(sb, model);
        renderSection3(sb, model);
        renderSection4(sb, model);
        renderSection5(sb, model);
        renderSection6(sb, model);
        renderSection7(sb);

        return sb.toString().trim() + "\n";
    }

    private void renderSection1(StringBuilder sb, SchemaStructuredModel model) {
        sb.append("## 1. 领域定位\n\n");
        if (model.getDomainNarrative() != null && !model.getDomainNarrative().isBlank()) {
            sb.append(model.getDomainNarrative()).append("\n\n");
        }
    }

    private void renderSection2(StringBuilder sb, SchemaStructuredModel model) {
        sb.append("## 2. 分类体系\n\n");
        Taxonomy taxonomy = model.getTaxonomy();
        if (taxonomy == null) return;

        if (taxonomy.getNarrative() != null && !taxonomy.getNarrative().isBlank()) {
            sb.append(taxonomy.getNarrative()).append("\n\n");
        }

        if (taxonomy.getRoots() != null) {
            for (TaxonomyNode root : taxonomy.getRoots()) {
                renderTaxonomyNode(sb, root, 0);
            }
        }
        sb.append("\n");
    }

    private void renderTaxonomyNode(StringBuilder sb, TaxonomyNode node, int depth) {
        if (depth == 0) {
            sb.append("### ").append(node.getLabel()).append("\n");
            if (node.getDescription() != null && !node.getDescription().isBlank()) {
                sb.append(node.getDescription()).append("\n");
            }
        } else {
            sb.append("  ".repeat(depth - 1)).append("- ").append(node.getLabel());
            if (node.getDescription() != null && !node.getDescription().isBlank()) {
                sb.append("：").append(node.getDescription());
            }
            sb.append("\n");
        }

        if (node.getChildren() != null) {
            for (TaxonomyNode child : node.getChildren()) {
                renderTaxonomyNode(sb, child, depth + 1);
            }
        }
    }

    private void renderSection3(StringBuilder sb, SchemaStructuredModel model) {
        sb.append("## 3. 页面模板\n\n");
        Templates templates = model.getTemplates();
        if (templates == null) return;

        if (templates.getNarrative() != null && !templates.getNarrative().isBlank()) {
            sb.append(templates.getNarrative()).append("\n\n");
        }

        for (PageTemplate pt : templates.getPageTemplates()) {
            sb.append("### ").append(pt.getLabel()).append("\n\n");
            int order = 1;
            for (SectionDef sd : pt.getSections()) {
                String requiredMark = sd.isRequired() ? "（必需）" : "（可选）";
                sb.append(order++).append(". ").append(sd.getLabel()).append(requiredMark).append("\n");
            }
            sb.append("\n");
        }
    }

    private void renderSection4(StringBuilder sb, SchemaStructuredModel model) {
        sb.append("## 4. 命名与引用约定\n\n");
        Naming naming = model.getNaming();
        if (naming == null) return;

        if (naming.getNarrative() != null && !naming.getNarrative().isBlank()) {
            sb.append(naming.getNarrative()).append("\n\n");
        }

        NamingRule entity = naming.getEntity();
        if (entity != null && hasNamingRules(entity)) {
            sb.append("### 实体页面命名\n\n");
            renderNamingRule(sb, entity);
        }

        NamingRule summary = naming.getSummary();
        if (summary != null && hasNamingRules(summary)) {
            sb.append("### 摘要页命名\n\n");
            renderNamingRule(sb, summary);
        }

        if (naming.getExamples() != null && !naming.getExamples().isEmpty()) {
            sb.append("### 命名示例\n\n");
            for (String ex : naming.getExamples()) {
                sb.append("- ").append(ex).append("\n");
            }
            sb.append("\n");
        }
    }

    private boolean hasNamingRules(NamingRule rule) {
        return (rule.getLanguage() != null && !rule.getLanguage().isBlank())
            || rule.getMaxLength() > 0
            || (rule.getPattern() != null && !rule.getPattern().isBlank())
            || (rule.getForbiddenPrefixes() != null && !rule.getForbiddenPrefixes().isEmpty());
    }

    private void renderNamingRule(StringBuilder sb, NamingRule rule) {
        if (rule.getLanguage() != null && !rule.getLanguage().isBlank()) {
            String langLabel = "zh-CN".equals(rule.getLanguage()) ? "中文" : rule.getLanguage();
            sb.append("- 语言要求：").append(langLabel).append("\n");
        }
        if (rule.getMaxLength() > 0) {
            sb.append("- 长度限制：不超过").append(rule.getMaxLength()).append("字\n");
        }
        if (rule.getForbiddenPrefixes() != null && !rule.getForbiddenPrefixes().isEmpty()) {
            sb.append("- 禁止前缀：");
            for (String prefix : rule.getForbiddenPrefixes()) {
                sb.append("「").append(prefix).append("」");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private void renderSection5(StringBuilder sb, SchemaStructuredModel model) {
        sb.append("## 5. 摄入工作流\n\n");
        Workflow workflow = model.getWorkflow();
        if (workflow == null) return;

        if (workflow.getNarrative() != null && !workflow.getNarrative().isBlank()) {
            sb.append(workflow.getNarrative()).append("\n\n");
        }

        sb.append("- 默认审批级别：").append(workflow.getDefaultApproval()).append("\n");
        if (workflow.getConfirmTriggers() != null && !workflow.getConfirmTriggers().isEmpty()) {
            sb.append("- 需要确认的场景：\n");
            for (String trigger : workflow.getConfirmTriggers()) {
                sb.append("  - ").append(trigger).append("\n");
            }
        }
        sb.append("\n");
    }

    private void renderSection6(StringBuilder sb, SchemaStructuredModel model) {
        sb.append("## 6. 健康检查规则\n\n");
        LintRulesConfig lint = model.getLintRules();
        if (lint == null) {
            lint = LintRulesConfig.buildDefaults();
        }

        renderSection61(sb, lint);
        renderSection62(sb, lint);
        renderSection63(sb, lint);
        renderSection64(sb, lint);
        renderSection65(sb, lint);
    }

    private void renderSection61(StringBuilder sb, LintRulesConfig config) {
        sb.append("### 6.1 诊断项定义\n\n");
        renderDiagRule(sb, config, "orphan", "孤儿页面（零入站链接）");
        renderDiagRule(sb, config, "stale", "过时声明");
        renderDiagRule(sb, config, "missing_crossref", "缺失交叉引用");
        renderDiagRule(sb, config, "conflict", "页面矛盾");
        renderDiagRule(sb, config, "gap", "概念缺口");
        renderDiagRule(sb, config, "action", "AI 改进建议");
        renderDiagRule(sb, config, "web_gap", "网络信息缺口");

        LintRulesConfig.DiagnosticStandard ds = config.getDiagnosticStandard();
        sb.append("\n**诊断标准**：\n\n");
        sb.append("- 孤儿页面最小年龄：").append(ds.getOrphanMinAgeDays()).append(" 天\n");
        sb.append("- 过时高优先级阈值：滞后 ").append(ds.getStaleLagHighDays()).append(" 天\n");
        sb.append("- 过时中优先级阈值：滞后 ").append(ds.getStaleLagMediumDays()).append(" 天\n");
        if (ds.isProbeEnabled()) {
            sb.append("- 启用 AI 探查\n");
        }
        sb.append("\n");
    }

    private void renderDiagRule(StringBuilder sb, LintRulesConfig config, String key, String label) {
        LintRulesConfig.DiagnosticRule rule = config.getDiagnosticRules().get(key);
        if (rule == null) return;
        sb.append("- **").append(label).append("**：优先级 ").append(rule.getDefaultPriority())
            .append("，处置方式 ").append(rule.getDefaultHandlingMethod());
        if (!rule.isAutoFixEnabled()) {
            sb.append("（需人工确认）");
        }
        sb.append("\n");
    }

    private void renderSection62(StringBuilder sb, LintRulesConfig config) {
        sb.append("### 6.2 自动修复授权范围\n\n");
        LintRulesConfig.InterventionTiers tiers = config.getInterventionTiers();
        sb.append("- 风险分 ≤ ").append(tiers.getAutoRepairMax()).append("：自动修复\n");
        sb.append("- 风险分 ≤ ").append(tiers.getAutoRepairWithNotifyMax()).append("：自动修复 + 通知用户\n");
        sb.append("- 风险分 > ").append(tiers.getAutoRepairWithNotifyMax()).append("：生成裁决简报等用户拍板\n\n");
    }

    private void renderSection63(StringBuilder sb, LintRulesConfig config) {
        sb.append("### 6.3 反馈学习\n\n");
        LintRulesConfig.FeedbackLearningConfig fl = config.getFeedbackLearning();
        sb.append("- 用户连续忽略同类诊断 ").append(fl.getDismissCountToDowngrade()).append(" 次后，自动降级探查敏感度\n");
        sb.append("- 降级优先级级别：").append(fl.getDowngradePriorityLevels()).append("\n\n");
    }

    private void renderSection64(StringBuilder sb, LintRulesConfig config) {
        sb.append("### 6.4 调度配置\n\n");
        LintRulesConfig.ScheduleConfig sc = config.getScheduleConfig();
        sb.append("- 个人知识库调度：").append(sc.getPersonalCron()).append("\n");
        sb.append("- 团队知识库调度：").append(sc.getTeamCron()).append("\n");
        sb.append("- 跳过窗口：最近 ").append(sc.getSkipWindowHours()).append(" 小时内已完成且无新来源则跳过\n");
        sb.append("- 单次执行 Token 软上限：").append(sc.getPerExecutionTokenSoftLimit()).append("\n\n");
    }

    private void renderSection65(StringBuilder sb, LintRulesConfig config) {
        sb.append("### 6.5 矛盾裁决策略\n\n");
        LintRulesConfig.ConflictResolutionRules cr = config.getConflictResolutionRules();
        sb.append("- 默认策略：").append(cr.getDefaultStrategy()).append("\n");

        List<LintRulesConfig.SourcePriorityTier> tiers = cr.getSourcePriorityTiers();
        if (tiers != null && !tiers.isEmpty()) {
            sb.append("- 来源优先级层级：\n");
            for (LintRulesConfig.SourcePriorityTier tier : tiers) {
                sb.append("  ").append(tier.getTier()).append(". ").append(tier.getLabel()).append("\n");
            }
        }
        sb.append("\n");
    }

    private void renderSection7(StringBuilder sb) {
        sb.append("## 7. 变更日志\n\n");
        sb.append("- ").append(LocalDate.now()).append(" 由结构化模型渲染生成。\n\n");
    }
}
