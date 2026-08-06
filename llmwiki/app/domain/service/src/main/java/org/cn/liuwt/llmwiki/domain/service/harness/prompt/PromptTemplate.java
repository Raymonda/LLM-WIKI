package org.cn.liuwt.llmwiki.domain.service.harness.prompt;

import java.time.LocalDate;
import java.util.List;

public class PromptTemplate {

    public static final String JSON_OUTPUT_CONSTRAINT = "请只返回纯 JSON，不要包含任何其他文字、解释或代码围栏（```）。";
    public static final String MARKDOWN_OUTPUT_CONSTRAINT = """
            ## Markdown 输出规范
            直接输出纯 Markdown 页面内容，不要用代码围栏包裹整个输出。

            ### 严禁输出任何对话式前言或结语（违反此规则等同于编译失败）
            你的输出必须是**纯 Markdown 页面内容**，严禁在 Markdown 内容之前或之后添加任何形式的对话式文字，包括但不限于：
            - 前言类："好的，..."/"好的。"/"收到，..."/"明白了，..."/"了解。"
            - 自我描述类："作为知识编译器，我将..."/"我将严格依据..."/"我将忠实于..."
            - 引导类："以下是生成的页面："/"以下是更新后的完整页面："/"以下是...的 Markdown 页面"
            - 过程描述类："经过分析，..."/"根据上述要求，..."
            - 结语类："以上就是更新后的页面。"/"页面已更新完成。"/"希望这对您有帮助。"

            你的输出第一个字符必须是 Markdown 内容（## 标题、| 表格起始、或正文文字），不可能是任何对话性文字。系统会自动清除对话式前言，但主动违反此规则会浪费 Token 并降低输出质量。

            ### 优先级原则
            **准确性 > 格式规范 > 视觉美观**。以下所有格式要求都服从于信息来源真实性约束：
            - 当格式要求与源文档忠实性冲突时，**永远以忠实于源文档为准**
            - 宁可格式朴素但 100% 准确，不要为了格式美观而编造或扭曲内容

            ### 标题层级
            - 用 ## 作为最高标题（h2），依次使用 ### / #### / #####，禁止使用 #（h1 由系统渲染页面标题）
            - 每个标题下必须有实质内容，禁止连续标题

            ### 表格（对比/分类/参数/属性说明时**优先**使用）
            - 标准 Markdown 表格，必须有表头和 |---| 分隔线
            - 元数据信息（日期/版本/标签/状态等键值对）一律用表格呈现，禁止逐行文本罗列

            ### 富元素（条件性使用，准确性为前提）
            以下元素能显著提升页面专业性，但**只有在能从源文档中准确构造时才使用**：
            - **代码块**（风险低）：源文档中有代码/配置/SQL → 直接原样抄入围栏代码块，标注语言
            - **数学公式**（风险低）：源文档中有数学表达 → 行内 $...$ 或块级 $$...$$
            - **提示框**（风险低）：> [!NOTE] / > [!TIP] / > [!IMPORTANT] / > [!WARNING]，用于源文档中的关键警告或要点
            - **Mermaid 图**（风险中）：仅当源文档**明确描述了**流程步骤/系统交互/状态流转，且你能准确还原所有节点名称和关系时使用。如果源文档描述模糊或步骤不完整，**不要强行构造图表**，用文字描述即可。**图表类型限制**：只允许使用 `flowchart`（流程图）、`sequenceDiagram`（时序图）、`classDiagram`（类图）、`stateDiagram-v2`（状态图）、`erDiagram`（ER图）五种已验证稳定的语法。**禁止**使用 `xychart-beta`、`pie`、`mindmap`、`timeline`、`gantt` 等实验性或语法复杂的类型，这些类型语法多变，容易产生解析错误。时序数据优先用 Markdown 表格呈现
            - **ECharts 图表**（风险高）：仅当源文档中有**精确数值数据**且你能一字不差地引用这些数字时使用。严禁为凑出有效 ECharts JSON 而近似化或推算数据

            ### 排版要求
            - 段落长度自然为主，避免大段文字墙（>8句），但不要为了追求短段落而打断完整的逻辑链
            - 有序列表步骤用 1. 2. 3.，要点用无序 - 列表
            - 重要术语首次出现用 **加粗**，代码/命令用 `行内代码`
            - 提及其他知识页面时用 [[页面标题]] 交叉引用
            - 引用原文关键表述用 > 引用块

            ### 禁止生成聚合链接章节
            系统前端已有独立的"相关页面"面板（由数据库 wiki_page_link 表驱动），因此正文中**严禁**在末尾生成任何形式的聚合链接列表章节，包括但不限于：
            - 标题含"相关页面"/"参见"/"相关链接"/"相关组织"/"延伸阅读"等的章节（## 或 ### 级别）
            - 将多个 [[页面标题]] 链接平铺罗列的纯列表章节
            正文中的 [[页面标题]] 内联交叉引用不受此限制——在正文叙述到相关概念时，应在上下文语义中自然嵌入 [[链接]]，而不是把它们汇总到末尾列表。

            ### 反模式（避免，但不得违反源文档忠实性）
            - 避免空洞过渡句："接下来我们将介绍..."/"下面我们来看看..."
            - 避免用"综上所述"/"总而言之"来重复已述内容（但如果源文档原文包含这些表述，应忠实保留）
            - 避免无信息量的标题：如"其他"/"补充说明"（标题应描述具体内容）
            - 禁止连续空行超过 1 行
            - 禁止用纯文本罗列键值对（如 "标题：xxx\\n类型：yyy"），必须用表格
            """;
    public static final String SOURCE_FIDELITY_PRINCIPLE = """
        【信息来源真实性约束 —— 最高优先级规则，优先级高于所有其他写作要求】
        你是知识编译器（Knowledge Compiler），不是创意写作者。你的职责是将原始资料编译为结构化的 Wiki 页面，而不是创作新内容。
        违反以下任何一条都是不可接受的：

        下方用户消息包含【原始文件内容】和【结构化导航索引】两个部分，信息优先级如下：
        - 【原始文件内容】= 事实来源（最高优先级）——所有事实、数据、描述必须源自此处
        - 【结构化导航索引】= 写作方向指引（辅助）——帮助你确定覆盖范围和页面结构，但其中的概述性描述不能替代原始文件中的具体内容，不可将导航索引中的简略表述当作完整事实来展开

        1. 【严禁编造】所有输出的事实性内容必须严格源自【原始文件内容】。严禁引入你训练数据中的外部知识，严禁编造、推测、杜撰原始文件中不存在的事实、数据、日期、人名、数字、百分比。结构化导航索引中的信息仅作为写作方向参考，如果你在原始文件中找不到导航索引提到的某个事实的具体表述，则不得将该事实写入页面。
        2. 【严禁填充】如果原始文件对某个问题没有提供足够信息，直接说"原始资料未提供该信息"，不要为了"让内容更充实"而臆测填充。宁可页面简短准确，不要冗长华丽但虚假。
        3. 【忠实转述】对原始文件中的具体事实、数据、结论，保持原样转述，不要改写、扭曲、夸大或缩小。原始文件说"可能"就写"可能"，不要说"确定"。
        4. 【推断标注】任何基于原始文件做出的合理推断（例如从数据中推导趋势、从现象中归纳规律），必须明确用「推断」标签标注，并注明推演依据。推断内容与原文事实之间要有清晰区分。
        5. 【来源行引用】引用具体事实和数据时，在正文中用纯文本括号注明来源文件名。例如：「Q3 营收增长 15%（来源：财报分析.pdf）」。**严禁**将来源文件名包装为 Markdown 链接（如 [文件名](...) 格式），来源引用必须是纯文本，不是可点击链接。禁止在页面末尾写「参考来源」或「来源列表」章节——来源追踪由系统自动维护，无需你列出。
        6. 【拒绝猜测】如果某个事实不清楚、数据不完整、逻辑链有缺口，不要用猜测填补。如实记录信息的不完整性和来源的局限性。

        记住：一个简短但100%准确的页面，比一个长篇但20%编造的页面有价值得多。""";

