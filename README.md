<p align="center">
  <img src="llmwiki-web-ui/public/logo-v2.png" alt="LLM Wiki" width="120" />
</p>

<h1 align="center">LLM Wiki（知了）</h1>

<p align="center">
  An AI-powered knowledge base that <strong>compiles</strong> documents into a living, interlinked wiki.<br/>
  Not another RAG wrapper.
</p>

<p align="center">
  <a href="README_zh.md">中文</a> · <a href="ARCHITECTURE.md">Architecture</a> · <a href="DESIGN.md">Design System</a> · <a href="AGENTS.md">Developer Guide</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License" />
  <img src="https://img.shields.io/badge/Java-17+-orange.svg" alt="Java" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.x-6db33f.svg" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/Vue-3.x-42b883.svg" alt="Vue" />
  <img src="https://img.shields.io/badge/Docker-ready-2496ed.svg" alt="Docker" />
</p>

---

## What is LLM Wiki?

Most LLM + document systems work like this: upload files → retrieve chunks at query time → generate an answer. The knowledge is **re-derived from scratch on every question**. Nothing accumulates. Nothing compounds.

LLM Wiki takes a fundamentally different approach. Instead of retrieving from raw documents at query time, the AI **incrementally compiles documents into a persistent, structured wiki** — a network of interlinked markdown pages with entity pages, summaries, cross-references, and health indicators. When you add a new source, the AI doesn't just index it. It reads it, extracts key information, integrates it into the existing knowledge network — updating entities, revising summaries, flagging contradictions, strengthening the evolving synthesis.

The result: **knowledge that compounds.** Cross-references are already built. Contradictions are already flagged. The synthesis already reflects everything you've ingested. Every new document makes the entire wiki richer.

## Key Features

- **Incremental Ingestion** — New documents are compiled into the existing knowledge network via a 4-agent pipeline (Parser → Analyzer → Writer → Indexer), updating entity pages, summaries, and cross-references in one pass
- **Schema Governance** — AI behavior is constrained by a versioned Schema "constitution" (7-section skeleton + compliance validation + patch approval), preventing hallucination-driven structure drift
- **Knowledge Lint** — Periodic AI health checks detect contradictions, orphan pages, knowledge gaps, stale content, and missing cross-references — with a feedback loop that auto-tunes sensitivity
- **AI-Assisted Editing** — Select any passage on a wiki page and ask the AI to rewrite it; edits stream in live, are committed step by step, and can be undone individually
- **Source Traceability** — Every compiled artifact traces back to its source document. Reference pages are high-fidelity mirrors of the original text
- **Team Collaboration** — Multi-tenant scopes with strict isolation, a knowledge plaza, join-request workflow, team subscriptions, audit logs, and an activity center
- **Search + AI Q&A** — Elasticsearch hybrid search with streaming AI answers that cite sources. Good answers can be saved back into the wiki
- **Multi-Provider AI** — Route different tasks (analysis, OCR, diagram recognition) to any OpenAI-compatible provider: DashScope, DeepSeek, Moonshot, OpenAI, Ollama
- **Real-Time Pipeline Tracking** — SSE-powered live progress for the entire ingestion pipeline with step-level granularity
- **i18n** — Full Chinese / English interface
- **One-Command Deploy** — `docker-compose up` brings up MySQL, Elasticsearch, and the app

## How It Works

LLM Wiki treats knowledge processing as **compilation**:

| Compilation | LLM Wiki | Description |
|:-----------:|:--------:|:-----------:|
| Source code | Raw documents | Immutable source of truth (PDF, DOCX, MD) |
| Compiler | AI Pipeline | Transforms raw information into structured knowledge |
| Object code | Wiki pages | AI-generated, human-readable, interlinked markdown |
| Linker | Cross-references | Relationships between pages, contradiction links |
| Static analysis | Lint | Detects inconsistencies, gaps, and staleness |
| Build config | Schema | Governs AI behavior and output constraints |

### Core Operations

**Ingest** (Compile + Link)
Drop in a document. The 4-agent pipeline parses it (with OCR and diagram recognition for rich formats), analyzes entities and facts, writes/updates wiki pages (summaries, entities, references), builds cross-references, and indexes into Elasticsearch. A single source may touch many existing pages.

**Query** (Runtime)
Ask questions against the compiled knowledge. The AI searches relevant pages, synthesizes an answer with citations, and streams it in real time. Valuable answers can be saved back as new wiki pages — your explorations compound into the knowledge base.

**Lint** (Static Analysis)
Periodic health checks scan for five diagnostic types: contradictions, orphan pages, knowledge gaps, stale content, and missing cross-references. Findings feed back into page health indicators. User feedback auto-tunes detection sensitivity over time.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Vue 3 Web UI                          │
│         (Vite + Element Plus + Pinia + SSE)             │
└────────────────────────┬────────────────────────────────┘
                         │ REST / SSE
