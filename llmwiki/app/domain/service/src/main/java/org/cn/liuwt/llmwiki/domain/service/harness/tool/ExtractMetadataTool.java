package org.cn.liuwt.llmwiki.domain.service.harness.tool;

import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ExtractMetadataTool {

    @Autowired(required = false)
    private LlmClient chatClient;

    @Tool(description = "使用 LLM 从页面内容提取关键词、标签、分类和摘要。返回纯 JSON 格式的元数据，包含 title/summary/category/tags/keywords 字段")
    public String extractMetadata(
        @ToolParam(description = "页面内容（Markdown 格式）") String content
    ) {
        if (chatClient == null || !chatClient.isAvailable()) {
            return "{\"error\": \"AI 服务未配置\"}";
        }

        String prompt = PromptRegistry.forIngest().extractMetadata() + "\n\n页面内容：\n" + content;

        return chatClient.chat(prompt);
    }
}