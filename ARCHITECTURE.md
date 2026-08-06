# LLM Wiki — 架构概览

> 本文档是系统架构的**概览**：讲清楚系统由什么组成、核心流程如何运转、关键设计决策是什么。
> 数据库表结构以 `llmwiki/app/bootstrap` 下的 Flyway 迁移脚本为准；API 端点以 `llmwiki/app/web` 下的 Controller 为准。
> UI 设计规范见 [DESIGN.md](DESIGN.md)，编码约定与构建命令见 [AGENTS.md](AGENTS.md)。

---

## 1. 产品第一性原理

Wiki 的本质是**知识的持久化、组织化和可演化性**。AI 是辅助工具，不是产品本身。

- **人指挥、AI 执行、知识沉淀** — 用户通过对话与编辑器下达意图，AI 负责执行；好的问答答案沉淀回 Wiki，而非消失在对话历史中
- **准确性优先于一切** — 编译产物的准确性是不可妥协的底线，优先级高于展示丰富度、格式规范和视觉美观
- **AI 是增强** — AI 让知识更好，但不改变 Wiki 的本质

## 2. 知识编译 — 核心隐喻

LLM Wiki 对知识的处理，本质上是让 AI 对知识进行一次"编译"：

| 编译概念 | LLM Wiki 对应 | 说明 |
|---------|-------------|------|
| 源码 | Raw 来源（PDF / DOCX / MD…） | 不可变，是真相来源 |
| 结构化源码 | parsed 层（标准 Markdown） | AI 可读的工作副本，保留章节语义 |
| 编译器 | AI Pipeline | 把原始信息转化为结构化知识 |
| 目标码 | Wiki 页面 | AI 产出物，互联的 Markdown 网络 |
| 符号表 / Source Map | ES 索引 + `wiki_page_source` | 编译产物 ↔ 原文的回溯映射 |
| 链接器 | 交叉引用 / 矛盾链接 | 页面间建立关系 |
| 调试信息 | `execution` 表 | 每次操作的完整执行记录 |
| 构建配置 | Schema | 约束 AI 行为与产物格式，随知识库共同演化 |
| 增量编译 | Ingest 更新已有页面 | 只重编受影响的部分 |
| 运行 | Query | 对编译产物执行查询 |
| 静态分析 | Lint | 检查矛盾、孤立、缺口、过时 |

由此推导出四条硬性规则：

1. **编译器必须完整** — Ingest 必须做增量编译（更新已有页面，不是只创建新页面），必须有来源-页面追踪，链接器必须真正执行链接
2. **项目隔离必须严格** — scope 隔离如同不同项目各有自己的 build 目录：关系表必须有 `scope_id`，文件路径必须包含 scopeId，`raw/` 目录绝对不可写
3. **准确性优先于一切** — 当富元素（Mermaid 图、ECharts 图表）需要 LLM 从源文档中"推断"或"构造"信息时，宁可不用也不能编造
4. **编译必须可追溯** — 编译允许有损，但每一段产物必须能回溯到原文。Raw 层是 source of truth

## 3. 系统总览

系统采用 DDD 领域驱动设计分层架构，后端代码在 `llmwiki/app/`：

```
┌────────────────────────────────────────────────────────────┐
│                        用户界面                              │
│   Vue 3 SPA — Wiki 浏览/编辑、摄入、查询问答、体检、图谱      │
├────────────────────────────────────────────────────────────┤
│                     REST API 层（app/web）                   │
│   Spring Boot Controllers + JWT 认证，统一 Result<T> 响应    │
├────────────────────────────────────────────────────────────┤
│                  业务编排层（app/biz/service）                │
│   Ingest / Query / Lint / Auth / Scope 等业务服务            │
├────────────────────────────────────────────────────────────┤
│                  领域层（app/domain）                        │
│   ┌────────────────────────────────────────────────────┐   │
│   │  Harness 引擎 — AI 编排层                            │   │
│   │  IngestOrchestrator · PipelineOrchestrator ·        │   │
│   │  Parser/Analyzer/Writer/Indexer Agent · Schema 治理  │   │
│   └────────────────────────────────────────────────────┘   │
│   Wiki / Search / Editor 等领域模型与领域服务                 │
├────────────────────────────────────────────────────────────┤
│                  公共层（app/common）                        │
│   DAL（MyBatis-Plus）· Facade DTO · AI 多 Provider 集成      │
├────────────────────────────────────────────────────────────┤
│                        数据层                                │
│   MySQL（元数据真相）· 文件存储 Local/NAS/MinIO ·             │
│   Elasticsearch（Wiki 检索）· RocketMQ（可选，分布式）         │
└────────────────────────────────────────────────────────────┘
```

