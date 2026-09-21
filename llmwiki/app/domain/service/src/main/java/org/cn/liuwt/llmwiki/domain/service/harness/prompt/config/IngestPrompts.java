package org.cn.liuwt.llmwiki.domain.service.harness.prompt.config;

import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;

public class IngestPrompts {

    private static final String ENTITY_CENTRIC_PRINCIPLE = """
            【实体中心原则 —— 实体页的核心使命】
            实体页是围绕实体的零断言汇集台：只汇集来源明确陈述过的事实，不产生任何新断言。你必须：
            1. 从实体的视角组织内容：章节应围绕实体的属性、特征、角色、规则、关联等维度展开，而不是照搬源文档的章节结构。
            2. 汇集而非重写：源文档是制度/规范/报告等特定体裁，实体页是陈述汇集台。你需要从源文档中逐条提取与该实体相关的陈述，保持来源的原始表述，按实体自身的维度归类，不得改写、压缩或合并来源中的独立陈述。
            3. 每条陈述可追溯：每条事实性陈述必须紧随「（来源：<来源名称>）」标注；没有来源标注的陈述不得写入。
            4. 举例说明——如果源文档是《合规管理办法》制度文件，其中提到"XX证券股份有限公司"：
               - ✗ 错误：按制度文件的章节结构（总则→组织架构→合规职责→合规管理流程→附则）来组织"XX证券股份有限公司"的实体页
               - ✓ 正确：按公司实体的维度组织（公司概况→组织架构→业务领域→合规要求→关联机构），从制度文件中逐条提取与公司相关的陈述填入各维度
            5. 实体类型决定内容重心：
               - 组织/公司类：定位、职能、业务范围、组织架构、关联实体
               - 人物类：角色、职责、权限、汇报关系
               - 系统/工具类：功能、用途、技术特征、集成关系
               - 概念/制度类：定义、规则、适用条件、执行要求""";

    private static final String STRUCTURED_SOURCE_CONSTRAINT = """

            【STRUCTURED 源文档引用约束】
            本文档为权威性文件（法律/制度/规范），编写页面时：
            1. 规则条款、定义表述、量化指标必须使用 blockquote（>）引用原文，不得改写
            2. 每条引用后标注来源章节（如"—— 第三章 第五条"）
            3. 你的综合描述应围绕原文引用展开，而非替代原文""";

    private IngestPrompts() {}

    public String analyzeChunk() {
        return """
            分析以下文本片段，输出结构化导航结果（后续步骤将基于此索引进行详细写作）：

            1. 主要实体列表（仅列出有独立知识价值的实体，严格控制数量。每行格式：- 实体名 | 类型(人物/组织/系统/概念/文档/事件) | 一句话定位）
               实体收录标准：该实体在原文中有足够的实质性内容（至少3条独立事实或一段完整描述），能够为读者提供独立的知识价值。仅在原文中名字出现、或只有1-2句提及的实体不要列入。
            2. 关键事实（列出所有重要的事实、数据、结论，无数量限制，每条1句话，只提取原文中明确记载的内容，严禁自行补充或推测。对于规则/制度/流程类内容，逐条列出具体规定，不要概括合并）
            3. 与现有知识的潜在关联点（简述本片段可能与哪些主题交叉）
            4. 具体规则与条款（如原文包含制度/规范/流程/规定，逐条列出每条规则的具体内容、适用条件、责任主体。严禁将多条规则合并概括，必须保持原文的条目粒度）

            要求：详尽，宁可多不可少。只提取原文明确包含的信息，不要引入外部知识。实体列表宁缺毋滥——只保留真正有知识价值的实体。规则和条款必须逐条保留，不要概括。
            """;
    }

    public String analyzeChunkWithGuidance(String guidance) {
        return PromptTemplate.formatGuidancePrefix(guidance) + analyzeChunk();
    }

