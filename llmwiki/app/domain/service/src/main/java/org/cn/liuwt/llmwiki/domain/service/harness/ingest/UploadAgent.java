package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class UploadAgent {

    private static final Logger log = LoggerFactory.getLogger(UploadAgent.class);

    private static final int MIN_CONTENT_LENGTH_FOR_VALIDATION = 5000;
    private static final int SAMPLE_SIZE = 1500;

    @Autowired
    private ParserAgent parserAgent;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaInjector schemaInjector;

    public int process(IngestContext context) {
        parserAgent.parseAndLoad(context);

        String sourceContent = context.getSourceContent();
        if (chatClient == null || !chatClient.isAvailable() || sourceContent == null || sourceContent.length() <= MIN_CONTENT_LENGTH_FOR_VALIDATION) {
            context.setParseValidationReport("skipped");
            return 0;
        }

        int tokensUsed = 0;
        try {
            String sample = buildSample(sourceContent);
            String validationPrompt = schemaInjector.prepend(context.getScopeId(), buildValidationSystemPrompt());
            String response = chatClient.chat(validationPrompt, sample);
            context.setParseValidationReport(response);
            tokensUsed = estimateTokens(sample + response);
            log.info("Parse validation completed for scopeId={}, sourceId={}, tokensUsed={}",
                context.getScopeId(), context.getSourceId(), tokensUsed);
        } catch (Exception e) {
            log.warn("Parse validation failed for scopeId={}, sourceId={}: {}",
                context.getScopeId(), context.getSourceId(), e.getMessage());
            context.setParseValidationReport("validation_failed: " + e.getMessage());
        }

        return tokensUsed;
    }

    private String buildSample(String content) {
        int length = content.length();
        if (length <= SAMPLE_SIZE * 3) {
            return content;
        }

        String head = content.substring(0, SAMPLE_SIZE);
        int midStart = (length - SAMPLE_SIZE) / 2;
        String mid = content.substring(midStart, midStart + SAMPLE_SIZE);
        String tail = content.substring(length - SAMPLE_SIZE);

        return head + "\n\n...(中间部分节选)...\n\n" + mid + "\n\n...(后段节选)...\n\n" + tail;
    }

    private String buildValidationSystemPrompt() {
        return """
            你是一位文档解析质量审核员。你的职责是检查解析后的 Markdown 文档是否存在以下问题：

            1. **标题结构保留**：原始文档的标题层级（H1/H2/H3）是否在解析结果中正确保留
            2. **表格完整性**：Markdown 表格是否结构完整（表头行、分隔行、数据行齐全）
            3. **代码块保留**：代码块（```围栏）是否完整保留，未被截断或乱码
            4. **无乱码文本**：是否存在乱码、不可读字符或明显的解析错误

            请输出简洁的审核报告，格式如下：
            - 标题结构：OK / 问题描述
            - 表格完整性：OK / 问题描述
            - 代码块：OK / 问题描述
            - 文本质量：OK / 问题描述
            - 总体评估：通过 / 需关注（附简要原因）

            如果所有项均正常，直接输出"解析质量良好，无异常"即可。
            """;
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        int chineseCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') chineseCount++;
        }
        int nonChinese = text.length() - chineseCount;
        return chineseCount / 2 + nonChinese / 4;
    }
}