## 4. 四层数据架构

每个 scope 的文件存储位于 `wiki-data/{scopeId}/`，与 MySQL 元数据配合形成四层：

### raw/ — 原始来源（不可变）

- 用户上传的源文档，系统绝不修改。文件名格式 `{uuid}-{originalName}` 避免冲突
- 元数据真相来源：MySQL `source` 表
- **不可变性约束**：任何写操作（WriteFileTool / StorageProvider）命中 `raw/` 直接拒绝；来源删除是逻辑删除，文件保留供溯源

### parsed/ — 结构化源层（编译中间产物）

- 原始文档经解析器（Python，支持 OCR / 多模态 / 图表识别）转换的标准 Markdown，文件名 `{sourceId}.parsed.md`
- 是 Raw 层的结构化派生，不是独立真相来源；**不被 ES 索引，不被 Query 搜索**

### wiki/ — LLM 编译产物（可变）

Harness 生成和维护的 Markdown 页面，随时间复利增长。三种页面类型：

| 类型 | 说明 |
|------|------|
| `summary` 摘要页 | 跨源综合，高度浓缩，每个来源一份 |
| `entity` 实体页 | 跨源综合，围绕一个实体/概念 |
| `reference` 参考页 | 单源高保真（LLM 结构化摘要 + 原文引用），为结构化文档按章节生成；**只读锁定**，不被其他文档的关联更新触碰 |

- 元数据真相来源：MySQL `wiki_page` 表；标签/关键词/链接/来源关联各有独立关系表（均含 `scope_id`）
- 链接类型：`related`（关联）、`cross-ref`(交叉引用)、`contradiction`（矛盾）、`query-save`（问答保存）
- 同步机制：所有写操作先写数据库，再同步文件系统；读取优先文件系统，fallback 数据库
- ES 统一索引所有 Wiki 页面（单源检索）

### schema/ — Schema 配置

- 真相来源：MySQL `schema_config` 表（Markdown 内容，7 段固定骨架，版本化）
- `schema/` 目录是从数据库导出的缓存副本，供用户查看

## 5. 存储架构 — StorageProvider 抽象层

文件读写统一走 `StorageProvider` 接口，三种实现可切换：

| 实现 | 场景 | 说明 |
|------|------|------|
| LocalStorageProvider | 本地开发 / 单机部署 | 默认实现，写本地磁盘 |
| NASStorageProvider | 生产 NAS 共享卷 | 多实例共享文件系统 |
| S3StorageProvider | MinIO / S3 兼容存储 | 可选，完整分布式模式 |

通过环境变量 `STORAGE_PROVIDER` 切换（`local` / `nas` / `s3`），路径布局在三种实现下保持一致：`{root}/{scopeId}/{raw|parsed|wiki|assets|schema}/`。

## 6. Harness 引擎 — AI 执行引擎

Harness 编排所有 LLM 驱动的操作。核心组成：

- **编排器**：`IngestOrchestrator`（摄入）、`PipelineOrchestrator`（Lint / 问答保存 / 晋升）
- **Agent**：ParserAgent、AnalyzerAgent、WriterAgent、IndexerAgent、LintAgent、QueryAgent 等，通过 Spring AI ChatClient 调用任意 OpenAI 兼容模型
- **Wiki 工具集**：readFile、writeFile、searchWiki、listPages、getRelatedPages、updateLinks 等，供 Agent 工具调用
- **治理与追踪**：ApprovalService（审批）、ExecutionTracker（执行记录）、SchemaManager / SchemaInjector（Schema 注入与补丁）
- **并发控制**：`LlmConcurrencyBarrier` 全局信号量约束 LLM 并发，避免 Provider 限流
- **多 Provider 路由**：`AiProviderRegistry` + Slot 路由，不同任务（分析 / OCR / 图表识别）可路由到不同 Provider