┌────────────────────────┴────────────────────────────────┐
│                  Spring Boot 3 Backend                    │
├──────────┬──────────┬───────────┬───────────────────────┤
│   Web    │   Biz    │  Domain   │       Common          │
│Controllers│Services │ Services  │  DAL / Util / Facade  │
├──────────┴──────────┴───────────┴───────────────────────┤
│              Harness Engine (AI Orchestration)            │
│   IngestOrchestrator · PipelineOrchestrator · Agents    │
└────┬──────────┬──────────┬──────────┬───────────────────┘
     │          │          │          │
   MySQL    Elastic-    File Storage RocketMQ
   8.x      search 8.x  (Local/NAS/  (optional)
                         MinIO)
```

### Tech Stack

| Layer | Technology |
|:-----:|:----------:|
| Backend | Java 17 · Spring Boot 3 · Spring AI · MyBatis-Plus |
| AI | Any OpenAI-compatible API (DashScope, DeepSeek, Moonshot, OpenAI, Ollama...) · Multi-agent orchestration |
| Frontend | Vue 3 · TypeScript · Vite · Element Plus · Pinia |
| Search | Elasticsearch 8.x |
| Storage | Local / NAS / MinIO (S3-compatible) |
| Queue | RocketMQ 5.x (optional, for distributed mode) |
| Deploy | Docker · docker-compose · nginx |

## Quick Start

> 📖 **Full deployment guide**: [docs/LOCAL-DEPLOY.md](docs/LOCAL-DEPLOY.md) — covers Docker Compose, local dev setup, environment variables, and troubleshooting.

### Prerequisites

- Docker & Docker Compose
- An API key from any OpenAI-compatible provider ([DashScope](https://dashscope.console.aliyun.com/), [DeepSeek](https://platform.deepseek.com/), [Moonshot](https://platform.moonshot.cn/), etc.)

### Run

```bash
git clone https://github.com/Raymonda/LLM-WIKI.git
cd LLM-WIKI

# Configure your API key
cp .env.example .env
# Edit .env and set AI_DASHSCOPE_API_KEY=sk-your-key-here

# Start (MySQL + ES + App + Web UI, ~2.5GB RAM)
docker-compose up -d
```

Open **http://localhost:3000** — get the admin temporary password from `docker logs llmwiki-app 2>&1 | grep -i password`.

For full distributed mode (adds MinIO + RocketMQ, ~4GB RAM):

```bash
docker-compose -f docker-compose.full.yml up -d
```

### Configuration

All configuration is via environment variables. The only **required** variable is an AI provider API key (e.g. `AI_DASHSCOPE_API_KEY`). Everything else has sensible defaults.

LLM Wiki supports **multiple AI providers** simultaneously — you can route different tasks (analysis, OCR, diagram recognition) to different providers. See the [deployment guide](docs/LOCAL-DEPLOY.md#ai-provider-configuration) for multi-provider setup.

## Roadmap

Recent releases focused on team collaboration (knowledge plaza, subscriptions, audit logs), the AI-assisted wiki editor, and i18n. Planned directions:

- Richer ingestion formats and smarter multi-modal parsing
- Incremental re-compilation when a source is updated or removed
- Deeper conflict resolution workflows between sources and pages
- Token-cost optimization and lighter models for routine tasks
- Plugin / MCP integration for external tools

Ideas and feedback are welcome via [issues](https://github.com/Raymonda/LLM-WIKI/issues).

## Documentation

| Document | Purpose |
|:--------:|:-------:|
| [docs/LOCAL-DEPLOY.md](docs/LOCAL-DEPLOY.md) | Local deployment guide — Docker Compose, dev setup, troubleshooting |
| [ARCHITECTURE.md](ARCHITECTURE.md) | System architecture overview — components, layers, core flows |
| [DESIGN.md](DESIGN.md) | UI/UX design system — visual language, interaction patterns |
| [AGENTS.md](AGENTS.md) | Developer guide — coding conventions, build commands, configuration |
| [llm-wiki.md](llm-wiki.md) | The original idea document that started it all |

## Project Structure

```
llmwiki/                  # Backend (DDD-layered Maven multi-module)
├── app/bootstrap/        # Spring Boot entry point + Flyway migrations
├── app/web/              # Controllers, Security (JWT)
├── app/biz/service/      # Business orchestration (Ingest, Query, Lint)
├── app/domain/           # Domain model + Domain services + AI Harness
├── app/common/           # DAL (MyBatis-Plus) + Util + Facade DTOs
└── wiki-data/            # Runtime file storage (gitignored)

llmwiki-web-ui/           # Frontend (Vue 3 + Vite)
├── src/views/            # Pages: Wiki, Editor, Search, Ingest, Lint, Graph...
├── src/components/       # Shared: layout, wiki renderer, harness tracker
├── src/stores/           # Pinia state management
└── src/api/              # Axios API layer
```

## License

[MIT](LICENSE)

## Contact

**Liu Weitao** — cool_zeel@163.com

<div align="center">
  <img width="190" height="282" alt="WeChat QR code" src="https://github.com/user-attachments/assets/3d2e1531-da91-4c01-8c19-f7a3e97a9279" />
  <p>
    <strong>Scan with WeChat to add the author</strong><br/>
    Feel free to reach out about using or extending LLM Wiki, or any AI knowledge-base topic.
  </p>
</div>
