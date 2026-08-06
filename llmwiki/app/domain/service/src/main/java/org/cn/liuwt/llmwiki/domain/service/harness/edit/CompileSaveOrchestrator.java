package org.cn.liuwt.llmwiki.domain.service.harness.edit;

import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI 增强工具 — 为页面保存提供 Schema 校验、内容增强、摘要生成能力。
 * 编排职责已迁移至 PageSaveService，本类仅封装 LLM 调用。
 */
@Service
public class CompileSaveOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CompileSaveOrchestrator.class);

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaComplianceChecker schemaComplianceChecker;

    @Autowired
    private SchemaInjector schemaInjector;

    public boolean isAvailable() {
        return chatClient != null && chatClient.isAvailable();
    }

    public ComplianceResult runSchemaCheck(Long scopeId, String title, String content, String category) {
        try {
            String metadataJson = "{\"title\":\"" + safe(title) + "\",\"category\":\"" + safe(category) + "\"}";
            return schemaComplianceChecker.check(scopeId, metadataJson,
                Map.of(title != null ? title : "untitled", content != null ? content : ""));
        } catch (Exception e) {
            log.warn("Schema check failed, skipping: {}", e.getMessage());
            return ComplianceResult.ok();
        }
    }

    public String enhanceContent(Long scopeId, String title, String content, String category) {
        try {
            String systemPrompt = schemaInjector.prependForQuery(scopeId,
                "你是一个专业的 Wiki 文档编辑助手。请对以下 Markdown 文档进行结构化增强：\n" +
                "1. 确保标题层级合理（# 一级、## 二级等）\n" +
                "2. 补充必要的章节（如概述、参考链接等，如果缺少的话）\n" +
                "3. 保持原有信息不丢失\n" +
                "4. 只输出增强后的 Markdown 内容，不要添加任何解释\n");
            String enhanced = chatClient.chat(systemPrompt,
                "标题：" + title + "\n分类：" + (category != null ? category : "无") + "\n\n内容：\n" + content);
            return (enhanced != null && !enhanced.isBlank()) ? enhanced : content;
        } catch (Exception e) {
            log.error("AI content enhancement failed, using original: {}", e.getMessage());
            return content;
        }
    }

    public String generateSummary(Long scopeId, String title, String content) {
        try {
            String prompt = "请为以下 Wiki 页面生成一段简短的摘要（50-100字）：\n\n标题：" + title + "\n\n内容：\n" +
                content.substring(0, Math.min(content.length(), 2000));
            String summary = chatClient.chat(prompt);
            return (summary != null && !summary.isBlank()) ? summary.trim() : "";
        } catch (Exception e) {
            log.warn("Summary generation failed: {}", e.getMessage());
            return "";
        }
    }

    public String suggestCategory(Long scopeId, String title, String content, List<String> existingCategories) {
        try {
            String systemPrompt = schemaInjector.prependForQuery(scopeId,
                "你是一个知识库分类专家。请根据页面标题和内容，从已有分类中选择一个最合适的分类，"
                + "或者如果没有合适的已有分类，建议一个新的分类名称。\n"
                + "要求：\n"
                + "1. 分类名简短（2-6个字），适合作为文件夹名称\n"
                + "2. 优先复用已有分类\n"
                + "3. 只输出分类名称，不要输出任何其他内容\n");
            String existing = (existingCategories != null && !existingCategories.isEmpty())
                ? "已有分类：" + String.join("、", existingCategories) + "\n"
                : "";
            String userMsg = existing + "标题：" + title + "\n\n内容摘要：\n"
                + content.substring(0, Math.min(content.length(), 1000));
            String result = chatClient.chat(systemPrompt, userMsg);
            if (result != null && !result.isBlank()) {
                return result.trim().replaceAll("[\"']", "");
            }
            return "";
        } catch (Exception e) {
            log.warn("Category suggestion failed: {}", e.getMessage());
            return "";
        }
    }

    private String safe(String s) {
        return s != null ? s.replace("\"", "\\\"") : "";
    }
}
