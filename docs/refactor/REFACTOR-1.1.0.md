# LLM Wiki 1.1.0 重构计划：Agent 运行时架构升级

> **状态**：草案（待评审）
> **分支**：`refactor/1.1.0`（基于 `main` @ `v1.0.0` 拉出）
> **版本**：后端 `1.1.0-SNAPSHOT` / 前端 `1.1.0`
> **基线**：`v1.0.0` tag（main 分支稳定基线，重构回滚锚点）
>
> 本文档定义 1.1.0 的重构目标、范围、收益与风险。**代码未动，文档先行**——任何实施动作以本文档评审通过为前提。

---

## 1. 背景与动因

v1.0.0 确立了 LLM Wiki 的产品内核：知识编译隐喻、DDD 分层、Schema 共治宪法、scope 严格隔离。这些是**业务治理层面**的强项。

通过与成熟开源 agent harness（deepseek-harness）的深度对比，确认本项目的短板集中在**通用 agent 运行时能力**：

| 短板 | 现状 | 影响 |
|------|------|------|
| 工具调用无统一管道 | `@Tool` 注解直接执行，无 pre/post 拦截、无超时、无结果规范化 | QueryAgent 自主导航时无护栏，异常来源不可判 |
| 执行记录是状态快照 | `ExecutionTracker` 记录步骤状态，非事件流 | 崩溃只能回收 stale 任务，无法从断点重放；SSE 重连全量加载 |
| 无上下文治理 | `TokenUsageMonitor` 只做计费统计，不做压力治理 | 长 Wiki 检索结果全量塞 prompt，易触发超长/429 |
| Agent 硬编码 | Parser/Analyzer/Writer/Indexer/Lint/Query 全部写死 | 新增/覆盖 Agent 必须改编排代码，无法 scope 级定制 |
| 工程门控缺失 | 文档手写、无覆盖率门控、配置无启动校验 | 文档漂移、回归无保障、误配置运行时才爆 |

**重构北极星**：把 Harness 引擎从"硬编码 4-Agent 流水线"升级为"可扩展、可观测、可恢复的 agent 运行时"，同时**不动摇**编译隐喻与 Schema 治理这两块业务基石。

---

## 2. 版本与分支策略（已完成部分）

| 项 | 状态 | 说明 |
|----|------|------|
| `v1.0.0` tag | ✅ 已创建 | 打在 main（commit `2027539`），annotated tag，作为回滚锚点 |
| `refactor/1.1.0` 分支 | ✅ 已创建 | 从 main 拉出，重构专用分支 |
| 后端版本 | ✅ 已升级 | 11 个 pom：`0.0.1-SNAPSHOT` → `1.1.0-SNAPSHOT` |
| 前端版本 | ✅ 已升级 | `llmwiki-web-ui/package.json`：`0.0.0` → `1.1.0` |
| 文档同步 | ✅ 已更新 | AGENTS.md / CONTRIBUTING.md / LOCAL-DEPLOY*.md 中 jar 路径引用 |

**合并策略**：按 Phase 分批合入 main（每 Phase 独立可验证、可 revert），禁止一次性巨型 PR。任一 Phase 出现不可控风险，整体可退回 `v1.0.0`。

---

## 3. 重构目标（五大目标）

| 编号 | 目标 | 一句话定义 | 优先级 |
|------|------|-----------|--------|
| G1 | 工具执行管道 | 所有 `@Tool` 调用经过统一的 pre → around → post 管道，内置守卫链 | P0 |
| G2 | 事件溯源执行日志 | 执行过程记录为追加式事件流 + 投影，支持断点重放与冷读阶梯 | P0 |
| G3 | 上下文治理 | Compaction（压力检测→剪枝→摘要）+ Spill（超大结果溢出存储） | P0 |
| G4 | Agent 可扩展性 | AgentPlugin SPI + ExecutionPlan/Todo 建模 + 循环卫生守卫 | P1 |
| G5 | 工程基线加固 | 防御式编程约定 + 文档生成验证 + 覆盖率门控 + 配置校验 + LLM 回放测试 | P1 |

---

## 4. 范围界定

### 4.1 范围内

- `domain/service/harness`：管道、事件、上下文治理、插件 SPI 的新增与改造
- `biz/service`：Ingest/Lint 编排接入新管道（保持对外 API 契约不变）
- `common/dal`：新增 `execution_event` 等表（Flyway 迁移）
- `web`：HarnessController 增加事件流查询端点（新增，不修改既有端点签名）
- `app/test`：补齐门控测试
- 文档体系：AGENTS.md 防御式编程章节、生成脚本

