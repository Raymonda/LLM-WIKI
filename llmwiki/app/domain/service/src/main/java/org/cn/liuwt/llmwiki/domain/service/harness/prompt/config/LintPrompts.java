package org.cn.liuwt.llmwiki.domain.service.harness.prompt.config;

import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;

public class LintPrompts {

    private LintPrompts() {}

    public String promoteFromUpstream(String pageTitle) {
        return PromptTemplate.knowledgeNormalizationPrinciple() + "\n\n---\n\n" + """
            提炼可共享的团队级知识，移除个人语境和隐私细节，保留知识核心结构。
            不搬运原文段落，不暴露原作者个人信息。输出是团队视角的知识，而非个人笔记。

            提炼标准：
            - 去掉"我认为"、"我的理解"等个人主观表述
            - 去掉个人经历、工作细节等隐私相关内容
            - 保留概念定义、方法论、技术原理等客观知识
            - 补全省略的上下文，使知识独立可理解
            - 格式规范，使用标准的 Markdown 结构

            原始知识标题：""" + pageTitle + "\n\n原始内容：";
    }

    public String checkConflicts(String pagesContext) {
        return """
            检查以下知识库页面中是否存在矛盾或过时的内容。
            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            返回 JSON 数组，每个元素包含：
            - pagePath: 存在问题的页面路径
            - conflictType: 问题类型（contradiction/outdated/incomplete）
            - description: 问题的具体描述

            判定标准：
            - contradiction: 两个或多个页面对同一事物有相反的描述
            - outdated: 内容引用了已过时的信息或版本
            - incomplete: 核心主题缺少关键的说明部分

            """ + pagesContext;
    }

    public String checkGaps(String pagesContext) {
        return """
            检查以下知识库中是否存在概念缺口。所谓概念缺口，是指根据已有页面的知识网络，
            推论出应该存在但尚未创建的页面或主题。
            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            返回 JSON 数组，每个元素包含：
            - gapTopic: 缺失的主题名称
            - suggestedCategory: 建议的分类
            - reason: 为什么这个主题是缺口（基于已有页面的逻辑推导）
            """ + pagesContext;
    }

    public String suggestActions(String report) {
        return """
            根据以下知识库健康报告，建议具体的改进措施。
            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            返回 JSON 数组，每个元素包含：
            - action: 建议的行动名称
            - priority: 优先级（high/medium/low）
            - category: 建议类别（fix/explore_topic/find_source/web_supplement）
            - description: 具体描述（说明行动内容、影响和理由）

            行动类别说明：
            - fix: 修复建议 — 合并页面、更新内容、解决矛盾、创建链接、重组分类、修复格式
            - explore_topic: 探索建议 — 基于当前知识结构，推荐下一步可以深入研究的主题
            - find_source: 来源建议 — 指出哪些主题可以通过搜索获取更多公开资料补充
            - web_supplement: 网络缺口 — 标注哪些已存在页面缺少关键的可公开获取信息，建议搜索补充

            """ + report;
    }

    public String checkStale(String pageTitle, String pageSummary) {
        return """
            请判断以下 Wiki 页面的内容是否可能已经过时。
            背景：该页面的来源文件在页面最后一次检查后有过更新。
            请基于你的知识，判断页面中描述的信息是否与当前最新认知存在偏差。

            页面标题：""" + pageTitle + """
            页面摘要：""" + pageSummary + """

            请用 1-3 句话回答：
            - 是否存在明显过时或不准确的描述？
            - 如果是，具体是什么方面过时了？
            - 如果无法判断，直接说"无法确定"。
            """;
    }