### 6.1 Ingest — 摄入流水线（编译 + 链接）

由 `IngestOrchestrator` 编排 4 个专业化 Agent，通过共享的 `IngestContext` 数据容器流转中间产物。对外呈现 4 个步骤：`UPLOAD → ANALYZE → WRITE → COMPLETE`。

```
ParserAgent（解析）
  多格式解析（PDF/DOCX/PPT/MD/TXT/图片），OCR、多模态、图表识别
  产物写入 parsed/，按文档结构分块
      ↓
AnalyzerAgent（分析）
  实体识别、关键事实提取、与已有知识网络的关联分析
  短文档单次全文分析；超长文档分块并发分析（受并发屏障约束）
      ↓
WriterAgent（写入）
  Plan-then-Execute：先产出全局写作蓝图（WritingPlan），
  再并行执行摘要页 / 实体页 / 关联页面更新
  WriterOrchestrator 四阶段把关：并行写入 → 确定性合规校验
  → AI 一致性仲裁 → 结构质量校验
      ↓
IndexerAgent（索引）
  交叉引用建立、矛盾检测、ES 批量索引、Schema 补丁建议
```

关键设计：

- **增量编译**：关联页面更新真正读已有页面 → LLM 融合 → 写回，`reference` 页面自动跳过
- **来源追踪**：所有写入同步建立 `wiki_page_source` 关联，汇聚后补偿校验
- **准确性约束**：STRUCTURED（权威性）文档的实体/摘要页中，规则条款与量化指标必须 blockquote 引用原文并标注章节
- **实时进度**：全程 SSE 推送步骤级进度，支持暂停 / 取消

### 6.2 Query — 查询问答（运行时）

Agent 自主导航 Wiki，而非系统预组装上下文：

```
用户提问
  ↓
ChatClient 工具调用（读工具 + 写工具）
  第一步通过 GlobalSummary（从 DB 实时构建的分类骨架）建立全局认知
  ↓
Agent 循环：searchWiki 检索 → readFile 逐页阅读 → getRelatedPages 扩展
  → 综合信息给出带引用的流式答案（三层：Wiki 事实 + AI 解读 + 前瞻推演）
  ↓
对话历史按 session 持久化，支持追问
  ↓
可选：保存为 Wiki 页面 → 走完整编译 Pipeline
  （格式化答案 → 写入页面 → 建立链接 → 矛盾检测）
```

### 6.3 Lint — 知识体检（静态分析）

诊断 + 修复 + 回写的流水线，步骤错误隔离不阻断整体：

1. **诊断探查（PROBE_AND_VALIDATE）**：LintAgent 单次 LLM 调用读取 GlobalSummary + 上次诊断 + Schema 规则，输出 5 种诊断类型（orphan / stale / missing_crossref / conflict / gap）；SQL 安全网对 orphan / stale 做零 Token 确定性检测，两路结果交叉验证合并去重
2. **自动修复**：孤立页补链接、缺失交叉引用补链接、矛盾创建 contradiction 链接、概念缺口生成新页面
3. **健康状态回写**：按 open finding 类型判定每页 `health_status`（healthy / needs-update / has-problems / conflict-warning），同步 ES
4. **建议与 Schema 补丁**：产出修复建议（CONFIRM 审批），并提议 Schema 分类体系增补

**反馈学习闭环**：用户对诊断结果的反馈（接受/忽略/修改）持久化；同一类型被连续忽略达阈值后自动降低探查敏感度。

**定期执行**：体检由 `@Scheduled` 后台定期运行（个人库每周、团队库每天），结果即时反映到页面健康指示器。