### 4.2 明确不做（本次）

| 不做项 | 原因 |
|--------|------|
| 引入 Cordis/任何外部 DI 框架 | Spring DI 已满足，避免技术栈漂移 |
| MCP 集成 | 依赖 G4 插件 SPI 成熟后再做，候选 1.2.0 |
| Subagent 子代理 | 知识库场景 ROI 中等，候选 1.2.0 |
| 修改 Schema 共治宪法 | 业务基石，只允许插件接入时**接受**其校验，不改其规则 |
| 修改编译隐喻/三层页面模型 | 业务基石，不动 |
| 前端大改版 | 前端仅被动适配 SSE 事件格式变化（保持向后兼容） |
| OTel 全链路追踪 | 候选 1.2.0，本次以事件流 + token 计量替代大部分诉求 |

---

## 5. 关键设计要点

### G1 工具执行管道

**现状**：QueryAgent 的 `searchWiki` / `readFile` / `getRelatedPages` 等工具经 Spring AI `@Tool` 直接执行；scope 路径校验与并发控制散落在各调用点。

**方案**：新增 `domain/service/harness/pipeline` 包：

```
ToolExecutionPipeline（责任链入口）
├── pre-filter 链：ScopePathGuard（迁移现有逻辑）、ConcurrencyGuard（包装 LlmConcurrencyBarrier）、ApprovalFilter（REVIEW 级审批接入点）
├── around：TimeoutGuard（新增）、MetricsCollector（token/耗时计量）
├── 工具执行体
└── post-filter 链：LoopHygieneFilter、ResultNormalizer、SpillFilter（G3）
```

- 用 Spring AOP `@Around` 拦截 `@Tool` 方法，守卫实现为 `ToolFilter` 接口的有序 Bean 列表；
- **结果规范化铁律**（借鉴 defensive-patterns"公共契约双侧尊重"）：工具失败一律归一为 `ToolResult.error(...)`，消费方不再猜异常来自 Provider、包装层还是自身装配；
- 灰度开关：`llmwiki.harness.tool-pipeline.enabled`（默认 `false`，关闭时走原路径）。

### G2 事件溯源执行日志

**现状**：`execution_step` 表记录状态快照；崩溃恢复（`ExecutionRecoveryService`）只能回收 stale 任务；SSE 重连靠前端重新拉全量。

**方案**：

1. 新增 `execution_event` 表（Flyway，**只增不改**）：`execution_id / seq / event_type / payload_json / node_id / created_at`；
2. 事件词汇表（首版）：`turn/start`、`step/start`、`tool/call`、`tool/result`、`step/end`、`turn/end`、`error`、`compaction/triggered`、`spill/written`；
3. `ExecutionTracker` 保留为**投影**角色：从事件流折叠出当前状态，`execution_step` 表降级为投影缓存；
4. **冷读阶梯**：执行列表页只读投影；SSE 重连只回放 `Last-Event-ID` 之后的事件，不再全量加载；
5. **编码铁律**："模型可见 ⟺ 已记录"——任何进入 LLM 请求的上下文都必须有对应事件，写入 AGENTS.md 编码约定（直接服务于"准确性 > 一切"红线：可追溯性从页面级提升到上下文级）。

### G3 上下文治理

**现状**：长对话与大检索结果全量进 prompt；`TokenUsageMonitor` 只统计不治理。

**方案**：

1. **CompactionService**：step 结束后检查 token 压力（阈值可配 `llmwiki.harness.compaction.pressure-threshold`）→ 两段式处置：先模型无关的工具结果剪枝，再调 LLM 摘要（复用 `GlobalSummaryService` 的摘要能力，走 `AiSlotRouter` 路由到低成本模型槽位）；
2. **SpillService**：超过 `llmwiki.harness.spill.max-inline-bytes` 的工具结果写入 `wiki-data/{scopeId}/spill/`，返回定位器 + 检索提示，下游 Agent 按需读取（与现有 scopeId 路径隔离天然兼容，`spill/` 目录生命周期随 execution 清理）；
3. 压缩/溢出动作全部落事件（G2），保证可审计——**摘要只影响模型上下文，绝不改写 wiki 存储产物**（编译产物准确性红线不受影响）。

### G4 Agent 可扩展性

**现状**：6 个 Agent 硬编码于 `domain/service/harness`，新增 Agent 必须改编排代码。

