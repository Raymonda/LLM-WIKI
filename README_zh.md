<p align="center">
  <img src="llmwiki-web-ui/public/logo-v2.png" alt="LLM Wiki" width="120" />
</p>

<h1 align="center">LLM Wiki（知了）</h1>

<p align="center">
  一个将文档<strong>编译</strong>为持续生长的互联 Wiki 的 AI 知识库。<br/>
  不是又一个 RAG 套壳。
</p>

<p align="center">
  <a href="README.md">English</a> · <a href="ARCHITECTURE.md">架构</a> · <a href="DESIGN.md">设计系统</a> · <a href="AGENTS.md">开发者指南</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License" />
  <img src="https://img.shields.io/badge/Java-17+-orange.svg" alt="Java" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.x-6db33f.svg" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/Vue-3.x-42b883.svg" alt="Vue" />
  <img src="https://img.shields.io/badge/Docker-ready-2496ed.svg" alt="Docker" />
</p>

---

## LLM Wiki 是什么？

大多数「LLM + 文档」系统的工作方式是：上传文件 → 查询时检索切片 → 生成回答。知识**在每个问题上都从零重建**，不积累、不复利。

LLM Wiki 走了一条根本不同的路：AI 不是在查询时从原始文档里检索，而是**把文档增量编译为一个持久化、结构化的 Wiki**——由实体页、摘要页、交叉引用和健康指示器组成的互联 Markdown 网络。每加入一份新资料，AI 不只是索引它，而是阅读它、提取关键信息、把它融入既有知识网络——更新实体、修订摘要、标记矛盾、强化整体综合。

结果是**会复利的知识**：交叉引用已经建好，矛盾已经被标记，综合已经反映了你摄入的一切。每份新文档都让整个 Wiki 更丰富。

## 核心特性

- **增量摄入** — 新文档通过 4-Agent 流水线（Parser → Analyzer → Writer → Indexer）编译进既有知识网络，一次完成实体页、摘要页与交叉引用的更新
- **Schema 治理** — AI 行为受版本化 Schema「宪法」约束（7 段骨架 + 合规校验 + 补丁审批），防止幻觉驱动的结构漂移
- **知识体检（Lint）** — 周期性 AI 健康检查发现矛盾、孤立页、知识缺口、过时内容与缺失交叉引用，并通过反馈回路自动调节敏感度
- **AI 辅助编辑** — 在 Wiki 页面上圈选任意段落让 AI 改写，编辑实时流式呈现、分步提交、可逐步撤销
- **来源可追溯** — 每一段编译产物都能回溯到源文档；参考页是原文的高保真镜像
- **团队协作** — 多租户 Scope 严格隔离，知识广场、加入申请、团队订阅、审计日志与活动中心
- **搜索 + AI 问答** — Elasticsearch 混合检索 + 流式 AI 回答并附引用；好的回答可保存回 Wiki
- **多 Provider AI** — 不同任务（分析、OCR、图表识别）可路由到任意 OpenAI 兼容提供商：DashScope、DeepSeek、Moonshot、OpenAI、Ollama
- **实时流水线追踪** — SSE 实时推送摄入流水线进度，细到每一步
- **国际化** — 完整的中 / 英双语界面
- **一键部署** — `docker-compose up` 拉起 MySQL、Elasticsearch 与应用

## 工作原理

LLM Wiki 把知识处理视为**编译**：

| 编译概念 | LLM Wiki | 说明 |
|:-----------:|:--------:|:-----------:|
| 源码 | 原始文档 | 不可变的真相来源（PDF、DOCX、MD） |
| 编译器 | AI 流水线 | 把原始信息转化为结构化知识 |
| 目标码 | Wiki 页面 | AI 生成、人类可读、互相链接的 Markdown |
| 链接器 | 交叉引用 | 页面间关系、矛盾链接 |
| 静态分析 | Lint | 发现不一致、缺口与过时内容 |
| 构建配置 | Schema | 约束 AI 行为与输出 |

### 核心操作

**Ingest（编译 + 链接）**
丢进一份文档。4-Agent 流水线解析它（富格式支持 OCR 与图表识别），分析实体与事实，写入/更新 Wiki 页面（摘要页、实体页、参考页），建立交叉引用，并索引到 Elasticsearch。一份资料可能牵动许多既有页面。

**Query（运行时）**
向编译好的知识提问。AI 检索相关页面、综合出带引用的回答并实时流式输出。有价值的回答可以保存为新的 Wiki 页面——你的探索会沉淀进知识库。

**Lint（静态分析）**
周期性健康检查扫描五类诊断：矛盾、孤立页、知识缺口、过时内容与缺失交叉引用。发现结果反馈到页面健康指示器；用户反馈会随时间自动调节检测敏感度。

## 架构