    public String subDocumentAnalyze(String parentTitle) {
        return """
            分析以下文档片段（属于更大文档「%s」的一个独立章节），提取实体和关键事实。
            严格忠于原文，不要引入外部知识。

            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """

            输出 JSON，包含以下字段：
            - entities: 实体列表（JSON数组，每个元素含：
                * name: 实体名称
                * type: 类型（人物/组织/系统/概念/文档/事件）
                * aliases: 别名数组
                * significance: "core"/"important"/"contextual"
                只提取在该章节中有实质性描述的实体（至少3条独立事实）。
                在该章节中仅名字出现或1-2句提及的实体不要列入。）
            - keyFacts: 该章节的关键事实列表（string数组，每条1句话，只提取原文明确记载的内容）
            """
            .formatted(parentTitle);
    }

    public String analyzeAndExtract(String guidance, String pagesContext) {
        String basePrompt = """
            分析以下文本，同时提取结构化导航结果和元数据。一次性完成，避免重复调用。

            提取事实和实体时严格忠于原文，不要引入训练数据中的外部知识。

            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """

            输出 JSON，包含以下字段：
            - mergedAnalysis: 分析文本（Markdown格式，包含：1.主要实体列表（列出所有重要实体，每行：- 实体名 | 类型 | 一句话定位）2.关键事实（列出所有重要事实，每条1句话，只提取原文明确记载的内容）3.潜在关联点）
            - metadata: 元数据对象，包含：
                * title: 页面标题（不超过30字，基于原文主题）
                * summary: 一句话摘要（不超过100字，不得超出原文内容范围）
                * category: 分类路径（格式：一级/二级）。【硬性约束】分类名称必须使用纯中文，禁止使用英文或中英混合；必须严格从上方 Schema 骨架「## 2. 分类体系」中定义的分类中选取，不得自创分类名称。如果 Schema 未注入或分类体系为空，则使用中文通用分类（如"技术实践/架构设计"、"业务领域/企业治理"、"组织管理/部门职能"等）
                * tags: 标签列表（3-5个，必须使用中文，专有名词除外）
                * keywords: 关键词列表（5-10个，按重要性排序，必须是原文中出现的术语，使用中文）
                * entities: 关键实体列表（JSON数组，每个元素含name、type、aliases、significance字段。按重要性排序。type必须使用中文值：人物/组织/系统/概念/文档/事件，禁止使用英文person/organization/system/concept/document/event。aliases字符串数组——如果实体在原文中有多个称呼如简称、别名、英文名等，在aliases中列出所有变体。
                    【硬性约束·宁缺毋滥】每个实体必须通过"独立页面价值测试"：如果为该实体单独建一个Wiki页面，读者能否从中获得有价值的、不可替代的知识？如果答案是否定的（信息太薄、只是名字提及、或者信息完全可以融入摘要页），则不应列入。
                    significance分级：
                    - "core"：文档的核心主题或关键角色——缺了它就无法理解文档。通常只有1-3个
                    - "important"：在原文中有充分的实质性描述（至少3条独立事实或一段完整论述），值得单独建立知识页面。一份文档通常不超过5-8个important实体
                    - "contextual"：在原文中信息较薄（仅名字出现、1-2句提及、或作为背景/例证被引用），其信息更适合融入摘要页而非独立建页
                    分级原则：严格把控important门槛——信息不足以支撑独立页面的实体一律归为contextual。宁可漏掉一个边缘实体，也不要制造一个信息稀薄的空壳页面）
                * coverageAssessment: 完整性自检（JSON对象，包含：
                    coveredTopics: 本次提取的实体所覆盖的文档主题领域列表 string[]（简要描述每个主题）
                    uncoveredTopics: 文档中可能存在但未被任何实体覆盖的重要主题 string[]（如果确实有遗漏，列出遗漏的主题和简要说明。如果认为实体列表已完整覆盖所有重要主题，返回空数组 []）
                    confidence: 对本次提取完整性的信心程度，取值 "high"(已覆盖所有重要主题) / "medium"(可能有轻微遗漏) / "low"(存在明显遗漏)。
                    说明：这是你对自己分析结果的自检——确保提取的实体集合能全面反映和概括导入文档的内容。宁可多报uncoveredTopics，也不要遗漏）
            """;
        String prompt = PromptTemplate.formatGuidancePrefix(guidance) + basePrompt;
        if (pagesContext == null || pagesContext.isBlank()) {
            return prompt + "- metadata.affectedPages: []（当前知识库为空）\n";
        }
        return prompt + """
            - metadata.affectedPages: 受影响的现有页面列表（JSON数组）：
                * 与已有页面主题高度相关 → {"title":"<已有标题>","path":"<已有路径>","action":"更新"}
                * 对已有页面的扩展/补遗 → {..., "action":"补充"}
                * 独立全新主题 → {"title":"<新标题>","path":"<新路径>","action":"新建"}

            基于上方分类骨架（含每类页面数），按分类名称推断受影响页面。相同分类下 → action=更新；相近分类 → action=补充；全新 → action=新建

            """ + pagesContext;
    }

    public String mergeAnalyses() {
        return """
            将以下多个片段的精简分析结果合并为一份整体分析。去重实体、整合关联点，但保留所有片段中的具体规则和条款，不要合并或概括。

            输出格式：
            1. 主要实体列表（去重后保留所有实体，格式：- 实体名 | 类型 | 一句话定位）
            2. 关键事实（合并去重，保留所有重要事实，每条1句话，不要新增）
            3. 潜在关联点（合并去重，保留所有关联点）
            4. 具体规则与条款（保留所有片段中的逐条规则/制度/流程，不要合并概括，保持原始条目粒度）
            """;
    }

    public String extractMetadataFromStructure(String chapterSummary, String pagesContext) {
        String basePrompt = """
            根据以下文档章节结构和各章节摘要，快速提取元数据。这是一个结构化制度/规范文档，将按章节编译，不需要合并分析。
            严格基于已有信息，不要引入外部知识。""" + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """

            【文档章节结构与摘要】
            """ + chapterSummary + """

            输出 JSON，包含以下字段：
            - title: 页面标题（简洁准确，不超过30字，基于文档整体主题）
            - summary: 一句话摘要（不超过100字）
            - category: 分类路径（格式：一级/二级。【硬性约束】分类名称必须使用纯中文，禁止使用英文或中英混合；必须严格从上方 Schema 骨架「## 2. 分类体系」中定义的分类中选取。如果 Schema 未注入分类体系，使用中文通用分类）
            - tags: 标签列表（3-5个，中文）
            - keywords: 关键词列表（5-10个，按重要性排序，中文）
            - entities: 关键实体列表（JSON数组，每个含 name/type/aliases/significance 字段。type 使用中文：人物/组织/系统/概念/文档/事件。
                significance分级："core"(1-3个)/"important"(不超过5-8个)/"contextual"。制度文档中主要实体通常是制度名称、组织架构、关键流程等)
            """;
        if (pagesContext == null || pagesContext.isBlank()) {
            return basePrompt + "- affectedPages: []（当前知识库为空）\n";
        }
        return basePrompt + """
            - affectedPages: 受影响的现有页面列表（JSON数组）：
                * 与已有页面主题高度相关 → {"title":"<已有标题>","path":"<已有路径>","action":"更新"}
                * 对已有页面的扩展/补遗 → {..., "action":"补充"}
                * 独立全新主题 → {"title":"<新标题>","path":"<新路径>","action":"新建"}

            基于上方分类骨架（含每类页面数），按分类名称推断受影响页面。

            """ + pagesContext;
    }