**方案**：

1. **AgentPlugin SPI**：定义 `AgentPlugin` 接口（name / capability / schemaConstraints / execute），Spring `@Conditional` + 注册表装配；scope 级覆盖（同 capability 下 scope 自定义插件优先于默认实现）；**插件注册必须通过 `SchemaSkeletonValidator` 相关校验**，与 Schema 宪法对齐（违规即阻断）；
2. **ExecutionPlan/Todo 建模**：Ingest Phase 1（分析阶段）产出显式 `ExecutionPlan`（step 列表、依赖、暂停点），Phase 2 按 plan 执行并逐项回写事件；QueryAgent 增加 `todo` 工具支持多跳问题分解；Lint 诊断项（orphan/gap 等）统一进 todo 模型做进度跟踪；
3. **LoopHygieneFilter**（挂 G1 post 链）：检测重复工具调用（同参数 N 次内重复）、空转 step 上限，触发后强制收敛或升级人工介入。

### G5 工程基线加固

1. **防御式编程约定**：AGENTS.md 新增章节，收录 7 条语言无关规则（正交结果独立报告 / 公共契约双侧尊重 / 异步状态≠同步状态 / Dispose 必须达到静止 / 回调异常容器化 / 不受信任输出环境隔离 / 链接路径清理），重点落地三处：
   - `PythonProcessRunner`：子进程 env 清洗（剥离 `*API_KEY*` / `*SECRET*` / `*TOKEN*`，防止 `AI_DASHSCOPE_API_KEY` 泄漏进解析子进程）+ kill 后 await 退出；
   - SSE 与执行线程的异步边界：`SseEmitter` 完成/超时/异常三态独立报告；
   - RocketMQ 消费幂等的既有约束补测试。
2. **文档生成 + 验证闭环**：Maven 插件或脚本扫描 `@RestController` / `@Tool` / `AgentPlugin`，生成 API/工具/Agent 目录 Markdown；CI 校验手写文档引用的端点与工具名在生成目录中存在（`verify-doc-sync`），不通过即 fail；
3. **测试门控**：JaCoCo 覆盖率门控（harness 核心包 ≥ 80%）；**LLM 快照回放测试**——record/replay 模式录制一次真实 LLM 响应后续回放，既省 token 又能捕捉 prompt 漂移（本次重构的行为等价性验证主要依赖它）；
4. **配置启动校验**：关键配置（`JWT_SECRET` 非默认值、主 Provider API Key 存在、ES 可达）用 `@Validated` + `ConfigurationProperties` 在启动时 fail-fast（"误配置大声失败"）。

---

## 6. 收益评估

### 6.1 Agent 能力收益

| 收益 | 机制 | 预期效果 |
|------|------|----------|
| Query 答案质量提升 | 工具管道去噪 + Compaction 保近期尾部 | 长文档问答不再因上下文超长而截断关键证据 |
| 多跳推理能力 | QueryAgent todo 分解 | 复杂问题先分解再检索，减少无效搜索 |
| 能力可扩展 | AgentPlugin SPI | 新增 Agent 零编排代码改动；scope 级定制成为可能（为 MCP/1.2.0 铺路） |
| 崩溃可恢复 | 事件溯源 | 从最后事件断点恢复，而非丢弃整个执行 |

### 6.2 非功能收益

| 维度 | 收益 |
|------|------|
| **稳定性** | 结果规范化消除"异常来源不可判"；守卫链杜绝失控循环；SSE 重连从全量加载降为尾部回放，弱网体验显著改善 |
| **性能** | Spill 减少 prompt 传输体积；冷读阶梯使执行列表查询不再随历史执行数线性变慢；Compaction 降低超长重试的浪费 |
| **成本** | 长会话 token 消耗预计下降 20–40%（剪枝 + 摘要 + 低成本模型槽位分流）；回放测试节省回归 token |
| **可观测性** | 每次执行的 token/耗时/工具调用全量可归因（事件流 + MetricsCollector），Token 监控从"日粒度计费"升级为"执行粒度归因" |
| **可维护性** | 文档生成验证杜绝漂移；覆盖率门控防止重构期回归；防御式约定降低子进程/异步类缺陷复发率 |

---

## 7. 风险评估（重点）

### 7.1 风险矩阵总览

