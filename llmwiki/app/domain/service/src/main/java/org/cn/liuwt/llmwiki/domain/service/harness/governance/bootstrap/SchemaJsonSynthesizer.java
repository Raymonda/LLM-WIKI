package org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaMarkdownRenderer;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaStructuredParser;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class SchemaJsonSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(SchemaJsonSynthesizer.class);

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaStructuredParser schemaStructuredParser;

    @Autowired
    private SchemaMarkdownRenderer schemaMarkdownRenderer;

    @Autowired
    private ParadigmCatalog paradigmCatalog;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SynthesisResult synthesize(String q1, String a1, String q2, String a2,
                                     String q3, String a3, String q4, String a4,
                                     java.util.List<String> paradigmIds) {
        if (chatClient == null || !chatClient.isAvailable()) {
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }

        java.util.List<SchemaStructuredModel> skeletons = new java.util.ArrayList<>();
        java.util.List<String> skeletonLabels = new java.util.ArrayList<>();
        if (paradigmIds != null) {
            for (String pid : paradigmIds) {
                if (pid == null || pid.isBlank()) continue;
                ParadigmCatalog.Paradigm paradigm = paradigmCatalog.getById(pid);
                if (paradigm != null) {
                    skeletons.add(paradigm.skeleton);
                    skeletonLabels.add(paradigm.label);
                }
            }
        }

        String systemPrompt = buildSystemPrompt(skeletons, skeletonLabels);
        String userPrompt = buildUserPrompt(q1, a1, q2, a2, q3, a3, q4, a4);

        String raw = chatClient.chat(systemPrompt, userPrompt);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_SYNTH_EMPTY);
        }

        String cleaned = stripFences(raw).trim();
        SchemaStructuredModel model = tryParseJson(cleaned);

        if (model == null) {
            log.info("LLM 未返回有效 JSON，尝试从 Markdown 回退解析");
            model = schemaStructuredParser.parse(cleaned);
        }

        if (model == null) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_SYNTH_PARSE_FAILED);
        }

        if (model.getLintRules() == null) {
            model.setLintRules(LintRulesConfig.buildDefaults());
        }

        String markdown = schemaMarkdownRenderer.render(model);
        String json = schemaStructuredParser.toJson(model);

        return new SynthesisResult(model, json, markdown);
    }

    public SynthesisResult synthesizeFromStructured(SchemaStructuredModel model, java.util.List<String> capabilityIds) {
        if (chatClient == null || !chatClient.isAvailable()) {
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }

        String systemPrompt = """
            你是知识库 Schema 架构师。用户已经通过结构化向导完成了知识库设定，你需要将结构化数据润色为自然语言的 Schema Markdown。

            输出要求：
            1. 输出完整的 7 段 Markdown Schema（## 1. 领域定位 ~ ## 7. 变更日志）
            2. §1 领域定位：基于 domainNarrative 润色为流畅的中文描述
            3. §2 分类体系：直接渲染 taxonomy 结构为层级列表
            4. §3 页面模板：直接渲染 templates 中的页面模板和章节定义
            5. §4 命名与引用约定：直接渲染 naming 规则，补充具体示例
            6. §5 摄入工作流：基于 workflow 润色为自然语言规则
            7. §6 健康检查规则：基于 lintRules 生成可读的规则描述
            8. §7 变更日志：只放一条初始记录 `- YYYY-MM-DD 冷启动初版，由 AI 与用户协同生成。`
            9. 只输出 Markdown，禁止 ``` 包裹

            输入 JSON：
            %s
            """.formatted(toJson(model));

        String userPrompt = "今天是 %s，请生成 7 段 Schema Markdown。".formatted(LocalDate.now());

        String raw = chatClient.chat(systemPrompt, userPrompt);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_SYNTH_EMPTY);
        }

        String markdown = raw.trim();
        if (markdown.startsWith("```")) {
            int firstNewline = markdown.indexOf('\n');
            if (firstNewline > 0) markdown = markdown.substring(firstNewline + 1);
            if (markdown.endsWith("```")) markdown = markdown.substring(0, markdown.length() - 3);
            markdown = markdown.trim();
        }

        String json = schemaStructuredParser.toJson(model);
        return new SynthesisResult(model, json, markdown);
    }

    private String buildSystemPrompt(java.util.List<SchemaStructuredModel> skeletons,
                                      java.util.List<String> skeletonLabels) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            你是知识库 Schema 架构师。你要把四轮对话归纳成一份结构化的 Schema JSON。

            输出要求：
            1. 输出一个完整的 JSON 对象，结构严格遵循 SchemaStructuredModel 格式
            2. 所有值必须来自用户的实际回答，不要编造用户未提及的内容
            3. 如果用户未明确某个字段，使用合理的默认值或留空
            4. lintRules 字段可以省略（系统会自动填充默认值）
            5. 只输出 JSON，不要任何前后文、不要用 ``` 包裹

            JSON 结构说明：
            {
              "schemaFormatVersion": 1,
              "domainNarrative": "领域定位的完整描述（1-3句话）",
              "taxonomy": {
                "narrative": "分类体系的说明",
                "roots": [
                  {
                    "id": "分类标识（英文下划线）",
                    "label": "分类名称",
                    "description": "分类说明",
                    "children": [...]
                  }
                ]
              },
              "templates": {
                "narrative": "模板说明",
                "pageTemplates": [
                  {
                    "type": "模板标识",
                    "label": "模板名称",
                    "sections": [
                      {"id": "section_id", "label": "章节名", "required": true, "order": 1}
                    ]
                  }
                ]
              },
              "naming": {
                "narrative": "命名约定说明",
                "entity": {"language": "zh-CN", "maxLength": 30},
                "summary": {"language": "zh-CN", "maxLength": 40},
                "examples": ["示例1", "示例2"]
              },
              "workflow": {
                "narrative": "工作流说明",
                "defaultApproval": "AUTO 或 CONFIRM",
                "confirmTriggers": ["需确认的场景1"]
              }
            }
            """);

        if (!skeletons.isEmpty()) {
            sb.append("\n\n");
            if (skeletons.size() == 1) {
                sb.append("参考范式骨架（基于此进行微调，保留合理结构，根据用户回答修改具体内容）：\n");
                sb.append(schemaStructuredParser.toJson(skeletons.get(0)));
            } else {
                sb.append("用户选择了多个范式（").append(String.join("、", skeletonLabels))
                    .append("）。请融合以下多个范式骨架，取其分类和模板的并集，根据用户回答调整具体内容：\n");
                for (int i = 0; i < skeletons.size(); i++) {
                    sb.append("\n--- 范式 ").append(i + 1).append(": ").append(skeletonLabels.get(i)).append(" ---\n");
                    sb.append(schemaStructuredParser.toJson(skeletons.get(i)));
                    sb.append("\n");
                }
            }
        }

        return sb.toString();
    }

    private String buildUserPrompt(String q1, String a1, String q2, String a2,
                                    String q3, String a3, String q4, String a4) {
        return """
            对话原文：

            【Q1】%s
            【A1】%s

            【Q2】%s
            【A2】%s

            【Q3】%s
            【A3】%s

            【Q4】%s
            【A4】%s

            今天是 %s，请生成结构化 Schema JSON。
            """.formatted(q1, a1, q2, a2, q3, a3, q4, a4, LocalDate.now());
    }

    private SchemaStructuredModel tryParseJson(String raw) {
        try {
            String json = raw;
            int braceStart = json.indexOf('{');
            int braceEnd = json.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = json.substring(braceStart, braceEnd + 1);
            }
            return objectMapper.readValue(json, SchemaStructuredModel.class);
        } catch (Exception e) {
            log.debug("JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private String stripFences(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) s = s.substring(firstNewline + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s;
    }

    public RefineResult refineSection(SchemaStructuredModel model, int sectionNumber, String feedback) {
        if (chatClient == null || !chatClient.isAvailable()) {
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }

        String sectionJson = extractSectionJson(model, sectionNumber);
        String sectionLabel = getSectionLabel(sectionNumber);

        String systemPrompt = """
            你是 Schema 架构师。用户要对当前知识库规范的「%s」章节提出修改意见。

            输出要求：
            1. 输出修改后的该章节 JSON（仅该章节，不要输出完整 Schema）
            2. 严格遵循用户的修改要求，保留用户未提及的部分
            3. 只输出 JSON，不要任何前后文

            当前「%s」章节内容：
            %s
            """.formatted(sectionLabel, sectionLabel, sectionJson);

        String userPrompt = "我的修改意见：" + feedback;

        String raw = chatClient.chat(systemPrompt, userPrompt);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_REFINE_EMPTY);
        }

        String cleaned = stripFences(raw).trim();
        SchemaStructuredModel updated = applySectionJson(model, sectionNumber, cleaned);
        String json = schemaStructuredParser.toJson(updated);
        String markdown = schemaMarkdownRenderer.render(updated);
        return new RefineResult(updated, json, markdown);
    }

    private String extractSectionJson(SchemaStructuredModel model, int sectionNumber) {
        return switch (sectionNumber) {
            case 1 -> model.getDomainNarrative() != null ? "\"" + model.getDomainNarrative() + "\"" : "\"\"";
            case 2 -> toJson(model.getTaxonomy());
            case 3 -> toJson(model.getTemplates());
            case 4 -> toJson(model.getNaming());
            case 5 -> toJson(model.getWorkflow());
            default -> "\"\"";
        };
    }

    private String getSectionLabel(int sectionNumber) {
        return switch (sectionNumber) {
            case 1 -> "领域定位";
            case 2 -> "分类体系";
            case 3 -> "页面模板";
            case 4 -> "命名与引用约定";
            case 5 -> "摄入工作流";
            default -> "未知";
        };
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private SchemaStructuredModel applySectionJson(SchemaStructuredModel model, int sectionNumber, String json) {
        try {
            String cleaned = json;
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            } else if (sectionNumber == 1) {
                int qStart = json.indexOf('"');
                int qEnd = json.lastIndexOf('"');
                if (qStart >= 0 && qEnd > qStart) {
                    model.setDomainNarrative(json.substring(qStart + 1, qEnd));
                } else {
                    model.setDomainNarrative(json.replaceAll("^\"|\"$", "").trim());
                }
                return model;
            }

            return switch (sectionNumber) {
                case 2 -> { model.setTaxonomy(objectMapper.readValue(cleaned, SchemaStructuredModel.Taxonomy.class)); yield model; }
                case 3 -> { model.setTemplates(objectMapper.readValue(cleaned, SchemaStructuredModel.Templates.class)); yield model; }
                case 4 -> { model.setNaming(objectMapper.readValue(cleaned, SchemaStructuredModel.Naming.class)); yield model; }
                case 5 -> { model.setWorkflow(objectMapper.readValue(cleaned, SchemaStructuredModel.Workflow.class)); yield model; }
                default -> model;
            };
        } catch (Exception e) {
            log.warn("applySectionJson failed for section {}: {}", sectionNumber, e.getMessage());
            return model;
        }
    }

    public static class RefineResult {
        public final SchemaStructuredModel model;
        public final String json;
        public final String markdown;

        public RefineResult(SchemaStructuredModel model, String json, String markdown) {
            this.model = model;
            this.json = json;
            this.markdown = markdown;
        }
    }

    public static class SynthesisResult {
        public final SchemaStructuredModel model;
        public final String json;
        public final String markdown;

        public SynthesisResult(SchemaStructuredModel model, String json, String markdown) {
            this.model = model;
            this.json = json;
            this.markdown = markdown;
        }
    }
}