### 6.4 Promote — 知识晋升

个人知识库的成熟知识被 AI 提炼到团队知识库，形成知识正向流动。核心原则：**鼓励分享、荣誉激励、事后退出**。

```
SCAN_CANDIDATES  扫描候选（open 可见 + 多来源支撑 + 健康）
MATCH_SCHEMA     与团队 Schema 分类体系对齐
REQUEST_PERSONAL_CONSENT  页面作者逐一授权（CONFIRM）
REQUEST_TEAM_APPROVAL     团队管理者审批（REVIEW）
DISTILL          AI 蒸馏：提炼团队级知识，不搬运原文，移除个人语境
WRITE_PROMOTED_PAGE       写入团队 scope，标注贡献者（荣誉溯源）
UPDATE_LINKS     建立与团队已有页面的交叉引用
```

晋升由用户手动触发；用户可随时将页面标记为 private 退出共享，系统自动召回已晋升内容。

## 7. 知识矛盾检测与处置

矛盾检测内建在三大流程中，目标是零人工阻断：

- **编译时感知**：Ingest 的分析与写作环节输出冲突标注，命中页面标记 `conflict-warning`
- **消费时透明**：Query 回答自动并排呈现矛盾双方观点
- **体检时治理**：Lint 自动创建 contradiction 链接
- **元层面演化**：Schema 定义裁决策略（保留双方 / 来源优先级 / 最新优先等），随知识库演化调整

矛盾类型：价值冲突、事实冲突、定义冲突、时序冲突。

## 8. Schema 治理

Schema 是每个 scope 的"构建配置宪法"，约束所有 AI 行为：

- **7 段固定骨架**：Markdown 格式，骨架破坏由 `SchemaSkeletonValidator` 拒绝
- **强制注入**：所有 LLM 调用经 `SchemaInjector` 注入当前 Schema 规则（剥离变更日志段以省 Token）
- **冷启动引导**：新 scope 通过 SchemaBootstrapAgent 三轮引导对话产出 v1，落库前禁止任何操作
- **版本化**：`schema_config_version` 记录历史链，页面记录遵循的 Schema 版本
- **无手动直编**：前端只提供只读渲染 + 版本切换 + AI 对话调整
- **SchemaLint 独立体检**：定期扫描膨胀、事实偏离、规则冲突、僵尸规则；诊断处置策略从 Schema 读取，不硬编码

## 9. 安全架构 — Scope 隔离

知识库范围（Scope）是权限与数据隔离的基本单元（一期个人库 scope_id = user_id，团队库为独立 scope）：

- **数据隔离**：所有 Wiki/Harness 数据表与关系表强制 `scope_id` 过滤；文件路径包含 scopeId，禁止路径遍历
- **权限模型**：角色-权限体系，团队 scope 支持 owner/admin/member 与加入申请工作流
- **AI 操作隔离**：所有 LLM 工具调用校验目标路径归属当前 scope
- **认证**：Spring Security + JWT

## 10. 前端架构

Vue 3 + TypeScript + Vite + Element Plus + Pinia，详见 [DESIGN.md](DESIGN.md)。

- **页面结构**：3 项主导航（知识库、搜索问答、设置管理）+ Wiki 页面阅读/编辑、摄入、体检、图谱、执行记录等视图
- **状态管理**：Pinia stores — 认证、当前 scope、摄入进度、任务进度、活动中心、语言等
- **实时性**：摄入/问答/编辑均通过 SSE 流式呈现
- **国际化**：vue-i18n，中 / 英双语完整覆盖

## 11. 部署形态

| 模式 | 组成 | 适用 |
|------|------|------|
| 极简模式（默认） | MySQL + Elasticsearch + App + Web UI | 个人 / 小团队，`docker-compose.yml` |
| 完整分布式 | 增加 MinIO + RocketMQ | 生产多实例，`docker-compose.full.yml` |
| 本地开发 | MySQL/ES 外部提供，后端 `mvn` + 前端 `npm run dev` | 开发调试，见 [AGENTS.md](AGENTS.md) |