    public String mergeAndExtract(String pagesContext) {
        String basePrompt = """
            将以下多个片段的精简分析结果合并，并同时提取元数据。一次性完成两个任务，避免重复调用。

            合并时只基于已有分析结果，不要自行补充新内容或引入外部知识。

            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """

            输出 JSON，包含以下字段：
            - mergedAnalysis: 合并后的整体分析文本（Markdown格式，包含：1.主要实体列表（去重，列出所有实体，格式：- 实体名 | 类型 | 一句话定位）2.关键事实（去重合并，保留所有重要事实，每条1句话，不要新增）3.潜在关联点（去重合并，列出所有关联点））
            - metadata: 元数据对象，包含：
                * title: 页面标题（不超过30字，基于原文主题）
                * summary: 一句话摘要（不超过100字，不得超出原文内容范围）
                * category: 分类路径（格式：一级/二级）。【硬性约束】分类名称必须使用纯中文，禁止使用英文或中英混合；必须严格从上方 Schema 骨架「## 2. 分类体系」中定义的分类中选取，不得自创分类名称。如果 Schema 未注入或分类体系为空，则使用中文通用分类（如"技术实践/架构设计"、"业务领域/企业治理"、"组织管理/部门职能"等）
                * tags: 标签列表（3-5个，必须使用中文，专有名词除外）
                * keywords: 关键词列表（5-10个，按重要性排序，必须是原文中出现的术语，使用中文）
                * entities: 关键实体列表（JSON数组，每个元素含name、type、aliases、significance字段。去重后按重要性排序。type必须使用中文值：人物/组织/系统/概念/文档/事件，禁止使用英文person/organization/system/concept/document/event。aliases字符串数组——如果实体在原文中有多个称呼。
                    【硬性约束·宁缺毋滥】每个实体必须通过"独立页面价值测试"：该实体在原文中是否有充分的实质性描述，足以支撑一个对读者有价值的独立Wiki页面？信息太薄（仅名字出现、1-2句提及）的实体一律归为contextual。
                    significance分级：
                    - "core"：文档的核心主题或关键角色——缺了它就无法理解文档。通常只有1-3个
                    - "important"：在原文中有充分的实质性描述（至少3条独立事实或一段完整论述），值得单独建立知识页面。一份文档通常不超过5-8个important实体
                    - "contextual"：信息较薄，更适合融入摘要页而非独立建页
                    分级原则：严格把控important门槛——信息不足以支撑独立页面的实体一律归为contextual）
                * coverageAssessment: 完整性自检（JSON对象，包含：
                    coveredTopics: 本次提取的实体所覆盖的文档主题领域列表 string[]
                    uncoveredTopics: 文档中可能存在但未被任何实体覆盖的重要主题 string[]（如果完整覆盖，返回空数组 []）
                    confidence: "high"/"medium"/"low"。确保提取的实体集合能全面反映和概括导入文档的内容）
            """;
        if (pagesContext == null || pagesContext.isBlank()) {
            return basePrompt + "- metadata.affectedPages: []（当前知识库为空）\n";
        }
        return basePrompt + """
            - metadata.affectedPages: 受影响的现有页面列表（JSON数组）：
                * 与已有页面主题高度相关 → {"title":"<已有标题>","path":"<已有路径>","action":"更新"}
                * 对已有页面的扩展/补遗 → {..., "action":"补充"}
                * 独立全新主题 → {"title":"<新标题>","path":"<新路径>","action":"新建"}

            基于上方分类骨架（含每类页面数），按分类名称推断受影响页面。相同分类下 → action=更新；相近分类 → action=补充；全新 → action=新建

            """ + pagesContext;
    }

    public String extractMetadata() {
        return extractMetadata(null);
    }

    public String extractMetadata(String pagesContext) {
        String basePrompt = """
            从以下分析结果中提取元数据。严格基于已有分析结果，不要引入外部知识。""" + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """

            包含以下字段：
            - title: 页面标题（简洁准确，不超过30字）
            - summary: 一句话摘要（不超过100字）
            - category: 分类路径（格式：一级/二级，例如"业务领域/企业治理"、"技术实践/架构设计"、"组织管理/部门职能"。【硬性约束】分类名称必须使用纯中文，禁止使用英文或中英混合；必须严格从上方 Schema 骨架「## 2. 分类体系」中定义的分类中选取，不得自创。如果 Schema 未注入分类体系，使用中文通用分类，不超过二级）
            - tags: 标签列表（3-5个，每个标签简洁有区分度，必须使用中文，专有名词除外）
            - keywords: 关键词列表（5-10个，按重要性排序，使用中文）
            - entities: 关键实体列表（JSON 数组，每个元素包含 name、type、aliases、significance 字段。type 必须使用中文值：人物/组织/系统/概念/文档/事件，禁止使用英文。按重要性排序。
                【硬性约束·宁缺毋滥】每个实体必须通过"独立页面价值测试"：原文中是否有足够的实质性描述来支撑一个有价值的独立Wiki页面？信息太薄（仅名字出现、1-2句提及）的实体不应列入。aliases为可选字符串数组。
                significance分级："core"(核心，通常1-3个)/"important"(重要，原文中有至少3条独立事实，一份文档通常不超过5-8个)/"contextual"(附带提及，信息更适合融入摘要页)。
                分级原则：严格把控important门槛——信息不足以支撑独立页面的实体一律归为contextual）
            """;
        if (pagesContext == null || pagesContext.isBlank()) {
            return basePrompt + "- affectedPages: 受影响的现有页面列表（当前知识库为空，返回 []）\n";
        }
        return basePrompt + """
            - affectedPages: 受影响的现有页面列表（JSON 数组）。规则：
                * 若新内容与某已有页面主题高度相关，输出 {"title":"<已有页面标题>","path":"<已有页面路径>","action":"更新"}
                * 若新内容是对某已有页面主题的扩展或补遗，输出 {..., "action":"补充"}
                * 若新内容为独立全新主题，输出 {"title":"<新页面标题>","path":"<建议的新页面路径>","action":"新建"}
                * 允许同时给出多条；若确无受影响页面且非新主题，返回 []

            基于上方分类骨架（含每类页面数），按分类名称推断受影响页面。相同分类下 → action=更新；相近分类 → action=补充；全新 → action=新建

            """ + pagesContext;
    }

    public String writeSummary(String metadataJson) {
        return writeSummary(metadataJson, null);
    }

    public String writeSummary(String metadataJson, String schemaPageTemplate) {
        String pageStructureGuidance;
        if (schemaPageTemplate != null && !schemaPageTemplate.isBlank()) {
            pageStructureGuidance = "页面结构请严格遵循上方 Schema 骨架中「## 3. 页面模板」的定义来组织章节，不要自行发明结构。如果 Schema 页面模板中定义了必需章节，确保每个必需章节都有实质性内容。";
        } else {
            pageStructureGuidance = "页面结构（来源编译页，遵循忠实编译约束）：\n"
                + "1. 文档结构地图（列出源文档的章节结构，每章一句话说明其内容范围，帮助读者定位原文，不写 H1 标题）\n"
                + "2. 关键陈述（逐条列出源文档明确陈述的关键事实、数据、规则，每条一句话或一段，保持原始表述粒度，紧随「（来源：<来源名称>）」标注）\n"
                + "3. 核心内容章节（按主题分节，每节聚焦一个方面，用 ## 或 ### 标题）\n"
                + "4. 原文引用（对规则条款、定义表述、量化指标，使用 blockquote（>）引用原文，不得改写）\n"
                + "5. 来源未提供的信息（列出读者可能期待但源文档未明确陈述的主题清单，帮助消费端识别知识边界）";
        }

        return PromptTemplate.SOURCE_FIDELITY_PRINCIPLE + "\n\n" + PromptTemplate.knowledgeNormalizationPrinciple() + "\n\n" + PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT + "\n\n---\n\n" + """
            根据下方用户消息中的原始文件内容和结构化导航索引，生成一个完整深入的 Wiki 页面（Markdown 格式）。
            用户消息包含【原始文件内容】（事实来源）和【结构化导航索引】（写作方向指引）两个部分。

            """ + pageStructureGuidance + """

            要求：
            - 所有事实性内容必须严格来自原始文件内容，不要添加外部知识
            - 结构化导航索引帮助你确定覆盖范围，但其中的概述性描述不可替代原始文件中的具体表述
            - 每个章节聚焦原始文件提供的事实，宁可简短准确，不可冗长编造
            - 在相关概念处使用 [[页面标题]] 格式添加双向链接
            - 引用原始文件中的具体事实和数据时，在正文中用纯文本注明来源（如「（来源：xxx.pdf）」），严禁将来源文件名包装为 Markdown 链接
            - 如果原始文件内容中包含「源文档图表数据」章节，其中包含从图表中提取的结构化数据表格和关键指标，请优先采纳这些定量数据到页面中（准确性前提下）

            """ + PromptTemplate.MARKDOWN_OUTPUT_CONSTRAINT + """

            元数据：""" + metadataJson;
    }

    public String updateRelated(String analysisResult, String metadataJson, String pagesContext) {
        String header = """
            根据以下新内容的分析结果和现有知识库页面列表，判断哪些现有页面需要更新以反映新知识。
            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            返回 JSON 数组，每个元素包含：
            - pagePath: 需要更新的页面路径
            - reason: 更新原因（简明说明为什么需要更新）
            - suggestedUpdate: 建议的更新内容摘要（不超过100字）

            新内容分析：
            """;
        String sep = System.lineSeparator() + System.lineSeparator();
        return header + analysisResult + sep + "新内容元数据：" + metadataJson + sep + pagesContext;
    }

    public String updateLinks(String newPagePath, String metadataJson, String relatedAnalysis) {
        return updateLinks(newPagePath, metadataJson, relatedAnalysis, null);
    }

    public String updateLinks(String newPagePath, String metadataJson, String relatedAnalysis, String pageInventory) {
        String inventorySection = (pageInventory != null && !pageInventory.isBlank())
            ? "\n\n当前知识库中的所有页面（fromPage和toPage的值必须是以下列表中的filePath）：\n" + pageInventory
            : "";
        return """
            根据以下新页面和其相关页面分析，建议应该建立的链接关系。
            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            返回 JSON 数组，每个元素包含：
            - fromPage: 来源页面路径（必须是已有页面的 filePath）
            - toPage: 目标页面路径（必须是已有页面的 filePath）
            - linkType: 链接类型（related/reference/supplement/extend/contradiction）
            - linkContext: 简要说明链接关系的原因
            
            新页面路径：""" + newPagePath + "\n新页面元数据：" + metadataJson
            + inventorySection
            + "\n相关页面分析：" + relatedAnalysis;
    }

    public String mergeIntoExistingPage(String existingContent, String sourceContent, String newAnalysis, String newMetadataJson, String action) {
        String sourceSection = "";
        if (sourceContent != null && !sourceContent.isBlank()) {
            sourceSection = "\n\n【原始文件内容】（事实来源 —— 最高优先级，融合新内容时所有新增的事实性内容必须源自此处）\n" + sourceContent;
        }
        return PromptTemplate.SOURCE_FIDELITY_PRINCIPLE + "\n\n" + PromptTemplate.knowledgeNormalizationPrinciple() + "\n\n" + PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT + "\n\n" + """

            你是知识库的增量编译器。请将下面的新内容融合到现有 Wiki 页面中，生成更新后的完整页面（Markdown 格式）。

            用户消息中包含【原始文件内容】（事实来源）和【结构化导航索引】（写作方向指引）两个部分。

            原则：
            1. 保留现有页面的有效信息，不要丢弃。现有页面的 Markdown 格式风格（标题层级、表格结构、链接格式等）必须保持一致，新融合的内容要融入现有风格。
            2. 仅将结构化导航索引中确实有新增信息的差异点融合到适当章节，融合时必须以原始文件内容中的具体表述为事实来源，不要为了"充实"而展开原始文件没有的内容。
            3. 对于冲突信息，忠实并列呈现双方主张与各自来源，用「（另有观点认为...）」格式标注新主张，严禁裁决、严禁择一保留。
            4. 新内容中未涉及的章节保持原样不动。
            5. 新增的每条事实性陈述必须紧随「（来源：<来源名称>）」标注；不得改写、压缩或删除现有页面中已标注来源的陈述。

            """ + PromptTemplate.MARKDOWN_OUTPUT_CONSTRAINT + """

            操作类型：""" + action + "\n\n现有页面内容：\n" + existingContent +
            sourceSection +
            "\n\n【结构化导航索引】（写作方向指引 —— 帮助你确定需要融合的差异点，但不是事实来源）\n" + newAnalysis +
            "\n\n新内容元数据：\n" + newMetadataJson;
    }

    public String writeEntityPage(String entityName, String entityType, String analysisContext, String metadataJson) {
        return writeEntityPage(entityName, entityType, analysisContext, metadataJson, null);
    }

    public String writeEntityPage(String entityName, String entityType, String analysisContext, String metadataJson, String schemaPageTemplate) {
        return writeEntityPage(entityName, entityType, analysisContext, metadataJson, schemaPageTemplate, false);
    }

    public String writeEntityPage(String entityName, String entityType, String analysisContext, String metadataJson, String schemaPageTemplate, boolean structuredSource) {
        String pageStructureGuidance;
        if (schemaPageTemplate != null && !schemaPageTemplate.isBlank()) {
            pageStructureGuidance = "页面结构请严格遵循上方 Schema 骨架中「## 3. 页面模板」的定义来组织章节。如果 Schema 页面模板中定义了必需章节，确保每个必需章节都有实质性内容。";
        } else {
            pageStructureGuidance = "页面结构（实体汇集台，遵循忠实编译约束）：\n"
                + "1. 概述（逐条列出该实体在源文档中被明确陈述的核心事实，每条紧随「（来源：<来源名称>）」标注，不写 H1 标题）\n"
                + "2. 详细信息（从源文档中逐条提取与该实体相关的陈述：属性、职责、规则、关联等，保持原始表述粒度，每条紧随「（来源：<来源名称>）」标注）\n"
                + "3. 关联关系（以叙述性段落描述该实体与其他实体/概念的关联，在叙述中自然嵌入 [[页面标题]] 链接。禁止生成纯链接平铺列表）";
        }

        return PromptTemplate.SOURCE_FIDELITY_PRINCIPLE + "\n\n" + PromptTemplate.knowledgeNormalizationPrinciple() + "\n\n" + PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT + "\n\n---\n\n"
            + "根据下方用户消息中的原始文件内容和结构化导航索引，为实体「" + entityName + "」生成一个独立的 Wiki 页面（Markdown 格式）。\n"
            + "用户消息包含【原始文件内容】（事实来源）和【结构化导航索引】（写作方向指引）两个部分。\n\n"
            + "实体类型：" + entityType + "\n\n"
            + ENTITY_CENTRIC_PRINCIPLE + "\n\n"
            + pageStructureGuidance + "\n\n"
            + "要求：\n"
            + "- 只提取与该实体直接相关的内容，不要泛泛而谈\n"
            + "- 所有事实性内容必须严格来自原始文件内容，结构化导航索引帮助你确定覆盖范围，但不可将导航索引中的简略表述当作完整事实来展开\n"
            + "- 如果原始文件中找不到导航索引提到的某个事实的具体表述，则不得将该事实写入页面\n"
            + "- 严禁基于实体名称进行脑补或推测性扩展——如果原始文件中关于该实体的实质性内容少于3条独立事实，页面应简短如实记录，并用「（原文信息有限，以上为原始资料中关于该实体的全部信息）」结尾\n"
            + "- 使用 [[页面标题]] 格式添加双向链接\n"
            + "- 引用原始文件中的具体事实和数据时，在正文中用纯文本注明来源（如「（来源：xxx.pdf）」），严禁将来源文件名包装为 Markdown 链接\n"
            + "- 如果原始文件内容中包含「源文档图表数据」章节，其中的结构化数据表格和关键指标与该实体相关时，请优先采纳（准确性前提下）\n\n"
            + PromptTemplate.MARKDOWN_OUTPUT_CONSTRAINT + "\n\n"
            + "来源元数据：" + metadataJson
            + (structuredSource ? STRUCTURED_SOURCE_CONSTRAINT : "");
    }

    public String writingPlan(String metadataJson, String pagesContext) {
        return writingPlan(metadataJson, pagesContext, null, null, null);
    }

    public String writingPlan(String metadataJson, String pagesContext, String chapterList) {
        return writingPlan(metadataJson, pagesContext, chapterList, null, null);
    }

    public String writingPlan(String metadataJson, String pagesContext, String chapterList, String entityRelationshipSummary, String completenessHint) {
        String chapterSection = "";
        if (chapterList != null && !chapterList.isBlank()) {
            chapterSection = "\n\n【文档章节结构】\n" + chapterList + "\n\n"
                + "- chapterPlans: 章节页写作方向（JSON对象，key为章节标题，value包含 "
                + "positioning:该章节在全文中的角色、"
                + "keyTopics:该章节的核心主题列表 string[]、"
                + "crossReferences:与其他章节/实体的交叉引用 string[]）\n";
        }
        return "你是一个知识库的主编。基于下方用户消息中的结构化导航索引（已覆盖文档所有片段的完整分析结果）和文档章节结构，制定一份全局写作计划（WritingPlan），"
            + "确保即将生成的摘要页、实体页、章节页和受影响的页面之间的一致性。"
            + "结构化导航索引已涵盖文档所有部分。注意：只为真正有独立知识价值的实体制定写作计划——如果某个实体在原文中信息太薄（仅名字出现或1-2句提及），其信息应融入摘要页而非独立建页。"
            + "coreThesis 是所有页面的锚点——摘要页必须完整覆盖 coreThesis 的所有关键主题，每个实体页必须明确其在 coreThesis 中的角色定位。\n\n"
            + PromptTemplate.JSON_OUTPUT_CONSTRAINT + "\n\n"
            + "包含以下字段：\n"
            + "- coreThesis: 核心论点（一句话概括本次摄入的中心主题，所有页面都应围绕此论点展开）\n"
            + "- summaryOutline: 摘要页的大纲（JSON对象，包含 mainSections: string[] 和 keyEntitiesToCover: string[]）\n"
            + "- entityPlans: 实体页写作方向（JSON对象，key为实体名，value包含 positioning:定位、focus:重点内容方向、"
            + "relationToSummary:与摘要页的关系、crossReferences:与哪些其他实体的交叉引用 string[]）\n"
            + "- affectedPagePlans: 受影响页面的更新方向（JSON对象，key为页面路径，value包含 updateDirection:更新方向、"
            + "newCrossRefs:新增的交叉引用 string[]）\n"
            + chapterSection
            + "- richElements: 富元素规划（JSON对象，key为页面路径或实体名，value为 richElementType[] 数组。"
            + "每个 richElementType 包含 type（mermaid/echarts/code/formula/table/admonition）和 description（简要说明该元素的用途）。"
            + "基于源文档内容的充分程度，为每个页面规划**可行的**富元素类型：\n"
            + "  * 有代码示例/配置片段/SQL语句 → 应规划 code（风险低，直接抄录源文档）\n"
            + "  * 有分类/对比/参数说明 → 应规划 table（风险低）\n"
            + "  * 有关键警告/重要提示 → 应规划 admonition（风险低）\n"
            + "  * 有算法复杂度/数学公式 → 应规划 formula（风险低）\n"
            + "  * 有明确完整的流程/架构/时序描述 → 可规划 mermaid（风险中，需源文档步骤清晰完整）\n"
            + "  * 有精确数值数据 → 可规划 echarts（风险高，需源文档数字精确且可直接引用）\n"
            + "  宁可不规划某类富元素，也不要让后续写作阶段被迫构造不准确的图表）\n"
            + "- consistencyRules: 全局一致性约束（JSON对象，包含以下子字段：\n"
            + "  * forbiddenPhrases: 禁用短语列表（string[]，如 [\"大概\", \"差不多\"]，所有页面禁止使用这些模糊表述）\n"
            + "  * requiredStructure: 页面必需章节（JSON对象，key为页面类型模式如 entity_* 或 summary，"
            + "value为必需章节标题列表 string[]。例如 {\"entity_*\": [\"定义\", \"职责\"]}）\n"
            + "  * rules: 自然语言规则列表（string[]，补充上述结构化规则的额外约定））\n"
            + "- conflictAnnotations: 矛盾标注列表（JSON数组，每个元素包含：pagePath:涉及页面路径、conflictType:矛盾类型"
            + "（value_conflict/fact_conflict/definition_conflict/temporal_conflict）、"
            + "existingClaim:现有页面中的主张、newClaim:本次新内容的相反主张、"
            + "resolution:处置方式（固定值 annotate_both）、"
            + "sourceRef:新主张的来源引用）。若本次摄入内容与现有页面无矛盾，返回空数组 []。\n\n"
            + "冲突处置原则：本知识库为忠实编译模式，所有冲突一律 annotate_both——并列呈现双方主张与各自来源，"
            + "禁止裁决、禁止来源优先级排序、禁止时效优先。\n\n"
            + "元数据：" + metadataJson + "\n\n"
            + (entityRelationshipSummary != null && !entityRelationshipSummary.isBlank()
                ? entityRelationshipSummary + "\n\n"
                : "")
            + (completenessHint != null && !completenessHint.isBlank()
                ? completenessHint + "\n\n"
                : "")
            + (pagesContext != null && !pagesContext.isBlank() ? pagesContext + "\n\n" : "");
    }

    public String writeSummaryWithPlan(String metadataJson, String writingPlanJson) {
        return writeSummaryWithPlan(metadataJson, writingPlanJson, null);
    }

    public String writeSummaryWithPlan(String metadataJson, String writingPlanJson, String schemaPageTemplate) {
        return writeSummaryWithPlan(metadataJson, writingPlanJson, schemaPageTemplate, false);
    }

    public String writeSummaryWithPlan(String metadataJson, String writingPlanJson, String schemaPageTemplate, boolean structuredSource) {
        String structureHint;
        if (schemaPageTemplate != null && !schemaPageTemplate.isBlank()) {
            structureHint = "页面结构请严格遵循上方 Schema 骨架中「## 3. 页面模板」的定义来组织章节，不要自行发明结构。如果 Schema 页面模板中定义了必需章节，确保每个必需章节都有实质性内容。";
        } else {
            structureHint = "页面结构（来源编译页，遵循忠实编译约束，可根据内容灵活调整）：\n"
                + "1. 文档结构地图（列出源文档的章节结构，帮助读者定位原文，不写 H1 标题、不写元数据表格）\n"
                + "2. 关键陈述（逐条列出源文档明确陈述的关键事实、数据、规则，保持原始表述粒度）\n"
                + "3. 核心内容章节（按主题分节，每节聚焦一个方面，用 ## 或 ### 标题）\n"
                + "4. 原文引用（对规则条款、定义表述、量化指标，使用 blockquote（>）引用原文）\n"
                + "5. 来源未提供的信息（列出读者可能期待但源文档未明确陈述的主题清单）";
        }
        return PromptTemplate.SOURCE_FIDELITY_PRINCIPLE + "\n\n" + PromptTemplate.knowledgeNormalizationPrinciple() + "\n\n" + PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT + "\n\n" + """

            根据下方用户消息中的原始文件内容、结构化导航索引、元数据和全局写作计划，生成摘要页。
            用户消息包含【原始文件内容】（事实来源）和【结构化导航索引】（写作方向指引）两个部分。
            严格遵循写作计划中的核心论点（coreThesis）和大纲方向（summaryOutline），确保摘要页覆盖 coreThesis 中定义的所有关键主题。

            """ + structureHint + """

            要求：
            - 所有事实性内容必须严格来自原始文件内容，不要添加外部知识
            - 结构化导航索引帮助你确定覆盖范围，但其中的概述性描述不可替代原始文件中的具体表述
            - 每个章节聚焦原始文件提供的事实，宁可简短准确，不可冗长编造
            - 在相关概念处使用 [[页面标题]] 格式添加双向链接
            - 引用原始文件中的具体事实和数据时，在正文中用纯文本注明来源（如「（来源：xxx.pdf）」），严禁将来源文件名包装为 Markdown 链接
            - 参考写作计划中 richElements 的规划，在源文档信息充分时使用对应的富元素（准确性为前提）
            - 如果原始文件内容中包含「源文档图表数据」章节，其中包含从图表中提取的结构化数据表格和关键指标，请优先采纳这些定量数据到页面中（准确性前提下）

            """ + PromptTemplate.MARKDOWN_OUTPUT_CONSTRAINT + """

            【全局写作计划】
            """ + writingPlanJson + """

            元数据：""" + metadataJson
            + (structuredSource ? STRUCTURED_SOURCE_CONSTRAINT : "");
    }

    public String writeEntityPageWithPlan(String entityName, String entityType, String writingPlanJson, String metadataJson) {
        return writeEntityPageWithPlan(entityName, entityType, writingPlanJson, metadataJson, null);
    }

    public String writeEntityPageWithPlan(String entityName, String entityType, String writingPlanJson, String metadataJson, String schemaPageTemplate) {
        return writeEntityPageWithPlan(entityName, entityType, writingPlanJson, metadataJson, schemaPageTemplate, false);
    }

    public String writeEntityPageWithPlan(String entityName, String entityType, String writingPlanJson, String metadataJson, String schemaPageTemplate, boolean structuredSource) {
        String pageStructureGuidance;
        if (schemaPageTemplate != null && !schemaPageTemplate.isBlank()) {
            pageStructureGuidance = "页面结构请严格遵循上方 Schema 骨架中「## 3. 页面模板」的定义来组织章节。如果 Schema 页面模板中定义了必需章节，确保每个必需章节都有实质性内容。";
        } else {
            pageStructureGuidance = "页面结构（实体汇集台，遵循忠实编译约束）：\n"
                + "1. 概述（逐条列出该实体在源文档中被明确陈述的核心事实，每条紧随「（来源：<来源名称>）」标注，不写 H1 标题、不写元数据表格）\n"
                + "2. 详细信息（按主题分节，从源文档中逐条提取与该实体相关的陈述：属性、职责、规则、关联等，保持原始表述粒度，每条紧随「（来源：<来源名称>）」标注）\n"
                + "3. 关联关系（以叙述性段落描述与其他实体/概念的关系，在叙述中自然嵌入 [[页面标题]] 链接。禁止生成纯链接平铺列表）";
        }
        return PromptTemplate.SOURCE_FIDELITY_PRINCIPLE + "\n\n" + PromptTemplate.knowledgeNormalizationPrinciple() + "\n\n" + PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT + "\n\n"
            + "根据下方用户消息中的原始文件内容和结构化导航索引，为实体「" + entityName + "」（类型：" + entityType + "）生成 Wiki 页面。"
            + "用户消息包含【原始文件内容】（事实来源）和【结构化导航索引】（写作方向指引）两个部分。"
            + "严格遵循写作计划中该实体的定位（positioning）、重点方向（focus）和交叉引用约定（crossReferences）。\n\n"
            + ENTITY_CENTRIC_PRINCIPLE + "\n\n"
            + pageStructureGuidance + "\n\n"
            + "要求：\n"
            + "- 所有事实性内容必须严格来自原始文件内容，不要添加外部知识\n"
            + "- 结构化导航索引帮助你确定覆盖范围，但不可将导航索引中的简略表述当作完整事实来展开\n"
            + "- 如果原始文件中找不到导航索引提到的某个事实的具体表述，则不得将该事实写入页面\n"
            + "- 严禁基于实体名称进行脑补或推测性扩展——如果原始文件中关于该实体的实质性内容少于3条独立事实，页面应简短如实记录\n"
            + "- 使用 [[页面标题]] 格式添加双向链接，链接目标应与 writingPlan 的 crossReferences 一致\n"
            + "- 引用原始文件中的具体事实和数据时，在正文中用纯文本注明来源（如「（来源：xxx.pdf）」），严禁将来源文件名包装为 Markdown 链接\n"
            + "- 参考写作计划中 richElements 的规划，在源文档信息充分时使用对应的富元素（准确性为前提）\n"
            + "- 如果原始文件内容中包含「源文档图表数据」章节，其中的结构化数据表格和关键指标与该实体相关时，请优先采纳（准确性前提下）\n\n"
            + PromptTemplate.MARKDOWN_OUTPUT_CONSTRAINT + "\n\n"
            + "【全局写作计划】\n" + writingPlanJson + "\n\n"
            + "来源元数据：" + metadataJson
            + (structuredSource ? STRUCTURED_SOURCE_CONSTRAINT : "");
    }

    public String mergeEntityClaims(String existingEntries, String newSourceMaterial) {
        return PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT + "\n\n" + """
            你是实体页的增量编译器。下面给出【现有条目清单】（编号 [n]）与【新来源材料】，请判断新来源中有哪些事实应进入该实体页，并以候选条目的形式输出。

            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            输出严格 JSON 数组，每个元素包含：
            - section: 目标章节名（引用现有条目时给出其所属章节；relation 为 new 时给出应归入或新建的章节）
            - claim: 陈述内容（忠实于来源原文，保持原始表述粒度，不推断、不合并、不裁决）
            - source: 来源名称（用于「（来源：<来源名称>）」标注，必填）
            - quote: 来源原文引用（可选，规则条款/定义/量化指标建议提供）
            - relation: 关系类型，取值必须是以下之一：
              * new：新来源陈述了现有条目未覆盖的事实
              * duplicate_of:<n>：与第 n 条现有条目实质相同（将为其追加来源标注）
              * conflict_with:<n>：与第 n 条现有条目冲突（两条将并列保留，不做裁决）

            要求：
            - 只提取新来源材料中明确陈述的、与该实体直接相关的事实
            - 来源未陈述的内容不得输出；不确定的内容不输出
            - relation 为 duplicate_of/conflict_with 时，<n> 必须是【现有条目清单】中真实存在的编号
            - 严禁改写既有陈述、严禁选择"更可信"版本；冲突一律用 conflict_with 标注
            - 若无任何新事实可补充，输出空数组 []

            【现有条目清单】
            """ + existingEntries + """

            【新来源材料】
            """ + newSourceMaterial;
    }

    public String batchMergeEntityClaims() {
        return PromptTemplate.FAITHFUL_COMPILATION_CONSTRAINT + "\n\n" + """
            你是实体页的增量编译器。用户消息给出一个 JSON 数组，每个元素描述一个待更新的实体页：
            - pageIndex: 页面序号（从 0 开始）
            - title: 页面标题（实体名）
            - existingEntries: 【现有条目清单】（编号 [n]）
            - material: 【新来源材料】

            请逐页判断新来源材料中有哪些事实应进入该实体页，并为每一页输出候选条目。

            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            输出严格 JSON 数组，必须覆盖输入中的每个 pageIndex，每个元素包含：
            - pageIndex: 与输入对应的页面序号
            - candidates: 该页的候选条目数组；每条包含：
              * section: 目标章节名（引用现有条目时给出其所属章节；relation 为 new 时给出应归入或新建的章节）
              * claim: 陈述内容（忠实于来源原文，保持原始表述粒度，不推断、不合并、不裁决）
              * source: 来源名称（用于「（来源：<来源名称>）」标注，必填）
              * quote: 来源原文引用（可选，规则条款/定义/量化指标建议提供）
              * relation: 关系类型，取值必须是以下之一：
                - new：新来源陈述了现有条目未覆盖的事实
                - duplicate_of:<n>：与该页第 n 条现有条目实质相同（将为其追加来源标注）
                - conflict_with:<n>：与该页第 n 条现有条目冲突（两条将并列保留，不做裁决）
            - 若某页无任何新事实可补充，该页 candidates 输出空数组 []

            要求：
            - 只提取新来源材料中明确陈述的、与该页实体直接相关的事实；与页面实体无关的事实不得写入该页
            - 来源未陈述的内容不得输出；不确定的内容不输出
            - relation 为 duplicate_of/conflict_with 时，<n> 必须是该页【现有条目清单】中真实存在的编号
            - 严禁改写既有陈述、严禁选择"更可信"版本；冲突一律用 conflict_with 标注
            """;
    }

    public String referenceSummary() {
        return "你是一个知识编译助手。根据提供的章节原文，生成结构化参考摘要。\n"
            + "输出格式（严格遵循）：\n"
            + "## 核心要点\n"
            + "- 逐条列出本章的关键规则、定义或事实（每条一行，保留具体数值、条件、责任主体）\n"
            + "## 关键术语\n"
            + "- 列出本章引入或定义的重要术语及其简要释义\n"
            + "## 与其他章节的关联\n"
            + "- 列出本章内容与其他章节的依赖、引用或例外关系（如有）\n"
            + "要求：\n"
            + "- 严格基于原文，不推断不构造\n"
            + "- 保留条款编号、阈值、条件分支等精确信息\n"
            + "- 保留原文结构：摘要按原文条款顺序组织，不重新编排、不合并独立条款\n"
            + "- 引用原文：规则条款、定义、量化指标使用 blockquote（>）引用原文表述，不得改写\n"
            + "- 最小改写：只做归纳性连接，不替换原文明示的表述与责任主体\n"
            + "- 直接输出，不加前缀标记";
    }

    public String batchReferenceSummaries() {
        return """
            你是一个知识编译助手。以下是文档各章节的标题和开头内容。
            请为每个章节生成简洁的结构化摘要。
            
            """ + PromptTemplate.JSON_OUTPUT_CONSTRAINT + """
            返回 JSON 数组，每个元素包含：
            - chapterIndex: 章节序号（从0开始）
            - corePoints: 核心要点数组（每条一行，保留具体数值、条件、责任主体，最多8条）
            - keyTerms: 关键术语数组（本章引入或定义的重要术语）
            - crossRefs: 与其他章节的关联数组（依赖、引用或例外关系）
            
            要求：
            - 严格基于提供的原文片段，不推断不构造
            - 保留条款编号、阈值、条件分支等精确信息
            - 保留原文结构：corePoints 按原文条款顺序排列，不重新编排、不合并独立条款
            - 引用原文：定义与规则条款的 corePoints 应引用原文关键句，不做归纳之外的改写
            - 最小改写：不替换原文明示的表述与责任主体
            - 每个章节的 corePoints 最多8条，keyTerms 最多10个
            """;
    }

    public static IngestPrompts get() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        static final IngestPrompts INSTANCE = new IngestPrompts();
    }
}
