# LLM Wiki — 架构蓝图

> **English Summary**
>
> LLM Wiki is a three-layer knowledge management system where an AI execution engine (Harness) incrementally builds and maintains a persistent wiki from curated source documents.
>
> **Core metaphor — Knowledge Compilation:** Raw documents (source code) are compiled by AI agents into interlinked wiki pages (object code), with cross-references (linker), health checks (static analysis), and a versioned Schema (build config) governing AI behavior.
>
> **Architecture highlights:**
> - DDD-layered Spring Boot 3 backend (Web → Biz → Domain → Common/DAL)
> - 4-agent ingestion pipeline: Parser → Analyzer → Writer → Indexer (parallel execution with LLM concurrency barriers)
> - Schema governance: 7-section skeleton, versioned patches, compliance validation, bootstrap advisor
> - Storage abstraction: Local / NAS / S3 (MinIO) via `StorageProvider` interface
> - Elasticsearch full-text indexing with bulk operations and retry queue
> - SSE real-time pipeline progress tracking
> - Multi-tenant scope isolation (personal + team knowledge bases)
>
> **Key design rules:** (1) Incremental compilation — ingest updates existing pages, not just creates new ones. (2) Strict scope isolation — all tables and file paths include scopeId. (3) Accuracy over richness — never fabricate content for visual appeal. (4) Full traceability — every artifact links back to its source document.
>
> *The full document below is in Chinese. See [README.md](README.md) for an English overview.*

> **文档体系**：本项目有三份核心文档，各司其职：
> - **[ARCHITECTURE.md](ARCHITECTURE.md)**（本文档）= 系统架构蓝图 — 系统怎么设计、组件怎么协作
> - **[DESIGN.md](DESIGN.md)** = UI 设计与用户体验规范 — 界面怎么画、交互怎么定义
> - **[AGENTS.md](AGENTS.md)** = 编码与实现指南 — 如何写代码、项目怎么跑
>
> **推荐阅读顺序**：ARCHITECTURE.md（理解系统全貌）→ DESIGN.md（理解 UI 规范）→ AGENTS.md（开始编码）
>
> **冲突解决规则**：当文档之间存在不一致时，以实际代码实现为最终真相来源。

## 产品第一性原理

Wiki 的本质是**知识的持久化、组织化和可演化性**。AI 是辅助工具，不是产品本身。

**核心原则：**
- **管理要好用** — 知识库结构清晰合理，维护方便
- **查看要专业** — 知识阅读体验清晰、专业、可信
- **AI 是增强** — AI 让知识更好，但不改变 Wiki 的本质
- **人指挥、AI 执行、知识沉淀** — 用户不直接编辑 Wiki，而是通过"请求修改"下指令；好答案存入 Wiki 而非消失在对话历史中；Schema 由用户和 AI 共同演化

**功能分级：**
- 核心功能（加强）：知识阅读、知识导航、知识关系、知识可信度
- 辅助功能（弱化）：添加资料（全局按钮，内嵌来源管理）、知识问答（搜索栏升级）、知识体检（后台定期运行）
- 非核心功能（最弱化）：执行记录、系统配置、Token 监控（折叠在设置管理中）

**导航结构：** 3 项导航 — 知识库（首页）、搜索问答、设置管理（折叠子菜单：执行记录、系统配置、Token监控）

## 知识编译 — 系统的核心隐喻

LLM Wiki 对知识的处理过程，本质上是让 AI 对知识进行了一次"编译"。三个核心操作都是基于 AI 完成的知识编译过程：

| 编译概念 | LLM Wiki 对应 | 说明 |
|---------|-------------|------|
| **源码** (`.c`, `.java`) | Raw 来源 (`.pdf`, `.docx`, `.md`) | 不可变，是真相来源 |
| **结构化源码** | parsed 层（标准 Markdown） | AI 可读的工作副本，按章节边界保留语义 |
| **编译器** | AI (DashScope + Pipeline) | 把原始信息转化为结构化知识 |
| **目标码** (`.o`, `.class`) | Wiki 页面 (`.md`) | AI 产出物，人只读不直接改 |
| **符号表 / Source Map** | ES Wiki 索引 + `wiki_page_source` | 编译产物↔原文位置的回溯映射 |
| **链接器** | `UPDATE_RELATED` / `UPDATE_LINKS` | 交叉引用，页面间建立关系 |
| **调试信息** | `execution` 表 | 操作记录，可回溯 |
| **构建规范 / 编译器配置** | Schema（AGENTS.md / DESIGN.md / ARCHITECTURE.md） | 契约层，定义编译器的行为与产物约束；与 LLM 共同演化 |
| **增量编译** | Ingest 更新已有页面 | 只重新编译受影响的部分 |
| **运行** | Query | 对编译产物执行查询 |
| **静态分析** | Lint | 检查矛盾、孤立、缺口 |

**核心洞察：**

- **Ingest = 编译 + 链接**：AI 把 Raw "编译"成 Wiki 页面，再"链接"到已有知识网络。**增量编译是关键** — 不是每次全量重编，而是只 touch 受影响的页面。一个来源可能触及 10-15 个 Wiki 页面，必须读取已有页面内容、分析增量影响、然后更新页面。
- **Query = 运行时**：对"编译产物"（Wiki）执行查询，综合多个页面给出答案。好答案存回 Wiki 相当于运行时产生的新知识反过来成为知识库的一部分（类似 JIT 编译的热点代码回写）。答案必须附引用，标注信息来源页面。
- **Lint = 静态分析 + 诊断探查**：AI 探查 GlobalSummary（通过 `GlobalSummaryService` 从 DB 构建，含分类骨架+链接图谱+最近活动，**替代已废弃的 FS index.md + log.md**）识别 5 种诊断类型（矛盾/孤立/缺口/过时/缺失交叉引用），SQL 安全网兜底确定性检测，交叉验证合并去重。检查结果必须回写到各页面的健康指示器，用户反馈驱动探查敏感度自动调节（反馈学习闭环）。

**编译隐喻推导出的四条硬性规则：**

1. **编译器必须完整** — Ingest 必须实现增量编译（更新已有页面，不是只创建新页面），必须有来源-页面追踪，链接器必须真正执行链接
2. **项目隔离必须严格** — scope 隔离就像不同的项目各有自己的 build 目录，关系表必须有 scope_id，文件系统路径必须包含 scopeId，raw 目录绝对不可写
3. **准确性优先于一切** — 编译产物的准确性是不可妥协的底线，优先级高于展示丰富度、格式规范和视觉美观。当富元素（Mermaid 图、ECharts 图表等）的使用需要 LLM 从源文档中"推断"或"构造"信息时，宁可不用也不能编造。一个简短但 100% 准确的页面，比一个长篇但 20% 编造的页面有价值得多
4. **编译必须可追溯** — 编译过程允许有损（格式转换、内容综合），但每一段编译产物必须能回溯到原文。Raw 层是 source of truth。Wiki 层包含三种页面类型：摘要页（`summary`，跨源综合）、实体页（`entity`，跨源综合）、参考页（`reference`，单源高保真）。ES 统一索引所有 Wiki 页面。对于结构化文档，参考页保留章节级原文和结构化摘要，确保高信息密度文档的编译产物保真度。parsed 层是纯编译中间产物，不被索引和搜索

## 系统总览

LLM Wiki 是一个三层知识管理系统，AI 执行引擎（Harness）从用户精选的源文档中增量构建和维护持久化 Wiki。系统采用 DDD 领域驱动设计分层架构，代码放置在 `llmwiki/app/` 目录下。

```
┌─────────────────────────────────────────────────────────────────┐
│                        用户界面                                  │
│  Vue 3 SPA — Wiki 浏览、摄入控制、查询对话、                     │
│  健康检查报告、系统管理、图谱可视化                               │
├─────────────────────────────────────────────────────────────────┤
│                     REST API 层（app/web）                        │
│  Spring Boot Controllers — /api/wiki, /api/ingest,              │
│  /api/query, /api/lint, /api/system, /api/harness              │
├─────────────────────────────────────────────────────────────────┤
│                     业务编排层（app/biz/service）                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐          │
│  │ 摄入     │ │ 查询     │ │ 健康检查 │ │ 认证     │          │
│  │ Service  │ │ Service  │ │ Service  │ │ Service  │          │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘          │
│         │          │          │                                  │
│         └──────────┼──────────┘                                  │
│                    │                                             │
│         ┌─────────────────────────┐                             │
│         │   Harness 引擎          │                             │
│         │   (domain/service)      │                             │
│         └─────────────────────────┘                             │
│                    │                                             │
│         ┌─────────────────────────┐                             │
│         │   Wiki 服务             │                             │
│         │   (domain/service)      │                             │
│         └─────────────────────────┘                             │
├─────────────────────────────────────────────────────────────────┤
│                     数据层                                       │
│  ┌──────────┐ ┌──────────┐ ┌────────────┐ ┌────────────┐       │
│  │ MySQL   │ │ NAS/S3   │ │ Elastic-   │ │ DashScope  │       │
│  │ (真相)  │ │ (文件)   │ │ search     │ │ (AI集成)   │       │
│  │         │ │          │ │ (搜索索引) │ │            │       │
│  └──────────┘ └──────────┘ └────────────┘ └────────────┘       │
└─────────────────────────────────────────────────────────────────┘
```

## 存储架构 — StorageProvider 抽象层

系统通过 `StorageProvider` 接口抽象文件存储，支持多实例部署和云原生迁移：

```
StorageProvider（接口）
  ├── NASStorageProvider    — 默认生产环境，NAS 共享卷
  │     配置：llmwiki.storage.provider=nas
  │     存储：NAS 挂载路径 wiki-data/{scopeId}/
  │     特性：多节点共享读写、FileLock 并发安全、启动健康检查
  │     适用：标准分布式部署、LLM 工具直接读文件
  │
  ├── LocalStorageProvider  — 开发/测试环境，本地磁盘
  │     配置：llmwiki.storage.provider=local
  │     存储：本地 wiki-data/{scopeId}/ 目录
  │     特性：单机、无共享、matchIfMissing=true 开发时自动激活
  │
  └── S3StorageProvider     — 可选生产环境，MinIO / 任意 S3 兼容服务
        配置：llmwiki.storage.provider=s3
        存储：S3 bucket llmwiki-{scopeId}
        支持：MinIO、阿里云 OSS、AWS S3、腾讯云 COS
        适用：云原生 Kubernetes 无 NAS 场景
```

**切换只需改配置，业务代码零改动。**

### 配置项

```yaml
llmwiki:
  storage:
    provider: nas | local | s3    # 存储提供商选择（生产默认 nas）
    nas:
      path: /data/llmwiki/wiki-data    # NAS 挂载路径（所有节点相同）
      lock-enabled: true               # FileLock 并发写安全
    s3:
      endpoint: http://localhost:9000  # MinIO/S3 endpoint
      access-key: minioadmin           # S3 access key
      secret-key: minioadmin           # S3 secret key
      region: us-east-1                # S3 region
      bucket-prefix: llmwiki           # bucket 名称前缀
      path-style: true                 # MinIO 使用 path-style
      public-url:                      # 文件公开访问 URL（可选）
```

### NAS 文件路径映射

| 数据类型 | NAS 路径 | 说明 |
|---------|---------|------|
| 原始来源 | {nas-path}/{scopeId}/raw/{uuid}-{filename} | **不可变**，AI 只读不写；UUID 前缀避免同名文件冲突 |
| 解析产物 | {nas-path}/{scopeId}/parsed/{sourceId}.parsed.md | PARSE_DOCUMENT 步骤产出，与 raw 隔离，保护 raw 不可变 |
| Wiki 页面 | {nas-path}/{scopeId}/wiki/pages/{title}.md | AI 产出物，增量编译更新 |
| Wiki 索引 | ~~{nas-path}/{scopeId}/wiki/index.md~~ **已废弃**：索引由 GlobalSummaryService 从 DB 实时构建 | 不再维护 FS 副本 |
| Wiki 日志 | ~~{nas-path}/{scopeId}/wiki/log.md~~ **已废弃**：执行记录直接查 execution 表 | 不再维护 FS 副本 |
| 资源文件 | {nas-path}/{scopeId}/assets/{filename} | Wiki 页面引用的图片等媒体 |
| Schema 缓存 | ~~{nas-path}/{scopeId}/schema/CLAUDE.md, schema/AGENTS.md~~ **已废弃**：DB schema_config 表是唯一 source of truth | 不再维护 FS 副本 |

### S3 对象路径映射

| 数据类型 | S3 bucket | S3 key |
|---------|-----------|--------|
| 原始来源 | llmwiki-{scopeId} | raw/{uuid}-{filename} |
| 解析产物 | llmwiki-{scopeId} | parsed/{sourceId}.parsed.md |
| Wiki 页面 | llmwiki-{scopeId} | wiki/pages/{title}.md |
| Wiki 索引 | ~~llmwiki-{scopeId}~~ ~~wiki/index.md~~ **已废弃** | 不再维护 FS 副本 |
| Wiki 日志 | ~~llmwiki-{scopeId}~~ ~~wiki/log.md~~ **已废弃** | 不再维护 FS 副本 |
| 资源文件 | llmwiki-{scopeId} | assets/{filename} |
| Schema 缓存 | ~~llmwiki-{scopeId}~~ ~~schema/CLAUDE.md, schema/AGENTS.md~~ **已废弃** | 不再维护 FS 副本 |

### scope 目录完整结构

```
wiki-data/{scopeId}/
├── raw/              # 原始来源（不可变），命名格式 {uuid}-{filename} 避免冲突
├── parsed/           # PARSE_DOCUMENT 解析产物，与 raw 隔离保护不可变性
├── wiki/             # LLM 生成的 Wiki 页面
│   └── pages/        # 各个主题、实体、概念页面
├── assets/           # Wiki 页面引用的图片等媒体资源
└── schema/           # ~~Schema 文件缓存~~ **已废弃**：DB schema_config 表是唯一 source of truth
```

