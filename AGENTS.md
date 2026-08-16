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
> - Run: `java -jar target/boot/llmwiki-bootstrap-1.1.0-SNAPSHOT.jar --spring.profiles.active=dev`
> - Frontend dev: `cd llmwiki-web-ui && npm install && npm run dev` (http://localhost:5173)
> - Deploy: `docker-compose up -d` (MySQL + ES + App + Web UI); `docker-compose.full.yml` adds MinIO + RocketMQ
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

LLM Wiki 是一个知识管理系统，通过 LLM 增量构建和维护持久化 Wiki。系统提供三个核心操作：Ingest（摄入/源文件处理）、Query（查询/知识检索）、Lint（健康检查），由基于 Spring AI 构建的 AI 执行引擎（Harness）驱动。

系统架构、存储分层、Pipeline 流程详见 **[ARCHITECTURE.md](ARCHITECTURE.md)**；UI 规范与交互定义详见 **[DESIGN.md](DESIGN.md)**。

## 知识编译硬性规则

LLM Wiki 对知识的处理本质上是 AI 对知识的一次"编译"。以下是代码 review 时的四条红线：

1. **编译器必须完整** — Ingest 必须实现增量编译（更新已有页面，而非只创建新页面），必须维护来源-页面追踪（`wiki_page_source`）；Query 保存到知识库也必须走完整编译 Pipeline，禁止绕过分层直接调 DB/FS/ES
2. **项目隔离必须严格** — 所有数据表（含关系表）必须有 `scope_id`，文件系统路径必须包含 `scopeId`，`raw/` 目录绝对不可写
3. **准确性优先于一切** — 编译产物的准确性优先级高于展示丰富度、格式规范和视觉美观。当富元素（Mermaid 图、ECharts 图表等）需要 LLM "推断"或"构造"源文档中不存在的信息时，宁可不用也不能编造。优先级链：**准确性 > 格式规范 > 视觉美观**
4. **编译必须可追溯** — Raw 层是 source of truth，每段编译产物必须能回溯到原文。Wiki 层包含三种页面类型：摘要页（`summary`，跨源综合）、实体页（`entity`，跨源综合）、参考页（`reference`，单源高保真，只读锁定——不可被其他文档 Ingest 的关联页更新步骤改写）。`parsed/` 是纯编译中间产物，不被 ES 索引、不被 Query 搜索

## Schema 共治宪法（编码约束摘要）

以下七条为编码层面不可绕过的硬规则，架构细节详见 **[ARCHITECTURE.md § Schema 治理](ARCHITECTURE.md)**：

1. **单文件 + 7 段固定骨架** — 每个 scope 的 Schema 必须包含 7 个固定 H2 section，破坏骨架的写入由 `SchemaSkeletonValidator` 拒绝
2. **Schema 必须注入 Prompt** — 所有 LLM 调用通过 `SchemaInjector.prepend(scopeId, prompt)` 注入 Schema 规则（section 1-6，剥离 section 7 变更日志以节省 tokens）
3. **违规即阻断** — AI 决策违反 Schema 规则 → REVIEW 级审批；Schema 未覆盖的新规则 → 先触发扩展申请再执行
4. **强制冷启动** — 新 scope 在 Schema v1 落库前禁止任何 Ingest/Query/Lint 入口；Schema v1 由 `SchemaBootstrapAgent` 三轮引导对话产出
5. **版本化溯源** — `schema_config_version` 记录版本历史；`wiki_page.schema_version` 记录页面遵循的 Schema 版本；补丁必须带证据字段
6. **不保留手动编辑入口** — 前端只提供 Schema 只读渲染 + 版本切换 + AI 调整入口；绕过 AI 对话直接改写 Schema 的 REST 调用必须被拒绝
7. **治理与防腐** — `SchemaLint` 独立于 Wiki Lint 定期扫描；Lint 处置决策从 Schema 读取（禁止硬编码）；用户连续忽略同类诊断 ≥ N 次自动降级探查敏感度

## 技术栈

### 后端

| 组件 | 技术 | 版本 |
|------|------|------|
| 运行时 | JDK 17+ | LTS |
| 框架 | Spring Boot | 3.x |
| AI 框架 | spring-ai + spring-ai-starter-model-openai | 1.1.x（OpenAI 兼容协议，多 Provider 混用） |
| 安全认证 | Spring Security + JWT | Spring Boot 管理 |
| ORM | MyBatis-Plus | 3.5.x |
| 数据库 | MySQL | 8.x |
| 搜索引擎 | Elasticsearch + spring-data-elasticsearch | 8.15 / 5.4.x |
| 对象存储 | Local（默认）/ NAS / MinIO（S3 兼容） | 可配置切换 |
| 消息队列 | RocketMQ + rocketmq-spring-boot-starter | 可选，分布式模式 |
| 文档解析 | Python 子进程（`tools/doc_parser.py`） | PyMuPDF / pandoc 等 |
| 构建 | Maven | 3.9+ |

### 前端

| 组件 | 技术 |
|------|------|
| 框架 / 构建 / 语言 | Vue 3 + Vite 5 + TypeScript 5 |
| UI 库 | Element Plus（用设计 Token 覆盖定制） |
| 状态 / 路由 / HTTP | Pinia + Vue Router + Axios |
| Markdown 渲染 | markdown-it + shiki + katex |
| Markdown 编辑 | @bytemd/vue-next（AI 协助编辑器内部使用） |
| 图谱 / 图标 | vis.js · D3.js · Lucide Icons |

## 项目结构

采用 DDD 分层架构，后端代码在 `llmwiki/app/`：

```
llmwiki/                         # 后端（DDD 分层 Maven 多模块）
├── pom.xml                      # 父 POM
├── tools/                       # Python 文档解析引擎（doc_parser.py）
├── app/bootstrap/               # 启动模块（LlmwikiApplication + application*.yml）
├── app/web/                     # 适配层：Controller、Security（JWT）、Result 包装
├── app/biz/service/             # 业务编排：Ingest/Query/Lint/Auth 流程编排、RocketMQ 消息
├── app/domain/
│   ├── model/                   # 领域模型（system / wiki / harness）
│   └── service/                 # 核心业务逻辑
│       └── harness/             # HarnessEngine、Pipeline 编排、Agent 群、Wiki Tool
│       └── harness/governance/  # Schema 治理（校验、解析、冷启动、审批）
├── app/common/
│   ├── dal/                     # MyBatis Mapper + DO
│   ├── util/                    # Result<T>、异常体系、常量
│   └── service/                 # facade（API DTO）+ integration（多 Provider AI 集成）
├── app/test/                    # 测试模块
└── wiki-data/                   # 运行时文件存储（不入源码控制）
    └── {scopeId}/
        ├── raw/                 # 原始来源文件【不可变】，{uuid}-{filename}
        ├── parsed/              # 解析产物（可重建缓存）
        ├── wiki/pages/          # LLM 编译生成的 Wiki 页面
        ├── assets/              # 图片与媒体
        └── schema/              # Schema 配置导出

llmwiki-web-ui/                  # 前端（Vue 3 + Vite）
├── src/
│   ├── api/                     # Axios API 层（按模块分文件）
│   ├── stores/                  # Pinia stores（每领域一个）
│   ├── styles/                  # 设计 Token（tokens.css + 双主题）
│   ├── components/              # layout / wiki / harness / editor / common
│   └── views/                   # wiki / ingest / search / editor / harness / lint / dashboard / system
└── public/
```

## 构建与运行命令

### 后端

```bash
# 构建（仅 bootstrap 模块及其依赖）
cd llmwiki
mvn clean package -DskipTests -pl app/bootstrap -am

# 开发环境启动（需要 MySQL + Elasticsearch）
java -jar target/boot/llmwiki-bootstrap-1.1.0-SNAPSHOT.jar --spring.profiles.active=dev

# 运行测试
mvn test                          # 全量
mvn test -pl app/domain/service   # 指定模块
```

**启动要点：**

1. **可执行 jar 位置**：repackage 后的可执行 jar 在 `llmwiki/target/boot/`（约 75MB），不是 `app/bootstrap/target/` 下的小 jar（缺少主清单属性，直接运行会报错）
2. **基础设施依赖**：默认 profile 指向 localhost 的 MySQL 和 ES，本地没有这些服务会启动失败；开发环境必须使用 `--spring.profiles.active=dev`，可通过环境变量覆盖基础设施地址
3. **Flyway**：首次连接自动执行迁移，schema 已最新则跳过
4. **Admin Bootstrap**：启动时自动确保 `ADMIN_USERNAMES` 中的用户具有 admin 角色

### 前端

```bash
cd llmwiki-web-ui
npm install
npm run dev          # 开发服务器 http://localhost:5173/（API proxy 到 8080）
npm run build        # 生产构建
npm run type-check   # vue-tsc --noEmit 类型检查
npm run lint
```

前端可单独启动验证 UI 改动（无需后端），但登录和数据操作需要后端服务。PowerShell 不支持 `&&` 连接命令，用 `;` 代替。

## 编码约定

### Java（后端）

- **包命名**：`org.cn.liuwt.llmwiki.{layer}.{domain}`，遵循 DDD 分层——`common.dal.*`（Mapper/DO）、`common.util.*`（Result/异常）、`domain.model.*` / `domain.service.*`（领域层）、`service.*`（业务编排）、`web.controller` / `web.security`（适配层）、`integration.ai`（AI 集成）、`facade.model`（API DTO）
- **命名**：类 PascalCase（`*Service` / `*Controller` / `*DO` / `*Model` 后缀），方法 camelCase 且使用描述性名称（`ingestSource`、`searchWikiPages`）
- **代码中不加注释**，除非明确要求；代码应通过命名自文档化
- **异常处理**：使用 common 模块的自定义异常层级，绝不静默吞异常
- **日志**：SLF4J。ERROR 失败 / WARN 可恢复问题 / INFO 关键业务事件 / DEBUG 开发细节
- **API 响应**：所有 REST 端点使用统一 `Result<T>` 包装
- **数据库**：MyBatis-Plus 负责 CRUD，`LambdaQueryWrapper` 类型安全查询，`Page<T>` 分页；复杂查询用 XML mapper
- **Spring AI 集成**：通过 OpenAI 兼容协议接入任意 Provider（`AiProviderRegistry` + `AiSlotRouter` + `LlmClient`），工具用 `@Tool` 注解注册。绝不直接调用 LLM API，始终走 Spring AI 抽象层
- **数据隔离（红线）**：所有数据表必须含 `scope_id`，关系表（`wiki_page_tag` / `wiki_page_keyword` / `wiki_page_link` / `wiki_page_source`）也不例外——不依赖 JOIN 的隔离是不可靠的。所有查询强制 `WHERE scope_id = ?`，禁止暴露无 scope 过滤的方法。文件路径必须含 `scopeId`，不允许路径遍历
- **文件职责红线**：`raw/` 写入后绝对不可再写，`WriteFileTool` / `WikiFileService` 命中 `raw/` 直接抛 `BusinessException`；所有 AI 写入集中在 `wiki/`；健康状态是 `wiki_page.health_status` 字段，不得生成游离的 `health-report.md`
- **存储层**：文件操作一律通过 `StorageProvider` 接口（Local / NAS / S3 三种实现），禁止直接使用 `java.nio.file.Files`，切换只改配置 `llmwiki.storage.provider`
- **Ingest 参数动态化**：Ingest 策略参数（分析上限、裁剪阈值等）一律从 `ExecutionStrategy` 获取（由 `IngestionStrategyAdvisor` 按文档特征选档），禁止在业务代码中硬编码
- **交叉引用一致性**：`wiki_page_link` 的 `from_page_id` / `to_page_id` 必须引用已存在的 `wiki_page.id`，链接在页面写入完成（文件 + DB）之后才生成
- **LLM 并发控制**：所有并行 LLM 调用受 `LlmConcurrencyBarrier` 全局信号量约束，避免 Provider 429 过载；新增并行 LLM 调用点必须接入

### TypeScript（前端）

- **文件命名**：Vue 组件 PascalCase（`WikiPageRenderer.vue`），工具函数 camelCase（`formatDate.ts`）
- **组件风格**：`<script setup lang="ts">` + `<template>` + `<style scoped>`
- **代码中不加注释**，除非明确要求
- **状态管理**：Pinia，每个领域一个 store（`useWikiStore`、`useHarnessStore`…）
- **API 调用**：集中在 `src/api/` 层，按模块分文件
- **设计 Token**：始终使用 CSS 自定义属性（`var(--accent-primary)`、`var(--space-4)`），绝不硬编码颜色或间距值；双主题通过 Token 集切换，默认跟随系统
- **图标**：Lucide 图标组件，绝不使用 Emoji 作为图标

## 防御式编程（硬性模式）

以下为不可违背的防御式模式，违反即埋雷（来源：Harness 框架失败复盘）：

- **正交结果独立报告**：多维度失败独立检查、独立报告（如子进程"等待超时"与"退出码"是两件事，超时了 exit code 仍可能是 0），绝不合并成单一布尔
- **公共契约双侧尊重**：越过公共 API 边界的数据先归一化（去空、裁剪、类型收敛），生产方不得输出脏值，消费方不得假设上游已清洗
- **异步状态 ≠ 同步状态**：`SseEmitter` / 异步完成的成功、超时、客户端断开三态独立；`await` 必须处理"无可等待对象"分支，不能假设事件总会到达
- **Dispose 必须达到静止**：子进程终止必须 kill → `waitFor` 退出 → join 输出线程三步走，kill 不代表已退出；先注销回调注册、后释放资源，顺序不可颠倒
- **回调异常容器化**：RocketMQ 监听器、SSE 回调中单个监听器异常不得拖垮其他监听器或整体生命周期，回调入口独立捕获并记录
- **不受信任输出环境隔离**：子进程（`PythonProcessRunner`）环境变量用白名单，剔除含 `KEY` / `SECRET` / `TOKEN` / `PASSWORD` 的变量，杜绝 API Key 泄漏给解析进程
- **链接路径清理**：symlink 删除用 unlink 语义而非跟随目标删除；路径操作先规范化再执行

## 模块依赖（DDD 分层）

```
common/util（最底层，无内部依赖）
  ↑
common/dal          common/service/facade    common/service/integration
  ↑                        ↑                        ↑
                    domain/model
                        ↑
                    domain/service（核心业务逻辑、Harness 引擎）
                        ↑
                    biz/service（Ingest/Query/Lint 编排）
                        ↑
                      web（Controller、Security）
                        ↑
                    bootstrap（启动入口）
```

- 无循环依赖，底层模块不向上依赖
- Harness 引擎核心逻辑在 `domain/service`；Ingest/Query/Lint 编排在 `biz/service`；AI 集成在 `common/service/integration`

## 文档解析引擎（Python）

系统通过 `PythonProcessRunner` 调用 `tools/doc_parser.py` 子进程解析文档，采用分层回退架构：每个格式一线引擎追求最佳质量，二线/三线兜底保障可用性。所有引擎在输出 JSON 中标注 `extractionEngine` 便于排查。

### Python 依赖（渐进式安装）

| 依赖 | 作用 | 必需性 |
|------|------|--------|
| PyMuPDF | PDF 一线引擎（markdown 模式提取） | **必需** |
| python-docx | DOCX 兜底引擎 + 元数据 | **必需** |
| openpyxl | XLSX 解析 + 元数据 | **必需** |
| python-pptx | PPTX 兜底引擎 + 备注页 | **必需** |
| pypdf | PDF 二线兜底 + 加密检测 | 推荐 |
| pandas / markitdown / pandoc | XLSX / PPTX / DOCX 一线引擎 | 可选 |

可选依赖缺失时自动降级到下一层引擎，不影响核心功能（仅丢失部分格式细节）。安装脚本见 `llmwiki/setup-python.sh` / `setup-python.bat`。

### 格式支持矩阵

| 格式 | 引擎链 | 说明 |
|------|--------|------|
| PDF | fitz → pypdf | 加密检测 + 扫描件预警 |
| DOCX / DOC | pandoc → python-docx | 表格 + 列表识别 |
| PPTX / PPT | pandoc → markitdown → python-pptx | 备注页提取 |
| XLSX / XLS | pandas → openpyxl | 大表截断 |
| MD / TXT / CSV / JSON | 直接读取 | — |

**全链路一致性约束**：前端上传 accept 属性、后端 `FileFormatValidator.isParsableFormat()`、`ParserAgent.needsDocumentParsing()` 三层格式白名单必须保持一致；`FileFormatValidator.isValidFormat()` 通过文件头魔数校验防止扩展名伪造。

### OCR 策略（视觉模型辅助）

扫描件、内嵌图片等无文字层的复杂文档采用**视觉模型按需唤醒**策略（推荐 `qwen-vl-ocr`，与主 LLM 同一 OpenAI 兼容通道，零额外部署），而非本地 OCR 工具链。仅在必要时触发：PDF 扫描件预警（低文字页超阈值）、DOCX/PPTX 图片占比过高、用户显式开启"图片文字识别"。

关键配置：`llmwiki.ocr.enabled`（总开关）、`llmwiki.ocr.model`、`llmwiki.ocr.scanThreshold`（触发阈值）、`llmwiki.ocr.maxPages`（单次上限，防止成本爆炸）。

## 环境与配置

### 主要环境变量

| 变量 | 用途 | 示例 |
|------|------|------|
| `AI_DASHSCOPE_API_KEY` | 主 LLM Provider API 密钥 | `sk-xxx...` |
| `WIKI_DATA_PATH` | Wiki 文件存储根路径 | `/data/llmwiki/wiki-data` |
| `STORAGE_PROVIDER` | 存储提供商 | `local`（默认）/ `nas` / `s3` |
| `ES_URIS` | Elasticsearch 地址（逗号分隔） | `http://localhost:9200` |
| `ES_USERNAME` / `ES_PASSWORD` | ES 认证（启用 xpack 时必填） | — |
| `JWT_SECRET` | JWT 签名密钥 | 生产环境必须修改 |
| `ALLOW_SELF_REGISTER` | 是否允许自注册 | `false`（默认） |
| `ADMIN_USERNAMES` | 启动时自动确保为 admin 的用户名 | `admin` |
| `ROCKETMQ_ENABLED` / `ROCKETMQ_NAME_SERVER` | 分布式模式开关与 NameServer 地址 | 默认关闭 |

完整变量清单见 [.env.example](.env.example)。数据库与 ES 默认配置在 `application*.yml` 中，仅在需要覆盖时用环境变量。

### 应用 Profile

- `dev`：开发环境，**日常开发必须使用**；连接开发 MySQL + ES，DEBUG 日志
- `prod`：生产环境，INFO 日志
- 默认（无 profile）：连接 localhost MySQL + ES，本地无基础设施会启动失败

## 分布式架构（RocketMQ，可选）

系统支持多节点部署。无 RocketMQ 时退化为单机模式（Spring ApplicationEvent + 本地线程池），行为完全一致，由 `llmwiki.rocketmq.enabled` 开关控制。

三个 Topic：

| Topic | 消费模式 | 作用 |
|-------|---------|------|
| `llmwiki-execution-event` | Broadcasting | SSE 事件广播，所有节点收到，匹配本地 SseEmitter 推送 |
| `llmwiki-execution-ctrl` | Broadcasting | 取消/暂停信令，只中断执行所在节点的线程 |
| `llmwiki-pipeline-task` | Clustering | Pipeline 任务队列，单节点消费，失败自动重投递 |

编码约束：

- 每个执行记录绑定 `execution.node_id`，恢复服务只回收本节点的 stale 任务
- 任务提交双路径：MQ 可用走 MQ，否则走本地线程池，不硬编码模式判断
- 消费端幂等：消费前检查 execution 状态，已完成/已取消直接 ACK 跳过
- Query SSE 是短生命周期请求绑定型，不做分布式广播

## 测试策略

- **单元测试**：JUnit 5 + Mockito，测试业务逻辑而非 Spring 上下文（`app/test` 模块）
- **集成测试**：`@SpringBootTest` 覆盖 Pipeline 执行与文件操作；涉及 ES 的测试用 Testcontainers 起容器，不依赖宿主机 ES
- **前端测试**：Vitest 覆盖工具函数，复杂组件可选组件测试
- **测试命名**：`should{预期行为}When{条件}`，如 `shouldCreateSummaryPageWhenSourceIngested`
- **本地启动前置**：`docker compose up -d mysql elasticsearch`；应用启动对 ES 做 ping 自检，不可达直接 fail-fast

## Git 与分支

- 主分支：`main`；功能分支：`feature/{模块名}/{功能描述}`
- 不直接提交到 `main`，所有变更通过功能分支
- 提交信息简洁描述变更内容，无 Emoji 前缀