    public String batchStaleCheck(String pageDigest) {
            return """
                以下 Wiki 页面都没有来源追踪（无法通过来源更新时间判定是否过时），且长期未更新。
                请根据你的知识判断这些页面中哪些很可能已经过时。
    
                """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
                返回 JSON 对象，包含一个 likelyStale 数组，每个元素：
                - title: 页面标题（必须与输入标题完全匹配）
                - reason: 1-2 句简短说明为什么认为过时（如：技术已演进、概念已变更、行业标准已更新）
                - confidence: high/medium/low
    
                判定标准：
                - 页面描述的技术/概念/标准是否已有公认的重大更新
                - 页面内容是否涉及快速变化的领域（技术规范、API、法规等）
                - 如果无法判断或领域稳定不易过时，不要列入
    
                只返回确实可能过时的页面，不要列入"不确定"或"可能不过时"的页面。
    
                """ + pageDigest;
        }
    
        public String checkMissingCrossrefs(String pageATitle, String pageBTitle) {
        return """
            两个 Wiki 页面共享关键词但彼此没有链接。请判断它们的内容是否存在语义关联，
            是否应该建立交叉引用链接。

            """ + "页面 A：「" + pageATitle + "」\n" + "页面 B：「" + pageBTitle + "」\n\n" + """
            请用 1 句话回答：
            - 如果有关联，说明什么关联方向（如 A explains B / B extends A / 并列关系）
            - 如果无关联，说"无关联"。
            """;
    }

    public String diagnoseOrphan(String pageTitle, String pageSummary, String globalSummary) {
        return """
            你是一个知识库诊断专家。以下是一个孤立页面（无任何入站链接），请诊断它孤立的根因。

            页面标题：""" + pageTitle + """

            页面摘要（前200字）：
            """ + pageSummary + """

            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            返回一个 JSON 对象，包含以下字段：
            - diagnosis: 诊断结果，必须是以下四种之一：
              * "integrate" — 该页面与知识库中已有页面存在强语义关联，但缺少链接。需要补建链接。
              * "duplicate" — 该页面与知识库中某个已有页面内容高度重复（同一主题的不同表述或变体）。应该合并。
              * "standalone" — 该页面是独立知识点（如术语定义、标准编号、独立概念），与现有体系关联较弱，接受孤立状态。
              * "thin" — 该页面内容过薄（少于200字、仅标题+一句话、无实质知识内容），需要补充或删除。
            - reason: 诊断理由（1-2句话）
            - duplicateTarget: 仅当 diagnosis="duplicate" 时填写，为目标页面的 filePath（从下方知识库结构中匹配）
            - thinSuggestion: 仅当 diagnosis="thin" 时填写，为补充内容的建议主题（用于引导用户补充资料）

            判定优先级：thin > duplicate > integrate > standalone
            （即：先判断是否过薄，再判断是否重复，再判断是否应链接，最后才认为是独立知识）

            知识库现有页面结构：
            """ + globalSummary;
    }

    public String suggestOrphanLinks(String orphanFilePath, String orphanTitle, String orphanSummary, String globalSummary) {
        return """
            以下是一个孤立页面（无入站链接），请从知识库现有页面中为其选择最合适的链接目标。

            孤儿页面路径：""" + orphanFilePath + """
            孤儿页面标题：""" + orphanTitle + """
            孤儿页面摘要：
            """ + orphanSummary + """

            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            返回 JSON 数组，每个元素包含：
            - fromPage: 链接源页面的 filePath（从下方知识库结构中选择）
            - toPage: 链接目标页面的 filePath
            - linkType: 链接类型（related/extends/sibling/prerequisite）
            - reason: 建议链接的理由（1句话）

            链接方向规则：
            - 入站链接（推荐）: fromPage=目标页面path, toPage=""" + orphanFilePath + """
            - 出站链接: fromPage=""" + orphanFilePath + """
              , toPage=目标页面path
            - 建议优先创建入站链接（让其他页面引用孤儿），因为这是孤儿缺少的

            约束：
            - 最多返回 3 条链接
            - fromPage 和 toPage 的值必须是 filePath 格式（如 pages/xxx.md），不是页面标题
            - 只选择与孤儿页面有强语义关联的页面，不要牵强链接
            - filePath 必须从下方知识库结构中精确匹配

            知识库现有页面结构：
            """ + globalSummary;
    }

    public String enrichThinPage(String pageTitle, String existingContent, String userSupplement) {
        return """
            你是一个知识库编辑专家。以下 Wiki 页面内容过薄，用户提供了补充资料，请将其融合到现有页面中。

            页面标题：""" + pageTitle + """

            当前页面内容：
            ---
            """ + existingContent + """
            ---

            用户补充的资料：
            ---
            """ + userSupplement + """
            ---

            融合规则：
            - 保留原有页面结构和已有内容，在此基础上扩展和丰富
            - 将补充资料整合到合适的章节中，必要时新增章节
            - 去除重复内容，保持知识页面的简洁和专业性
            - 使用与现有页面一致的 Markdown 格式风格
            - 直接输出完整的融合后页面内容（Markdown 格式），不要输出说明或注释
            - 不要使用 ```markdown 围栏包裹输出
            """;
    }

    public String generateCrossrefLink(String pageATitle, String pageAContent, String pageBTitle, String pageBContent) {
        // 截断内容以避免超出 token 限制（保留前 3000 字符）
        String pageAContentTruncated = pageAContent != null && pageAContent.length() > 3000 
            ? pageAContent.substring(0, 3000) + "\n...(内容截断)" 
            : (pageAContent != null ? pageAContent : "(无内容)");
        String pageBContentTruncated = pageBContent != null && pageBContent.length() > 3000 
            ? pageBContent.substring(0, 3000) + "\n...(内容截断)" 
            : (pageBContent != null ? pageBContent : "(无内容)");
        
        return """
            你是知识库链接质量评估专家。现有两个页面共享关键词但彼此没有链接，请判断是否应该建立交叉引用。

            页面 A：「""" + pageATitle + """
            」
            内容摘要：
            ---
            """ + pageAContentTruncated + """
            ---

            页面 B：「""" + pageBTitle + """
            」
            内容摘要：
            ---
            """ + pageBContentTruncated + """
            ---

            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            请基于以下维度综合判断：
            1. **语义相关性**：两个页面是否讨论同一主题的不同方面？
            2. **互补性**：合在一起是否能提供更完整的理解？
            3. **链接价值**：建立链接后能否提升知识发现性？
            4. **避免过度链接**：链接是否自然且有意义（非强制拼接）？

            返回 JSON 数组（如果判断应该链接，返回 1-2 个元素表示双向或单向链接；如果不应链接，返回空数组 []）：
            每个元素包含：
            - shouldLink: 是否应该建立链接（boolean，true/false）
            - sourceTitle: 源页面标题
            - targetTitle: 目标页面标题
            - linkType: 链接类型（related-一般关联/reference-引用参考/supplement-信息补充/dependency-依赖前提/contradiction-内容矛盾）
            - linkContext: 链接上下文说明（1-2 句话，说明"为什么建立这个链接"，将展示给用户）
            - direction: 关系方向（A_explains_B / B_extends_A / sibling / complementary / A_contrasts_B）
            - confidence: AI 置信度（0.0-1.0，基于语义相关性和证据充分性）
            - reason: 判断理由（1 句话，解释为什么应该/不应该链接）

            注意：
            - 如果 shouldLink=false，不要返回该元素（直接 omit）
            - 如果两个页面只是提到相同名词但语境完全不同，shouldLink=false
            - confidence < 0.5 的建议应该谨慎，可能需要人工审核
            """;
    }

    public String fillGapPage(String gapTopic, String gapDetail) {
        return PromptTemplate.knowledgeNormalizationPrinciple() + "\n\n---\n\n" + """
            根据以下概念缺口的描述，生成一篇简洁的知识页面的 Markdown 内容。
            缺口主题：""" + gapTopic + """
            缺口描述：""" + gapDetail + """

            要求：
            - 标题为缺口主题，使用一级标题
            - 包含概览段落（2-3 句）
            - 若有具体的知识要点，列出 2-5 条
            - 标注页面底部的交叉引用建议（以 `> 相关页面：` 格式）
            - 不要使用 "TODO"、"待补充" 等占位符，生成真实内容
            """;
    }

    public String generateRulingBrief(String findingTitle, String findingDetail, String pageContent, String relatedContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            根据以下诊断发现和页面内容，生成一份裁决简报 JSON，用于决定是否及如何自动修复此问题。

            诊断发现：
            标题：""").append(findingTitle).append("""
            描述：""").append(findingDetail).append("\n\n");

        if (pageContent != null && !pageContent.isBlank()) {
            sb.append("目标页面内容：\n```\n").append(truncate(pageContent, 3000)).append("\n```\n\n");
        }
        if (relatedContent != null && !relatedContent.isBlank()) {
            sb.append("关联页面内容：\n```\n").append(truncate(relatedContent, 3000)).append("\n```\n\n");
        }

        sb.append("""
            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            返回一个 JSON 对象，包含以下字段：
            - action: 推荐执行动作（merge/rewrite/discard/keep），merge=合并两页内容，rewrite=重写目标页，discard=废弃目标页保留关联页，keep=保持现状不做修改
            - targetPagePath: 需修改的目标页面路径（merge/rewrite/discard 时必填）
            - evidence: 证据对比列表（对象数组），每个对象包含：field（矛盾字段名，如"定义"、"数值"、"分类"）、before（目标页面中的当前描述）、after（建议修正后的描述）
            - recommendation: 推荐方案（1-3句话的最终建议，如"建议将页面A中关于X的描述合并到页面B，统一采用较新的定义"）
            - alternatives: 备选方案列表（对象数组），每个对象包含：label（方案名称）、description（方案描述）、riskLevel（风险等级：high/medium/low）
            - riskTags: 风险标签（字符串数组，如["数据丢失风险", "需要人工确认"]）
            - confidence: 置信度（0.0到1.0之间的浮点数，表示对此裁决方案的信心程度）
            """);
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "\n...(截断)";
    }

    public String probeSystemPrompt(LintRulesConfig rules) {
        LintRulesConfig.DiagnosticStandard ds = rules.getDiagnosticStandard();
        LintRulesConfig.FeedbackLearningConfig fl = rules.getFeedbackLearning();
        return String.format(PROBE_SYSTEM_PROMPT_TEMPLATE,
            ds.getOrphanMinAgeDays(),
            ds.getStaleLagMediumDays(),
            ds.getStaleLagHighDays(),
            ds.getStaleSourcelessAgeDays(),
            ds.getCrossrefMinSharedKeywords(),
            fl.getDismissCountToDowngrade(),
            fl.getDowngradePriorityLevels()
        );
    }

    private static final String PROBE_SYSTEM_PROMPT_TEMPLATE = """
        你是知识库健康诊断 Agent。你的任务是综合分析知识库的索引、日志、历史诊断结果和页面健康状态，
        在一次探查中识别所有类型的健康问题，输出结构化 JSON。

        你需要诊断以下 6 种类型：
        1. **orphan**（孤立页面）：页面无入站链接，在知识图谱中难以被发现。
           判定标准：入站链接数为0，且页面创建超过 %d 天。
        2. **stale**（过时声明）：页面内容落后于来源文件更新，或无来源且长期未更新。
           判定标准：来源文件修改时间晚于页面更新时间超过 %d 天（中优先级）或 %d 天（高优先级）；无来源页面超过 %d 天未更新。
        3. **missing_crossref**（缺失交叉引用）：两个页面共享关键词但彼此无链接。
           判定标准：共享至少 %d 个关键词。
        4. **conflict**（矛盾/过时内容）：两个或多个页面对同一事物有矛盾描述，或内容引用已过时信息。
           必须指出与哪个页面存在矛盾，给出对方页面的文件路径和ID（若知识库中存在）。
        5. **schema_violation**（Schema 合规违规）：页面违反知识库 Schema 规则（分类不合规、命名不符合约定、页面结构缺失必需章节等）。
           仅报告目前仍存在的 schema_violation——Ingest 已记录的类型不再重复输出。
        6. **schema_compliance**（Schema 结构合规）：页面结构或命名不符合 Schema 定义的模板约定。
           检查：页面是否缺少 Schema 定义必需的章节、命名是否遵循约定、是否使用正确的分类体系。
           violationType: CATEGORY（分类不在 Schema 分类体系中）、NAMING（命名不符合约定）、PAGE_STRUCTURE（缺少必需章节）。

        反馈学习规则：
        - 如果某种诊断类型被用户连续忽略 %d 次以上，对该类型降低 %d 个优先级级别。
        - 如果上次诊断结果中已包含相同页面/相同类型且状态为已解决/已驳回，不要重复报告。

        """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
        返回 JSON 数组，每个元素必须包含以下字段：
        - type: 问题类型（orphan/stale/missing_crossref/conflict/schema_violation/schema_compliance）
        - pagePath: 相关页面路径
        - title: 短标题（如："孤立页面：「XX」无入站链接"）
        - detail: 详细描述（2-4句话，说明问题现象、影响和建议）
        - priority: 优先级（high/medium/low）
        - handlingMethod: 建议处置方式（auto_repair/auto_refresh/manual_ingest/ruling_brief/manual_edit）
        - confidence: 置信度（high/medium/low）

        针对不同类型，还需要包含以下额外字段：
        - orphan: suggestedAction（建议行动，如"添加入站链接"）
        - stale: 无额外字段，但 detail 中应说明滞后天数
        - missing_crossref: relatedPagePath（另一页面的路径）, relatedPageId（另一页面的ID，如知道）
        - conflict: relatedPagePath（矛盾对方页面路径）、relatedPageId（对方页面ID，如知道）、relatedPageTitle（对方页面标题，如知道）、conflictType（矛盾类型：value_conflict/fact_conflict/definition_conflict/temporal_conflict）、existingClaim（对方主张摘要）、newClaim（本方主张摘要）
        - schema_violation: violationType（违规类型，如 CATEGORY/NAMING/PAGE_STRUCTURE），suggestion（修复建议）
        - schema_compliance: violationType（违规类型：CATEGORY/NAMING/PAGE_STRUCTURE），expectedStructure（期望结构），actualStructure（实际结构）

        重要约束：
        - 只输出你确信存在问题的情况，不要输出"不确定"或"可能没问题"的条目
        - 如果知识库整体健康，输出空数组 []
        - 不要输出与上次诊断完全重复的条目（除非问题仍在且未被修复）
        - 不要输出已处于 resolved/dismissed/rolled_back 状态的重复问题
        """;

    public static LintPrompts get() {
        return Holder.INSTANCE;
    }

    public static LintPrompts getInstance() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        static final LintPrompts INSTANCE = new LintPrompts();
    }
}