    public static final String LANGUAGE_CONSTRAINT = """
        【语言规范 —— 中文优先】
        本知识库以中文为工作语言。所有输出内容（包括正文、元数据表格的键名和键值、标签、关键词、分类名称）必须使用中文，仅以下情况允许使用英文：
        1. 专有名词（如产品名"AI"、技术术语"API"、"SDK"、人名拼音等）
        2. 原文中本身为英文且无通用中文译名的术语
        3. 日期格式（如 2026-05-22）、版本号（如 v2.3）、代码标识符
        元数据表格的键名必须统一使用中文（如「标题」「类型」「标签」「创建日期」「更新日期」），禁止使用英文键名（如 title、type、tags、created）。
        实体类型必须使用中文（如「人物」「组织」「系统」「概念」「文档」「事件」），禁止使用英文（如 person、organization、system、concept、document、event）。
        """;

    public static String knowledgeNormalizationPrinciple() {
        LocalDate today = LocalDate.now();
        String yesterday = today.minusDays(1).toString();
        String twoDaysAgo = today.minusDays(2).toString();
        String tomorrow = today.plusDays(1).toString();
        String thisMonth = today.getYear() + "年" + today.getMonthValue() + "月";
        String lastMonth = today.minusMonths(1).getYear() + "年" + today.minusMonths(1).getMonthValue() + "月";
        String thisYear = String.valueOf(today.getYear());
        String lastYear = String.valueOf(today.getYear() - 1);
        return LANGUAGE_CONSTRAINT + "\n\n" + """
            【知识标准化约束 —— 消除歧义，确保知识库长期可用】
            知识库的内容会被长期引用，因此必须消除所有依赖"阅读时上下文"才能理解的表述。

            今天是{TODAY}。在生成内容时，请严格遵守以下规则：

            1. 【时间绝对化】严禁使用相对时间表述，所有时间必须转换为绝对日期或日期范围：
               - "今天"/"今日" → "{TODAY}"
               - "昨天"/"昨日" → "{YESTERDAY}"
               - "前天" → "{TWO_DAYS_AGO}"
               - "明天" → "{TOMORROW}"
               - "本周"/"这周" → 标注具体日期范围（如 "2026年5月11日-17日"）
               - "上周" → 标注具体日期范围
               - "本月"/"这个月" → "{THIS_MONTH}"
               - "上个月" → "{LAST_MONTH}"
               - "今年" → "{THIS_YEAR}"
               - "去年" → "{LAST_YEAR}"
               - "最近"/"近来"/"近日" → 标注具体时间段（如 "2026年4月至5月"）
               - "几天前"/"几个月前"/"几周前" → 推算并写明具体日期
               - "不久后"/"即将" → 能确定则标注具体日期，否则标注"时间未定"

            2. 【指代明确化】严禁使用模糊指代，必须使用具体名称或页面链接：
               - "如上所述"/"前文提到"/"后面会讲到" → 使用明确的章节引用或 [[页面链接]]
               - "这个人"/"那个项目"/"该公司"/"该团队" → 必须使用具体名称
               - "等等"/"诸如此类" → 要么完整列举，要么标注"列举未尽"

            3. 【版本锚定】涉及版本、状态的表述必须锚定到具体信息：
               - "最新版本" → 标注具体版本号和日期（如 "v2.3（截至{TODAY}）"）
               - "当前"/"目前"/"现阶段" → 标注时间锚点（如 "截至{THIS_MONTH}，"）

            4. 【客观化】使用客观中立的第三人称表述，去个人化：
               - "我认为"/"我觉得"/"我的理解" → 改写为客观陈述或标注信息来源
               - "我们团队"/"我们公司" → 使用具体组织名称或客观指代

            记住：知识库可能在数年后仍被引用，明确的时间锚点和具体指代是其长期价值的基石。"""
                .replace("{TODAY}", today.toString())
                .replace("{YESTERDAY}", yesterday)
                .replace("{TWO_DAYS_AGO}", twoDaysAgo)
                .replace("{TOMORROW}", tomorrow)
                .replace("{THIS_MONTH}", thisMonth)
                .replace("{LAST_MONTH}", lastMonth)
                .replace("{THIS_YEAR}", thisYear)
                .replace("{LAST_YEAR}", lastYear);
    }