**目录职责红线：**

- `raw/` — 不可变 source of truth。AI 只读不写，不删；同名文件通过 UUID 前缀隔离；解析产物不可混入
- `parsed/` — **结构化源层**（Structured Source Layer）。二进制文档（PDF/DOCX/PPT）经 Python 解析后的标准 Markdown，是 AI 可读的工作副本。承认格式转换有损（复杂表格、图片语义可能丢失），但按文档自然结构（章节边界）保留完整语义。ES 按章节索引 parsed 内容，用户搜索可直接命中原文精确位置。与 raw 严格隔离，不可回写
- `wiki/` — 编译产物。AI 写入和维护，人在 UI 层只读，不提供直接编辑入口。**Wiki 层只做"跨源综合"**（摘要、实体、概念、关联更新），不承担"原文保真"职责
- `assets/` — 资源层。Wiki 页面引用的图片等媒体文件
- `schema/` — ~~配置缓存~~ **已废弃**。DB `schema_config` 表是唯一 source of truth，`SchemaInjector` 直接从 DB 加载并缓存在内存中。文件系统中的残留文件为历史遗留，不再更新

### FS → DB 概念替代映射

原始设计中 4 个 FS 文件承担的系统能力，已全部迁移至 DB 实现。以下是逐项替代关系：

| 废弃文件 | 原始目标能力 | DB 替代实现 | 关键代码位置 |
|---------|-----------|-----------|------|
| ~~index.md~~ | 按分类列出所有页面+一行摘要，每次 Ingest 后更新，Query 时 LLM 先读 index 建立全局认知 | `GlobalSummaryService` 从 `wiki_page` 表实时构建分类骨架 + 链接图谱 + 来源目录 + 最近活动，`toCompactPrompt()` 注入 systemPrompt | `GlobalSummaryService.build()` / `toCompactPrompt()` |
| ~~log.md~~ | 按时间顺序追加 ingests/queries/lint 记录，LLM 了解"最近做了什么"，避免重复告警 | `execution` + `execution_step` 表记录操作历史，`lint_finding` 表 + `previousFindings` 查询实现去重 | `ExecutionTracker`（写入）/ `ExecutionHistoryService`（读取） |
| ~~CLAUDE.md~~ | 告诉 LLM wiki 如何组织、约定是什么、遵循什么工作流，用户和 LLM 共同演进 | `schema_config` 表（Markdown + 结构化 JSON 双存储），`SchemaInjector` 按操作类型选择性注入 section 1-6，补丁提案 → Gatekeeper 双审 → 人工审批闭环 | `SchemaInjector.prepend()` / `SchemaPatchProposer` |
| ~~AGENTS.md~~ | 同 CLAUDE.md 机制，面向编程 Agent | `agent_schema` configKey 从未被使用，磁盘上 0 个文件，**无需替代** | — |

**替代后的能力增强：**
- `GlobalSummaryService` 新增链接图谱统计、矛盾链接警告、来源文档目录（原始 index.md 不具备）
- `execution` 表新增步骤级粒度（每步 input/output tokens/耗时）、scope 隔离（原始 log.md 不具备）
- `SchemaInjector` 新增按操作类型选择性注入 section、内存缓存、版本历史链（原始 CLAUDE.md 不具备）

**已知的能力差异（已确认不影响系统）：** 外部 AI 工具（如 Claude Desktop）直接读取 FS 上的 CLAUDE.md — 多 scope Web 架构不兼容此用法，如需外部接入可通过 API 按需导出

**已废弃但未实现的规划能力（FS 和 DB 均未实现，属于未来规划）：**
- 增量对比扫描：index.md 快照比对触发重点检查（Lint 已有 `DeltaLintContext.findChangedPages`，但未与 GlobalSummary 联动）
- 健康趋势可视化：前端展示连续 N 次健康分布（未实现）

### 四大核心概念的 DDD 领域服务

原始设计中 4 个 FS 文件对应的系统能力，已演进为 4 个内聚的领域服务：

| 领域服务 | 替代原始文件 | 职责 | 位置 |
|---------|-----------|------|------|
| `GlobalSummaryService` | ~~index.md~~ | 构建全局摘要（分类骨架 + 链接图谱 + 来源目录），只读 Wiki 域数据 | `harness/` |
| `ExecutionHistoryService` + `ExecutionTracker` | ~~log.md~~ | 执行历史的统一读写入口，封装所有 `execution` 表查询 | `harness/tracker/` |
| `SchemaManager` + `SchemaInjector` | ~~CLAUDE.md~~ | Schema CRUD + 版本管理 + Prompt 注入 | `harness/governance/` |
| （已废弃） | ~~AGENTS.md~~ | 从未实际使用 | — |

**governance/ 子包结构：**

| 子包 | 职责 | 核心类 |
|------|------|--------|
| `governance/`（根） | Schema CRUD + 补丁 + 运行时注入 | `SchemaManager`, `SchemaInjector`, `SchemaPatchService`, `SchemaPatchProposer`, `ApprovalService`, `RateLimitService`, `TokenUsageMonitor`, `SchemaLintScheduler`, `SystemConfigService` |
| `governance/parser/` | Schema 解析与渲染 | `SchemaStructuredParser`, `SchemaSection6Parser`, `SchemaMarkdownRenderer` |
| `governance/validation/` | Schema 合规校验 | `SchemaSkeletonValidator`, `SchemaComplianceChecker`, `SchemaConsistencyChecker` |
| `governance/bootstrap/` | Schema 冷启动引导 | `SchemaBootstrapService`, `BootstrapAdvisor`, `ParadigmCatalog`, `SchemaJsonSynthesizer` |

**关键架构约束：**
- `GlobalSummaryService` 不直接查 `execution` 表，通过 `ExecutionHistoryService.getRecentActivitySummary()` 获取最近活动
- 所有 `execution` 表读取必须通过 `ExecutionHistoryService`，禁止绕过领域服务直接注入 `ExecutionMapper`
- `ExecutionTracker` 负责写入（create/update/fail），`ExecutionHistoryService` 负责读取（query/count/find）

### NASStorageProvider 并发安全机制

多节点共享 NAS 时，同一文件可能被多个节点并发写入。`NASStorageProvider` 通过以下机制保证安全：

1. **FileLock 文件锁**：每次 `write` 操作前获取 `RandomAccessFile` 的 `FileLock`，写完成后释放。保证同一时刻只有一个节点写同一文件。
2. **原子目录创建**：`Files.createDirectories` 本身是原子操作。
3. **启动健康检查**：应用启动时对 NAS 路径执行写入+读取+删除验证，确认挂载可用。
4. **读操作无锁**：`read` 操作不需要文件锁，POSIX 文件系统保证读取一致性（文件写入完成后才可见）。

### 高可用能力

| 存储模式 | 多实例部署 | 数据持久性 | 扩展方式 | 运维复杂度 |
|---------|-----------|-----------|---------|-----------|
| LocalStorageProvider | ❌ 不支持 | 单机磁盘 | 不支持 | 低 |
| NASStorageProvider | ✅ 支持 | NAS 存储 | NAS 扩容 | 低 |
| S3StorageProvider (MinIO 单机) | ✅ 支持 | MinIO 磁盘 | MinIO 分布式升级 | 中 |
| S3StorageProvider (云 S3) | ✅ 支持 | 云服务保证 | 云服务自动 | 中 |

**推荐选择**：标准分布式部署用 NAS（运维最简、LLM 读取最快、文件即写即读）；云原生无 NAS 场景用 S3。

## 四层数据架构

系统采用四层分离设计。**核心原则：Raw 层是 source of truth，parsed 层是纯编译中间产物（AI 可读的结构化源层），Wiki 层是 LLM 编译产物（含摘要页、实体页、参考页三种类型），数据库是元数据真相来源，ES 是 Wiki 统一检索引擎。**

### 第一层：原始来源（不可变）

用户上传的源文档。系统绝不修改。

- 存储：NAS/文件系统 `wiki-data/{scopeId}/raw/` 目录（LLM 读取时使用），文件名格式 `{uuid}-{originalName}` 避免同名冲突
- 元数据真相来源：MySQL `source` 表（name, file_path, format, size, status, scope_id, upload_user_id, created_at）
- 支持格式：Markdown (.md)、PDF (.pdf)、纯文本 (.txt)、Word (.docx)、图片 (.png/.jpg/.webp)
- 处理：Harness 读取源内容，提取文本，传递给 LLM 分析
- **不可变性硬性约束**：
  - WriteFileTool / StorageProvider 必须拒绝对 `raw/` 的任何写操作（除上传时的初写）
  - PARSE_DOCUMENT 解析产物写入 `parsed/` 而非 `raw/`
  - 重新摄入创建新来源记录（新 UUID），不覆盖原文件
  - 来源删除是逻辑删除（source.status = deleted），文件仍保留供溯源

### 第二层：结构化源层（parsed，AI 可读的工作副本）

原始文档经 Python 解析器转换后的标准 Markdown。这是 AI 可读的工作副本，按文档自然结构（章节边界）保留完整语义。

- 存储：NAS/文件系统 `wiki-data/{scopeId}/parsed/` 目录，文件名 `{sourceId}.parsed.md`
- 真相来源：不是独立的真相来源，是 Raw 层的结构化派生。当 Raw 删除时，parsed 随之失效
- 承认有损：复杂表格合并单元格、图片语义（流程图/架构图）、精确分页信息可能在格式转换中丢失。但按文档自然结构保留完整语义，不丢失条款/段落内容
- 不可回写：parsed 内容不可被 LLM 修改，与 raw 严格隔离
- **纯中间产物**：parsed 层不被 ES 索引，不被 Query 搜索。仅作为 Ingest 编译原料和参考页生成素材
- 章节编译模式（CHAPTER_BASED / LARGE_POLICY）下，parsed 层的章节结构被 `WriterAgent` 用于生成参考页（`page_type=reference`，LLM 结构化摘要 + 原文引用），参考页作为一等公民 Wiki 页面被 ES 索引和检索

### 第三层：Wiki（LLM 跨源综合产物，可变）

Harness 生成和维护的 Markdown 文件。这是随时间复利增长的知识产物（编译产出）。Wiki 层包含三种页面类型：
- **摘要页**（`page_type=summary`）：跨源综合，高度浓缩，每个来源一份
- **实体页**（`page_type=entity`）：跨源综合，围绕一个实体/概念
- **参考页**（`page_type=reference`）：单源高保真，为结构化文档的每个章节生成 LLM 结构化摘要 + 原文引用，确保高信息密度文档的编译产物保真度。**参考页只读锁定**：不可被其他文档 Ingest 的 `UPDATE_RELATED` 步骤更新（`updateRelatedPage` 自动跳过 `page_type=reference`），仅在源文档被重新 Ingest 时更新

**STRUCTURED 文档双层编译**：对于权威性文档（法律合同、监管规定、公司制度、操作规程、产品告知书等），`DocumentStructureAnalyzer` 通过 `AUTHORITATIVE_TITLE_PATTERN` 语义匹配标题中的权威性关键词，优先判定为 `STRUCTURED` 类型。章节编译模式并行生成参考页（原文镜像）+ 摘要/实体页（受约束编译）。**blockquote 引用约束**：当源文档为 STRUCTURED 时，entity/summary 页面中的规则条款、定义表述、量化指标必须使用 blockquote（`>`）引用原文，不得改写，每条引用后标注来源章节。

**Lint 差异化治理**：
- `reference` 页面不参与 stale 时间推测和 orphan 检测（SQL 层过滤 `page_type != 'reference'`）
- 同源参考页对之间的冲突提升为 P0 级（`detectReferenceConflictsBySql`），意味着源文档内部存在内容矛盾或结构问题
- 通用 conflict 检测排除 reference 页面（`page_type != 'reference'`），避免与专用检测重复

- 存储：NAS/文件系统 `wiki-data/{scopeId}/wiki/` 目录（LLM 读取时使用）
- 元数据真相来源：MySQL `wiki_page` 表（title, file_path, category, summary, scope_id, source_count, health_status, page_type, created_at, updated_at）
- 标签真相来源：MySQL `wiki_page_tag` 表（page_id, tag, scope_id）
- 关键词真相来源：MySQL `wiki_page_keyword` 表（page_id, keyword, scope_id）
- 链接图谱真相来源：MySQL `wiki_page_link` 表（from_page_id, to_page_id, link_type, scope_id）
  - linkType 枚举值：`related`（普通关联）、`query-save`（问答保存）、`cross-ref`（交叉引用）、`contradiction`（知识矛盾）
- 来源关联真相来源：MySQL `wiki_page_source` 表（page_id, source_id, scope_id）
- 结构：
  - Wiki 索引已不再维护为 FS 文件（~~index.md~~），转为 `GlobalSummaryService` 从 `wiki_page` 表实时构建分类骨架(~800 tokens)
  - Wiki 日志已不再维护为 FS 文件（~~log.md~~），执行回溯直接查 `execution` 表
  - `pages/` — 各个主题、实体、概念、对比页面
- 所有权：Harness 写入和维护所有 Wiki 内容。用户阅读；LLM 写入。UI 不提供直接编辑入口，用户通过"请求修改"下指令
- 同步机制：所有写操作先写数据库，再同步到文件系统。LLM 工具 readFile 优先读文件系统缓存（快），fallback 到数据库
- **增量编译硬性约束**：
  - Ingest 的 `UPDATE_RELATED` 步骤必须真正写入（读已有页面 + LLM 融合增量 + 写回），不仅是生成建议文本
  - `WRITE_SUMMARY` 写入页面时必须同步写入 `wiki_page_source` 关联
  - `index.md` 已废弃：索引不再作为 FS 文件维护，由 `GlobalSummaryService` 从 DB 实时构建

