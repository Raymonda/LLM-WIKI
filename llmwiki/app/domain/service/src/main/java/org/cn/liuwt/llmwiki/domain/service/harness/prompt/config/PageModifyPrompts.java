package org.cn.liuwt.llmwiki.domain.service.harness.prompt.config;

import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;

public class PageModifyPrompts {

    private PageModifyPrompts() {}

    private static final PageModifyPrompts INSTANCE = new PageModifyPrompts();

    public static PageModifyPrompts get() {
        return INSTANCE;
    }

    public String analyzeFeedback(String currentContent, String instruction, String globalSummary) {
        String gs = (globalSummary != null && !globalSummary.isBlank())
            ? "\n[[知识库上下文]]\n" + globalSummary
            : "";

        return """
            你是知识库 Wiki 页面的修改分析 Agent。用户对某个 Wiki 页面提出了修改意见，你需要分析这个意见并制定修改计划。

            核心原则：
            1. 用户意见绝对优先——除非明确违反 Schema 规则（见上方 Schema 骨架），否则必须完全采纳
            2. 分析用户意见会影响当前页面的哪些 section/段落
            3. 分析知识库中哪些其他页面引用或依赖了被修改的内容 → 这些页面也需要同步更新
            4. 自我检查修改计划是否与 Schema 规则冲突

            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """

            输出 JSON：
            {
              "targetChanges": [
                {"section": "目标段落标题（如 ## 概述）", "change": "具体怎么改", "rationale": "基于用户意见的理由"}
              ],
              "affectedPages": [
                {"pagePath": "wiki/pages/xxx.md", "relevantChange": "需要同步修改的内容", "whyAffected": "该页面引用了被修改内容"}
              ],
              "schemaSelfCheck": [
                {"rule": "Schema 规则名", "potentialIssue": "潜在冲突（无则填 null）", "mitigation": "消解方案"}
              ]
            }

            [[当前 Wiki 页面内容]]
            """ + currentContent + """

            [[用户修改意见]]
            """ + instruction + gs;
    }

    public String modifyPage(String existingContent, String instruction, String analysisContext) {
        String analysisSection = (analysisContext != null && !analysisContext.isBlank())
            ? "\n\n[[修改计划]]\n" + analysisContext
            : "";

        return """
            【用户反馈优先约束 —— 最高优先级规则】
            你是知识库 Wiki 页面的编辑 Agent。用户对这个页面提出了具体的修改意见，你必须完全采纳用户的意见。
            用户的修改意见是本次操作的唯一指令来源，其优先级高于任何其他写作规则。

            要求：
            1. 只修改用户意见涉及的段落，其他内容保持原样不变
            2. 保持页面的叙述风格和语气不变
            3. 所有 wiki 内部链接（[[page]] 语法）保持有效性，如果修改涉及链接目标需要同步调整
            4. 保持 Schema 规则合规（见上方 Schema 骨架）
            5. 用户反馈的意见必须完全采纳——如果用户说"应该强调 X"，你必须在页面中强调 X

            """ + PromptTemplate.knowledgeNormalizationPrinciple() + """

            """ + PromptTemplate.MARKDOWN_OUTPUT_CONSTRAINT + """

            [[待修改的页面当前内容]]
            """ + existingContent + """

            [[用户修改意见（必须采纳）]]
            """ + instruction + analysisSection;
    }
}
