# LLM Wiki — AGENTS.md（编码与实现指南）

> **English Overview**
>
> This is the developer guide for LLM Wiki — coding conventions, project structure, build commands, and configuration reference.
>
> **Quick facts:**
> - Backend: Java 17 + Spring Boot 3 + Spring AI + MyBatis-Plus (DDD-layered Maven multi-module under `llmwiki/app/`)
> - Frontend: Vue 3 + TypeScript + Vite + Element Plus (under `llmwiki-web-ui/`)
> - Package root: `org.cn.liuwt.llmwiki`
> - Build: `cd llmwiki && mvn clean package -DskipTests -pl app/bootstrap -am`
> - Run: `java -jar target/boot/llmwiki-bootstrap-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev`
> - Frontend dev: `cd llmwiki-web-ui && npm install && npm run dev` (http://localhost:5173)
> - Deploy: `docker-compose up -d` (MySQL + ES + MinIO + RocketMQ + App + Web UI)
>
> **Key conventions:** DDD layering (web → biz → domain → common), all tables require `scope_id`, `raw/` directory is immutable, unified `Result<T>` API responses, SLF4J logging, environment-variable-driven configuration.
>
> *The full document below is in Chinese. See [README.md](README.md) for an English overview.*

> **文档体系**：本项目有三份核心文档，各司其职：
> - **[ARCHITECTURE.md](ARCHITECTURE.md)** = 系统架构蓝图 — 系统怎么设计、组件怎么协作
> - **[DESIGN.md](DESIGN.md)** = UI 设计与用户体验规范 — 界面怎么画、交互怎么定义
> - **[AGENTS.md](AGENTS.md)**（本文档）= 编码与实现指南 — 如何写代码、项目怎么跑
>
> **推荐阅读顺序**：ARCHITECTURE.md（理解系统全貌）→ DESIGN.md（理解 UI 规范）→ AGENTS.md（开始编码）
>
> **冲突解决规则**：当文档之间存在不一致时，以实际代码实现为最终真相来源。

## 项目概述

LLM Wiki 是一个知识管理系统，通过 LLM 增量构建和维护持久化 Wiki。系统提供三个核心操作：Ingest（摄入/源文件处理）、Query（查询/知识检索）、Lint（健康检查），由基于 spring-ai 构建的 AI 执行引擎（Harness）驱动。

完整的系统架构设计、存储架构、Pipeline 流程、API 端点、数据流示例等，详见 **[ARCHITECTURE.md](ARCHITECTURE.md)**。
UI 设计规范、视觉主题、组件样式、交互模式等，详见 **[DESIGN.md](DESIGN.md)**。

## 知识编译硬性规则

LLM Wiki 对知识的处理本质上是 AI 对知识的一次"编译"。完整隐喻解释详见 **[ARCHITECTURE.md § 知识编译](ARCHITECTURE.md)**。以下是代码 review 时的四条红线：

1. **编译器必须完整** — Ingest 必须实现增量编译（更新已有页面，不是只创建新页面），必须有来源-页面追踪（`wiki_page_source`），链接器必须真正执行链接；Query 保存到知识库也必须走完整编译 Pipeline（`IngestOrchestrator` / `PipelineOrchestrator.runSaveQueryResultPipeline`：FORMAT_ANSWER → WRITE_SAVED_PAGE → SAVE_LINKS → DETECT_SAVE_CONFLICTS），禁止绕过分层直接调 DB/FS/ES
2. **项目隔离必须严格** — scope 隔离就像不同项目各有自己的 build 目录，所有关系表必须有 scope_id，文件系统路径必须包含 scopeId，raw 目录绝对不可写
3. **准确性优先于一切** — 编译产物的准确性是不可妥协的底线，优先级高于展示丰富度、格式规范和视觉美观。当富元素（Mermaid 图、ECharts 图表等）的使用需要 LLM 从源文档中"推断"或"构造"信息时，宁可不用也不能编造。Prompt 中所有格式指导必须服从此原则，不得为了追求页面视觉丰富度而牺牲事实准确性
4. **编译必须可追溯** — 编译过程允许有损（格式转换、内容综合），但每一段编译产物必须能回溯到原文。Raw 层是 source of truth。Wiki 层包含三种页面类型：摘要页（`summary`，跨源综合）、实体页（`entity`，跨源综合）、参考页（`reference`，单源高保真）。ES 统一索引所有 Wiki 页面（单源检索）。对于 `STRUCTURED` 类型且章节数 ≥ 3 的文档，章节编译模式（CHAPTER_BASED / LARGE_POLICY）下 `WriterAgent` 为每个章节生成参考页（LLM 结构化摘要 + 原文引用），确保高信息密度文档的编译产物保真度。parsed 层是纯编译中间产物，不被 ES 索引，不被 Query 搜索。**参考页只读锁定**：`reference` 页面是原文的忠实镜像，不可被其他文档 Ingest 的 `UPDATE_RELATED` 步骤更新（`updateRelatedPage` 自动跳过 `page_type=reference`）。**STRUCTURED 文档引用约束**：当源文档为 STRUCTURED 时，entity/summary 页面中的规则条款、定义表述、量化指标必须使用 blockquote（`>`）引用原文，不得改写，每条引用后标注来源章节

## Schema 共治宪法（编码约束摘要）

以下七条为编码层面不可绕过的硬规则。架构实现细节详见 **[ARCHITECTURE.md § Schema 治理](ARCHITECTURE.md)**。

### 规则 1：Schema 单文件 + 7 段固定骨架
每个 scope 的 `schema_config`（`config_key = 'wiki_schema'`）的 `config_value` 必须包含 7 个固定 H2 section，破坏骨架的写入由 `SchemaSkeletonValidator` 拒绝。

### 规则 2：Schema 必须注入 Prompt
所有 LLM 调用的 Prompt 必须通过 `SchemaInjector.prepend(scopeId, prompt)` 注入当前 scope 的 Schema 骨架（section 1-6，剥离 section 7 变更日志）。

### 规则 3：Schema 骨架先行，违规即阻断
AI 决策违反 Schema 规则 → REVIEW 级审批；Schema 未覆盖的新规则 → 先触发扩展申请再执行；Ingest 两段串审（Schema 扩展 + Wiki 内容）。

### 规则 4：强制冷启动，无模板逃生舱
新 scope 创建后、Schema v1 落库前，禁止任何 Ingest/Query/Lint 入口。Schema v1 由 `SchemaBootstrapAgent` 三轮引导对话产出。

### 规则 5：Schema 版本化，页面溯源
`schema_config_version` 记录版本历史链；`wiki_page.schema_version` 记录页面遵循的 Schema 版本；Schema 补丁必须带证据字段。

### 规则 6：不保留手动编辑入口
前端不提供 Schema 的 Markdown 直接编辑器，只提供只读渲染 + 版本切换 + AI 调整入口。REST API 绕过 AI 对话直接改写 Schema 的调用必须被拒绝。

### 规则 7：治理与防腐
`SchemaLint` 独立于 Wiki Lint 每周扫描；Section 6 四段式结构（诊断标准/干预层级/反馈学习/调度配置）；处置决策从 Schema 读取（`LintFindingService` 废弃 @Value 硬编码）；反馈学习闭环（用户连续忽略同类型 ≥ N 次自动降级探查敏感度）。

## 技术栈

### 后端

| 组件 | 技术 | 版本 |
|------|------|------|
| 运行时 | JDK 17+ | LTS |
| 框架 | Spring Boot | 3.x |
| AI 框架 | spring-ai | 1.1.2 |
| AI 模型接入 | spring-ai-starter-model-openai | OpenAI 兼容协议，支持多 Provider |
| AI 模型提供商 | 任意 OpenAI 兼容 API | DashScope / DeepSeek / Moonshot / OpenAI / Ollama，多 Provider 混用 |
| 安全认证 | Spring Security + JWT | Spring Boot 管理 |
| ORM | MyBatis-Plus | 3.5.x |
| 数据库 | MySQL | 8.x |
| 搜索引擎 | Elasticsearch + spring-data-elasticsearch | 8.15 / 5.4.5 |
| 对象存储 | NAS（默认）/ MinIO (S3 兼容，可选) | 生产环境 |
| 消息队列 | RocketMQ + rocketmq-spring-boot-starter | 5.3.1 / 2.3.1（分布式模式，可选） |
| 构建 | Maven | 3.9+ |
| API 风格 | RESTful JSON | — |

### 前端

| 组件 | 技术 | 版本 |
|------|------|------|
| 框架 | Vue 3 | 3.x |
| 构建工具 | Vite | 5.x |
| 语言 | TypeScript | 5.x |
| UI 库 | Element Plus | 2.x（使用设计 Token 定制） |
| 状态管理 | Pinia | 2.x |
| 路由 | Vue Router | 4.x |
| HTTP | Axios | 1.x |
| Markdown 渲染 | markdown-it + shiki + katex | — |
| Markdown 编辑器 | @bytemd/vue-next + plugin-gfm + plugin-highlight | — |
| 图谱 | vis.js / D3.js | — |
| 图标 | Lucide Icons (SVG) | — |

## 项目结构

采用 DDD 领域驱动设计分层架构，代码放置在 `llmwiki/app/` 目录下：

```
llmwiki-main/
├── DESIGN.md                  # UI 设计系统（设计 Agent 读取）
├── AGENTS.md                  # 本文件（编码 Agent 读取）
├── ARCHITECTURE.md            # 系统架构蓝图
│
├── llmwiki/                   # 后端（DDD 分层 Maven 多模块）
│   ├── pom.xml                # 父 POM
│   ├── llm-wiki.md            # 原始想法文档（仅作参考）
│   │
│   ├── app/bootstrap/         # 启动模块（Spring Boot Application 入口）
│   │   └── LlmwikiApplication.java
│   │   └── application.yml / application-dev.yml / application-prod.yml
│   │
│   ├── app/web/               # 适配层-Web（Controller、Response、Security 配置）
│   │   └── controller/        # AuthController, WikiController, IngestController...
│   │   └── security/          # Spring Security + JWT 配置
│   │   └── Response.java / PageResponse.java
│   │
│   ├── app/biz/service/       # 业务编排层（组合领域服务，实现业务流程）
│   │   └── ingest/            # IngestService — 摄入流程编排
│   │   └── query/             # QueryService — 查询流程编排
│   │   └── lint/              # LintService — 健康检查流程编排
│   │   └── auth/              # AuthService — 认证流程编排
│   │   └── harness/mq/       # 分布式消息（RocketMQ Consumer/Producer/DTO/Registry）
│   │   └── InnerService.java  # 内部接口定义
│   │
│   ├── app/domain/
│   │   ├── model/             # 领域模型层（实体、值对象、领域事件）
│   │   │   └── system/        # UserModel, RoleModel, PermissionModel
│   │   │   └── wiki/          # WikiPageModel, SourceModel, LinkModel
│   │   │   └── harness/       # ExecutionModel, StepModel, SchemaModel
│   │   └── service/           # 领域服务层（核心业务逻辑）
│   │       └── system/        # UserInfoService — 用户领域服务
│   │       └── wiki/          # WikiFileService, WikiSearchService, WikiIndexService
│   │       └── harness/       # HarnessEngine, PipelineOrchestrator, AgentRunner
│   │       └── harness/ingest/ # IngestOrchestrator, ParserAgent, AnalyzerAgent, WriterAgent, IndexerAgent, IngestStep, IngestContext
│   │       └── harness/tool/  # Wiki Tool 实现（readFile, writeFile, searchWiki...）
│   │       └── harness/tracker/ # ExecutionTracker
│   │       └── harness/governance/ # ApprovalService, SchemaManager, SchemaPatchService, SchemaInjector
│   │       └── harness/governance/parser/ # SchemaStructuredParser, SchemaSection6Parser, SchemaMarkdownRenderer
│   │       └── harness/governance/validation/ # SchemaSkeletonValidator, SchemaComplianceChecker, SchemaConsistencyChecker
│   │       └── harness/governance/bootstrap/ # SchemaBootstrapService, BootstrapAdvisor, ParadigmCatalog
│   │
│   ├── app/common/
│   │   ├── dal/               # 数据访问层（MyBatis Mapper、DO、数据库操作）
│   │   │   └── dataobject/    # UserDO, RoleDO, WikiPageDO, ExecutionDO...
│   │   │   └── mapper/        # UserMapper, WikiPageMapper, ExecutionMapper...
│   │   ├── util/              # 工具层（通用工具类、常量、异常、Result）
│   │   │   └── result/        # Result<T> 统一响应
│   │   │   └── exception/     # BusinessException, AuthenticationException...
│   │   │   └── constant/      # Constants
│   │   └── service/
│   │       ├── facade/        # 外部接口层（API DTO、接口定义）
│   │       │   └── model/     # UserInfo, WikiPageInfo, ExecutionInfo...
│   │       │   └── result/    # UserInfoResult, IngestResult...
│   │       └── integration/   # 集成层（调用外部系统）
│   │           └── ai/        # AI 集成（多 Provider 注册 + Slot 路由 + LlmClient）
│   │
│   ├── app/test/              # 测试模块
│   │
│   └── wiki-data/             # Wiki 文件存储（运行时，不在源码控制中；生产环境为 NAS 共享卷）
│       └── {scopeId}/             # 每个 scope 独立目录，一期 scopeId = userId
│           ├── raw/               # 原始来源文件（【不可变】，{uuid}-{filename} 避免冲突）
│           ├── parsed/            # 解析产物 .parsed.md（可重建缓存，严禁写 raw/）
│           ├── wiki/              # LLM 生成的 Wiki 页面（Markdown）
│           │   └── pages/         # 主题/实体/概念页面（编译目标码）
│           ├── assets/            # 下载的图片与媒体
│           └── schema/            # Schema 配置导出（供用户查看/编辑）
│
├── llmwiki-web-ui/            # 前端（Vue 3 + Vite）
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── src/
│   │   ├── main.ts
│   │   ├── App.vue
│   │   ├── router/            # Vue Router 配置
│   │   ├── stores/            # Pinia 状态管理
│   │   ├── api/               # Axios API 层
│   │   ├── styles/            # 设计 Token、全局样式、主题
│   │   │   ├── tokens.css     # CSS 自定义属性（颜色、间距、字体）
│   │   │   ├── theme-light.css
│   │   │   ├── theme-dark.css
│   │   │   └── global.css
│   │   ├── components/        # 共享组件
│   │   │   ├── layout/        # AppLayout, AppSidebar(3项导航), AppTopBar, AiAssistantPanel
│   │   │   ├── wiki/          # WikiPageRenderer, WikiSearch, WikiGraph, HealthIndicator
│   │   │   ├── harness/       # PipelineTracker, StepCard, ApprovalDialog
│   │   │   └── common/        # Button, Card, Input, Modal, ToastContainer
│   │   ├── views/
│   │   │   ├── system/        # 登录页（动态图谱背景）、系统配置（Wiki Schema编辑）
│   │   │   ├── wiki/          # 知识库首页（目录+最近更新+最近来源+推荐）、Wiki 页面（健康指示器+来源+请求修改）
│   │   │   ├── search/        # 搜索问答（双模式：搜索+问答+保存到知识库）
│   │   │   ├── ingest/        # 添加资料（5步协作流程+内嵌来源管理）
│   │   │   ├── harness/       # 执行记录列表、执行详情
│   │   │   ├── dashboard/     # Token 监控
│   │   │   └── lint/          # 知识体检（诊断项分组展示：孤立/过时/缺失引用/矛盾/缺口/建议，支持忽略与跳转页面）
│   │   └── utils/             # 辅助工具、格式化
│   └── public/
```

## 构建与运行命令

### 后端

```bash
# 构建（仅 bootstrap 模块及其依赖）
cd llmwiki
mvn clean package -DskipTests -pl app/bootstrap -am

# 开发环境启动（需要 MySQL + Elasticsearch 基础设施）
java -jar target/boot/llmwiki-bootstrap-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev

# 本地 mvn spring-boot:run 方式（需要先 install）
mvn clean install -DskipTests
cd app/bootstrap
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 运行测试
mvn test

# 运行指定模块测试
mvn test -pl app/domain/service
```

**后端启动要点（踩坑记录）：**

1. 可执行 jar 位置：Spring Boot Maven Plugin 配置了 `<outputDirectory>../../target/boot</outputDirectory>`，因此 repackage 后的可执行 jar 在 `llmwiki/target/boot/` 目录下（约 75MB），不是 `app/bootstrap/target/` 下的那个 12KB 小 jar（那个缺少主清单属性，直接运行会报错）
2. 基础设施依赖：后端启动必须连接 MySQL 和 Elasticsearch。默认 profile（无 `-Dspring.profiles.active`）指向 localhost 的 MySQL 和 ES，本地没有这些服务会启动失败。开发环境使用 `--spring.profiles.active=dev` 并通过环境变量配置基础设施地址
3. Flyway 迁移：dev 环境的 Flyway baseline-on-migrate=true，首次连接时会自动执行迁移。后续启动如果 schema 已是最新版本则跳过
4. Admin Bootstrap：启动时会自动检查 admin 用户是否存在并确保角色为 admin

### 前端

```bash
cd llmwiki-web-ui

# 安装依赖
npm install

# 开发服务器（http://localhost:5173/）
npm run dev

# 生产构建
npm run build

# 类型检查
npm run type-check

# 代码检查
npm run lint
```

**前端启动要点：**

1. Vite dev server 在 http://localhost:5173/，API 请求通过 proxy 转发到 http://localhost:8080/
2. 前端单独启动可验证 UI 改动（无需后端），但登录和数据操作需要后端服务
3. PowerShell 不支持 `&&` 语法连接命令，使用 `;` 代替。例如 `cd dir; npm run dev` 而非 `cd dir && npm run dev`
4. `vue-tsc --noEmit` 用于类型检查（exit code 0 = 无类型错误）

### 前后端联调启动流程

1. 编译后端：`cd llmwiki; mvn clean package -DskipTests -pl app/bootstrap -am`
2. 启动后端：`java -jar target/boot/llmwiki-bootstrap-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev`（后台长运行进程）
3. 启动前端：`cd llmwiki-web-ui; npm run dev`（Vite 开发服务器）
4. 验证：浏览器打开 http://localhost:5173/，登录后检查 UI

## 编码约定

### Java（后端）

- **包命名**：遵循现有 DDD 分层包结构 `org.cn.liuwt.llmwiki.{layer}.{domain}` — 例如：
  - `org.cn.liuwt.llmwiki.common.util.result` — Result 统一响应
  - `org.cn.liuwt.llmwiki.common.util.exception` — 异常体系
  - `org.cn.liuwt.llmwiki.common.dal.dataobject` — 数据对象（DO）
  - `org.cn.liuwt.llmwiki.common.dal.mapper` — MyBatis Mapper
  - `org.cn.liuwt.llmwiki.domain.model.system` — 系统领域模型
  - `org.cn.liuwt.llmwiki.domain.model.wiki` — Wiki 领域模型
  - `org.cn.liuwt.llmwiki.domain.model.harness` — Harness 领域模型
  - `org.cn.liuwt.llmwiki.domain.service.system` — 系统领域服务
  - `org.cn.liuwt.llmwiki.domain.service.wiki` — Wiki 领域服务
  - `org.cn.liuwt.llmwiki.domain.service.harness` — Harness 领域服务
  - `org.cn.liuwt.llmwiki.service.ingest` — 摄入业务编排
  - `org.cn.liuwt.llmwiki.service.query` — 查询业务编排
  - `org.cn.liuwt.llmwiki.service.lint` — 健康检查业务编排
  - `org.cn.liuwt.llmwiki.web.controller` — Web Controller
  - `org.cn.liuwt.llmwiki.web.security` — Security 配置
  - `org.cn.liuwt.llmwiki.integration.ai` — AI 集成（多 Provider 支持）
  - `org.cn.liuwt.llmwiki.facade.model` — API DTO
- **类命名**：PascalCase。Service 类以 `Service` 结尾，Controller 以 `Controller` 结尾，DO 以 `DO` 结尾，Model 以 `Model` 结尾
- **方法命名**：camelCase。使用描述性名称：`ingestSource`、`searchWikiPages`、`executeLintPipeline`
- **代码中不加注释**，除非明确要求。代码应通过命名自文档化。
- **异常处理**：使用 `llmwiki-common` 中的自定义异常层级。绝不静默捕获并吞掉异常。
- **日志**：使用 SLF4J。按适当级别记录：ERROR 用于失败，WARN 用于可恢复问题，INFO 用于关键业务事件，DEBUG 用于开发。
- **API 响应**：所有 REST 端点使用 `llmwiki-common` 的统一 `Result<T>` 包装器。
- **配置**：使用 `application.yml` 配合 profile 特定覆盖（`application-dev.yml`、`application-prod.yml`）。
- **数据库**：MyBatis-Plus 用于 CRUD。复杂查询使用 XML mapper 文件。Entity 类使用 `@TableName` 注解。
- **Spring AI 集成**：使用 `spring-ai-starter-model-openai` 通过 DashScope 兼容模式接入 LLM。ChatModel 注入使用 `@Autowired(required=false)` setter 方式，手动构建 ChatClient。工具实现使用 `@Tool` 注解。绝不直接调用 LLM API — 始终通过 Spring AI 抽象层。
- **数据隔离**：所有 Wiki/Harness 数据表必须包含 `scope_id` 字段。**关系表（`wiki_page_tag`、`wiki_page_keyword`、`wiki_page_link`、`wiki_page_source`）也必须包含 `scope_id`**，不依赖 JOIN 的隔离是不可靠的。一期 `scope_id = user_id`。所有数据查询必须强制 `WHERE scope_id = ?` 过滤（包括 `listPages`、`searchPages`等），禁止暴露无 scope 过滤的方法。文件系统路径必须包含 `scopeId`（`wiki-data/{scopeId}/`），不允许路径遍历。
- **文件职责红线**：`raw/` 是编译源码，**写入后绝对不可再写**；`WriteFileTool` 和 `WikiFileService` 必须校验目标路径，命中 `raw/` 直接抛出 `BusinessException`。`parsed/` 存放解析产物，禁止写入到 `raw/`。`wiki/` 是目标码，所有 AI 写入活动集中在此。**不得生成游离的 `health-report.md`**——健康状态是 `wiki_page.health_status` 字段。
- **增量编译硬性约束**：Ingest Pipeline 的 `UPDATE_RELATED` 步骤必须 **真正执行增量更新**（读已有页面 → LLM 融合 → 写回文件+数据库），不得降级为"只生成建议不落盘"。`WRITE_SUMMARY`/`UPDATE_RELATED` 必须同步写入 `wiki_page_source`关联表，建立来源→页面追踪。
- **多 Agent 编排架构**：Ingest Pipeline 由 `IngestOrchestrator` 编排 4 个 Agent（`ParserAgent` → `AnalyzerAgent` → `WriterAgent` → `IndexerAgent`），Agent 间通过 `IngestContext` 共享数据容器流转中间产物。`PipelineOrchestrator` 的 Ingest 方法委托给 `IngestOrchestrator`，Lint/QuerySave 仍由 `PipelineOrchestrator` 处理。Lint Pipeline 的诊断步骤从 5 个独立 AI 步骤（CHECK_ORPHANS/CHECK_STALE/CHECK_MISSING_CROSSREFS/CHECK_CONFLICTS/CHECK_GAPS）合并为 `PROBE_AND_VALIDATE` 单步——由 `LintProbeService` 编排 `LintAgent`（单次 LLM 调用读取 GlobalSummary + 上次诊断结果 + Schema 规则，输出 5 种诊断类型的结构化 JSON）与 SQL 安全网（orphan/stale 的确定性检测零 token），交叉验证合并去重。步骤定义从字符串硬编码升级为 `IngestStep` 枚举（类型安全，携带 type/requiresAi/baselineMs 元数据）。`WriterAgent` 采用 Plan-then-Execute 模式：主 Agent 先产出 `WritingPlan`（全局写作蓝图），再由 `writeSummaryPage`、`writeEntityPage×N`、`updateRelatedPage×N` 按 WritingPlan 并行执行（`CompletableFuture` + `writerExecutor` 线程池 + `LlmConcurrencyBarrier` 全局信号量控制 DashScope API 并发度），Planning 失败时降级为原串行流程。`AnalyzerAgent` 的并行 chunk 分析也受 `LlmConcurrencyBarrier` 约束，避免与 Writer 竞争导致 429 过载。
- **WritingPlan 结构化分发**：`WriterAgent` 将 `writingPlanJson` 解析为子 Plan，各并行子任务只注入与其相关的定向子 Plan（摘要页注入 `coreThesis + summaryOutline + consistencyRules`，实体页注入 `entityPlan + consistencyRules`，关联页注入 `affectedPagePlan + consistencyRules`），而非全量 WritingPlan JSON。减少每个子任务的重复 input tokens。
- **富元素风险分级使用**：`MARKDOWN_OUTPUT_CONSTRAINT` 对富元素采用准确性优先的条件性使用策略——代码块/表格/提示框/公式（风险低，直接抄录源文档）鼓励使用；Mermaid 图（风险中，需源文档步骤清晰完整才可构造）条件性使用；ECharts 图表（风险高，需源文档数字精确且可直接引用）谨慎使用。宁可不使用某类富元素，也不让 LLM 被迫构造不准确的内容。Prompt 中建立明确的优先级链：**准确性 > 格式规范 > 视觉美观**。
- **Schema 瘦身注入**：`SchemaInjector.prepend()` 注入时自动剥离 section 7（变更日志）——LLM 执行写作/分析时不需要历史变更记录，只需规则 section 1-6。每次注入减少 20-40% Schema 前缀 tokens。完整版 Schema 仍用于前端只读渲染和 Schema Lint 膨胀检查。
- **分析结果按需裁剪**：`WriterAgent` 为 entity page 和 related update 步骤裁剪 `analysisResult`——entity 步骤只注入与该实体相关的片段（含实体导航和实体间关系 section），related 步骤只注入与目标页面相关的差异点（含关键事实 section）。摘要页仍使用完整 analysisResult。减少 entity/related 步骤 50-70% 的 input tokens。
- **Chunk 分析 Prompt 瘦身**：`analyzeChunk` prompt 要求 LLM 输出精简版导航结果（3 个 section：实体列表含类型+定位、关键事实 3-5 条、关联点 1-2 条），而非完整 4 section 展开。每个 chunk output 从 ~2500 tokens 降至 ~800 tokens。`mergeAnalyses` prompt 同步适配精简格式。
- **自适应分析策略**：`AnalyzerAgent.analyze()` 根据文档长度自适应选择分析路径——文档 ≤ `singlePassMaxChars`（默认 1.2M 字符，约 857K tokens，基于 deepseek-v4-flash 1.5M token 上下文窗口 85% 安全比）时全文单次分析（零合并损失、跨章节引用完整、LLM 天然实体去重）；超限时退回章节分块 + 并发分析 + `skipMerge` 快速路径（平衡质量与性能）。配置项：`llmwiki.ingest.analysis.single-pass-max-chars`。绝大多数文档走单次路径，分块仅作为极限边界兜底。
- **分析结果按需裁剪动态化**：`WriterAgent` 的 `filterAnalysisForEntity` 和 `filterAnalysisForRelated` 不再使用硬编码截断常量（原 3000/2000 字符），改为从 `ExecutionStrategy` 动态获取 `maxAnalysisCharsEntity` / `maxAnalysisCharsRelated`（默认 8000/6000，FULL_CONTEXT 模式下提升到 20000/15000）。两个方法均优先使用 `EntityDossier` / `InformationCatalog` 构建定向信息，fallback 到旧行匹配逻辑。`writeEntityPage` 和 `updateRelatedPage` 方法签名新增 `IngestContext context` 参数以支持新逻辑。
- **pagesContext 改为 GlobalSummary**：`AnalyzerAgent.buildPagesContext()` 改用 `GlobalSummaryService.build()` 构建分类骨架（~800 tokens），替代全量 DB 查询 `wiki_pages` 表 + `estimateAndTruncatePageList` 截断（~12000 tokens）。`GlobalSummary` 存入 `IngestContext.pagesContext` 后，`WriterAgent.generateWritingPlan()` 和 `updateRelated()` 等直接复用分类骨架进行受影响页面推断。大幅减少 Ingest 分析的 input tokens 并消除截断导致的信息丢失。
- **ES 批量索引**：`WriterAgent` 并行写入阶段不再每个子任务独立调用 `syncPageToIndex`（逐条 ES 索引），改为汇聚完成后统一调用 `SearchService.bulkIndexPages(scopeId, allPages)`（`searchRepository.saveAll()` 批量写入）。批量失败时降级为逐条 `syncPageToIndex` + retry queue。
- **汇聚后补偿校验**：`WriterAgent` 在并行写入汇聚完成后执行 `verifySourceRelations()`，检查所有写入的 pageDO 是否都有对应的 `wiki_page_source` 关系记录，缺失的自动补建。防止并行写入部分失败导致的数据不一致。
- **InformationCatalog 信息目录**：`InformationCatalogBuilder`（纯 Java，无 LLM 调用）在 AnalyzerAgent 完成后、WriterAgent 写入前构建段落级精确定位索引。`InformationCatalog` 包含：`SectionNode` 树（文档层级结构）、`EntityRecord`（每个实体的所有出现位置，含 `MentionType` 分类：DEFINITION/RULE/DATA/ELABORATION）、`coOccurrenceGraph`（实体共现关系图）。实体定位使用全文搜索（`contains` + 别名列表），替代原来 `buildEntityChunkMap` 的 chunk 级粗匹配。存入 `IngestContext.informationCatalog`。
- **EntityDossier 实体档案**：`EntityDossierBuilder`（纯 Java）从 `InformationCatalog` 为每个 core/important 实体构建精确档案。`EntityDossier` 包含：`definitionText`（定义段落原文）、`ruleTexts`（规则条款原文列表）、`dataPoints`（数据指标原文列表）、`sectionElaborations`（按章节组织的阐述段落）、`relatedEntities`（共现实体 + 关系提示）。`formatForPrompt(maxChars)` 方法将档案格式化为 LLM 可读的 Markdown。`WriterAgent.buildEntitySourceFromChunks()` 优先使用 EntityDossier（精准投喂），fallback 到旧 chunk 拼接逻辑。存入 `IngestContext.entityDossiers`。
- **WriterOrchestrator 写入编排器**：`WriterOrchestrator` 作为 `WriterAgent` 的上层编排器，`writeWithVerification(context)` 方法执行四阶段流水线：①`WriterAgent.write()` 并行写入 → ②`WritingPlanComplianceChecker.check()` 确定性合规校验（术语替换+禁用短语+必需章节）→ ③`ConsistencyReconciler.reconcile()` 单次 LLM 全局一致性仲裁 → ④`WriterQualityVerifier.verify()` 结构质量校验。三阶段后处理形成"确定性先行 + AI 终审 + 结构兜底"的防线体系，消除多 Agent 并行写入的语义分歧。`IngestOrchestrator` 的 3 处写入调用已全部改为 `writerOrchestrator.writeWithVerification(context)`。
- **WritingPlanComplianceChecker 合规校验**：纯 Java 从 WritingPlan 的 `consistencyRules` 结构化对象中提取三类约束并逐页校验：①`termMap`（术语映射表）——key 为标准术语，value 为变体列表，发现变体自动全局替换并回写文件；②`forbiddenPhrases`（禁用短语列表）——发现即记录违规；③`requiredStructure`（必需章节）——按页面类型模式匹配检查章节完整性。`consistencyRules` 向后兼容旧 `string[]` 格式和新结构化 `object` 格式。
- **ConsistencyReconciler 一致性仲裁**：并行写入完成后单次 LLM 调用做全局一致性校验。收集所有页面内容（最多 15 页，每页 1500 字符预览），注入 WritingPlan 的 consistencyRules，要求 LLM 输出 `termInconsistencies`（术语不一致，含 standardTerm/variants/affectedPages）和 `factConflicts`（事实冲突，含 pageA/pageB/claimA/claimB）。Java 层执行术语替换修复（确定性操作），事实冲突记录为 unfixable。使用独立 `LlmConcurrencyBarrier.Bucket.RECONCILE` 信号量（默认 1 permit），避免与 Writer/Analyzer 竞争。
- **WriterQualityVerifier 质量校验**：写入后自动执行 4 维度校验（纯 Java，无 LLM 调用）：①实体覆盖率——检查每个实体的定义和规则条款是否出现在页面中；②摘要完整性——检查摘要页是否提及所有核心实体；③交叉引用对称性——检查 A→B 和 B→A 的双向引用；④来源追溯——检查关键事实能否追溯到 EntityDossier。校验结果存入 `IngestContext.verificationReport`，`CRITICAL` 级别问题记录警告日志（当前不阻断，积累数据后再开启自动重试）。
- **跨章节关系检测**：`InformationCatalogBuilder` 新增 `detectCrossChapterRelations()`（纯 Java），为每个跨章节出现的实体建立 `CrossChapterRelation` 记录（`DEFINED_IN`/`REFERENCED_BY`/`DEPENDS_ON`/`SUPERSEDES`），包含定义章节、引用章节、关系类型和上下文片段。`EntityDossierBuilder.extractExtendedParagraph()` 为阐述段落多取 1 段相邻上下文，增强跨段落信息完整性。
- **STRUCTURED 文档双层编译**：对于权威性文档（法律合同、监管规定、公司制度、操作规程、产品告知书等），`DocumentStructureAnalyzer` 通过 `AUTHORITATIVE_TITLE_PATTERN` 语义匹配标题中的权威性关键词，优先判定为 `STRUCTURED` 类型（零 LLM 调用，性能无损）。章节编译模式（`writeChapterBased`）并行生成三类页面：①参考页（`reference`）——原文忠实镜像，不可被 `UPDATE_RELATED` 改写（只读锁定）；②摘要页（`summary`）+ 实体页（`entity`）——AI 编译，但受 blockquote 引用约束（规则条款、定义表述、量化指标必须引用原文，不得 paraphrase）。Lint 差异化治理：`reference` 页面不参与 stale 时间推测和 orphan 检测（SQL 层过滤），同源参考页对之间的冲突提升为 P0 级（`detectReferenceConflictsBySql`）。
- **IndexerAgent 并行化 + 异步 Schema 补丁**：`IndexerAgent.index()` 中 `UPDATE_LINKS` 和 `PROPOSE_SCHEMA_PATCH` 无依赖关系，通过 `CompletableFuture` 并行执行取 max 耗时。`PROPOSE_SCHEMA_PATCH` 改为异步执行——Pipeline 标记 `completed` 后后台线程生成补丁，先提取 `patchSummary` 纯文本（不依赖 IngestContext 生命周期），再异步调用 `SchemaPatchProposer.propose()`。用户感知时间 -25s。
- **全局 LLM 并发控制**：`LlmConcurrencyBarrier`（默认 8 permits）作为全局 `@Component` 信号量，`AnalyzerAgent`（ParallelAnalysisExecutor）和 `WriterAgent`（writeSummaryPage/writeEntityPage/updateRelatedPage/generateWritingPlan）共享使用，避免两者合计峰值 14 个并发 LLM 调用导致 DashScope 429 过载。
- **知识矛盾检测与处置**：`IngestContext` 新增 `ConflictAnnotation` record（pagePath, conflictType, existingClaim, newClaim, resolution, sourceRef）和 `conflictAnnotations` / `schemaPatchHints` 字段。`WriterAgent.writingPlan()` 的 prompt 要求 LLM 输出 `conflictAnnotations[]`，`extractConflictMeta()` 从 WritingPlan JSON 解析矛盾标注，`processConflictAnnotations()` 生成 Schema 补丁建议。页面写入时通过 `hasConflictAnnotationsForPath()` 判定 `healthStatus = "conflict-warning"`。`IndexerAgent.createContradictionLinks()` 创建 `linkType="contradiction"` 的矛盾链接。Query Save 流程 `runSaveQueryResultPipeline` 新增 `DETECT_SAVE_CONFLICTS` 步骤——LLM 逐页比对最近 20 个页面并标记矛盾链接与健康状态。所有裁决策略从 Schema 6.5 `LintRulesConfig.ConflictResolutionRules` 读取，向后兼容（无配置时默认 `annotate_both`）。
- **DashScopeChatClient 超时生效**：`LlmClient.chatWithRetry()` 改为 `CompletableFuture.supplyAsync().get(timeoutMs, TimeUnit.MILLISECONDS)` 模式，`timeoutMs` 配置（默认 60000ms）真正生效。超时异常视为可重试，纳入指数退避重试循环。`streamChat` 方法不受此改动影响。
- **前端并行进度模型**：`progressModel.ts` 新增 `ParallelGroup` 定义和 `PHASE2_PARALLEL_GROUPS` 配置，`estimateRemainingMs` 改为并行组取最大剩余时间而非累加。`IngestStepTimeline.vue` 新增并行组视觉标记：并行组 header（`Layers` 图标 + "页面写作进行中"提示）、并行步骤 `并行` badge + 缩进样式。`ingestProgress.ts` 的 `computedCurrentTip` 改为并行聚合提示（多步骤同时 running 时显示"页面写作进行中（N 个任务并行）"）。
- **AI 长等待进度反馈**：`ANALYZE_CHUNKS` 步骤（Phase 1 权重最高、耗时最长的 AI 步骤）必须通过 SSE `step_progress` 事件实时推送每个 chunk 的实体发现结果（`chunkIndex` + `chunkPreview`，从 LLM 返回的「1. 主要实体列表」section 中正则提取）。前端 `EntityDiscoveryWall` 组件以标签墙形式渲染逐段发现的实体，每个新标签以 staggered spring 动画入场（opacity + translateY + scale），降低用户长等待焦虑。其他 AI 步骤不推送 chunkPreview（LLM 输出无结构化实体列表）。数据通路：`ParallelAnalysisExecutor.whenComplete` → `ProgressListener`（扩展签名）→ `ExecutionTracker.publishStepProgress`（重载）→ `StepProgressEvent`（新字段）→ `IngestController.onStepProgressEvent`（SSE map 追加）→ 前端 `ingestProgressStore.chunkPreviews` 累积。
- **步骤预估耗时**：所有 `baselineMs >= 3000` 的步骤在 `running` 状态时，`IngestStepTimeline` 组件在 tip 区前置展示预估剩余时间（`约 Xs`，由 `baselineMs - elapsed` 计算），耗时超过基线 1.5 倍时显示「比预期稍慢...」。此逻辑纯前端实现，不依赖后端推送，让每个 AI 步骤都有基础进度感知。
- **文件命名**：`raw/` 下文件一律用 `{uuid}-{filename}` 格式避免同名冲突。索引已不再维护 FS 副本（~~index.md/log.md~~），改由 GlobalSummaryService 从 DB 实时构建 + execution 表查询。
- **存储层**：通过 `StorageProvider` 接口操作文件存储，禁止直接使用 `java.nio.file.Files`。`LocalStorageProvider` 用于开发环境（matchIfMissing=true），`NASStorageProvider` 用于生产环境（NAS 共享卷 + FileLock），`S3StorageProvider` 用于云原生场景（MinIO/S3 兼容）。切换只需改配置 `llmwiki.storage.provider`，业务代码零改动。`StorageProvider` 接口新增 `append()` 方法，支持 O(1) 追加写入——`LocalStorageProvider` 使用 `StandardOpenOption.APPEND`，`NASStorageProvider` 使用 `FileChannel.APPEND` + `FileLock`，`S3StorageProvider` 使用读+合并+写（S3 不支持原生追加）。
- **Token 用量监控**：每次 AI 操作后记录 Token 消耗（`TokenUsageMonitor.recordUsage`），80% 参考值触发 `budget_warning` 通知、100% 触发 `budget_exceeded` 通知。Token 参考值不阻断操作——不存在 `budget_exhausted` 阻断逻辑，已从 Pipeline 入口和步骤间移除所有硬阻断检查。个人知识库审批级别默认 AUTO，团队知识库默认 CONFIRM。操作频率和文件大小限制按范围类型配置。
- **Query 工具注册**：Query 的 `ChatClient` 必须同时注册读工具和写工具：读（`readFile`、`searchWiki`、`getRelatedPages`、`listPages`）+ 写（`writeFile`、`updateLinks`）。Prompt 采用三层回答架构（Layer 1 Wiki 事实 + Layer 2 AI 解读 + Layer 3 前瞻推演），每层有不同可信度标签，实现"可信与有价值"的平衡。
> **2026-05 变更**：已移除 `updateIndex` 和 `appendLog` 工具（从 8 tools 降为 6 tools），索引与日志统一由 DB 管理。
- **Query 自主导航约定**：流式路径（`runQueryAgentStreaming`）由系统预检索（`RetrievalService`）代替 Agent 自主导航——系统构建 GlobalSummary（通过 `GlobalSummaryService` 从 DB 构建，含分类骨架+链接图谱+最近活动，替代读取完整 index.md）+ ES 结果 + Top-5 页面内容，注入 streamingSystemPrompt。LLM 只在预检索内容不足以回答问题时才调用工具补充检索。非流式路径（`runQueryAgent`）也注入 GlobalSummary 提供全局认知，Agent 仍需通过工具（readFile/searchWiki）检索具体页面内容以获取完整信息。
- **Query SSE 路径选择**：SSE 流式接口（`/api/query/stream`）采用两阶段架构：`RetrievalService.preRetrieve()` 系统预检索（~500ms-2s 并行 IO），然后 `ChatClient.prompt().stream().content()` 返回 `Flux<String>` 真 token 级流式。`SseEmitter` 通过 Flux 的 `doOnNext`/`doOnComplete`/`doOnError` 驱动 SSE 事件推送（start → mode → answer-chunk×N → answer-complete）。预检索失败或 streaming 报错时降级为 `runSimpleQuery`（单轮 DashScope chat + 搜索结果摘要），降级 prompt 须告知用户回答可能不够完整。ChatModel 不可用时降级为 mock 回答。
- **Query 多格式输出**：Prompt 引导 Agent 根据问题类型输出合适的格式——对比/差异问题自动产 Markdown Table，汇报/分享需求产 Marp 兼容 Slide，趋势/分布问题标注 `（可生成可视化图表）`。Table 和 Slide 为纯文本零额外开销，Chart 为按需生成（匹配 PARSE_DOCUMENT 的 Python 进程调用模式）。
- **Query 自动沉淀**：当回答涉及 ≥2 个 Wiki 页面的跨页面对比或填补了已知信息缺口时，Agent 在回答末尾主动提示沉淀建议。只有用户明确说"保存/沉淀"或点击保存按钮时才触发写入，确保人的指令在前。
- **Query 预检索服务**：`RetrievalService` 为流式 Query 提供两阶段架构的第 1 阶段——系统级预检索。`preRetrieve(scopeId, question)` 构建 GlobalSummary（同步 DB 查询），并行执行 ES 搜索（单源 Wiki 索引，含摘要页/实体页/参考页），汇聚后并行读取 Top-5 匹配页面的完整内容（5s 超时），构建 `RetrievalContext`。`RetrievalContext.toPromptContext()` 将预检索结果格式化为 LLM 可消费的 Markdown 上下文字符串：全局摘要部分注入分类骨架+链接图谱+最近活动（~800 tokens）、搜索结果和页面内容部分统一截断（5000 字符，超长加"可使用 readFile 工具读取完整内容"提示）。Token 消耗在流式路径中通过 `AtomicInteger` 累积 chunk 字符数，`doOnComplete` 时 `estimateTokensFromChars`（charCount / 2）上报。
- **Lint 后台调度**：Lint Pipeline 必须通过 `@Scheduled` 后台定期运行（个人每周、团队每天）。`WRITE_HEALTH_STATUS` 步骤必须将结果回写到 `wiki_page.health_status` 和 `last_checked_at`，前端直接读字段渲染健康指示器。诊断步骤从 5 个独立步骤合并为 `PROBE_AND_VALIDATE` 单步——`LintProbeService` 编排 `LintAgent`（单次 LLM 调用读取 GlobalSummary + 上次诊断结果 + Schema 规则，识别 5 种诊断类型：orphan/stale/missing_crossref/conflict/gap）与 SQL 安全网（orphan 纯 SQL 检测零入站链接、stale Part 1 纯 SQL 检测来源更新滞后），交叉验证合并去重（AI 发现覆盖 SQL 发现时跳过 SQL 重复）。`LintContext` 共享上下文扩展为 11 字段（全量页面/来源/链接/关键词/标签 + GlobalSummary/previousFindings/previousHealthStatus），替代冗余 SCAN_WIKI 步骤。反馈学习闭环：`LintFindingService.recordFeedback()` 记录用户反馈（accepted/ignored/modified），同一类型连续忽略 ≥ `dismissCountToDowngrade` 次时自动降级探查敏感度，下次探查通过 `buildPreviousFindingsSummary()` 注入历史反馈统计。所有诊断项持久化到 `lint_finding` 表（新增 `user_feedback` + `feedback_count` 字段），`LintFindingService.createFinding()` 实现 upsert 逻辑。Token 预算栅栏（`LINT_TOKEN_BUDGET=30000`）：AI 探查步骤调用前检查累计 token，预算耗尽时跳过探查仅执行 SQL 安全网。错误隔离：单步异常不阻断整体 Pipeline，`hasFailures` 标志记录。调度防重：定时任务检查 1 小时内是否已完成且无新来源，跳过不必要执行。渐进式深度：>200 页面时 SQL 安全网仍全量执行，AI 探查优先采样有健康问题的页面 + 均匀采样健康页面。log.md 已废弃（~~LogRetentionService 已删除~~），执行记录直接查 execution 表。
- **编译三原则优先级**：质量（信息准确、表述一致、编译抽取理解正确）> 性能（大知识库高使用人数尽可能快）> 成本（省 Token）。当三者冲突时按此优先级裁决。禁止为节省 Token 而牺牲编译准确性。
- **ExecutionStrategy 动态化**：`IngestionStrategyAdvisor.selectStrategy(DocumentProfile)` 根据文档特征（长度、章节数、代码块密度）自动选择 5 种 Preset（COMPACT / NARRATIVE / TECHNICAL_RICH / CHAPTER_BASED / LARGE_POLICY），再调用 `adjustForProfile()` 基于模型上下文窗口（1.5M 字符，85% 安全比）动态计算 `QualityTier`（FULL_CONTEXT / BATCH_OPTIMIZED / SAMPLED）和各参数上限。禁止在业务代码中硬编码 Ingest 参数——所有上限必须通过 ExecutionStrategy 获取。
- **智能采样替代暴力截断**：`sampleSourceContent()` 采用头 35% + 中 30% + 尾 35% 均匀采样，替代原 `truncateSourceContent()` 的头部截断。确保大文档（400+ 页制度文件）的信息从各部分均被保留。所有需要裁剪源内容的场景必须使用 `sampleSourceContent()`，禁止使用 `truncateSourceContent()`。
- **批量参考摘要生成**：`batchGenerateReferenceSummaries()` 将原本 30 个独立 LLM 调用合并为 1 次批量调用（JSON 数组格式输出），质量不达标时自动 fallback 到逐条生成。新增参考页生成逻辑必须走批量路径。
- **参考页原文保真**：`writeReferencePage()` 不再对章节内容做 50K 截断，完整保留原文。参考页是单源高保真产物（`page_type=reference`），截断会破坏保真度，违反编译规则第 4 条"编译必须可追溯"。
- **交叉引用一致性**：`LinkWritingService` 创建链接时 `fromPage`/`toPage` 必须使用页面 path（非 title），且必须在页面写入完成（writeFile + DB insert）之后才生成链接，避免时序断裂。`wiki_page_link` 表的 `from_page_id`/`to_page_id` 必须引用已存在的 `wiki_page.id`。

### TypeScript（前端）

- **文件命名**：Vue 组件使用 PascalCase（`WikiPageRenderer.vue`），工具函数使用 camelCase（`formatDate.ts`）
- **组件风格**：`<script setup lang="ts">` + `<template>` + `<style scoped>`。样式部分使用设计 Token CSS 变量。
- **代码中不加注释**，除非明确要求。
- **状态管理**：Pinia stores。每个领域一个 store：`useWikiStore`、`useHarnessStore`、`useSystemStore`。
- **API 调用**：集中在 `src/api/` 层。每个模块有自己的 API 文件：`wiki.ts`、`harness.ts`、`system.ts`。
- **设计 Token**：始终使用 CSS 自定义属性（`var(--accent-primary)`、`var(--space-4)`）。绝不硬编码颜色或间距值。
- **图标**：使用 Lucide 图标组件。绝不使用 Emoji 作为图标。
- **主题**：通过 CSS 自定义属性支持双主题。主题切换在 `theme-light.css` 和 `theme-dark.css` Token 集之间切换。默认跟随系统偏好。

## 模块依赖（DDD 分层）

```
依赖方向（从底到顶）：

common/util（最底层，无内部依赖）
  ↑
common/dal（依赖 common/util）
  ↑
common/service/facade（依赖 common/util）
common/service/integration（依赖 common/util）
  ↑
domain/model（依赖 common/dal + facade + integration）
  ↑
domain/service（依赖 domain/model）
  ↑
biz/service（依赖 domain/service + facade）
  ↑
web（依赖 biz/service + facade）
  ↑
bootstrap（依赖 web + biz-service，启动入口）
```

**依赖规则：**
- `common/util` 无内部依赖 — 它是最底层
- `common/dal` 只依赖 `common/util` — 数据访问层
- `domain/model` 依赖 `common/dal` + `facade` + `integration` — 领域模型
- `domain/service` 只依赖 `domain/model` — 领域服务，核心业务逻辑
- `biz/service` 依赖 `domain/service` + `facade` — 业务编排层
- `web` 依赖 `biz/service` — Web 适配层
- `bootstrap` 依赖 `web` + `biz-service` — 启动模块
- 无循环依赖。底层模块不向上依赖高层模块。
- Harness 引擎核心逻辑放在 `domain/service`（领域服务层）
- Ingest/Query/Lint 编排放在 `biz/service`（业务编排层）
- DashScope AI 集成放在 `common/service/integration`（集成层），现已升级为多 Provider 架构（AiProviderRegistry + AiSlotRouter + LlmClient）

## 关键库及其用法

### spring-ai

- **ChatClient 工具调用**：Query 操作采用**两阶段架构**——阶段 1 系统预检索（`RetrievalService.preRetrieve`：GlobalSummary + ES 搜索 Wiki 页面，再并行读取 Top-5 匹配页面完整内容），阶段 2 LLM 流式回答生成（`ChatClient.prompt().stream().content()` 返回 `Flux<String>`，SseEmitter 真流式推送）。预检索结果通过 `RetrievalContext.toPromptContext()` 注入 streamingSystemPrompt。LLM 只在预检索不足时才调用工具补充检索，大幅减少 tool-calling 轮次。`ChatClient.defaultTools()` 注册 6 个 Wiki 工具（4 读：readFile/searchWiki/getRelatedPages/listPages + 2 写：writeFile/updateLinks）。Ingest Pipeline 步骤由 4 个 Agent 分担：`ParserAgent`（无AI）、`AnalyzerAgent`（并行分析+合并+元数据）、`WriterAgent`（摘要页+实体页+参考页+关联页）、`IndexerAgent`（索引+链接+日志+补丁）。Lint Pipeline 诊断步骤由 `LintProbeService` 编排 `LintAgent`（单次 LLM 调用 + SQL 安全网交叉验证），其余步骤仍使用 `PipelineOrchestrator` 串行调用。
- **Graph API**：用于带条件路由的复杂 Pipeline 编排（例如 Ingest Step 5 中 LLM 决定更新哪些页面）。**注意：Query 不再使用 spring-ai-alibaba 的 ReactAgent**，因其在 DashScope 兼容模式下 tool calling 格式不兼容，已替换为 spring-ai 同源的 ChatClient。
- **OpenAI ChatModel (多 Provider 模式)**：主要 LLM 接入方式。通过 `spring-ai-starter-model-openai` 连接任意 OpenAI 兼容 endpoint。`AiProviderRegistry` 为每个配置的 Provider 构建独立 ChatModel，`AiSlotRouter` 按槽位路由。`LlmClient` 增加了指数退避重试（默认3次）和可配置超时（默认60s），429/502/503 响应自动重试。
- **Context Engineering**：使用内置 HITL 支持审批节点。长 Query 会话使用上下文压缩。
- **Tool Calling**：通过 `@Tool` 注解注册 Wiki 工具。Query 工具由 ChatClient 自动发现和调用；Ingest Agent 内部由 LlmClient 单轮调用。
- **三层回答架构**：Query Prompt 引导 LLM 输出三层结构——Layer 1 Wiki 事实（可信基础，逐条标注来源）、Layer 2 AI 解读（训练知识补充视角，标注为分析）、Layer 3 前瞻推演（趋势预判，标注为仅供参考）。三层标签让用户自行判断可信度，实现"可信与有价值"的平衡。

### MyBatis-Plus

- 系统表（用户、角色、权限、执行记录、页面元数据）的标准 CRUD。
- 使用 `LambdaQueryWrapper` 进行类型安全查询。
- 分页：使用 `Page<T>` 配合 `IPage` 返回类型。

### NASStorageProvider

- 生产默认存储提供商，基于 NAS 共享卷的 POSIX 文件操作。
- 通过 `FileLock` 保证多节点并发写安全，读操作无锁。
- 启动时对 NAS 路径执行健康检查（写入+读取+删除验证）。
- 配置：`llmwiki.storage.provider=nas`，路径 `llmwiki.storage.nas.path`。
- 与 `LocalStorageProvider` 共享相同的 POSIX 逻辑，但语义不同：NAS 要求所有节点挂载同一路径。

### Element Plus（定制化）

- 作为基础组件库使用，但用设计 Token 覆盖样式。
- 绝不使用 Element Plus 默认颜色 — 始终映射到我们的设计系统 Token。
- 定制：按钮颜色、输入框样式、卡片样式、对话框样式、表格样式。
- 使用 Element Plus 布局组件（`ElMenu`、`ElTabs`、`ElDialog`）作为结构骨架。

### 文档解析引擎（Python）

系统通过 `PythonProcessRunner` 调用 `doc_parser.py` 子进程解析 PDF/DOCX/PPTX/XLSX 等格式文件。解析引擎采用分层回退架构：每个格式一线引擎追求最佳质量，二线/三线兜底保障可用性。

#### Python 依赖（渐进式安装）

| 依赖 | 安装命令 | 作用 | 必需性 |
|------|---------|------|--------|
| PyMuPDF | `pip install pymupdf` | PDF 一线引擎（markdown 模式提取） | **必需** |
| python-docx | `pip install python-docx` | DOCX 兜底引擎 + 元数据提取 | **必需** |
| openpyxl | `pip install openpyxl` | XLSX 解析引擎 + 元数据提取 | **必需** |
| python-pptx | `pip install python-pptx` | PPTX 兜底引擎 + 元数据提取 + 备注页 | **必需** |
| pypdf | `pip install pypdf` | PDF 二线兜底 + 加密检测 + 元数据 | 推荐 |
| pandas | `pip install pandas` | XLSX pandas 一线引擎 | 可选 |
| markitdown | `pip install "markitdown[pptx]"` | PPTX 二线引擎 | 可选 |
| pandoc | `apt install pandoc` / `winget install pandoc` | DOCX/PPTX 一线引擎 | 可选 |

**降级逻辑**：所有可选依赖缺失时自动跳到下一层，不影响核心功能。一线引擎缺失时二线兜底仍能完整提取文字内容，只是丢失部分格式细节（如 markdown 标题层级、表格结构）。

#### 各格式解析架构

| 格式 | 一线引擎 | 二线引擎 | 三线兜底 | 特色能力 |
|------|---------|---------|---------|---------|
| PDF | fitz (markdown模式，自动探测兼容性) | pypdf | — | 加密检测 + 扫描件预警 |
| DOCX | pandoc | python-docx | — | 表格+列表识别 + 元数据 |
| PPTX | pandoc | markitdown | python-pptx | 备注页提取 + 占位符识别 + 元数据 |
| XLSX | pandas | openpyxl | — | 500行截断 + 元数据 |
| MD/TXT | 直接读取 | — | — | — |
| CSV | 直接读取 | — | — | — |
| JSON | 直接读取 | — | — | — |

所有引擎在输出 JSON 中标注 `extractionEngine` 字段，方便统计分析和问题排查。

#### 文件格式支持（全链路一致性）

前端 `IngestView.vue` accept 属性、后端 `FileFormatValidator.isParsableFormat()`、`ParserAgent.needsDocumentParsing()` 三层格式白名单必须保持一致：

| 格式 | 前端 accept | Java isParsable | Python 解析 | 魔数校验 |
|------|-------------|-----------------|-------------|---------|
| pdf | ✓ | ✓ | fitz → pypdf | `%PDF` |
| docx | ✓ | ✓ | pandoc → python-docx | `PK` (ZIP) |
| doc | ✓ | ✓ | 同 docx 路由 | `D0CF` (OLE) |
| xlsx | ✓ | ✓ | pandas → openpyxl | `PK` (ZIP) |
| xls | ✓ | ✓ | 同 xlsx 路由 | `D0CF` (OLE) |
| pptx | ✓ | ✓ | pandoc → markitdown → python-pptx | `PK` (ZIP) |
| ppt | ✓ | ✓ | 同 pptx 路由 | `D0CF` (OLE) |
| md | ✓ | ✓ | 直接读取 | 无（文本） |
| txt | ✓ | ✓ | 直接读取 | 无（文本） |
| csv | ✓ | ✓ | 直接读取 | 无（文本） |
| json | ✓ | ✓ | 直接读取 | 无（文本） |

`FileFormatValidator.isValidFormat()` 通过文件头魔数校验防止扩展名伪造。ZIP 类格式（docx/xlsx/pptx）共享 `PK` 魔数，OLE 类格式（doc/xls/ppt）共享 `D0CF` 魔数。

### OCR 策略（视觉模型辅助解析）

对于包含大量图片、扫描件、手写体等无法通过文字层直接提取的复杂文档，系统采用 **DashScope 视觉模型按需唤醒** 策略，而非部署本地 OCR 工具链（pytesseract/PaddleOCR），以降低运维复杂度。

#### 方案选型

| 方案 | 单价（每千Token） | 中文OCR精度 | 部署成本 | 推荐度 |
|------|-----------------|-----------|---------|--------|
| **qwen-vl-ocr** | 0.3元输入 / 0.5元输出 | 专用最优 | 零（同 DashScope 生态） | ⭐⭐⭐⭐⭐ |
| qwen-vl-plus | ~0.4元输入 / ~1.2元输出 | 良好（兼顾推理） | 零 | ⭐⭐⭐ |
| qwen3-vl-8b（自部署） | 免费（需GPU） | 良好 | 高（GPU服务器运维） | ⭐⭐ |
| GLM-4V-Flash | 免费 | 良好 | 低（但非阿里生态） | ⭐⭐ |
| pytesseract | 免费 | 差（中文需额外语言包） | 高（tesseract二进制+语言包） | ⭐ |

**推荐方案：`qwen-vl-ocr`**

理由：
1. **生态一致性** — 与现有 DashScope + spring-ai OpenAI 兼容模式完全一致，同一 API Key、同一 endpoint，零额外部署
2. **精度最高** — 专为文档/表格/手写体 OCR 设计，DocVQA 评测领先
3. **成本极低** — 一页扫描 PDF 约 0.05 元，个人知识库月度 OCR 成本可控在 5 元以内
4. **API 格式兼容** — 支持 OpenAI 兼容接口，可通过现有 `DashScopeChatClient` 直接调用，仅需切换模型名为 `qwen-vl-ocr`

#### 触发条件（仅在必要时唤醒）

OCR 模型**不是每次 Ingest 都调用**，仅在以下场景触发：

1. **PDF 扫描件检测** — `parse_pdf()` 返回 `hasScanWarning=true`（lowTextPages > 30% 的页面文字少于50字符）时，由 `ParserAgent` 判断是否需要 OCR 补充
2. **DOCX 内嵌图片** — python-docx 解析发现 `shape.type == PICTURE` 占比超过页面内容的 30% 时
3. **PPTX 视觉内容** — 幻灯片中文字内容极少但有大量图片占位符时
4. **用户显式请求** — 前端"添加资料"流程中提供"启用图片文字识别"开关

#### 调用路径

```
ParserAgent.parseAndLoad(context)
  → Python 解析 → hasScanWarning=true
  → ParserAgent 判断触发 OCR
  → OcrAgent.extractImages(scopeId, filePath)
    → PyMuPDF/python-docx/python-pptx 提取图片页为 base64
    → DashScopeChatClient 调用 qwen-vl-ocr 模型
    → 提取文字合并回 parsedContent
```

OCR 调用受 `LlmConcurrencyBarrier` 约束，与 Ingest 主流程的 Writer/Analyzer 共享并发信号量，避免 429 过载。

#### 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `llmwiki.ocr.enabled` | `true` | OCR 能力全局开关 |
| `llmwiki.ocr.model` | `qwen-vl-ocr` | 视觉模型名 |
| `llmwiki.ocr.scanThreshold` | `0.3` | 触发 OCR 的低文字页比例阈值 |
| `llmwiki.ocr.maxPages` | `20` | 单次 OCR 最大页数（防止大文件成本爆炸） |

## 环境与配置

### 必需环境变量

| 变量 | 用途 | 示例 |
|------|------|------|
| `AI_DASHSCOPE_API_KEY` | DashScope API 密钥（通义千问模型） | `sk-xxx...` |
| `WIKI_DATA_PATH` | Wiki 文件存储根路径 | `/data/llmwiki/wiki-data` |
| `STORAGE_PROVIDER` | 存储提供商（nas/local/s3） | `local`（开发默认） |
| `NAS_PATH` | NAS 挂载路径（provider=nas 时） | `/data/llmwiki/wiki-data` |
| `ES_URIS` | Elasticsearch 集群地址（逗号分隔） | `http://localhost:9200` |
| `ES_INDEX_NAME` | Wiki 页面索引名 | `llmwiki-pages` |
| `ES_USERNAME` / `ES_PASSWORD` | ES 认证（可选，启用 xpack 时必填） | — |
| `ES_RETRY_INTERVAL_MS` | 失败重试基础间隔（毫秒） | `60000` |
| `ES_RETRY_MAX_ATTEMPTS` | 失败重试最大尝试次数 | `5` |
| `JWT_SECRET` | JWT 签名密钥 | 生产环境必须修改 |
| `ALLOW_SELF_REGISTER` | 是否允许自注册 | `false`（默认） |
| `ADMIN_USERNAMES` | 启动时自动确保为 admin 的用户名 | `admin` |
| `ROCKETMQ_NAME_SERVER` | RocketMQ NameServer 地址 | 空（不启用 RocketMQ） |
| `ROCKETMQ_ENABLED` | 分布式模式开关 | `false`（默认），生产环境设为 `true` |

注意：数据库配置（MySQL）和 ES 配置在 `application.yml` / `application-dev.yml` 中已硬编码，无需通过环境变量传入。仅在需要覆盖默认值时才用环境变量。

### 应用 Profile

- `dev`：开发环境，连接内网 MySQL + ES 集群，DEBUG 级别日志。**日常开发必须使用此 profile**
- `prod`：生产环境，连接生产 MySQL + ES，INFO 级别日志
- 默认（无 profile）：连接 localhost MySQL + ES，本地无基础设施会启动失败

## 分布式架构（RocketMQ）

系统支持 K8s 多节点分布式部署，通过 RocketMQ 实现 SSE 事件广播、Pipeline 任务调度和控制信令传播。采用**双模运行**架构——无 RocketMQ 时退化为单机模式（Spring ApplicationEvent + 本地线程池），行为与改造前完全一致。

### 配置开关

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `llmwiki.rocketmq.enabled` | `false` | 分布式模式总开关。`true` 启用 RocketMQ，`false` 使用本地模式 |
| `rocketmq.name-server` | 空 | RocketMQ NameServer 地址（如 `localhost:9876`） |
| `spring.autoconfigure.exclude` | `RocketMQAutoConfiguration` | RocketMQ 未启用时排除自动配置，避免启动失败 |

- **本地开发**：`llmwiki.rocketmq.enabled=false`（默认），无需 RocketMQ 基础设施
- **dev 环境**：`llmwiki.rocketmq.enabled=true`，连接内网 RocketMQ
- **prod 环境**：`llmwiki.rocketmq.enabled=true`，连接集群内 RocketMQ

### 三个 Topic 架构

| Topic | 消费模式 | 作用 |
|-------|---------|------|
| `llmwiki-execution-event` | **Broadcasting** | SSE 事件广播（step/status/progress），所有 Pod 都收到，匹配本地 SseEmitter 推送 |
| `llmwiki-execution-ctrl` | **Broadcasting** | 取消/暂停信令，所有 Pod 收到，执行节点中断本地线程 |
| `llmwiki-pipeline-task` | **Clustering** | Pipeline 任务队列，只有一个 Pod 消费并执行，失败自动重投递 |

### 核心组件

| 组件 | 位置 | 职责 |
|------|------|------|
| `DistributedEventPublisher` | domain-service | 事件发布接口，解耦 Spring Event / RocketMQ |
| `LocalDistributedEventPublisher` | domain-service | 本地模式实现（Spring ApplicationEvent） |
| `RocketMqDistributedEventPublisher` | biz-service | RocketMQ 模式实现（`@ConditionalOnProperty`） |
| `ExecutionNodeRegistry` | biz-service | 节点级 SSE emitter/future/线程池管理 + nodeId |
| `SseEventConsumer` | biz-service | Broadcasting 消费 SSE 事件 |
| `ExecutionCtrlConsumer` | biz-service | Broadcasting 消费控制信令 |
| `PipelineTaskConsumer` | biz-service | Clustering 消费 Pipeline 任务 |
| `ExecutionRecoveryService` | biz-service | 节点感知启动恢复（只回收本节点任务） |

### 编码约束

- **`execution.node_id`**：每个执行记录绑定到具体 Pod，`ExecutionRecoveryService` 只回收本节点（`nodeId` 匹配）的 stale 任务
- **`IngestController` 双路径提交**：`rocketMQTemplate != null` 走 MQ，否则走本地线程池。不硬编码模式判断
- **`IngestController` 保留 `@EventListener`**：本地模式下 `LocalDistributedEventPublisher` 发布 Spring Event，`@EventListener` 驱动 SSE 推送；RocketMQ 模式下由 `SseEventConsumer` 驱动，`@EventListener` 不会触发（无 Spring Event），不存在双重推送
- **幂等消费**：`PipelineTaskConsumer` 消费前检查 execution 状态，已完成/已取消直接 ACK 跳过
- **控制信令安全**：`ExecutionCtrlConsumer` 收到 CANCEL/PAUSE 后先检查 `registry.hasLocalExecution()`，只中断本节点线程
- **Query SSE 不改造**：Query 的 SSE 是短生命周期请求绑定型（60s-300s），不需要分布式广播
- **HarnessController 不改造**：只提供历史回放型 SSE（读 DB → 推送 → 关闭），无实时事件依赖

## 测试策略

- **单元测试**：JUnit 5 + Mockito 用于服务层。测试业务逻辑，不测试 Spring 上下文。现有测试模块（`app/test`）只承担纯 mock 单元测试能力。
- **集成测试**：`@SpringBootTest` 用于 Harness Pipeline 执行、Wiki 文件操作。涉及 Elasticsearch 的集成测试使用 `org.testcontainers:elasticsearch`（依赖已在 `llmwiki-test` 模块 test scope 声明）起容器，不依赖本地/CI 宿主机安装 ES。
- **前端测试**：Vitest 用于工具函数。复杂组件可选组件测试。
- **测试命名**：`should{预期行为}When{条件}` — 例如 `shouldCreateSummaryPageWhenSourceIngested`
- **本地开发启动前置**：`docker compose up -d mysql elasticsearch`。应用启动会对 ES 做 ping 自检，ES 不可达直接 fail-fast。

## Git 与分支

- 主分支：`main`
- 功能分支：`feature/{模块名}/{功能描述}`
- 不直接提交到 `main`。所有变更通过功能分支。
- 提交信息：简洁，描述变更内容，无 Emoji 前缀。