### 第四层：Schema（配置）

告诉 Harness 如何运作的系统配置。由用户和系统随时间共同演进。

- 真相来源：MySQL `schema_config` 表（config_key, config_value, config_group, scope_id）
- **config_value 存储 Markdown 格式内容** — 用户在 UI 的 Markdown 编辑器中编辑，保存到数据库
- 文件系统缓存：`wiki-data/{scopeId}/schema/` 目录（NAS/本地，取决于配置）
  - `CLAUDE.md` — 从数据库生成，告诉 LLM 如何运作（Prompt 模板、工作流约定、页面命名规则）
  - `AGENTS.md` — 从数据库生成，告诉 Agent 可用工具和行为规范
- 同步机制：每次 Schema 变更 → 异步生成文件系统缓存。LLM 读取时优先用文件系统缓存。
- 内容：
  - 每种操作类型的 Prompt 模板（Ingest、Query、Lint）
  - Pipeline 步骤定义和排序
  - 每步的审批级别设置（AUTO / CONFIRM / REVIEW）
  - 每种操作的 LLM 模型选择
  - Wiki 结构约定（页面命名、分类体系、链接格式）
  - 工具可用性配置

## Harness 引擎 — 核心架构

Harness 是编排所有 LLM 驱动操作的 AI 执行引擎。它基于 spring-ai 的 Agent Framework 和 Graph API 构建，配合 Wiki 专属的工具实现和治理层。

### Harness 组件

```
┌──────────────────────────────────────────────────────────┐
│                    Harness 引擎                            │
│                                                           │
│  ┌──────────────────────────────────────────────────┐    │
│  │              操作入口点                             │    │
│  │  IngestService  QueryService  LintService         │    │
│  └──────────────────────────────────────────────────┘    │
│  ┌──────────────────────────────────────────────────┐    │
│  │           spring-ai 运行时                         │    │
│  │                                                    │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌──────────┐  │    │
│  │  │ Graph API   │  │ ChatClient   │  │ Context   │  │    │
│  │  │ (Pipeline)  │  │ 工具调用     │  │ Eng.      │  │    │
│  │  │             │  │ (Query)     │  │ (HITL,    │  │    │
│  │  │ 条件路由    │  │ + Tool      │  │ 压缩,     │  │    │
│  │  │ 状态管理    │  │ Calling     │  │ 重试)     │  │    │
│  │  └─────────────┘  └─────────────┘  └──────────┘  │    │
│  │                                                    │    │
│  │  ┌─────────────────────────────────────────────┐  │    │
│  │  │     OpenAI ChatModel (DashScope兼容模式)      │  │    │
│  │  │  spring-ai-starter-model-openai               │  │    │
│  │  │  base-url: dashscope.aliyuncs.com/compatible  │  │    │
│  │  │  当前模型: deepseek-v4-flash                        │  │    │
│  │  └─────────────────────────────────────────────┘  │    │
│  └──────────────────────────────────────────────────┘    │
│                    │                                      │
│  ┌──────────────────────────────────────────────────┐    │
│  │              Wiki 工具注册表                        │    │
│  │  readFile  writeFile  searchWiki  listPages       │    │
│  │  getRelatedPages  updateLinks                     │    │
│  │  readImage  extractMetadata                       │    │
│  └──────────────────────────────────────────────────┘    │
│                    │                                      │
│  ┌──────────────────────────────────────────────────┐    │
│  │           治理与追踪                               │    │
│  │  ApprovalService  ExecutionTracker  SchemaManager │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

### 摄入 Pipeline — 多 Agent 架构

摄入 Pipeline 采用**多 Agent 编排架构**，由 `IngestOrchestrator` 编排 4 个专业化 Agent，每个 Agent 负责编译链的一个阶段。Agent 间通过共享的 `IngestContext` 传递数据，实现数据驱动流转而非调用传递。

```
┌─────────────────────────────────────────────────────────────┐
│                    IngestOrchestrator                        │
│   (编排层：管理 Agent 依赖图、并发控制、进度追踪)              │
│                                                              │
│   ┌──────────┐   ┌──────────┐   ┌──────────────┐            │
│   │ Parser   │──>│ Analyzer │──>│  Writer       │            │
│   │  Agent   │   │  Agent   │   │    Agent      │            │
│   │(解析+读取│   │(分析+合并│   │(摘要+实体页+  │            │
│   │ +分块)  │   │+元数据)  │   │ 关联页面更新) │            │
│   └──────────┘   └──────────┘   └──────────────┘            │
│                                              │               │
│                    ┌──────────────────────────┼──────┐        │
│                    │       Indexer Agent       │      │        │
│                    │(索引+链接+日志+补丁)       │      │        │
│                    └──────────────────────────┘      │        │
│                                                      │        │
│   数据流转：IngestContext（共享数据容器）              │        │
│   • sourceContent → chunks → mergedAnalysis          │        │
│   → metadataJson → summaryPage + entityPages         │        │
│   + updatedPages → log + index                       │        │
└─────────────────────────────────────────────────────────────┘
```

**Agent 职责划分**：

| Agent | 原步骤 | 核心职责 | AI调用特点 |
|-------|--------|---------|-----------|
| ParserAgent | PARSE_DOCUMENT + READ_SOURCE + SPLIT_CHUNKS | 文件解析、内容读取、智能分块 | 无 AI 调用，纯 IO/计算 |
| AnalyzerAgent | ANALYZE_CHUNKS + MERGE_RESULTS + EXTRACT_METADATA | chunk 并行分析、结果合并、元数据提取 | 8 线程并行池，Map-Reduce |
| WriterAgent | WRITE_SUMMARY + WRITE_ENTITY_PAGES + UPDATE_RELATED | 摘要页生成、实体页生成、参考页批量生成、关联页更新 | Plan-then-Execute 并行（WritingPlan 蓝图 + CompletableFuture），受 LlmConcurrencyBarrier 约束 |
| IndexerAgent | UPDATE_LINKS + PROPOSE_SCHEMA_PATCH | 链接建立、Schema 补丁 | 混合（部分 AI + 部分 IO） |

**ExecutionStrategy 动态化**：`IngestionStrategyAdvisor.selectStrategy(DocumentProfile)` 根据文档特征自动选择 5 种 Preset（COMPACT / NARRATIVE / TECHNICAL_RICH / CHAPTER_BASED / LARGE_POLICY），再调用 `adjustForProfile()` 基于模型上下文窗口（1.5M 字符，85% 安全比）动态计算 `QualityTier`（FULL_CONTEXT / BATCH_OPTIMIZED / SAMPLED）和各参数上限。参数上限包含 `maxAnalysisCharsEntity`（实体页分析结果裁剪上限，默认 8000，FULL_CONTEXT 模式 20000）和 `maxAnalysisCharsRelated`（关联页分析结果裁剪上限，默认 6000，FULL_CONTEXT 模式 15000），替代原 WriterAgent 中的硬编码截断常量。`singlePassMaxChars` 默认 1.2M 字符（约 857K tokens），覆盖 deepseek-v4-flash 1.5M token 上下文窗口的 85% 安全比，绝大多数文档走单次全文分析路径。编译三原则优先级：**质量 > 性能 > 成本**。

**InformationCatalog 信息目录**：AnalyzerAgent 完成后、WriterAgent 写入前，`InformationCatalogBuilder`（纯 Java，零 LLM 调用）构建段落级精确定位索引。`InformationCatalog` 包含：`SectionNode` 树（文档层级结构）、`EntityRecord`（每个实体的所有出现位置，按 `MentionType` 分类：DEFINITION/RULE/DATA/ELABORATION）、`coOccurrenceGraph`（实体共现关系图）、`CrossChapterRelation` 列表（跨章节关系检测：当实体在章节 A 定义但在章节 B 被引用时，自动建立 `DEFINED_IN`/`REFERENCED_BY`/`DEPENDS_ON`/`SUPERSEDES` 关系记录，补偿分块分析时的跨 chunk 关系断裂）。`EntityDossierBuilder` 从 `InformationCatalog` 为每个实体构建精确档案（`EntityDossier`），包含定义段落、规则条款、数据指标、按章节组织的阐述段落（`extractExtendedParagraph` 多取 1 段相邻上下文）、关联实体。`WriterAgent.buildEntitySourceFromChunks()` 优先使用 EntityDossier（精准投喂），fallback 到旧 chunk 拼接逻辑。两者均存入 `IngestContext`。

**WriterOrchestrator 四阶段写入编排**：`WriterOrchestrator.writeWithVerification()` 执行四阶段流水线：①`WriterAgent.write()` 并行写入 → ②`WritingPlanComplianceChecker.check()` 确定性合规校验（从 WritingPlan 的 `consistencyRules` 结构化对象提取 termMap 做术语全局替换、forbiddenPhrases 做禁用短语检测、requiredStructure 做必需章节检查）→ ③`ConsistencyReconciler.reconcile()` 单次 LLM 全局一致性仲裁（收集所有页面预览，要求 LLM 输出 termInconsistencies 和 factConflicts，Java 层执行术语修复）→ ④`WriterQualityVerifier.verify()` 4 维度结构质量校验（实体覆盖率/摘要完整性/交叉引用对称性/来源追溯）。三阶段后处理形成"确定性先行 + AI 终审 + 结构兜底"的防线体系，消除多 Agent 并行写入的语义分歧。`consistencyRules` 向后兼容旧 `string[]` 格式和新结构化 `object` 格式（含 termMap/forbiddenPhrases/requiredStructure/rules）。校验结果存入 `IngestContext.verificationReport`。`IngestOrchestrator` 的 3 处写入调用已全部改为 `writerOrchestrator.writeWithVerification()`。

**IngestStep 枚举**：步骤定义从字符串硬编码升级为类型安全枚举，每个步骤携带 `type`、`requiresAi`、`baselineMs` 元数据。

**IngestContext 数据容器**：Agent 间通过 `IngestContext` 传递中间产物，支持 `ConcurrentHashMap` 存储子 Agent 结果，承载 WritingPlan、ConflictAnnotation、chunkPreviews、`InformationCatalog`、`EntityDossier`、`VerificationReport` 等并行写入产物。

**架构收益**：
- 代码解耦：1700+ 行巨型 `PipelineOrchestrator` 拆为 4 个独立 Agent + 1 个编排器
- 独立优化：每个 Agent 可独立调整 Prompt、并行策略、DB 操作
- 并行化已实现：WriterAgent Plan-then-Execute 并行写入，`IngestContext` 的 `ConcurrentHashMap` 承载并行产物
- 信息精准投喂：`InformationCatalog` + `EntityDossier` 实现段落级实体定位，替代 chunk 级粗匹配，确保大文档（400+ 页）的信息完整性
- 写入质量闭环：`WriterOrchestrator` 四阶段后处理（合规校验→一致性仲裁→质量校验），确定性 Java 层先行消除术语分歧，单次 LLM 调用做全局语义终审，消除多 Agent 并行写入的判断不一致
- 渐进式迁移：`PipelineOrchestrator` 的 Ingest 方法委托给 `IngestOrchestrator`，外部调用方无感知

#### 格式解析层（ParserAgent + Python Sidecar）

`doc_parser.py` 随 jar 一起打包在 `BOOT-INF/classes/`（源码位于 `app/domain/service/src/main/resources/`）。首次调用时从 classpath 提取到系统临时目录，后续复用缓存脚本，通过 Java ProcessBuilder 调用 Python CLI 脚本，利用 PyMuPDF/python-docx/openpyxl 解析二进制文档格式，输出结构化 Markdown。Markdown/纯文本跳过此步骤直接进入读取阶段。

```
支持格式：
  PDF   → PyMuPDF (fitz) 提取 Markdown 文本，自动检测扫描件
  DOCX  → python-docx 提取段落 + 标题层级，保留样式信息
  XLSX  → openpyxl 提取工作表数据，转换为 Markdown 表格（限 500 行）
  MD/TXT → 直接读取，无解析开销
```

- Python 进程超时限制 120 秒，超时自动销毁
- 文件头魔数校验防止格式伪造
- 路径白名单校验防止目录遍历攻击
- 扫描件 PDF 自动检测并返回警告标记

#### 智能分块层（DocumentChunker）

Markdown 文本按 H1/H2/H3 标题边界智能分块，保留层级上下文，单块上限 8000 字符，`splitByParagraphs()` 带 2 段 overlap 避免上下文断裂。

#### 并行分析层（ParallelAnalysisExecutor + ChunkMergeCoordinator）

采用 Map-Reduce 模式：
- **Map 阶段**：4 线程并行池将各 chunk 发送至 AI 模型独立分析
- **Reduce 阶段**：ChunkMergeCoordinator 将各 chunk 分析结果去重整合
- **子进度回调**：`ProgressListener` 在每个 chunk future.whenComplete 时回调 `(current, total, avgMsPerUnit)`，PipelineOrchestrator 通过 `ExecutionTracker.publishStepProgress` 发出 `StepProgressEvent`，Controller 以 SSE `step_progress` 推送到前端，提供 ANALYZE_CHUNKS 步骤内的实时细粒度进度

```
步骤 1: PARSE_DOCUMENT（解析文档）[仅 PDF/DOCX/XLSX]
  输入：原始文件路径 + 格式
  动作：调用 Python CLI 解析二进制文档，输出 Markdown 写入 `parsed/{sourceId}.parsed.md`（与 raw 隔离）
  输出：解析后的 Markdown 文本（含可能的扫描件警告）
  审批：AUTO

步骤 2: READ_SOURCE（读取来源）
  输入：源文件路径（优先读取 parsed/ 缓存，其次 raw/ 原文）
  动作：读取解析后的 Markdown 内容
  输出：文本内容
  审批：AUTO

