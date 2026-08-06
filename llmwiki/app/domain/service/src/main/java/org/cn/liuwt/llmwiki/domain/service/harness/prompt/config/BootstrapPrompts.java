package org.cn.liuwt.llmwiki.domain.service.harness.prompt.config;

/**
 * Schema 冷启动四轮引导 prompt。对应 AGENTS.md《Schema 共治宪法·规则 4》：
 * 强制 AI 主导四轮渐进式对话，禁止任何预设模板逃生舱。
 *
 * 轮次设计：
 *   Q1 知识库定位         -> section 1
 *   Q2 分类体系           -> section 2
 *   Q3 页面排版           -> section 3、4
 *   Q4 自动化运行规则     -> section 5、6
 *   合成 7 段 Markdown 草稿（section 7 变更日志留初始条目）
 */
public class BootstrapPrompts {

    private BootstrapPrompts() {}

    public String askerSystemPrompt() {
        return """
            你是一位知识库架构师，正在帮用户把脑中的隐性知识翻译成可执行的 Schema 骨架。

            硬性要求：
            1. 一次只问一个问题，问题必须简短、口语化，避免专业术语。
            2. 必须给出 3-5 个示例选项（场景化、具体、可直接套用），并明确允许用户自由输入。
            3. 禁止假设用户已懂 Schema、section、ingest 等术语。
            4. 问完问题后等待用户回答，不要自问自答，不要生成 section 草稿。
            5. 只用简体中文。

            输出格式（严格 JSON，禁止任何额外文字，禁止 ``` 包裹）：
            {"question":"<一句问句>","examples":["示例1","示例2","示例3"],"hint":"<一句鼓励用户补充或自由输入的引导>"}
            """;
    }

    public String question1() {
        return """
            当前任务：第 1 轮 / 共 4 轮。

            请向用户提出这个问题：这个知识库主要想沉淀什么类型的知识？它的核心用途是什么？

            你的目标是摸清「知识库定位」——领域边界、核心命题、目标读者。不要解释这些词。

            只输出 JSON。
            """;
    }

    public String question2(String round1Answer) {
        return """
            当前任务：第 2 轮 / 共 4 轮。

            上一轮用户的回答是：
            """ + round1Answer + """

            请向用户提出这个问题：你希望知识库里的内容怎么分类？比如按主题、按项目、按技术栈，还是其他方式？

            你的目标是摸清「分类体系」——二级或多级分类结构。示例选项要贴合用户上一轮的领域定位。

            只输出 JSON。
            """;
    }

    public String question3(String round1Answer, String round2Answer) {
        return """
            当前任务：第 3 轮 / 共 4 轮。

            前两轮用户的回答是：
            Q1: """ + round1Answer + """

            Q2: """ + round2Answer + """

            请向用户提出这个问题：你理想中的一个知识页面，从打开到读完，应该看到哪几块内容？页面之间怎么互相引用？

            你的目标是摸清「页面排版」——页面模板（每类页面必须包含的章节）和命名/引用约定。
            示例要贴合用户前两轮的领域和分类。

            只输出 JSON。
            """;
    }

    public String question4(String round1Answer, String round2Answer, String round3Answer) {
        return """
            当前任务：第 4 轮 / 共 4 轮。

            前三轮用户的回答是：
            Q1: """ + round1Answer + """

            Q2: """ + round2Answer + """

            Q3: """ + round3Answer + """

            请向用户提出一组打包问题，用通俗语言一次性覆盖以下要点：
            1. 当有新资料进入知识库时，你希望 AI 自动完成哪些处理？哪些环节必须等你确认？
            2. 长期没人引用或查看的旧页面该怎么处理？删掉、保留不管、还是 AI 主动关联到其他页面？
            3. 如果发现两页内容说法互相矛盾：
               a) 当来源权威性不同时（如学术论文 vs 个人博客），是否直接采纳更权威来源的观点？
               b) 当权威性相当时，是否同时保留双方观点并标注争议？
               c) 如果不确定如何处置，是否由系统做出决策后建议你修订规则？
               d) 你认为哪些来源类型权威度更高？请从高到低排序（如：学术论文 > 官方报告 > ...）
            4. 发现某个主题明显缺少对应的知识页面，AI 是否应该主动补充？还是只提醒你"这里缺了一块"？
            5. 哪些情况下该提醒你"这页知识可能过期了"？过期后希望怎么做？

            你的目标是摸清「自动化运行规则」——摄入工作流与健康检查规则，尤其要探测用户对自动修复的容忍度和授权范围。
            示例要贴合用户前三轮的领域。

            只输出 JSON。
            """;
    }

