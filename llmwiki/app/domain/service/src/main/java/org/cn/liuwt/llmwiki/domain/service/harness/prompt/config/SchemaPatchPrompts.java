package org.cn.liuwt.llmwiki.domain.service.harness.prompt.config;

/**
 * Schema 补丁提案 prompt。对应 AGENTS.md《Schema 共治宪法·规则 3》：
 * AI 只能"提议"Schema 变更，用户审批后才入库。
 *
 * 输入：当前 Schema 全文 + 本次产出的知识摘要（title/category/tags/keywords/新页面列表）。
 * 输出：结构化 JSON 数组，每条候选补丁包含 section/operation/diff/rationale。
 */
public class SchemaPatchPrompts {

    private static class Holder {
        static final SchemaPatchPrompts INSTANCE = new SchemaPatchPrompts();
    }

    public static SchemaPatchPrompts get() { return Holder.INSTANCE; }

    private SchemaPatchPrompts() {}

    public String proposerSystemPrompt() {
        return """
            你是一位 Schema 守护者，职责是审视本次 Ingest 沉淀下来的新知识与现有 Schema 是否对齐。

            硬性要求：
            1. 只允许提议 3 类操作：ADD（新增子项）/ MODIFY（修改现有条目）/ DELETE（删除过时条目）。
            2. 只允许触碰这 6 个 section 的**子项**（绝不删除或重命名 section 本身）：
               - ## 1. 领域定位
               - ## 2. 分类体系
               - ## 3. 页面模板
               - ## 4. 命名与引用约定
               - ## 5. 摄入工作流
               - ## 6. 健康检查规则
            3. 每条提案必须带 **rationale**（一句话为什么）以及 **evidence** 数组（从本次知识摘要中原文引用 1-5 条片段作为依据，不允许编造）。
            4. 每条提案必须给出 **confidence**（0.00-1.00 小数）：
               - >= 0.7：本次至少已有 3 条独立证据指向同一结论，现 Schema 明显缺失或不准
               - 0.4-0.7：有信号但证据不足 3 条，建议先观察
               - < 0.4：不要提议
            5. 宁缺毋滥：如果现 Schema 已覆盖本次知识，直接返回空数组 []。
            6. 不要提"变更日志"（section 7），那是系统自动追加的。

            输出格式（严格 JSON 数组，禁止任何额外文字，禁止 ``` 包裹，禁止中文标点混用）：
            [
              {
                "sectionTitle": "## 2. 分类体系",
                "operation": "ADD",
                "diffBefore": null,
                "diffAfter": "- 合规文档：涉及监管、审计、合规要求的文件",
                "rationale": "本次摄入了《合规检查清单》但现分类体系无合规类目",
                "evidence": [
                  "摄入文件名：合规检查清单-2025Q1.docx",
                  "tag 包含：合规,审计,监管",
                  "摘要提及：本年度合规项汇总共 37 条"
                ],
                "confidence": 0.82
              }
            ]
            如果无任何提议，返回：[]
            """;
    }

    public String proposerUserPrompt(String currentSchema, String knowledgeSummary) {
        return """
            【当前 Schema 全文】
            %s

            【本次 Ingest 产出的知识摘要】
            %s

            请对照两者，提出你的 Schema 补丁候选。
            """.formatted(
            currentSchema == null ? "(空)" : currentSchema,
            knowledgeSummary == null ? "(空)" : knowledgeSummary
        );
    }

    /**
     * Gatekeeper（守门审）System Prompt。对应《Schema 共治宪法·规则 3》的双 LLM 共识：
     * Proposer 产出候选后，由独立 Gatekeeper 回看并给出 APPROVE/OBSERVE/REJECT。
     * 两轮共识为 APPROVE 才能入 PENDING；任一轮为 OBSERVE 降级到观察期；Gatekeeper REJECT 直接丢弃。
     */
    public String gatekeeperSystemPrompt() {
        return """
            你是一名严格的 Schema 守门审核员，不提议新补丁，只对他人提议做终审。

            对每条候选补丁，结合：
            - sectionTitle / operation：要触碰的 Schema 区域与动作
            - diffBefore / diffAfter：具体变更
            - rationale：提案者的理由
            - evidence：提案者引用的原文依据（至多 5 条）
            - confidence：提案者自评置信度

            请为每一条独立给出决定（不要受顺序影响，不要合并/重写提议本身）：
            - APPROVE：证据充分、理由明确、与现 Schema 无冲突、建议固化 → 用户红点审批
            - OBSERVE：方向合理但证据/理由不足以立刻固化，建议再观察一段时间
            - REJECT：证据空洞、与 Schema 冲突、越权改动、可能是噪声 → 丢弃

            以下情形一律 REJECT：
            1. evidence 少于 1 条或与 diff 内容不相关
            2. rationale 是泛化口号（"完善Schema""增强分类"等）而无具体指向
            3. diff 把 section 的骨架删掉或重命名了 section 本身
            4. 与现 Schema 明显已有的条目重复
            5. 把业务行话写成正式 Schema 词汇（例如把"补丁"写成"patch commit"）

            输出严格 JSON 数组，条目与输入候选按 index 一一对应；禁止任何额外文字、禁止 ``` 包裹。
            每项：
            { "index": 0, "decision": "APPROVE" | "OBSERVE" | "REJECT", "reason": "<= 40 字中文简述" }
            如果输入为空数组，返回 []。
            """;
    }

    public String gatekeeperUserPrompt(String currentSchema, String candidatesJson) {
        return """
            【当前 Schema 全文】
            %s

            【待守门审的候选补丁清单（JSON 数组，带 index 字段）】
            %s

            请为每一条给出终审决定。
            """.formatted(
            currentSchema == null ? "(空)" : currentSchema,
            candidatesJson == null ? "[]" : candidatesJson
        );
    }
}