| 编号 | 风险 | 类别 | 概率 | 影响 | 等级 |
|------|------|------|------|------|------|
| R1 | SSE 链路改造影响 Ingest/Lint 进度推送 | 稳定性 | 中 | 高 | **高** |
| R2 | 管道改造导致 QueryAgent 行为漂移 | 稳定性 | 中 | 高 | **高** |
| R3 | Compaction 摘要丢失关键证据 | 鲁棒性 | 中 | 高 | **高**（触碰准确性红线） |
| R4 | 守卫误拦截合法工具调用 | 鲁棒性 | 中 | 中 | 中 |
| R5 | 事件流写放大拖慢执行 | 性能 | 低 | 中 | 中 |
| R6 | Compaction 摘要调用增加 LLM 成本 | 性能 | 中 | 低 | 中 |
| R7 | Flyway 迁移失败导致启动失败 | 稳定性 | 低 | 高 | 中 |
| R8 | 灰度开关状态组合爆炸 | 鲁棒性 | 中 | 中 | 中 |
| R9 | 插件 SPI 破坏 Schema 治理 | 鲁棒性 | 低 | 高 | 中 |
| R10 | Spill 目录清理不彻底占用磁盘 | 性能 | 中 | 低 | 低 |

### 7.2 稳定性专项

**R1 SSE 链路改造（高）**：事件溯源改变进度事件的产生与分发路径，而 Ingest/Lint 的 SSE 是用户感知最强的链路，且涉及 RocketMQ Broadcasting 模式下的多节点广播。
- **缓解**：v1 SSE 路径完整保留，事件流双写（事件落库与 SSE 推送解耦——SSE 仍由现有推送通道发出，仅数据源切为事件投影）；灰度开关控制数据源切换；先用 Query（短生命周期、请求绑定型、不广播）做首发试点，Ingest/Lint 后置。

**R2 QueryAgent 行为漂移（高）**：管道介入后工具调用时序、错误语义变化，可能导致同一问题答案不同。
- **缓解**：LLM 回放快照测试建立 v1.0.0 行为基线，管道开关 on/off 双跑对比；灰度期先在测试 scope 验证；`tool/result` 语义变更必须在事件词汇表中标注版本。

**R7 Flyway 迁移（中）**：`execution_event` 建表失败会阻断启动。
- **缓解**：迁移脚本只新增表、不修改任何既有表结构；在 dev/prod 双 profile 预演；失败时开关关闭即可跑旧路径（新表空转无害）。

### 7.3 性能专项

**R5 事件流写放大（中）**：每个 tool/call/tool_result 各写一行。
- **定量评估**：单次 Ingest 执行约 20–100 个工具调用事件，即 40–200 行/执行；相对现有 `wiki_page_source` 等写入量可忽略。**缓解**：批量写（固定时间窗口 flush，借鉴 dsh 有界批处理），MQ 模式下事件写入与执行线程异步解耦。

**R6 Compaction 摘要成本（中）**：摘要本身是额外 LLM 调用。
- **缓解**：仅超压力阈值才触发（正常短会话零成本）；摘要走 `AiSlotRouter` 低成本模型槽位；摘要结果缓存复用（同一执行内相同前缀不重复摘要）。**净收益预期为正**：省下的超长重试与截断损失 > 摘要成本。

**R10 Spill 磁盘占用（低）**：溢出文件若清理失败会累积。
- **缓解**：spill 文件随 execution 终态清理（复用 `ExecutionRecoveryService` 的 stale 回收点）；`spill/` 目录配额告警。

### 7.4 鲁棒性专项

**R3 Compaction 证据丢失（高，准确性红线）**：摘要可能丢掉后续推理需要的关键事实。
- **缓解**（三层防线）：
  1. **边界隔离**——摘要只影响模型上下文，`raw/` 与 wiki 存储产物永不因压缩改变，真相来源不受损；
  2. **保守策略**——只压缩最旧完整段落，近期尾部全保留；被压缩段落的原文通过 Spill 定位器可随时回读；
  3. **可审计**——`compaction/triggered` 事件记录压缩范围与摘要 prompt，答案异常时可回溯复核。

**R4 守卫误拦截（中）**：ScopePathGuard/LoopHygieneFilter 可能把合法调用判为违规。
- **缓解**：守卫策略默认"只拦高危模式"；所有拦截落审计日志（复用 `AuditLogAspect`），支持事后复盘调整阈值；守卫放行/拒绝均有事件，灰度期监控误拦率（目标 < 0.1%）。