```
┌─────────────────────────────────────────────────────────┐
│                    Vue 3 Web UI                          │
│         (Vite + Element Plus + Pinia + SSE)             │
└────────────────────────┬────────────────────────────────┘
                         │ REST / SSE
┌────────────────────────┴────────────────────────────────┐
│                  Spring Boot 3 后端                       │
├──────────┬──────────┬───────────┬───────────────────────┤
│   Web    │   Biz    │  Domain   │       Common          │
│Controller │ 业务编排  │ 领域服务   │  DAL / Util / Facade │
├──────────┴──────────┴───────────┴───────────────────────┤
│              Harness 引擎（AI 编排层）                     │
│   IngestOrchestrator · PipelineOrchestrator · Agents    │
└────┬──────────┬──────────┬──────────┬───────────────────┘
     │          │          │          │
   MySQL    Elastic-     文件存储     RocketMQ
   8.x      search 8.x  (Local/NAS/  （可选）
                         MinIO)
```

### 技术栈

| 层 | 技术 |
|:-----:|:----------:|
| 后端 | Java 17 · Spring Boot 3 · Spring AI · MyBatis-Plus |
| AI | 任意 OpenAI 兼容 API（DashScope、DeepSeek、Moonshot、OpenAI、Ollama…）· 多 Agent 编排 |
| 前端 | Vue 3 · TypeScript · Vite · Element Plus · Pinia |
| 搜索 | Elasticsearch 8.x |
| 存储 | Local / NAS / MinIO（S3 兼容） |
| 队列 | RocketMQ 5.x（可选，分布式模式） |
| 部署 | Docker · docker-compose · nginx |

## 快速开始

> 📖 **完整部署指南**：[docs/LOCAL-DEPLOY_zh.md](docs/LOCAL-DEPLOY_zh.md) — 涵盖 Docker Compose、本地开发环境、环境变量与常见问题。

### 前置条件

- Docker & Docker Compose
- 任意 OpenAI 兼容提供商的 API Key（[DashScope](https://dashscope.console.aliyun.com/)、[DeepSeek](https://platform.deepseek.com/)、[Moonshot](https://platform.moonshot.cn/) 等）

### 启动

```bash
git clone https://github.com/Raymonda/LLM-WIKI.git
cd LLM-WIKI

# 配置 API Key
cp .env.example .env
# 编辑 .env，设置 AI_DASHSCOPE_API_KEY=sk-your-key-here

# 启动（MySQL + ES + 应用 + Web UI，约 2.5GB 内存）
docker-compose up -d
```

打开 **http://localhost:3000** — 管理员临时密码通过 `docker logs llmwiki-app 2>&1 | grep -i password` 获取。

完整分布式模式（增加 MinIO + RocketMQ，约 4GB 内存）：

```bash
docker-compose -f docker-compose.full.yml up -d
```

### 配置

全部通过环境变量配置。**唯一必填项**是 AI 提供商的 API Key（如 `AI_DASHSCOPE_API_KEY`），其余均有合理默认值。

LLM Wiki 支持**多个 AI 提供商同时使用**——你可以把不同任务（分析、OCR、图表识别）路由给不同提供商。多 Provider 配置见[部署指南](docs/LOCAL-DEPLOY_zh.md#ai-提供商配置)。

## 路线图

近期版本聚焦团队协作（知识广场、订阅、审计日志）、AI 辅助 Wiki 编辑器与国际化。规划方向：

- 更丰富的摄入格式与更智能的多模态解析
- 源文档更新/删除时的增量重编译
- 更深入的来源-页面冲突处置工作流
- Token 成本优化与轻量模型分流
- 插件 / MCP 集成外部工具

欢迎通过 [issues](https://github.com/Raymonda/LLM-WIKI/issues) 提出想法与反馈。

## 文档

| 文档 | 用途 |
|:--------:|:-------:|
| [docs/LOCAL-DEPLOY_zh.md](docs/LOCAL-DEPLOY_zh.md) | 本地部署指南 — Docker Compose、开发环境、常见问题 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 系统架构概览 — 组件、分层、核心流程 |
| [DESIGN.md](DESIGN.md) | UI/UX 设计系统 — 视觉语言、交互模式 |
| [AGENTS.md](AGENTS.md) | 开发者指南 — 编码约定、构建命令、配置参考 |
| [llm-wiki.md](llm-wiki.md) | 项目起源：最初的想法文档 |

## 项目结构

```
llmwiki/                  # 后端（DDD 分层 Maven 多模块）
├── app/bootstrap/        # Spring Boot 启动入口 + Flyway 迁移
├── app/web/              # Controller、Security（JWT）
├── app/biz/service/      # 业务编排（Ingest、Query、Lint）
├── app/domain/           # 领域模型 + 领域服务 + AI Harness
├── app/common/           # DAL（MyBatis-Plus）+ Util + Facade DTO
└── wiki-data/            # 运行时文件存储（已 gitignore）

llmwiki-web-ui/           # 前端（Vue 3 + Vite）
├── src/views/            # 页面：Wiki、编辑器、搜索、摄入、体检、图谱…
├── src/components/       # 共享：布局、Wiki 渲染器、流水线追踪器
├── src/stores/           # Pinia 状态管理
└── src/api/              # Axios API 层
```

## License

[MIT](LICENSE)

## Author

**Liu Weitao** — cool_zeel@163.com

<img width="190" height="282" alt="image" src="https://github.com/user-attachments/assets/61274634-54c4-49ac-b3c5-1a7b61503fb6" />