    private static final int MAX_PAGE_LIST_TOKENS = 12000;

    public static final int MAX_SOURCE_CHARS_SUMMARY = 60000;
    public static final int MAX_SOURCE_CHARS_ENTITY = 30000;
    public static final int MAX_SOURCE_CHARS_RELATED = 16000;
    public static final int MAX_SOURCE_CHARS_PLAN = 12000;
    public static final int MAX_SOURCE_CHARS_CHAPTER = 50000;

    public static String buildSourceAndAnalysisUserMessage(String sourceContent, String analysisResult) {
        StringBuilder sb = new StringBuilder();
        if (sourceContent != null && !sourceContent.isBlank()) {
            sb.append("【原始文件内容】（事实来源 —— 最高优先级，所有事实性内容必须源自此处）\n");
            sb.append(sourceContent);
        }
        if (analysisResult != null && !analysisResult.isBlank()) {
            sb.append("\n\n【结构化导航索引】（写作方向指引 —— 帮助你确定覆盖范围和页面结构，但不是事实来源）\n");
            sb.append(analysisResult);
        }
        return sb.toString();
    }

    public static String truncateSourceContent(String sourceContent, int maxChars) {
        if (sourceContent == null || sourceContent.isEmpty()) return sourceContent;
        if (sourceContent.length() <= maxChars) return sourceContent;
        int cutPos = sourceContent.lastIndexOf('\n', maxChars);
        if (cutPos <= 0 || cutPos > maxChars) cutPos = maxChars;
        return sourceContent.substring(0, cutPos) + "\n...(原始文件过长，已截断展示前部分内容)";
    }