步骤 3: SPLIT_CHUNKS（智能分块）
  输入：完整文本内容
  动作：按标题边界分块，保留层级上下文
  输出：分块数量 + 大文档标记
  审批：AUTO

步骤 4: ANALYZE_CHUNKS（并行分析片段）
  输入：分块列表
  动作：并行 AI 分析各片段（单块直接分析，多块 Map-Reduce 并行）
  输出：各片段分析结果（结构化 JSON）
  审批：AUTO

步骤 5: MERGE_RESULTS（合并分析结果）
  输入：各片段分析结果
  动作：AI 去重整合，生成统一的结构化分析
  输出：合并后的分析（实体列表、概念列表、关键论点、与现有 Wiki 的矛盾）
  审批：AUTO

步骤 6: EXTRACT_METADATA（提取元数据）
  输入：合并后的分析结果
  动作：提取关键词、标签、分类、摘要用于数据库索引
  输出：元数据对象（title, summary, category, tags, keywords, entities）
  审批：AUTO

  category 采用路径式分类格式（一级/二级），例如“业务领域/企业治理”、“技术实践/架构设计”

步骤 7: WRITE_SUMMARY（写入摘要页）
  输入：分析 + 元数据
  动作：LLM 生成深入 Wiki 页面，包含详细概述、核心概念详解、关键细节与要点、关系与关联（含 [[双链接]]）、参考来源；写入 wiki/pages/；**同步写入 `wiki_page_source` 关联记录**
  输出：Wiki 页面路径 + 页面-来源关联 ID
  审批：AUTO

步骤 7.5: WRITE_ENTITY_PAGES（生成实体页面）
  输入：分析 + 元数据中的 entities 列表
  动作：为每个关键实体（person/organization/system/concept/document/event）生成独立 Wiki 页面；若实体已有页面则增量融合；写入 wiki/pages/ + wiki_page_source 关联
  输出：实体页面路径列表
  审批：AUTO

步骤 8: UPDATE_RELATED（增量编译—更新关联页面）
  输入：分析结果 + 当前 Wiki 索引
  动作：**真正执行增量编译**—LLM 判断哪些已有 Wiki 页面需要基于新来源更新，读取该页面内容，用 LLM 将增量信息融合到页面中，写回文件和数据库；同步更新 `wiki_page_source` 关联
  输出：已更新页面路径列表 + 变更内容摘要
  审批：CONFIRM（修改已有页面容易影响其他读者，需用户确认）

步骤 9: UPDATE_LINKS（更新链接图谱）
  输入：新页面内容 + 已有 Wiki 页面
  动作：解析新页面中的交叉引用，更新数据库中的链接图谱（含 scope_id）
  输出：链接图谱更新
  审批：AUTO

步骤 10: PROPOSE_SCHEMA_PATCH（提议 Schema 补丁）
  输入：元数据 + 新页面摘要 + 关联页面更新摘要
  动作：基于本次 Ingest 内容，从 AI 视角检查现有 Schema 是否有遗漏的约束规则，生成补丁建议
  输出：Schema 补丁提案（存入 schema_config Draft）
  审批：AUTO
```

> 注：Markdown/纯文本跳过 PARSE_DOCUMENT 步骤，Pipeline 为 9 步。PDF/DOCX/XLSX 为 10 步。已废弃 UPDATE_INDEX（原步骤 9）和 APPEND_LOG（原步骤 11），索引由 GlobalSummaryService 实时构建。

### 查询 Agent

查询使用 spring-ai ChatClient 原生工具调用进行多轮对话。核心设计：**Agent 自主导航 Wiki**——通过 GlobalSummary 建立全局认知，而非系统代码预组装上下文塞入 prompt。

> **架构变更说明**：原设计使用 spring-ai-alibaba 的 ReactAgent，因其在 DashScope OpenAI 兼容模式下 tool calling 格式不兼容（导致 HTTP 400），已替换为 spring-ai 同源的 ChatClient.defaultTools()，消除三方兼容性鸿沟。
> **2026-05 变更**：已移除 `updateIndex` 和 `appendLog` 工具（索引/日志均改为 DB 真相来源），Query ChatClient 从 8 tools 降为 6 tools。

```
用户提问
    ↓
ChatClient 工具调用初始化（@PostConstruct 预构建）：
  - ChatModel（deepseek-v4-flash，通过 DashScope 兼容模式）
  - 工具（读工具 + 写工具，支持 Agent 自主决策沉淀）：
    - 读：[readFile, searchWiki, getRelatedPages, listPages]
    - 写：[writeFile, updateLinks]  ← 已移除 updateIndex/appendLog（索引/日志由 DB 管理）
  - Prompt 采用三层回答架构：Layer 1 Wiki 事实 + Layer 2 AI 解读 + Layer 3 前瞻推演
  - Prompt 强制流程：第一步必须通过 GlobalSummary 建立全局认知（替代读取废弃的 index.md）
    ↓
Agent 循环（自主导航，不受硬性数量/长度限制）：
  1. searchWiki("查询词") → 通过 ES/DB 检索相关页面
  2. readFile("wiki/pages/xxx.md") → 逐个读取相关页面完整内容（自主决定读取多少页面）
  3. getRelatedPages("页面路径") → 通过链接图谱扩展上下文，发现间接关联
  4. readFile("wiki/pages/yyy.md") → 继续读取新发现的相关页面
  5. 综合所有信息给出带引用的答案
    ↓
降级路径（仅在 ChatModel 不可用时）：
  → 单轮 DashScope chat + 搜索结果摘要（不含完整页面内容）
  → Prompt 告知用户回答可能不够完整
    ↓
对话历史持久化：query_conversation 表按 session_id 存储，追问时载入历史轮次入 Prompt
    ↓
可选："保存为 Wiki 页面"
  方式 A（UI 触发）：用户点击保存 → /api/query/save → Harness 将答案写为新的 Wiki 页面
  方式 B（Agent 自主）：Agent 判断答案有价值，主动调用 writeFile 沉淀（依赖写工具注册）
  无论哪种方式，保存时必须：
    - 检查同名文件冲突（填加时间戳后缀或要求用户改名）
    - 更新链接 + ES 索引
    - 写入 `wiki_page_source` 关联（指向本次参考的各来源页面）
  审批：CONFIRM（默认）
```

### 健康检查 Pipeline

健康检查 Pipeline 对知识库进行全面的诊断分析，共 11 个步骤，步骤错误隔离不阻断整体流程：

```
步骤 1: PROBE_AND_VALIDATE（诊断探查 + SQL 安全网交叉验证 — AI 步骤，受 LlmConcurrencyBarrier LINT bucket 约束）
  动作：LintAgent 单次 LLM 调用读取 GlobalSummary（通过 GlobalSummaryService 从 DB 构建，含分类骨架+链接图谱+最近活动，**替代已废弃的 FS index.md + log.md**）+ 上次诊断结果 + Schema 规则（通过 SchemaInjector.prepend 注入 Section 1-6），输出结构化 JSON 包含 5 种诊断类型（orphan/stale/missing_crossref/conflict/gap）；LintProbeService 编排 AI 探查与 SQL 安全网交叉验证：
    - SQL 安全网：orphan（零入站链接检测）+ stale Part 1（来源更新滞后检测），零 Token 确定性检测
    - 合并去重：AI 发现覆盖 SQL 发现时跳过 SQL 重复条目
  反馈学习：上次诊断结果中已解决/已驳回的同一页面同一类型不重复报告；同一类型被用户连续忽略 ≥ dismissCountToDowngrade 次时降低优先级级别
  输出：合并后的诊断项列表（AI 探查数 + SQL 安全网孤儿数/过时数 + 总计）
  审批：AUTO

步骤 2: AUTO_FIX_ORPHANS（自动修复孤立页面 — AI 步骤，受 LlmConcurrencyBarrier LINT bucket 约束）
  动作：对 open 状态的 orphan 类型 finding，LLM 分析应链接的相关页面并写入 wiki_page_link 表
  输出：修复的孤立页面数量
  审批：AUTO

步骤 3: AUTO_FIX_CROSSREFS（自动修复缺失交叉引用 + 矛盾链接 — AI 步骤，受 LlmConcurrencyBarrier LINT bucket 约束）
  动作：对 open 状态的 missing_crossref 类型 finding，LLM 分析引用关系并写入 wiki_page_link 表；同时扫描 conflict 类型 finding，创建 linkType="contradiction" 的矛盾链接，对已存在矛盾链接的 finding 直接自动解决
  输出：修复的交叉引用数量 + 创建的矛盾链接数量
  审批：AUTO

步骤 4: AUTO_FILL_GAP（自动填充概念缺口 — AI 步骤，受 LlmConcurrencyBarrier LINT bucket 约束）
  动作：对 open 状态的 gap 类型 finding，LLM 生成新页面内容，写入 wiki/ 文件系统 + wiki_page 表 + wiki_page_source 关系，路径生成经由 sanitizePathSegmentUnique 进行碰撞检测
  输出：填充的缺口页面数量
  审批：AUTO

步骤 5: EXECUTE_RULINGS（执行 Schema 裁决 — AI 步骤，受 LlmConcurrencyBarrier LINT bucket 约束）
  动作：对 open 状态且 handlingMethod ≠ dismiss 的 finding，根据 LintFindingService 从 LintRulesConfig 读取的处置策略（干预层级/处理方式/风险评分）执行对应操作；前置检查 AI 可用性，不可用时跳过并记录警告
  输出：裁决执行数量
  审批：AUTO

步骤 6: WRITE_HEALTH_STATUS（回写健康状态到页面）
  动作：汇总前面步骤产出的 open 状态 lint_finding 记录，按类型判定页面健康状态：
    - conflict/stale → has-problems
    - orphan/missing_crossref/gap/web_gap/action → needs-update
    - 无任何 open finding → healthy
    同步更新 ES 索引中的 healthStatus 字段和被检查页面的 last_checked_at
  输出：更新的页面数量 + 状态分布（healthy/needs-update/has-problems/conflict-warning）
  审批：AUTO

  健康状态完整枚举：
  - `healthy` — 内容完整、链接有效、最近更新，无任何 open finding
  - `needs-update` — 存在孤立、缺失交叉引用、概念缺口等结构性问题
  - `has-problems` — 存在矛盾或过时声明等严重问题
  - `conflict-warning` — 页面存在内容矛盾（Ingest 时写入，Query Save 时写入，Lint 诊断后写入），更高优先级的特定警告

步骤 7: GENERATE_REPORT（生成报告）
  动作：将 PROBE_AND_VALIDATE 步骤的诊断探查结果和 WRITE_HEALTH_STATUS 步骤的健康状态汇总为结构化报告，写入执行步骤输出（不产生游离的 wiki/health-report.md 文件）
  输出：知识库健康报告
  审批：AUTO

步骤 8: SUGGEST_ACTIONS（建议修复动作 — AI 步骤）
  动作：LLM 基于报告产出具体修复方案，解析 JSON 后创建 action/web_gap 类型的 lint_finding 记录
  输出：动作列表（含优先级和预估影响）
  审批：CONFIRM — 用户决定执行哪些动作

步骤 9: PROPOSE_SCHEMA_PATCH（提议 Schema 补丁）
  动作：基于本次 Lint 的健康汇总、矛盾、缺口、建议等信息，通过 SchemaPatchProposer 提议 Schema 分类体系的增补
  输出：Schema 补丁候选提案数量
  审批：AUTO

步骤 10: ~~UPDATE_INDEX~~ **已废弃**
  动作：~~重建 index.md~~ 已不再维护 FS 索引副本
  输出：无操作
  审批：AUTO（已移除）

步骤 11: ~~APPEND_LOG~~ **已废弃**
  动作：~~追加 Lint 执行记录到 log.md~~ 已不再维护 FS 日志副本
  输出：无操作
  审批：AUTO（已移除）
```

> **2026-05 变更**：Lint Pipeline 已移除 UPDATE_INDEX 和 APPEND_LOG 步骤（从 15 步降为 13 步）。索引/日志统一由 DB 管理。

**Lint 后台定期执行：**

知识体检需通过 `@Scheduled` 后台定期运行，而非仅手动触发。调度频率按知识库类型配置：
- 个人知识库：每周一次
- 团队知识库：每天一次
- 部门/公司知识库：每天一次

执行结果即时反映到各页面的健康指示器（前端 WikiPageRenderer 读取 wiki_page.health_status）。

### 知识矛盾检测与处置

系统在 Ingest 和 Query Save 流程中内建知识矛盾检测，实现零人工阻断、零额外 LLM 调用的矛盾自动处置。

**核心原则**：
- **编译时感知** — Ingest 流程中 WritingPlan/Merge prompt 已包含矛盾检测输出要求
- **消费时透明** — Query 回答自动呈现矛盾双方的完整观点
- **体检时治理** — Lint 后台定期扫描，AUTO_FIX_CROSSREFS 自动创建矛盾链接
- **元层面演化** — Schema 6.5 ConflictResolutionRules 定义裁决策略，随知识库演化自动调整

**矛盾类型**：

| 类型 | 名称 | 说明 |
|------|------|------|
| `value_conflict` | 价值冲突 | 两个页面提出不同的价值判断或推荐 |
| `fact_conflict` | 事实冲突 | 两个页面陈述的具体事实数据相互矛盾 |
| `definition_conflict` | 定义冲突 | 两个页面对同一概念的定义不一致 |
| `temporal_conflict` | 时序冲突 | 新内容覆盖旧数据但旧页面未更新 |

**裁决策略（从 Schema 读取，无硬编码）**：

| 策略 | 行为 | 适用场景 |
|------|------|---------|
| `annotate_both` | 保留双方观点，追加「（另有观点认为...）」格式 | 多观点并存的话题 |
| `annotate_and_patch` | 标注双方 + 触发 Schema 补丁建议 | 系统性定义冲突 |
| `source_priority` | 按来源层级裁决（学术论文>官方报告>行业分析>技术博客>个人笔记） | 权威性可判定 |
| `newer_wins` | 最新来源胜出 | 时效性强的信息 |

**实现路径**：

```
Ingest 流程（编译时）：
  AnalyzerAgent.mergeChunks() → 产出冲突元数据
  WriterAgent.writingPlan() → prompt 输出 conflictAnnotations[]
  WriterAgent.write() → healthStatus = "conflict-warning"
  IndexerAgent.index() → createContradictionLinks() → linkType="contradiction"

