package org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.TaxonomyNode;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BootstrapAdvisor {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdvisor.class);

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private ParadigmCatalog paradigmCatalog;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===== Mode A: Rule-based inline suggestions =====

    public List<Map<String, Object>> checkCategoryTree(List<String> capabilityIds, String treeJson) {
        List<Map<String, Object>> suggestions = new ArrayList<>();

        JsonNode tree;
        try {
            tree = objectMapper.readTree(treeJson);
        } catch (Exception e) {
            return suggestions;
        }

        JsonNode roots = tree.path("roots");
        if (!roots.isArray() || roots.isEmpty()) {
            suggestions.add(buildSuggestion("warning", "分类树为空，请至少添加一个顶级分类", null));
            return suggestions;
        }

        if (roots.size() == 1 && !roots.get(0).path("children").isArray()) {
            suggestions.add(buildSuggestion("recommendation",
                "当前只有 1 个分类且没有子分类，建议增加层级让知识更有条理",
                buildAction("addRoot", null)));
        }

        for (JsonNode root : roots) {
            String label = root.path("label").asText("");
            JsonNode children = root.path("children");
            if (children.isArray() && children.size() == 1) {
                suggestions.add(buildSuggestion("info",
                    "「" + label + "」下只有 1 个子分类，考虑是否需要补充或合并",
                    null));
            }
        }

        if (capabilityIds != null && capabilityIds.contains("chronicle")) {
            boolean hasTimeCategory = false;
            for (JsonNode root : roots) {
                String label = root.path("label").asText("").toLowerCase();
                if (label.contains("日") || label.contains("周") || label.contains("月")
                    || label.contains("资讯") || label.contains("时间")) {
                    hasTimeCategory = true;
                    break;
                }
                JsonNode children = root.path("children");
                if (children.isArray()) {
                    for (JsonNode child : children) {
                        String cl = child.path("label").asText("").toLowerCase();
                        if (cl.contains("日") || cl.contains("周") || cl.contains("月")
                            || cl.contains("资讯") || cl.contains("时间")) {
                            hasTimeCategory = true;
                            break;
                        }
                    }
                }
                if (hasTimeCategory) break;
            }
            if (!hasTimeCategory) {
                suggestions.add(buildSuggestion("recommendation",
                    "你选择了「过程记录」能力域，但分类树中没有按时间周期组织的分类。建议添加如「每日资讯」「周报」「月报」等分类",
                    buildAction("addNode", Map.of("label", "每日资讯", "parent", "chronicle"))));
            }
        }

        if (capabilityIds != null && capabilityIds.contains("research")) {
            boolean hasIndustryOrTopic = false;
            for (JsonNode root : roots) {
                String label = root.path("label").asText("").toLowerCase();
                if (label.contains("行业") || label.contains("研究") || label.contains("分析")
                    || label.contains("公司") || label.contains("策略")) {
                    hasIndustryOrTopic = true;
                    break;
                }
            }
            if (!hasIndustryOrTopic) {
                suggestions.add(buildSuggestion("recommendation",
                    "你选择了「研究分析」能力域，建议确保分类树包含行业研究或主题分析相关的分类",
                    null));
            }
        }

        return suggestions;
    }

    public List<Map<String, Object>> checkPageBlueprint(String blueprintJson) {
        List<Map<String, Object>> suggestions = new ArrayList<>();

        JsonNode blueprint;
        try {
            blueprint = objectMapper.readTree(blueprintJson);
        } catch (Exception e) {
            return suggestions;
        }

        JsonNode templates = blueprint.path("pageTemplates");
        if (!templates.isArray() || templates.isEmpty()) {
            suggestions.add(buildSuggestion("warning", "页面模板为空，请至少定义一个页面模板", null));
            return suggestions;
        }

        for (JsonNode tpl : templates) {
            JsonNode sections = tpl.path("sections");
            if (!sections.isArray() || sections.isEmpty()) {
                String tplLabel = tpl.path("label").asText("未知模板");
                suggestions.add(buildSuggestion("warning",
                    "模板「" + tplLabel + "」没有定义任何章节",
                    null));
            }

            boolean hasSourceRef = false;
            if (sections.isArray()) {
                for (JsonNode sec : sections) {
                    String secLabel = sec.path("label").asText("").toLowerCase();
                    if (secLabel.contains("来源") || secLabel.contains("引用") || secLabel.contains("参考")) {
                        hasSourceRef = true;
                        break;
                    }
                }
            }
            if (!hasSourceRef) {
                String tplLabel = tpl.path("label").asText("未知模板");
                suggestions.add(buildSuggestion("recommendation",
                    "模板「" + tplLabel + "」缺少来源引用章节，建议添加以确保知识可追溯",
                    buildAction("addSection", Map.of("template", tpl.path("type").asText(""), "label", "来源引用"))));
            }
        }

        return suggestions;
    }

    public List<Map<String, Object>> checkAutonomy(String level, Map<String, Object> overrides,
                                                     List<String> capabilityIds) {
        List<Map<String, Object>> suggestions = new ArrayList<>();

        if ("cautious".equals(level)) {
            if (capabilityIds != null && capabilityIds.contains("chronicle")) {
                suggestions.add(buildSuggestion("recommendation",
                    "你选择了「谨慎执行」模式，但「过程记录」能力域的知识更新频率较高（如每日资讯），每条都需确认可能会比较繁琐。建议对该能力域开启自动处理",
                    null));
            }
        }

        if ("autonomous".equals(level)) {
            if (capabilityIds != null && (capabilityIds.contains("compliance") || capabilityIds.contains("research"))) {
                suggestions.add(buildSuggestion("warning",
                    "「高度自主」模式下 AI 会自动执行大部分操作。如果你的知识库包含制度规范或深度研究内容，建议在关键变更时保留确认环节",
                    null));
            }
        }

        return suggestions;
    }

    // ===== Mode B: Conversational guidance (LLM) =====

    public Map<String, Object> chat(String step, String userMessage, String currentStepData,
                                     List<String> capabilityIds) {
        if (chatClient == null || !chatClient.isAvailable()) {
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }

        String stepContext = getStepContext(step);
        String capContext = capabilityIds != null ? String.join("、", capabilityIds) : "未选择";

        String systemPrompt = """
            你是知识库 Schema 配置助手。用户正在进行「%s」步骤的配置。
            用户已选择的知识能力域：%s

            你的职责：
            1. 用通俗易懂的中文回答用户问题，避免技术术语
            2. 如果用户困惑，给出具体建议和操作方案
            3. 如果用户的问题可以用操作指令解决，输出 action 字段

            输出 JSON 格式（不要 ``` 包裹）：
            {
              "reply": "你的回答（自然语言）",
              "actions": [
                {"type": "操作类型", "payload": {}}
              ]
            }

            actions 为空数组时表示无操作建议。
            """.formatted(stepContext, capContext);

        String userPrompt = userMessage;
        if (currentStepData != null && !currentStepData.isBlank()) {
            userPrompt += "\n\n当前配置数据（供参考）：\n" + currentStepData;
        }

        String raw = chatClient.chat(systemPrompt, userPrompt);
        if (raw == null || raw.isBlank()) {
            return Map.of("reply", "抱歉，我暂时无法回答，请稍后再试。", "actions", List.of());
        }

        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                int nl = cleaned.indexOf('\n');
                if (nl > 0) cleaned = cleaned.substring(nl + 1);
                if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
                cleaned = cleaned.trim();
            }
            JsonNode node = objectMapper.readTree(cleaned);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reply", node.path("reply").asText(raw));
            List<Object> actions = new ArrayList<>();
            JsonNode actionsNode = node.path("actions");
            if (actionsNode.isArray()) {
                actionsNode.forEach(a -> actions.add(objectMapper.convertValue(a, Map.class)));
            }
            result.put("actions", actions);
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse advisor chat response: {}", e.getMessage());
            return Map.of("reply", raw, "actions", List.of());
        }
    }

    // ===== Mode C: Step quality check (LLM) =====

    public List<Map<String, Object>> qualityCheck(String step, String stepData,
                                                    List<String> capabilityIds) {
        if (chatClient == null || !chatClient.isAvailable()) {
            return List.of();
        }

        String stepContext = getStepContext(step);
        String capContext = capabilityIds != null ? String.join("、", capabilityIds) : "未选择";

        String systemPrompt = """
            你是知识库 Schema 质量审核员。用户刚刚完成了「%s」步骤。
            用户已选择的知识能力域：%s

            请检查当前配置，找出 1-3 个可以优化的地方。只报告真正有价值的问题。

            输出 JSON 数组（不要 ``` 包裹）：
            [
              {
                "type": "info|warning|recommendation",
                "message": "建议内容（自然语言）",
                "action": {"type": "操作类型", "payload": {}}
              }
            ]

            如果没有需要改进的地方，返回空数组 []。
            """.formatted(stepContext, capContext);

        String userPrompt = "当前配置数据：\n" + (stepData != null ? stepData : "（空）");

        try {
            String raw = chatClient.chat(systemPrompt, userPrompt);
            if (raw == null || raw.isBlank()) return List.of();

            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                int nl = cleaned.indexOf('\n');
                if (nl > 0) cleaned = cleaned.substring(nl + 1);
                if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
                cleaned = cleaned.trim();
            }

            JsonNode arr = objectMapper.readTree(cleaned);
            List<Map<String, Object>> result = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    result.add(objectMapper.convertValue(item, Map.class));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Quality check failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ===== Helpers =====

    private Map<String, Object> buildSuggestion(String type, String message, Map<String, Object> action) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        s.put("message", message);
        if (action != null) {
            s.put("action", action);
        }
        return s;
    }

    private Map<String, Object> buildAction(String type, Map<String, Object> payload) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", type);
        a.put("payload", payload != null ? payload : Map.of());
        return a;
    }

    private String getStepContext(String step) {
        return switch (step) {
            case "capabilities" -> "选择知识能力";
            case "category-tree" -> "构建知识地图";
            case "page-blueprint" -> "设计页面结构";
            case "autonomy" -> "设定 AI 协作模式";
            default -> step;
        };
    }
}