    public static String sampleSourceContent(String sourceContent, int maxChars) {
        if (sourceContent == null || sourceContent.isEmpty()) return sourceContent;
        if (sourceContent.length() <= maxChars) return sourceContent;

        int headSize = (int) (maxChars * 0.35);
        int midSize = (int) (maxChars * 0.30);
        int tailSize = maxChars - headSize - midSize;

        String head = sourceContent.substring(0, headSize);
        int midCenter = sourceContent.length() / 2;
        int midStart = Math.max(headSize, midCenter - midSize / 2);
        int midEnd = Math.min(sourceContent.length() - tailSize, midCenter + midSize / 2);
        String mid = sourceContent.substring(midStart, midEnd);
        String tail = sourceContent.substring(sourceContent.length() - tailSize);

        return head + "\n\n...(文档中段节选)...\n\n" + mid + "\n\n...(文档后段节选)...\n\n" + tail;
    }

    private PromptTemplate() {}

    private static final int SUMMARY_TRUNCATE_LEN = 50;

    public static String estimateAndTruncatePageList(List<PageSummary> pages, String listLabel, int maxTokens) {
        StringBuilder sb = new StringBuilder(listLabel).append("\n");
        sb.append("| 标题 | 分类 | 概要 |\n");
        int estimatedTokens = sb.length() / 4;
        int included = 0;

        for (PageSummary page : pages) {
            String truncatedSummary = page.summary() != null && page.summary().length() > SUMMARY_TRUNCATE_LEN
                ? page.summary().substring(0, SUMMARY_TRUNCATE_LEN) + "…"
                : (page.summary() != null ? page.summary() : "-");
            String entry = "| " + page.title() + " | " + page.category() + " | " + truncatedSummary + " |\n";
            int entryTokens = entry.length() / 4;

            if (estimatedTokens + entryTokens > maxTokens) {
                sb.append("...（共 ").append(pages.size()).append(" 个页面，仅展示前 ").append(included).append(" 个）\n");
                break;
            }
            sb.append(entry);
            estimatedTokens += entryTokens;
            included++;
        }
        return sb.toString();
    }

