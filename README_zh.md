<p align="center">
  <img src="llmwiki-web-ui/public/logo-v2.png" alt="LLM Wiki" width="120" />
</p>

<h1 align="center">LLM Wiki</h1>

<p align="center">
  AI 驱动的知识库系统——将文档<strong>编译</strong>为持续生长的互联知识网络。<br/>
  不是又一个 RAG 套壳。
</p>

<p align="center">
  <a href="README.md">English</a> · <a href="ARCHITECTURE.md">架构蓝图</a> · <a href="DESIGN.md">设计系统</a> · <a href="ROADMAP.md">路线图</a>
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

大多数 LLM + 文档系统的工作方式是：上传文件 → 查询时检索分块 → 生成答案。知识在**每次提问时从零重新推导**，没有积累，没有复利。

LLM Wiki 采用根本不同的路径。AI 不是在查询时临时检索原始文档，而是**将文档增量编译为持久化的结构化 Wiki**——一个由实体页、摘要页、交叉引用和健康指示器组成的互联 Markdown 知识网络。当你添加一份新文档时，AI 不只是索引它，而是阅读、提取关键信息、融入已有知识网络——更新实体、修订摘要、标记矛盾、强化综合。

结果是：**知识产生复利。** 交叉引用已经建好，矛盾已经标记，综合已经反映了你摄入的所有内容。每一份新文档都让整个 Wiki 更丰富。

## 核心特性

- **📥 增量摄入** — 新文档通过 4-Agent 流水线（Parser → Analyzer → Writer → Indexer）编译进已有知识网络，一次操作更新实体页、摘要页和交叉引用
- **📜 Schema 治理** — AI 行为受版本化 Schema「宪法」约束（7 段骨架 + 合规校验 + 补丁审批），防止幻觉驱动的结构漂移
- **🩺 知识体检** — AI 定期健康检查：矛盾检测、孤立页面、知识缺口、过时内容、缺失交叉引用——反馈闭环自动调节检测敏感度
- **🔗 来源溯源** — 每段编译产物可回溯到原始文档，参考页（reference）是原文的高保真镜像
- **🏢 多租户隔离** — 严格隔离的个人/团队知识库 + 知识广场 + 加入申请制 + 成员管理
- **🔍 全文检索 + AI 问答** — Elasticsearch 混合检索 + 流式 AI 回答（附引用标注），好答案一键存回 Wiki
- **📡 实时流水线追踪** — SSE 实时推送 Ingest 全流程进度，步骤级粒度
- **🐳 一键部署** — `docker-compose up` 拉起 MySQL、Elasticsearch、MinIO、RocketMQ 和应用

## 工作原理

LLM Wiki 将知识处理视为**编译**：

| 编译概念 | LLM Wiki 对应 | 说明 |
|:--------:|:------------:|:----:|
| 源码 | 原始文档 | 不可变的真相来源（PDF、DOCX、MD） |
| 编译器 | AI 流水线 | 将原始信息转化为结构化知识 |
| 目标码 | Wiki 页面 | AI 生成、人类可读、互联的 Markdown |
| 链接器 | 交叉引用 | 页面间关系、矛盾链接 |
| 静态分析 | Lint 体检 | 检测不一致、缺口、过时内容 |
| 构建配置 | Schema | 治理 AI 行为与产物约束 |

### 三大核心操作

**Ingest（编译 + 链接）**
投入一份文档。4-Agent 流水线解析文档、分析实体与事实、写入/更新 Wiki 页面（摘要页、实体页、参考页）、构建交叉引用、索引到 Elasticsearch。一份文档可能触及 10–15 个已有页面。

**Query（运行时）**
对编译产物提问。AI 检索相关页面、综合多源信息、流式输出带引用的答案。有价值的答案可以存回 Wiki——你的探索也产生复利。

**Lint（静态分析）**
定期健康检查扫描 5 种诊断类型：矛盾、孤立页、知识缺口、过时内容、缺失交叉引用。诊断结果回写页面健康指示器，用户反馈自动调节检测敏感度。