Query Save 流程（运行时）：
  runSaveQueryResultPipeline → DETECT_SAVE_CONFLICTS 步骤
  → LLM 逐页比对最近 20 个页面 → 创建 contradiction 链接 + 标记 healthStatus

Lint 流程（体检时）：
  PROBE_AND_VALIDATE → 探测 conflict 类型 findings
  AUTO_FIX_CROSSREFS → 扫描 conflict finding → 创建 contradiction 链接

Query 回答（消费时）：
  Prompt 规则 → health_status=conflict-warning → 「⚠️ 该页面存在内容矛盾」
  Prompt 规则 → linkType=contradiction → 「观点A / 观点B」并排展示
```

**关键数据结构**：

- `ConflictAnnotation` record（IngestContext 内）：pagePath, conflictType, existingClaim, newClaim, resolution, sourceRef
- `wiki_page_link.linkType = "contradiction"`：矛盾链接类型，连接存在矛盾的页面
- `wiki_page.health_status = "conflict-warning"`：页面健康状态，指示页面存在矛盾
- `LintRulesConfig.ConflictResolutionRules`：Schema 6.5 矛盾裁决策略定义
- `IngestContext.schemaPatchHints`：Schema 补丁建议收集器

### Schema 治理 — SchemaLint 独立调度

独立于 Wiki Lint，由 `SchemaLintScheduler` 驱动，遵循 AGENTS.md《Schema 共治宪法·规则 7》：

**调度任务（`SchemaLintScheduler`）：**

| 任务 | 频率 | 说明 |
|------|------|------|
| `runSchemaLint` | 每小时 30 分 | 观察期补丁自动提升：聚合升级（同 section+operation 累计 ≥3 条 + 去重证据 ≥5 → PENDING）或老化高置信升级（confidence ≥0.85 + 证据 ≥3 + 停留 ≥7 天 → PENDING） |
| `runConsistencyScan` | 每周日 3:00 | Schema 体检报告（遍历所有存在 schema_config 的 scope，非仅 OBSERVING scope）：膨胀检查（正则匹配 H2 边界）、事实偏离扫描、规则冲突检查（LLM）、僵尸规则检测（排除 dismissed/auto_resolved） |

**Schema 体检报告四维检查（`SchemaConsistencyChecker`）：**

| 检查项 | 方法 | 说明 |
|--------|------|------|
| 膨胀检查 | `checkBloat` | 正则匹配 H2 section 边界统计字符数，超 8000 字符建议 Compaction |
| 事实偏离扫描 | `checkFactDeviation` | Section 6 诊断规则断言 vs lint_finding 实际分布；标记无对应活跃 finding 的规则 |
| 规则冲突检查 | `checkRuleConflicts` | LLM 逐对检查 Section 6 规则是否存在逻辑冲突 |
| 僵尸规则 | `checkZombieRules` | 30 天内无活跃 finding（排除 dismissed/auto_resolved/resolved/rolled_back）的规则标 deprecated |

**处置决策从 Schema 读取（`LintFindingService`）：**

`inferHandlingMethod`、`computeRiskScore`、`determineInterventionTier` 均从 `SchemaSection6Parser.parse(scopeId)` 解析的 `LintRulesConfig` 读取权重和阈值（RiskScoreWeights / InterventionTiers / DiagnosticRule / DiagnosticStandard / FeedbackLearningConfig），不再硬编码 `@Value` 配置。Section 6 四段式结构：6.1 诊断标准（判定阈值 + probeEnabled）→ 6.2 干预层级与处置方式 → 6.3 反馈学习（dismissCountToDowngrade 降级阈值）→ 6.4 调度配置。诊断标签采用逐行匹配（`findDiagKeyInLine`），消除跨行误关联。

**反馈学习闭环（`LintFindingService.recordFeedback`）：**

用户对 finding 的反馈（accepted/ignored/modified）持久化到 `lint_finding.user_feedback` 和 `lint_finding.feedback_count` 字段。同一类型被连续忽略 ≥ `dismissCountToDowngrade` 次时自动降低该类型探查优先级级别，下次 `LintAgent` 探查时通过 `buildPreviousFindingsSummary()` 注入历史反馈统计影响探查敏感度。

## Wiki 工具注册表

通过 spring-ai 的 `@Tool` 注解注册的工具，Pipeline（Graph）和 ChatClient 工具调用上下文均可使用：

| 工具 | 输入 | 输出 | 描述 |
|------|------|------|------|
| `readFile` | path: String | content: String | 读取 wiki-data/ 中的任何文件（原始或 Wiki） |
| `writeFile` | path: String, content: String | success: boolean | 写入/更新 Wiki 页面文件 |
| `searchWiki` | query: String, category: String（可选） | 带摘要的页面列表 | 通过 ES 搜索 Wiki 页面 |
| `listPages` | category: String（可选） | 带元数据的页面列表 | 从数据库列出所有 Wiki 页面 |
| `getRelatedPages` | pagePath: String | 相关页面列表 | 通过链接图谱获取与给定页面有链接关系的页面 |
| `readImage` | imagePath: String | 图片描述: String | 读取并描述图片文件（多模态）**待实现** |
| `extractMetadata` | content: String | 元数据对象 | LLM 从页面内容提取关键词、标签、摘要 |
| `updateLinks` | fromPage: String, toPage: String, linkType: String | success: boolean | 在链接图谱数据库中添加/更新链接 |

## 治理 — 三级审批模型

每个 Pipeline 步骤和 Agent 动作都有在 Schema 中配置的审批级别：

| 级别 | 行为 | UI 交互 |
|------|------|---------|
| **AUTO** | 立即执行，仅记录日志 | 无需用户交互。步骤运行并完成。 |
| **CONFIRM** | 暂停，等待用户审批 | Pipeline 暂停。UI 显示审批对话框含步骤详情。用户点击"批准"或"拒绝"。 |
| **REVIEW** | 暂停，展示步骤详情，要求明确审查 | Pipeline 暂停。UI 显示完整步骤输入/输出。用户必须审查并点击"批准"、"拒绝"或"修改"。 |

默认审批级别（可在 Schema 中配置）：

| 操作 | 步骤 | 默认级别 |
|------|------|----------|
| 摄入 | PARSE_DOCUMENT | AUTO |
| 摄入 | READ_SOURCE | AUTO |
| 摄入 | SPLIT_CHUNKS | AUTO |
| 摄入 | ANALYZE_CHUNKS | AUTO |
| 摄入 | MERGE_RESULTS | AUTO |
| 摄入 | EXTRACT_METADATA | AUTO |
| 摄入 | WRITE_SUMMARY | AUTO |
| 摄入 | WRITE_ENTITY_PAGES | AUTO |
| 摄入 | UPDATE_RELATED | CONFIRM |
| 摄入 | UPDATE_LINKS | AUTO |
| 查询 | 工具调用 | AUTO |
| 查询 | 保存为 Wiki 页面 | CONFIRM |
| 健康检查 | 所有扫描步骤 | AUTO |
| 健康检查 | SUGGEST_ACTIONS | CONFIRM |

## Wiki 搜索 — Elasticsearch 唯一实现

搜索系统统一走 Elasticsearch（ES），**MySQL 搜索实现已于 2026-04 彻底弃用**（删除 `MySqlSearchServiceImpl`）。原因：MySQL `LIKE '%kw%'` 无法命中索引、不支持中文分词与高亮、不支持相关性打分，已不满足产品"查找知识"核心场景。

### 组件拓扑

```
┌──────────────────────────────────────────────────────────────┐
│                     搜索写路径（Index）                       │
│                                                              │
│  Ingest/Update → WikiIndexService → ElasticsearchSearchImpl  │
│                                       │                      │
│                                       ├─ save/delete → ES    │
│                                       │                      │
│                                       └─ 失败兜底 (策略 A)   │
│                                              │                │
│                                              ▼                │
│                                       search_index_retry     │
│                                       （MySQL 重试表）        │
│                                              │                │
│                              定时任务 @Scheduled              │
│                              指数退避 → ES 重放               │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                     搜索读路径（Search）                      │
│                                                              │
│  WikiController /api/search, /api/suggest                    │
│      │                                                       │
│      ▼                                                       │
│  ElasticsearchSearchImpl                                     │
│      │ NativeQuery (multiMatch + scopeId filter + highlight) │
│      ▼                                                       │
│  llmwiki-pages 索引（scope 强制过滤）                         │
└──────────────────────────────────────────────────────────────┘
```

### ES 索引设计

- 索引名：`llmwiki-pages`（单索引，通过 `scopeId` 字段做范围隔离，所有查询强制 `term scopeId = ?`）
- 文档映射（`WikiPageDocument`）：
  - `id (Long)`、`scopeId (Long)`、`filePath/category/healthStatus/visibility (Keyword)`
  - `title/summary/content (Text, analyzer = ik_max_word, searchAnalyzer = ik_smart)`
  - `updatedAt (Date)`
- 分片策略：`shards=1, replicas=0`（一期单机即可，生产按节点数调整）
- 依赖：`elasticsearch:8.15.0` + `spring-data-elasticsearch 5.4.5` + `ik` 中文分词插件（运维部署时安装）

### 写路径失败策略（A + 重试表）

主业务流程遵循"**静默记日志 + 持久化重试任务**"策略，避免 ES 抖动拖垮写操作：

1. `indexPage` / `removePage` 捕获所有异常
2. 记 WARN 日志，不抛出给上游（主流程仍然成功）
3. 构造 `SearchIndexRetryDO` 写入 `search_index_retry` 表，`payload` 为文档 JSON 序列化
4. 独立 `SearchIndexRetryScheduler` 定时拉取 `status=PENDING AND next_retry_at <= now` 的批次（每批 50 条）
5. 重试成功 → `status=SUCCESS`；失败 → `retry_count++`，下次时间 = `intervalMs * 2^min(retryCount, 5)` 指数退避
6. 达到 `max_attempts` → `status=DEAD`（运维介入）

### 启动自检与首次全量回填

`SearchIndexBootstrap` 实现 `ApplicationRunner`，启动序列：

1. **Ping**：`elasticsearchClient.ping()` 返回 false 或抛异常 → **fail-fast**（`IllegalStateException`），应用不启动
2. **确保索引**：`indexOps.exists()==false` → `createWithMapping()`
3. **首次迁移回填**：`count(llmwiki-pages)==0 && wiki_page 表非空` → 扫描 `DISTINCT scope_id`，逐 scope 调 `rebuildIndex(scopeId)` 全量写入 ES

该逻辑天然支持"从 MySQL 搜索迁移到 ES"的首次部署，以及"ES 数据卷丢失后的自动恢复"。

### 配置

```yaml
llmwiki:
  elasticsearch:
    uris: ${ES_URIS:http://localhost:9200}
    index-name: ${ES_INDEX_NAME:llmwiki-pages}
    username: ${ES_USERNAME:}
    password: ${ES_PASSWORD:}
    retry:
      interval-ms: ${ES_RETRY_INTERVAL_MS:60000}    # 重试基础间隔
      max-attempts: ${ES_RETRY_MAX_ATTEMPTS:5}      # 最大尝试次数，超过标 DEAD
```

**本地开发**：`docker compose up -d elasticsearch`，默认 `http://localhost:9200`，关闭 xpack 认证。
**生产**：通过环境变量注入集群地址与认证；`docker-compose.yml` 已包含 ES 服务与 healthcheck。

### LLM 导航式搜索（保留）

查询 Agent（ChatClient 工具调用）仍然按原设计使用 `searchWiki` / `getRelatedPages` / `readFile` 工具链做**导航式检索**，只是底层 `searchWiki` 工具改为调用 ES 而非 MySQL LIKE：

1. `searchWiki(keyword)` → ES `multiMatch` 返回候选页面与高亮片段
2. `getRelatedPages(pageId)` → MySQL `wiki_page_link` 图谱扩展
3. `readFile(path)` → 存储层（NAS/S3）读取完整内容
4. 综合答案并附引用

**关系图谱、分类、摘要等结构化元数据仍存 MySQL**（wiki_page / wiki_page_link / wiki_page_tag / wiki_page_keyword），ES 只承担全文检索与相关性打分职责。

## 数据库 Schema

**核心原则：数据库是真相来源（source of truth），文件系统是 LLM 友好的缓存/导出层。**

### 系统表

```sql
-- 用户
`user` (id, username, password_hash, email, avatar, role, status, scope_id, created_at, updated_at)

-- 系统配置
system_config (id, config_key, config_value, config_group, scope_id, description, updated_at)

-- 范围 Token 用量监控追踪
scope_budget (id, scope_id, monthly_budget, used_tokens, reset_date, warning_notified, exceeded_notified)
```

### Wiki 表（所有表含 scope_id，一期 scope_id = user_id）

```sql
-- 源文档
source (id, name, file_path, format, size, status, scope_id, upload_user_id, created_at)

-- 用户（含知识共享同意）
user (id, username, password_hash, email, avatar, role, status, scope_id, created_at, updated_at,
      consent_knowledge_promotion)

-- 知识库范围
scope (id, name, description, type, owner_id, monthly_budget, default_approval, max_file_size, max_concurrent,
       upstream_scope_ids, created_at, updated_at)

-- 范围成员
scope_member (id, scope_id, user_id, role, joined_at)

-- Wiki 页面
wiki_page (id, title, file_path, category, summary, scope_id,
           source_count, health_status, last_checked_at, created_at, updated_at,
           visibility, promoted_from_scope_id, promoted_from_page_id, promoted_from_username)

-- 页面标签（独立表，支持多标签）
-- scope_id 冗余但必须：所有查询强制按 scope 过滤，关系表也不例外，避免跨 scope 污染
wiki_page_tag (id, scope_id, page_id, tag)

-- 页面关键词（独立表，支持多关键词）
wiki_page_keyword (id, scope_id, page_id, keyword)

-- 链接图谱（编译器的"符号引用表"）
wiki_page_link (id, scope_id, from_page_id, to_page_id, link_type)

-- 页面-来源关联（编译器的"来源-目标码映射"，是增量编译的基础）
-- Ingest UPDATE_RELATED 必须同步维护此表，否则无法回答"这个页面的知识来自哪些 raw 文件"
wiki_page_source (id, scope_id, page_id, source_id, created_at)
```

**硬性约束：所有关系表必须带 `scope_id`。** 尽管可以通过 `page_id` JOIN 回 `wiki_page` 间接获取 scope，但冗余 `scope_id` 保证：
- 所有查询可以直接 `WHERE scope_id = ?` 强制隔离，不依赖 JOIN
- 批量删除/清理某个 scope 时可以直接按 scope_id 扫所有表
- 即便业务代码忘记加 page_id 过滤，scope_id 也能兜底防止跨 scope 泄露

### Harness 表（含 scope_id）

```sql
-- Schema 配置（每个范围独立，config_value 存储 Markdown 格式内容）
schema_config (id, config_key, config_value, config_group, scope_id, description, updated_at)

-- 执行追踪
execution (id, type, status, scope_id, source_id, schema_config_id,
           started_at, completed_at, total_tokens, total_cost, created_at)

execution_step (id, execution_id, step_name, step_order, status,
                input_data, output_data, tokens_used, duration_ms,
                approval_level, approved_by, started_at, completed_at)

-- 步骤耗时基线（每次 step 完成后记录样本，用于前端 baselineProfile 预估剩余时间）
-- V10__add_step_baseline.sql
step_baseline (id, scope_id, step_name, doc_format,
               avg_ms,        -- 滑动平均：avg = (avg * n + d) / (n + 1)
               p95_ms,        -- EWMA 近似：p95 ← p95 * (1-α) + max(d, p95) * α
               sample_count,
               updated_at, created_at)
-- 唯一索引：(scope_id, step_name, doc_format)
```

### 搜索索引重试表

```sql
-- ES 写失败兜底重试队列（V7__add_search_index_retry.sql）
search_index_retry (
  id, scope_id, page_id,
  operation,      -- INDEX / REMOVE
  payload,        -- INDEX 时为 WikiPageDocument 的 JSON；REMOVE 为 NULL
  retry_count, max_attempts,
  status,         -- PENDING / SUCCESS / DEAD
  last_error, next_retry_at,
  created_at, updated_at
)

-- 索引：(status, next_retry_at)、(scope_id, page_id)
```

## API 端点

### 系统管理

```
POST   /api/auth/login              — 用户登录，返回 JWT（disabled 用户拒绝）
POST   /api/auth/register           — 用户注册（受 allow-self-register 开关控制）
GET    /api/auth/info               — 当前用户信息
PUT    /api/auth/consent-promotion  — 更新知识共享同意状态
GET    /api/users                   — 用户分页列表（admin only）
GET    /api/users/search            — 用户搜索补全（所有认证用户）
GET    /api/users/{id}              — 用户详情（admin only）
POST   /api/users                   — 创建用户（admin only，密码留空则自动生成临时密码）
PATCH  /api/users/{id}/status       — 启用/禁用用户（admin only）
PATCH  /api/users/{id}/role         — 切换系统角色（admin only）
POST   /api/users/{id}/reset-password — 重置密码（admin only，返回临时密码）
GET    /api/system/config           — 获取系统配置
PUT    /api/system/config           — 更新系统配置
```

### 来源管理

```
POST   /api/source/upload          — 上传源文件
GET    /api/source/list            — 来源列表
GET    /api/source/{id}            — 来源详情
GET    /api/source/{id}/download   — 下载来源原始文件
GET    /api/source/{id}/content     — 获取来源解析文本（parsed Markdown）
DELETE /api/source/{id}            — 删除来源
```

### Wiki 操作（已实现）

```
GET    /api/wiki/pages              — Wiki 页面列表
GET    /api/wiki/page/{id}          — Wiki 页面详情（内容+健康状态，ID-based URL）
GET    /api/wiki/search             — 搜索 Wiki 页面（查询参数）
GET    /api/wiki/categories         — Wiki 分类列表
GET    /api/wiki/recent             — 最近更新的 Wiki 页面
GET    /api/wiki/related            — 通过链接图谱获取相关页面（?id=pageId）
GET    /api/wiki/health/{id}        — 获取页面健康状态（ID-based URL）
GET    /api/wiki/index              — 获取 Wiki 索引页内容
GET    /api/wiki/log                — 获取 Wiki 日志
GET    /api/wiki/graph              — 获取完整链接图谱数据（用于可视化）
GET    /api/wiki/recommended        — 推荐阅读的 Wiki 页面（基于知识图谱多维打分排序）
POST   /api/wiki/modify             — 请求 AI 修改页面（?id=pageId，用户下指令，AI 执行）
PUT    /api/wiki/visibility          — 切换页面 visibility（?id=pageId）
POST   /api/wiki/init               — 初始化 Wiki 数据
```

#### 推荐阅读算法

`GET /api/wiki/recommended` 返回推荐阅读的 Wiki 页面列表（最多 5 个），由 `WikiFileServiceImpl.getRecommendedPages()` 实现。

**硬排除过滤**：
- `visibility = "private"` — 不推荐私有页面
- `healthStatus = "recalled"` — 不推荐已召回页面（内容已被 soft recall 替换为存根）
- 文件不存在或内容为空 — 双重安全网

**多维打分排序**：
```
score = 被引用数 × 10 + 来源数 × 5 + 健康加分
```
- **被引用数**：`wiki_page_link` 表中以该页面为 `to_page_id` 的记录数，反映页面在知识图谱中的枢纽地位
- **来源数**：`wiki_page.source_count`，反映内容可信度与丰富度
- **健康加分**：healthy (+10) > conflict-warning (+7) > needs-update (+5) > has-problems (+0)

按 score 降序取 top 5 返回。若没有任何符合条件的页面则返回空列表。

### URL 设计原则

Wiki 页面 API 使用**数据库 ID** 而非文件路径作为 URL 标识符：

| 维度 | URL 层 | 文件系统层 |
|------|--------|-----------|
| 标识 | `id=42`，稳定不变 | 自由命名，可随时改 |
| 安全 | 不暴露内部路径结构 | 仅服务端可见 |
| 可读性 | 简洁清晰 `/api/wiki/page/42` | 保持中文命名方便管理 |
| 维护 | 文件重命名不影响 URL | 重命名后更新数据库映射即可 |

**理由**：数据库是 source of truth，URL 应基于数据库标识符。文件系统路径可能包含中文（URL 编码问题）、可能被重命名（链接失效）、暴露内部目录结构（安全隐患）。ID-based URL 完全解耦 URL 层与文件系统层。

### Harness 操作

```
GET    /api/harness/executions      — 执行列表（分页，可按类型/状态筛选）
GET    /api/harness/executions/{id} — 执行详情含所有步骤
POST   /api/harness/executions/{id}/approve — 批准待审批步骤
POST   /api/harness/executions/{id}/reject  — 拒绝待审批步骤
GET    /api/harness/schema          — 获取 Schema 配置列表
GET    /api/harness/schema/{key}    — 获取指定 Schema 配置
PUT    /api/harness/schema/{key}    — 更新指定 Schema 配置
GET    /api/harness/providers       — LLM 提供商列表
PUT    /api/harness/providers/{id}  — 更新 LLM 提供商配置
```

### 搜索问答（已实现）

```
GET    /api/query/stream            — SSE 流式输出查询答案
POST   /api/query/ask               — 提问（同步返回）
GET    /api/query/{id}/conversation — 获取查询对话历史（待实现）
POST   /api/query/save               — 将查询答案通过 PipelineOrchestrator 编译 Pipeline 保存为 Wiki 页面（FORMAT_ANSWER → WRITE_SAVED_PAGE → SAVE_LINKS → DETECT_SAVE_CONFLICTS）
```

### 核心操作（已实现）

```
POST   /api/ingest/start            — 启动来源摄入（异步，返回 executionId）
GET    /api/ingest/{id}/stream      — SSE 实时推送摄入步骤状态
GET    /api/ingest/{id}/progress    — 获取摄入进度
GET    /api/ingest/{id}/result      — 获取摄入结果摘要

POST   /api/lint/start              — 启动健康检查操作（异步，返回 executionId）
GET    /api/lint/{id}/progress      — 获取健康检查进度
GET    /api/lint/{id}/report        — 获取健康检查报告
POST   /api/lint/{id}/execute-actions — 执行已批准的健康检查修复动作
```

### SSE 实时推送端点

```
GET    /api/ingest/{id}/stream              — SSE 实时推送摄入步骤状态（事件：init, step, step_progress, phase1_done, done）
GET    /api/query/stream                    — SSE 流式输出查询答案（事件：start, answer-chunk, answer-complete）
GET    /api/harness/executions/{id}/stream  — SSE 实时推送执行步骤状态变更（事件：step-update, execution-complete）
```

### Token 监控端点（待实现）

```
GET    /api/harness/token-usage             — 获取当前用户 Token 消耗汇总
GET    /api/harness/token-usage/by-type     — 按操作类型统计 Token 消耗
GET    /api/harness/token-budget            — 获取 Token 参考值配置
PUT    /api/harness/token-budget            — 更新 Token 参考值配置
```

## 异步执行架构

### 设计原则

AI 执行引擎的耗时特性决定了系统必须支持异步执行模式：

| 操作类型 | 交互模式 | 实时通信方式 | 前端体验 |
|---------|---------|------------|---------|
| 添加资料（Ingest） | 异步 | SSE 推送步骤状态 | Toast 通知 + AI 面板追踪 + 进度徽章 |
| 知识问答（Query） | 同步流式 | SSE 流式输出 | 对话界面逐字显示 |
| 知识体检（Lint） | 异步 | SSE 推送步骤状态 | Toast 通知 + AI 面板追踪 + 进度徽章 |

### 异步执行流程

```
前端发起操作 → POST /api/ingest/start
  → 后端立即返回 { executionId: "xxx", status: "running" }
  → 前端收到 executionId，显示 Toast "操作已启动"
  → 前端建立 SSE 连接：GET /api/ingest/{id}/stream?token=JWT
  → 后端异步执行 Pipeline，推送 SSE 事件：
    { event: "init",          data: ExecutionInfo （含 baselineProfile）}
    { event: "step",          data: { stepId, stepName, status, outputData } }
    { event: "step_progress", data: { stepId, stepName, current, total, avgMsPerUnit } }
    { event: "phase1_done",   data: ExecutionInfo （Phase 1 完成，SSE 不断开）}
    { event: "done",          data: ExecutionInfo }
  → 前端收到 SSE 事件，更新 execution store 和 AI 面板
  → 执行完成时，前端显示 Toast "操作完成"
```

### SSE 事件格式

```json
// 初始化（analyze 接口响应或 SSE init 均启用 baselineProfile，用于前端 “估时优于静态基线”）
{ "event": "init", "data": { "executionId": 1, "operationType": "ingest", "status": "running", "scopeId": 1, "sourceId": 1, "baselineProfile": { "ANALYZE_CHUNKS": 48000, "MERGE_RESULTS": 5200 } } }

// 步骤状态变更
{ "event": "step", "data": { "stepId": 1, "stepName": "READ_SOURCE", "status": "completed", "outputData": "..." } }

// 步骤开始运行
{ "event": "step", "data": { "stepId": 2, "stepName": "ANALYZE_CHUNKS", "status": "running", "outputData": "" } }

// 子进度推送（在 ANALYZE_CHUNKS 运行期间按 chunk 完成数连续推送；前端以 current/total 进度和 avgMsPerUnit 估时覆盖 baselineProfile）
{ "event": "step_progress", "data": { "stepId": 2, "stepName": "ANALYZE_CHUNKS", "current": 7, "total": 12, "avgMsPerUnit": 4200 } }

// Phase 1 完成（SSE 不断开，等待用户确认写入）
{ "event": "phase1_done", "data": { "executionId": 1, "status": "awaiting_confirmation", "totalTokens": 1800 } }

// 执行完成
{ "event": "done", "data": { "executionId": 1, "operationType": "ingest", "status": "completed", "totalTokens": 3200 } }
```

### 查询流式输出

```
前端发起查询 → GET /api/query/stream?question=xxx&token=JWT
  → 后端建立 SSE 连接
  → 后端使用 ChatClient 工具调用，推送答案：
    { event: "start", data: { sessionId: "query-1-xxx" } }
    { event: "answer-chunk", data: { content: "根据知识库中的信息..." } }
    { event: "answer-complete", data: { totalTokens: 1500 } }
  → 前端逐字显示在对话界面
```

### 后端实现要点

1. **异步执行**：Ingest 操作使用 `executor.execute()` 异步执行，Controller 立即返回 executionId
2. **SSE 推送**：使用 Spring 的 `SseEmitter` 实现步骤状态实时推送，EventSource 通过 `?token=JWT` 查询参数认证
3. **步骤内子进度**：ParallelAnalysisExecutor 接受 `ProgressListener`，whenComplete 时统计 `current/total` 与 `avgMsPerUnit`，通过 `ExecutionTracker.publishStepProgress` 发 `StepProgressEvent`，`IngestController` 监听并转发为 SSE `step_progress`
4. **baseline 样本归档**：PipelineOrchestrator 在每个 step 完成后测量 `elapsedMs`，写入 `step_baseline`（scope_id × step_name × doc_format），`avg_ms` 用滑动平均、`p95_ms` 用 EWMA 近似；`analyze` 接口的响应 `ExecutionInfo` 携带一份 `baselineProfile: Map<stepName, avgMs>` 供前端估时
5. **执行状态持久化**：Execution 和 Step 状态变更写入数据库，支持断线重连后恢复
6. **审批交互**：审批节点暂停 Pipeline，等待前端 POST `/api/harness/executions/{id}/approve` 或 `/reject`
7. **Token 统计**：每次 LLM 调用后记录 Token 消耗，汇总到 Execution 和用户维度

### 前端实现要点

1. **execution store**：Pinia store 管理执行状态、步骤进度、Token 统计
2. **SSE 连接**：使用 `EventSource` API 建立 SSE 连接，监听事件更新 store
3. **Toast 通知**：操作启动/完成/失败时弹出 Toast
4. **进度徽章**：侧栏导航项根据 execution store 状态显示黄色脉冲（执行中）或绿色圆点（有新结果）
5. **流式输出**：Query 对话界面逐字显示 SSE 推送的答案片段
6. **Ingest 进度建模 (progressModel.ts)**：静态步骤权重 + `resolveBaseline(meta, baselineProfile)` 优先级覆盖；running 步骤若 `total > 0` 使用 `current/total`，否则使用 `elapsed / baseline`；`estimateRemainingMs` 优先用 `(total - current) * avgMsPerUnit`，次优 baseline 比例，兼顶 elapsed；`monotonicProgress` 保障显示进度永不回退，review 状态锁定下界 0.76
7. **全局悬浮进度卡 (stores/ingestProgress.ts + IngestProgressFloating.vue)**：IngestView 推送 `phase/progress/remainingMs/currentTip/errorMessage/sourceName` 到 Pinia store，由 AppLayout 挂载的悬浮卡在非 `/ingest` 路由下渲染右下角；支持 analyzing/executing 的环形进度、review 的暂停态、done/failed 的结果态（可关闭），点击跳回 `/ingest`

## 数据流示例

### 摄入流程（= 编译 + 链接）

```
用户上传来源 → POST /api/sources/upload
  → 来源写入 wiki-data/{scopeId}/raw/{uuid}-{filename}
     （UUID 前缀避免同名冲突；raw/ 一旦写入绝对不可再写，WriteFileTool 必须拒绝）
  → source 表记录元数据（scope_id、file_path、format、size）

用户启动摄入 → POST /api/ingest/start {sourceId}
  → IngestService 校验 scope 权限
  → 创建 Execution 记录（scope_id 必填）
  → PipelineOrchestrator 启动摄入 Pipeline

  步骤 1 (PARSE_DOCUMENT，仅二进制格式)：
    → SourceService 检测格式，校验文件魔数
    → PythonProcessRunner 从 jar classpath 提取 doc_parser.py 至临时目录后调用解析
    → 解析产物写入 wiki-data/{scopeId}/parsed/{uuid}.parsed.md
       （严禁写入 raw/；parsed/ 是可重建缓存，与 raw/ 严格隔离）

  步骤 2 (READ_SOURCE)：
    → StorageProvider 读取 parsed/ 产物（或原始文本）
    → 文本内容传递到下一步

  步骤 3 (SPLIT_CHUNKS)：
    → DocumentChunker 按标题边界智能分块
    → 输出分块数量，标记大文档

  步骤 4 (ANALYZE_CHUNKS)：
    → 单块：直接调用 AI 分析
    → 多块：ParallelAnalysisExecutor 4 线程并行 Map 分析各 chunk
    → 输出各片段分析结果（结构化 JSON）

  步骤 5 (MERGE_RESULTS)：
    → ChunkMergeCoordinator Reduce 去重整合
    → 生成统一的结构化分析

  步骤 6 (EXTRACT_METADATA)：
    → LLM 提取关键词/标签/摘要/分类
    → 元数据写入 wiki_page + wiki_page_tag + wiki_page_keyword
       （全部带 scope_id）

  步骤 7 (WRITE_SUMMARY)：
    → LLM 生成深入的 Wiki 页面内容（详细概述+核心概念详解+关键细节+双链接+参考来源）
    → WikiFileService.writeFile(wiki-data/{scopeId}/wiki/pages/{title}.md)
    → 同步插入 wiki_page_source(scope_id, page_id, source_id)
       （建立来源→页面追踪，是后续增量编译的依据）

  步骤 7.5 (WRITE_ENTITY_PAGES)：
    → 从元数据中解析 entities 列表（name + type）
    → 为每个实体生成独立 Wiki 页面：已有则增量融合，否则新建
    → 实体分类按 type 自动推导（person→人物, organization→组织机构, system→系统/平台, document→文档/制度, event→事件）
    → 每个实体页写入 wiki_page_source 关联

  步骤 8 (UPDATE_RELATED) — 增量编译核心 — CONFIRM 级别：
    → LLM 基于当前来源的分析结果，识别受影响的已有页面
       （通过关键词/标签/概念匹配 + wiki_page_source 反查）
    → Pipeline 暂停，前端审批对话框展示：受影响页面列表 + 预计改动摘要
    → 用户批准后，对每个页面 **真正执行增量更新**：
       1) readFile 读取已有页面内容
       2) LLM 融合 prompt："已有内容 + 新 chunk 增量 → 合并后的完整页面"
       3) writeFile 写回文件 + 更新 wiki_page（updated_at、summary）
       4) 追加 wiki_page_source 关联（该页面知识来自新的 source）
    → **硬性约束**：此步不得降级为"只输出建议但不落盘"，否则增量编译失败

  步骤 9 (UPDATE_LINKS) — 链接器：
    → 解析新/更新页面中的 [[链接]]
    → 重算 wiki_page_link（scope_id, from, to, link_type）
    → 自动创建未定义概念的占位页（"undefined reference" 消解）

  → Execution 标记为 COMPLETED
  → Elasticsearch 索引异步更新（失败进入 search_index_retry 重试队列）
```

### 查询流程（= 运行时 + JIT 回写）

```
用户提问 → GET /api/query/stream?question=xxx（SSE）
  → QueryService → HarnessEngine → AgentRunner
  → 校验 scope
  → 启动 ChatClient 工具调用（6 tools），注册 scope 过滤后的工具集：
    读工具：readFile、searchWiki、getRelatedPages、listPages
    写工具：writeFile、updateLinks
           （缺写工具则无法实现"好答案存入 Wiki"的产品初衷）

  Agent 自主导航 Wiki（多轮工具调用，不受硬性数量/长度限制）：
    1. GlobalSummary 注入 systemPrompt → 建立全局认知（分类骨架+链接图谱+最近活动）
    2. searchWiki("认知偏差") → ES 搜索相关页面，与 GlobalSummary 合并候选列表
    3. readFile("wiki/pages/认知偏差.md") → 读取候选页面完整内容
    4. getRelatedPages("认知偏差") → 通过链接图谱发现间接关联
    5. readFile("wiki/pages/xxx.md") → 继续读取新发现的相关页面
    6. 综合答案，Prompt 强制要求标注引用（如 [[认知偏差]] 或脚注）

  降级路径（仅在 ChatModel 不可用时）：
    → 单轮 DashScope chat + 搜索结果摘要（不含完整页面内容）
    → Prompt 告知用户回答可能不够完整

  → SSE 分块推送答案（ChatClient 同步返回后分块推送）
  → 对话历史持久化到 query_conversation（按 session_id 支持多轮追问）

  保存为 Wiki 页面（两种路径）：
    方式 A — 用户点击"保存" → POST /api/query/save
      → QueryService → HarnessEngine.executeSaveQueryResult
      → PipelineOrchestrator.runSaveQueryResultPipeline（4 步 Pipeline）：
        1. FORMAT_ANSWER — AI 将问答格式化为 {title, summary, category, content, sourceRefs} JSON
        2. WRITE_SAVED_PAGE — FS write + DB insert + ES index + wiki_page_source 关联
        3. SAVE_LINKS — 解析 [[page]](path) 引用 → wiki_page_link 交叉引用
        4. DETECT_SAVE_CONFLICTS — LLM 逐页比对最近页面，标记矛盾链接与健康状态
    方式 B — Agent 自主判断答案有沉淀价值 → 调用 writeFile 工具

  保存时必做三项：
    1. 同名冲突检查（title + scope_id 唯一，冲突时走 UPDATE_RELATED 融合）
    2. 同步 wiki_page_link + ES 索引
    3. 写 wiki_page_source 关联（来源为本次 query 的引用页面集合）
```

## 前端架构

### 页面结构

```
App.vue
  ├── AppSidebar（左侧固定，260px）
  │   ├── Logo + 应用名称
  │   ├── 搜索输入框（药丸形，常驻可见）
  │   ├── 导航分区（3 项）：
  │   │   ├── 知识库（首页）
  │   │   ├── 搜索问答
  │   │   └── 设置管理 ▾
  │   │       ├── 执行记录
  │   │       ├── Token 监控
  │   │       └── 系统配置
  │   └── 主题切换 + 用户菜单（底部）
  │
  ├── AppTopBar（内容区域头部）
  │   ├── 面包屑
  │   ├── 页面标题
  │   └── 操作按钮（右对齐：添加资料按钮）
  │
  └── ContentArea（主内容，可滚动）
      │   ← 路由在此渲染视图
      │
      ├── views/wiki/          — 知识库首页（目录+最近更新+最近来源+推荐）
      │                         Wiki 页面（双栏布局：正文+TOC侧栏+阅读进度条+健康指示器+来源标注+请求修改）
      ├── views/search/        — 搜索问答（双模式：搜索+问答+保存到知识库，SSE 流式推送 via useSSEQuery composable）
      ├── views/ingest/        — 添加资料（5步协作流程+内嵌来源管理）
      ├── views/harness/       — 执行记录列表、执行详情
      ├── views/dashboard/     — Token 监控
      ├── views/lint/          — 知识体检（后台定期运行）
      └── views/system/        — 登录页、系统配置
```

### 状态管理（Pinia Stores）

| Store | 领域 | 关键状态 |
|-------|------|----------|
| `useAuthStore` | 认证 | token, username, role, systemRole, scopeId, userId, scopes, isSystemAdmin, isAuthenticated |
| `useWikiStore` | Wiki 页面 | pages, currentPage, searchResults, categories, recentSources |
| `useExecutionStore` | Harness | executions, currentExecution, steps, approvalPending |
| `useSystemStore` | 系统 | config, providers |
| `useToastStore` | 通知 | toasts, addToast, removeToast |
| `useThemeStore` | 主题 | theme, toggleTheme |

### 关键前端模式

| 模式 | 文件 | 说明 |
|------|------|------|
| **MarkdownIt 单实例** | `WikiPageRenderer.vue` | 组件 setup 时创建一次 MarkdownIt 实例（非 computed 重建），TOC 通过 render-time side effect 实时收集 |
| **Shiki 全局单例** | `utils/shiki-highlighter.ts` | 懒加载初始化 `createHighlighter()`，25 语言 + github-light/github-dark 主题。`highlightWithShiki(code, lang)` 纯函数供 MarkdownIt highlight 回调使用，`escapeHtml()` 已导出 |
| **SSE Composable** | `composables/useSSEQuery.ts` | 从 SearchView 提取的 SSE EventSource 生命周期管理：`startQuery`/`reset`/`closeEventSource`，3 步进度自动推进，`onUnmounted` 清理 |
| **Wiki 双栏布局** | `WikiPageView.vue` + `wiki-content.css` | `grid-template-columns: 1fr var(--wiki-toc-width)`，TOC 侧栏 sticky + scroll-spy 滚动侦测 |
| **表格包裹** | `wiki-content.css` | `<div class="wiki-table-wrap">` 包裹 `<table>`，包裹层 `overflow-x: auto`，表格保持 `display: table` |
| **标题锚点高亮** | `wiki-content.css` | CSS `:target` + `heading-highlight` 2s 渐隐动画 |
| **阅读进度条** | `WikiPageView.vue` | fixed 2px 高度条，accent-primary 色，scroll 百分比驱动宽度 |
| **Token 瘦身注入** | `tokens.css` + `theme-light/dark.css` | `--text-on-accent` 替代 #FFFFFF 硬编码，暖色调中性白 #FDFCFA 替代纯白，--border-default #DCD9D4 替代 #E5E7EB |

### 主题系统

双主题通过 CSS 自定义属性实现：

```css
/* tokens.css — 结构性 Token（两种主题相同） */
:root {
  --space-1: 4px;
  --space-2: 8px;
  --radius-default: 8px;
  --font-body: 'Inter', 'Noto Sans SC', sans-serif;
  --font-heading: 'Noto Serif SC', serif;
  --font-code: 'JetBrains Mono', monospace;
  --font-h1: 32px;
  --font-h2: 24px;
  --font-h3: 20px;
  --wiki-layout-max: 960px;
  --wiki-toc-width: 220px;
}

/* theme-light.css — 亮色主题色彩 Token */
[data-theme="light"] {
  --bg-primary: #FDFCFA;        /* 暖色调中性白，非纯 #FFFFFF */
  --surface-card: #FDFCFA;
  --border-default: #DCD9D4;    /* 偏暖灰，非冷灰 #E5E7EB */
  --text-on-accent: #FDFCFA;    /* 强调色背景文字，替代 #FFFFFF */
  --accent-primary: #5E6AD2;
  /* ... 所有亮色主题 Token，见 DESIGN.md */
}

/* theme-dark.css — 深色主题色彩 Token */
[data-theme="dark"] {
  --bg-primary: #0F0F14;
  --text-on-accent: #1A1814;    /* 强调色背景文字 */
  --accent-primary: #7C3AED;
  /* ... 所有深色主题 Token，见 DESIGN.md */
}
```

**Token 使用硬性规则：**
- 禁止硬编码白色（`#FFFFFF`、`#fff`、`#FFF`、`white`）— 强调色背景文字使用 `var(--text-on-accent)`，表面背景使用 `var(--surface-card)` 或 `var(--bg-primary)`
- 禁止使用已弃用的 `card-*` Token（`card-bg`、`card-border`、`card-shadow`）— 统一使用 `surface-*` / `border-*` / `shadow-*`
- 亮色主题背景色为暖色调中性白 `#FDFCFA`（chroma ~0.005 向品牌色偏移），不是纯白

主题切换：`document.documentElement.setAttribute('data-theme', theme)`。默认跟随 `window.matchMedia('(prefers-color-scheme: dark)')`。

## 安全架构 — 知识库范围驱动的权限与隔离

### 核心设计：知识库范围（Scope）是权限和数据隔离的基础单元

系统面向 1000 人左右的团队使用，一期先满足个人知识库，后续推广至团队/部门/公司级知识库。

**演进路径：**
- 一期：个人知识库 — scopeId = userId，用户是 Owner，拥有所有权限
- 二期：团队知识库 — 引入 scope 表和 scope_member 表，4 种角色（Owner/Admin/Editor/Viewer）
- 三期：部门知识库 — 跨团队知识整合
- 四期：公司知识库 — 公司级知识资产

### 数据隔离

按知识库范围隔离，不是按用户隔离：

- 文件系统：`wiki-data/{scopeId}/raw/` 和 `wiki-data/{scopeId}/wiki/pages/` 每个范围独立目录（NAS/本地/S3，取决于配置）
- 数据库：所有 Wiki/Harness 表含 `scope_id` 字段，查询时强制 `WHERE scope_id = ?`
- Schema：每个范围独立 Schema 配置（摄入流程、审批级别、模型选择）
- Token 用量监控：每个范围独立月度参考值，个人 100W（1,000,000）、团队 1000W（10,000,000）；参考值不阻断操作，80% 触发提醒、100% 触发超额通知

### 权限体系

**双层角色模型：**

| 层级 | 字段 | 取值 | 作用域 |
|------|------|------|--------|
| 系统层 | `user.role` | `admin` / `user` | 全局（平台维护者 vs 普通用户） |
| 知识库层 | `scope_member.role` | `owner` / `admin` / `editor` / `viewer` | 单个 scope |

两层互相独立。`user.role=admin` 的平台管理员进入他人知识库时，默认只拥有 `viewer` 权限。

**一期（个人知识库）：**
- 用户是自己知识库的 Owner，拥有所有权限
- JWT 认证 + Spring Security `@PreAuthorize` 方法级安全
- `JwtAuthenticationFilter` 从 token 的 role claim 注入 `ROLE_ADMIN` / `ROLE_USER` authorities
- `user.status = active / disabled`，禁用用户 token 校验时直接 401

**二期（团队知识库）：**

| 角色 | 权限 |
|------|------|
| Owner | 所有操作 + 管理成员 + 删除知识库 |
| Admin | Schema 配置 + Token 参考值 + 成员管理 |
| Editor | 添加资料 + 知识问答 + 请求修改 + 保存到知识库 |
| Viewer | 阅读知识库 + 搜索 + 查看报告 |

Scope 级权限校验通过自定义注解 `@RequireScopeRole({"owner","admin"})` + AOP 切面实现，切面从参数中提取 `scopeId`，调用 `ScopeService.getMemberRole()` 校验。

### AI 操作限制

| 限制项 | 个人知识库 | 团队知识库 | 部门/公司知识库 |
|--------|-----------|-----------|---------------|
| Token 月度参考值 | 100W | 1000W | 待实现 |
| 单次摄入最大文件数 | 1 | 5 | 10 |
| 单次摄入最大文件大小 | 10MB | 50MB | 100MB |
| 并发执行数 | 1 | 3 | 5 |
| 审批级别默认值 | AUTO | CONFIRM | REVIEW |
| 请求修改审批 | AUTO | CONFIRM | REVIEW |

关键决策：个人知识库审批默认 AUTO（用户信任自己的指令），团队知识库默认 CONFIRM（修改影响他人知识）。

Token 用量监控（非硬限制）：Token 月度参考值为监控阈值，不阻断任何 AI 操作。80% 参考值时触发 `budget_warning` 通知提醒用户关注用量趋势，100% 参考值时触发 `budget_exceeded` 通知告知用户已超额（操作仍不受限制）。由 `TokenUsageMonitor` 在每次操作后记录用量并自动检查阈值，通知通过 `NotificationService` 发送，每周期仅通知一次（`warning_notified` / `exceeded_notified` 字段追踪）。

### 安全实现

- JWT 认证 + Spring Security `@EnableMethodSecurity`
- 系统级权限：Controller 方法通过 `@PreAuthorize("hasRole('ADMIN')")` 保护
- Scope 级权限：通过 `@RequireScopeRole({"owner","admin"})` + AOP 切面保护写操作
- 数据查询强制 `scope_id` 过滤，防止跨范围数据泄露
- LLM API 密钥在数据库中加密存储，绝不暴露到前端
- 文件系统访问通过 `scopeId` 路径隔离，不允许路径遍历

### 注册与冷启动

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `llmwiki.auth.allow-self-register` | `false` | 关闭自注册，新用户只能由 admin 后台创建 |
| `llmwiki.bootstrap.admin-usernames` | `admin` | 启动时自动创建/提升为 admin 的用户列表 |

首管理员冷启动流程：
1. 全新部署，数据库为空，配置 `ADMIN_USERNAMES=admin`
2. 启动时 `AdminBootstrap` 发现 `admin` 用户不存在，自动创建（生成 8 位随机临时密码）
3. 临时密码以 `WARN` 级别打印到启动日志，仅展示一次
4. 用该密码登录，立即重置密码

## 知识晋升架构 — AI 自动提炼与荣誉激励

知识晋升是系统的核心创新机制：个人知识库中的成熟知识被 AI 自动提炼到团队知识库，形成个人→团队→更上层 scope 的知识正向流动。**核心原则：鼓励分享、荣誉激励、事后退出。**

### 产品哲学

- **默认开放** — 页面默认 visibility=open，可被 AI 自动晋升扫描和提炼。注册/加入团队时签署知识共享协议，默认同意
- **荣誉激励** — 被晋升的知识标注贡献者身份，个人知识库首页展示"影响力"统计，团队首页展示贡献榜
- **事后退出** — 用户可将页面标记为 private 退出共享，系统自动召回已晋升内容（软召回策略）
- **隐私协议** — 加入团队时签署："你的 open 页面可被 AI 提炼晋升；晋升内容经 AI 蒸馏不搬运原文；贡献者身份会被标注；可随时标记 private 退出"

### 晋升机制（独立 Promote Pipeline）

知识晋升是系统的第四个核心操作（Ingest / Query / Lint / Promote），已从 Lint Pipeline 中独立出来。Lint 只负责诊断分析，晋升由专门的 Promote Pipeline 执行，需要个人同意和团队管理者审批。Promote Pipeline 由用户手动触发，不做定时自动调度。

```
步骤 1: SCAN_CANDIDATES（扫描晋升候选）
  输入：scope.upstream_scope_ids 配置的上游 scope 列表
  动作：
    1. 对每个上游 scope，查询 wiki_page 表：
       WHERE scope_id = {上游scopeId}
       AND visibility = 'open'
       AND 页面作者（非 scope Owner）的 consent_knowledge_promotion = 1
       AND source_count >= 3          — 多来源支撑（成熟度）
       AND health_status = 'healthy'  — 健康良好
    2. 排除本 scope 已晋升同源页面的记录
  输出：候选页面列表（含主题、分类、来源scope、健康度）
  审批：AUTO

步骤 2: MATCH_SCHEMA（Schema 对齐检查）
  动作：将候选页面的主题/分类与团队 Schema 的分类体系匹配，判断是否填补了概念缺口
  输出：对齐评估报告 + 优先级排序
  审批：AUTO

步骤 3: REQUEST_PERSONAL_CONSENT（请求个人授权）
  动作：向每个候选页面的作者发送晋升请求通知，等待确认或拒绝（超时默认拒绝）
  输出：获得授权的候选子集
  审批：CONFIRM — 页面作者逐一确认

步骤 4: REQUEST_TEAM_APPROVAL（团队管理者审批）
  动作：将获得个人授权的候选列表提交给团队 owner/admin，展示摘要+Schema对齐评估+蒸馏预览
  输出：最终批准的候选列表
  审批：REVIEW — 团队管理者审查后批准/拒绝/修改

步骤 5: DISTILL（AI 蒸馏提炼）
  动作：AI 蒸馏提炼每页知识：
    "提炼可共享的团队级知识，移除个人语境和隐私细节，保留知识核心结构。
     不搬运原文段落，不暴露原作者个人信息。输出是团队视角的知识，而非个人笔记。"
  输出：蒸馏后的团队级知识内容
  审批：AUTO

步骤 6: WRITE_PROMOTED_PAGE（写入晋升页面）
  动作：将蒸馏内容写入本 scope 的 wiki_page + 文件系统
    设置 promoted_from_scope_id / promoted_from_page_id / promoted_from_username（溯源+荣誉标注）
  输出：晋升页面列表
  审批：AUTO

步骤 7: ~~UPDATE_INDEX~~ **已废弃**
  动作：~~重建 index.md~~ 索引由 GlobalSummaryService 从 DB 实时构建
  输出：无操作
  审批：AUTO（已移除）

步骤 8: UPDATE_LINKS（建立交叉引用）
  动作：为晋升页面建立与已有团队页面的交叉引用链接
  输出：链接关系列表
  审批：AUTO

步骤 9: ~~APPEND_LOG~~ **已废弃**
  动作：~~追加晋升记录到 log.md~~ 执行记录直接查 execution 表 + 发送荣誉通知给贡献者
  输出：无操作
  审批：AUTO（已移除）
```

**Lint 与 Promote 的协作关系：**

- Lint 的 `WRITE_HEALTH_STATUS` 步骤为晋升提供成熟度信号（healthStatus = healthy 才可被晋升扫描）
- Lint 的 `SUGGEST_ACTIONS` 步骤可包含"建议晋升 X 页面"的推荐
- Lint 是诊断，Promote 是治疗，用户是决策者
```

### 隐私保护 — 两层防护 + 软召回

**两层防护：**

| 层级 | 控制维度 | 默认值 | 效果 |
|------|---------|-------|------|
| 全局 | `user.consent_knowledge_promotion` | 1（同意） | 0 = 我的页面不会被任何团队晋升扫描 |
| 页面 | `wiki_page.visibility` | open | open 可被扫描提炼；private 不被扫描，触发召回 |

晋升扫描过滤：`visibility = 'open' AND consent_knowledge_promotion = 1`，缺一不可。

**软召回策略（事后退出）：**

用户将页面标记为 private → 自动召回流程：

1. 查询下游 scope 中 `WHERE promoted_from_scope_id = {当前scopeId} AND promoted_from_page_id = {当前pageId}`
2. 对每个匹配页面：
   - 内容替换为存根："此知识条目因贡献者选择隐私保护而暂停展示"
   - `promoted_from_username = null`（移除署名）
   - `promoted_from_scope_id = null`，`promoted_from_page_id = null`（移除溯源）
   - `health_status = 'recalled'`
   - 更新文件系统（写存根内容）
   - 更新 ES 索引（标题保留，标记为已召回）
3. 通知下游 scope Owner："一条提炼知识已被贡献者撤回共享授权"
4. 该个人页面从此不再被任何晋升扫描
5. 荣誉统计中移除该页面的贡献计数

**选择软召回而非硬召回的理由：**
- 标题和概念名称属于公共知识而非个人隐私
- 保留标题维护团队知识图谱完整性（`[[认知偏差]]` 链接不会断裂）
- 移除内容和署名满足"不被关联"的隐私需求
- 存根页面告知团队"此处曾有一条知识"，维护知情权

### 荣誉激励体系

**贡献者感知：**
- 晋升完成 → 通知贡献者："你的知识《认知偏差》已被 AI 提炼并纳入「产品团队」知识库"
- 个人知识库首页"影响力"区域：展示"你的知识已被 N 个团队采纳，共 M 条知识被提炼"
- 团队知识库首页"贡献榜"：展示 Top 贡献者（按晋升页面数排名）

**团队页面标注：**
- 提炼页面底部标注："本知识提炼自 {贡献者用户名} 的个人研究"
- 提炼页面元数据含 `promoted_from_username` 字段，前端渲染为荣誉标注

### 知识晋升的信任梯度

```
个人知识库（visibility=open）→ AI 蒸馏提炼 → 团队知识库（集体智慧）
                                    ↓
                          蒸馏过程移除：
                          - 原文段落
                          - 个人观点
                          - 隐私细节
                          保留：
                          - 概念结构
                          - 知识关系
                          - 结论摘要
                          添加：
                          - 贡献者署名
                          - 溯源标记
```

### 晋升 API 端点

```
PUT    /api/wiki/visibility          — 切换页面 visibility（open/private，?id=pageId）
GET    /api/wiki/promotion-stats     — 个人晋升统计（被采纳数、影响力）
GET    /api/scope/{scopeId}/contributors — 团队贡献榜（Top 贡献者列表）
POST   /api/scope/{scopeId}/recall   — 软召回（private 页面触发召回流程）
```