    public static String estimateAndTruncatePageList(List<PageSummary> pages, String listLabel) {
        return estimateAndTruncatePageList(pages, listLabel, MAX_PAGE_LIST_TOKENS);
    }

    public static String formatGuidancePrefix(String guidance) {
        if (guidance != null && !guidance.isEmpty()) {
            return "用户的引导方向：\"" + guidance + "\"\n\n";
        }
        return "";
    }

    public static String stripMarkdownFences(String content) {
        if (content == null) return null;
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            return trimmed.trim();
        }
        return content;
    }

    private static final java.util.regex.Pattern CONVERSATIONAL_PREFIX_PATTERN = java.util.regex.Pattern.compile(
        "^(?:好的[，。.]?|收到[，。.]?|明白[了]?[，。.]?|了解[，。.]?|没问题[，。.]?)"
    );

    private static final java.util.regex.Pattern CONVERSATIONAL_META_PATTERN = java.util.regex.Pattern.compile(
        "(?:作为.{0,8}(?:编译器|编译|助手|助手))|(?:我将(?:严格|忠实|根据|按照))"
            + "|(?:以下是[^\\n]{0,20}(?:页面|内容|Markdown|结果|版本))"
            + "|(?:如下[是为]?[^\\n]{0,10}(?:页面|内容|结果))"
            + "|(?:经过(?:分析|编译|处理))"
            + "|(?:根据(?:上述|以上|下方|提供的)[^\\n]{0,10}(?:要求|规则|指引|内容))"
            + "|(?:上面[是的])"
            + "|(?:以上就是[^\\n]{0,10}(?:页面|内容|结果))"
            + "|(?:页面已(?:更新|生成|完成))"
    );

    public static String stripConversationalFiller(String content) {
        if (content == null || content.isBlank()) return content;
        String trimmed = content.trim();
        int headingIdx = findFirstHeadingIndex(trimmed);
        if (headingIdx == 0) return content;

        if (headingIdx > 0) {
            String preamble = trimmed.substring(0, headingIdx);
            if (isConversationalPreamble(preamble)) {
                return trimmed.substring(headingIdx).trim();
            }
        }

        String[] lines = trimmed.split("\\n", -1);
        int firstContentLine = -1;
        for (int i = 0; i < Math.min(lines.length, 15); i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (isConversationalLine(line)) continue;
            firstContentLine = i;
            break;
        }

        if (firstContentLine <= 0) return content;

        StringBuilder sb = new StringBuilder();
        for (int i = firstContentLine; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static int findFirstHeadingIndex(String content) {
        for (int i = 0; i < content.length() - 1; i++) {
            if (content.charAt(i) == '#' && (i == 0 || content.charAt(i - 1) == '\n')) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isConversationalPreamble(String preamble) {
        String text = preamble.trim();
        if (text.isEmpty()) return false;
        if (CONVERSATIONAL_PREFIX_PATTERN.matcher(text).find()) return true;
        if (CONVERSATIONAL_META_PATTERN.matcher(text).find()) return true;
        return false;
    }

    private static boolean isConversationalLine(String line) {
        if (line.startsWith("#")) return false;
        if (line.startsWith("|")) return false;
        if (line.startsWith(">")) return false;
        if (line.startsWith("- ") || line.startsWith("* ")) return false;
        if (line.matches("^\\d+\\.\\s.*")) return false;
        if (line.startsWith("```")) return false;
        if (CONVERSATIONAL_PREFIX_PATTERN.matcher(line).find()) return true;
        if (CONVERSATIONAL_META_PATTERN.matcher(line).find()) return true;
        return false;
    }

    public record PageSummary(String title, String category, String summary, String filePath) {}
}