## 系统架构

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
│ 控制器层  │ 业务编排  │ 领域服务   │  DAL / 工具 / DTO     │
├──────────┴──────────┴───────────┴───────────────────────┤
│              Harness 引擎（AI 编排层）                     │
│   IngestOrchestrator · PipelineOrchestrator · Agents    │
└────┬──────────┬──────────┬──────────┬───────────────────┘
     │          │          │          │
   MySQL    Elastic-    文件系统     RocketMQ
   8.x      search 8.x  (NAS/S3)    （可选）
```

### 技术栈

| 层级 | 技术 |
|:----:|:----:|
| 后端 | Java 17 · Spring Boot 3 · Spring AI · MyBatis-Plus |
| AI | 任意 OpenAI 兼容 API（DashScope、DeepSeek、Moonshot、OpenAI、Ollama...）· 多 Agent 编排 |
| 前端 | Vue 3 · TypeScript · Vite · Element Plus · Pinia |
| 搜索 | Elasticsearch 8.x |
| 存储 | 本地 / NAS / MinIO（S3 兼容） |
| 消息 | RocketMQ 5.x（可选，分布式模式） |
| 部署 | Docker · docker-compose · nginx |

## 快速开始

> 📖 **完整部署指南**：[docs/LOCAL-DEPLOY_zh.md](docs/LOCAL-DEPLOY_zh.md) — 涵盖 Docker Compose 部署、本地开发环境、环境变量说明和常见问题排查。

### 前置条件

- Docker & Docker Compose
- 任意 OpenAI 兼容提供商的 API Key（[DashScope](https://dashscope.console.aliyun.com/)、[DeepSeek](https://platform.deepseek.com/)、[Moonshot](https://platform.moonshot.cn/) 等）

### 启动

```bash
git clone https://github.com/Raymonda/LLM-WIKI.git
cd LLM-WIKI

# 配置 API Key
cp .env.example .env
# 编辑 .env，填入 AI_DASHSCOPE_API_KEY=sk-your-key-here

# 一键启动（MySQL + ES + 后端 + 前端，约 2.5GB 内存）
docker-compose up -d
```

打开 **http://localhost:3000**——通过 `docker logs llmwiki-app 2>&1 | grep "temporary password"` 获取 admin 密码。

完整分布式模式（额外启动 MinIO + RocketMQ，约 4GB 内存）：

```bash
docker-compose -f docker-compose.full.yml up -d
```

### 配置

所有配置通过环境变量完成。唯一**必填项**是任一 AI 提供商的 API Key（如 `AI_DASHSCOPE_API_KEY`），其余均有合理默认值。

LLM Wiki 支持**多 AI 提供商混用**——可以将不同任务（分析、OCR、图表识别）路由到不同提供商。多提供商配置见[部署指南](docs/LOCAL-DEPLOY_zh.md#ai-提供商配置)。

## 文档导航

| 文档 | 用途 |
|:----:|:----:|
| [docs/LOCAL-DEPLOY_zh.md](docs/LOCAL-DEPLOY_zh.md) | 本地部署指南——Docker Compose、开发环境、常见问题 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 系统架构蓝图——组件、数据流、流水线设计 |
| [DESIGN.md](DESIGN.md) | UI/UX 设计系统——视觉语言、交互模式 |
| [AGENTS.md](AGENTS.md) | 开发者指南——编码约定、构建命令、配置参考 |
| [ROADMAP.md](ROADMAP.md) | 开发路线图与功能状态 |
| [llm-wiki.md](llm-wiki.md) | 一切开始的原始灵感文档 |

## 项目结构

```
llmwiki/                  # 后端（DDD 分层 Maven 多模块）
├── app/bootstrap/        # Spring Boot 启动入口 + Flyway 迁移
├── app/web/              # 控制器、安全认证（JWT）
├── app/biz/service/      # 业务编排（Ingest、Query、Lint）
├── app/domain/           # 领域模型 + 领域服务 + AI Harness
├── app/common/           # DAL（MyBatis-Plus）+ 工具 + Facade DTO
└── wiki-data/            # 运行时文件存储（已 gitignore）

llmwiki-web-ui/           # 前端（Vue 3 + Vite）
├── src/views/            # 页面：Wiki、搜索、摄入、体检、图谱...
├── src/components/       # 共享组件：布局、Wiki 渲染器、流水线追踪
├── src/stores/           # Pinia 状态管理
└── src/api/              # Axios API 层
```

## 许可证

[MIT](LICENSE)

## 作者

**Liu Weitao** — cool_zeel@163.com