**R8 开关组合爆炸（中）**：`tool-pipeline` / 事件源切换 / compaction 三个开关存在组合。
- **缓解**：开关设计为单向递进依赖（pipeline 关则其余不生效），同一时间只灰度一个维度；开关矩阵列入测试用例。

**R9 插件破坏 Schema 治理（中）**：第三方/自定义 AgentPlugin 绕过 Schema 约束。
- **缓解**：插件注册是 Schema 校验的强制前置（无 Schema v1 的 scope 本就被宪法禁止一切入口，插件继承此约束）；插件执行同样经过 G1 管道，无特权路径。

### 7.5 风险应对总原则

1. **一切新能力默认关闭**：灰度开关 + 双路径并存，任何时刻可退回 v1 行为；
2. **只增不改**：DB 只加表、API 只加端点、事件词汇只追加，既有契约零破坏；
3. **等价性验证先行**：每个 Phase 合入前，LLM 回放测试证明 on/off 行为等价（或差异可解释）；
4. **回滚锚点明确**：`v1.0.0` tag 可随时恢复 main 基线。

---

## 8. 实施路线图

| Phase | 周期 | 内容 | 退出标准 |
|-------|------|------|----------|
| **P1 基线与低风险项** | 第 1–2 周 | G5 防御式约定落 AGENTS.md；PythonProcessRunner env 清洗；配置启动校验；Spill 落地 | 约定评审通过；单测绿；spill 集成测试通过 |
| **P2 工具管道** | 第 3–6 周 | G1 管道 + 守卫链 + 结果规范化；QueryAgent 试点灰度 | 回放测试 on/off 等价；误拦率 < 0.1% |
| **P3 上下文治理** | 第 5–8 周（与 P2 部分并行） | G3 Compaction + Spill 接入管道 post 链 | 长会话 token 消耗下降 ≥ 20%；无准确性回归 |
| **P4 事件溯源** | 第 7–10 周 | G2 事件表 + 投影 + SSE 数据源切换（Query → Ingest → Lint 顺序灰度） | SSE 重连只回放尾部；崩溃恢复演练通过 |
| **P5 可扩展性** | 第 11–14 周 | G4 AgentPlugin SPI + ExecutionPlan/Todo + LoopHygiene；文档生成验证 + 覆盖率门控收尾 | 一个示例插件走通注册→校验→执行全链路；CI 门控全绿 |

**合入节奏**：每 Phase 独立 PR 合入 main，合入即打里程碑 tag（`v1.1.0-p1` … `v1.1.0-rc`），全部完成后发 `v1.1.0`。

---

## 9. 验收标准（1.1.0 发布门槛）

1. 全量回归测试绿 + harness 核心包覆盖率 ≥ 80%；
2. LLM 回放基线：Ingest/Query/Lint 三大流程 on/off 行为等价性报告通过评审；
3. 灰度观察：误拦率 < 0.1%，SSE 重连成功率 ≥ 99%，长会话 token 消耗下降 ≥ 20%；
4. 崩溃恢复演练：kill -9 后从事件断点恢复成功率 100%（测试环境）；
5. 文档验证：`verify-doc-sync` 通过；AGENTS.md 防御式编程章节合入；
6. 安全项：Python 子进程 env 清洗测试通过；spill 目录权限正确。

---

## 10. 附录

### 10.1 参考资料

- deepseek-harness 能力接缝设计：`docs/capability-seams.md`
- deepseek-harness 工具管道：`docs/tool-execution-pipeline.md`
- deepseek-harness 防御式编程：`docs/defensive-patterns.md`（本计划 G5 第 1 条的 7 条规则来源）
- 本项目架构约束：`ARCHITECTURE.md`、`AGENTS.md`（知识编译红线与 Schema 宪法为本次重构的不可触碰边界）

### 10.2 术语

| 术语 | 含义 |
|------|------|
| 事件溯源 | 状态变化记录为不可变追加事件，当前状态由事件折叠（投影）得出 |
| 投影 | 从事件流折叠出的物化视图（如 execution_step） |
| 冷读阶梯 | 列表查询先读投影缓存，仅必要时回放尾部事件，避免全量加载 |
| Compaction | 上下文压力超阈值时，剪枝/摘要旧内容以压缩模型上下文 |
| Spill | 超大工具结果溢出到文件存储，上下文中只留定位器 |
| 守卫（Guard） | 管道中"拒绝或弃权"的单向检查点 |
| 灰度开关 | 控制新旧路径切换的配置开关，默认关闭（走旧路径） |