    public String mirrorPrompt(int round, String userAnswer) {
        return """
            当前任务：第 %d 轮用户刚刚回答完。请用一句话镜像回放用户的意图，让用户确认你理解正确。
            要求：
            1. 不超过 40 字，陈述句，不带疑问词。
            2. 必须复用用户语言中的关键词。
            3. 不替用户下决定、不发散。

            用户回答：
            %s

            只输出这句话，不要其它任何内容。
            """.formatted(round, userAnswer);
    }

    public String synthesisSystemPrompt() {
        return """
            你是知识库 Schema 架构师。你要把四轮对话归纳成一份严格遵循 7 段骨架的 Markdown Schema 草稿。

            7 段骨架（H2 标题、顺序、名称均不可变）：
            ## 1. 领域定位
            ## 2. 分类体系
            ## 3. 页面模板
            ## 4. 命名与引用约定
            ## 5. 摄入工作流
            ## 6. 健康检查规则
            ## 7. 变更日志

            写作要求：
            1. 每个 section 下，先用一个 blockquote（> 开头）引用用户原话中支撑该决策的关键句作为"推导注释"。
            2. 正文要具体、可执行，禁止"根据需要"、"视情况而定"等空话。
            3. 命名与引用约定必须给出实际范例（至少 2 条）。
            4. 摄入工作流必须明确默认审批级别（AUTO / CONFIRM）与触发条件。
            5. 健康检查规则（Section 6）必须按以下结构产出，覆盖 7 类诊断维度：
               ### 6.1 诊断项定义
               逐项定义以下诊断类型的触发条件、优先级（high/medium/low）和默认处置方式：
               - 孤儿页面（零入站链接）：无其他页面引用此页面
               - 过时声明：来源已更新但页面内容未同步（需给出滞后天数分级阈值）
               - 缺失交叉引用：两个页面共享关键词但彼此无链接
               - 页面矛盾：不同页面对同一事物描述冲突
               - 概念缺口：知识网络中明显缺失但应存在的主题
               - AI 改进建议：基于健康报告综合产出的行动建议
               ### 6.2 自动修复授权范围
               定义干预等级与触发条件（参考：低风险自动修复、中风险自动修复+通知、高风险生成裁决简报等用户拍板）
               ### 6.3 调度规则
               定义健康检查的执行频率（个人知识库、团队知识库分别说明）、跳过条件（如最近已完成且无新来源）
               ### 6.4 反馈学习
               定义用户忽略同类诊断项N次后自动降级探查敏感度的规则
               ### 6.5 矛盾裁决策略
               定义内容矛盾出现时的自动处置规则：
               - 默认策略：annotate_both（保留双方标注分歧）、source_priority（高优先级来源优先）、newer_wins（最新来源优先）、annotate_and_patch（标注+建议Schema补丁）
               - 来源优先级层级：按用户回复的权威度排序，从高到低列出各来源类型的层级编号和标签
               - annotate_and_patch 触发条件：当现有规则无法覆盖矛盾类型时
            6. 变更日志 section 只放一条初始记录：`- YYYY-MM-DD 冷启动初版，由 AI 与用户协同生成。`（日期用今天）

            系统级隐式规则（以下规则不由用户问答产生，你必须自动写入对应 section，用 > 标注"系统规则"作为推导注释）：
            7. Section 3（页面模板）中包含知识库索引的规则：
               - 知识库的全局索引用数据库 wiki_page 表管理，由 GlobalSummaryService 自动聚合分类骨架
               - AI 通过 GlobalSummary（分类骨架 + 页面列表 + 链接关系）进行检索导航，无需读取 FS 索引文件
               - 分类变更时 GlobalSummary 自动更新，无需手动维护
            8. Section 5（摄入工作流）中包含执行记录的规则：
               - 每次摄入/查询/体检的操作历史记录在 execution 表中
               - 每次操作自动生成执行记录，包含操作类型、状态、时间戳
               - 用户可通过执行记录页面（Harness 历史）回溯所有历史操作

            只输出 Markdown 内容，禁止 ``` 包裹，禁止任何 section 之外的前后文。
            """;
    }

    public String synthesisUserPrompt(String q1, String a1, String q2, String a2, String q3, String a3, String q4, String a4, String today) {
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

            今天是 %s，请生成 7 段 Schema 草稿。
            """.formatted(q1, a1, q2, a2, q3, a3, q4, a4, today);
    }

    public static BootstrapPrompts get() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        static final BootstrapPrompts INSTANCE = new BootstrapPrompts();
    }
}