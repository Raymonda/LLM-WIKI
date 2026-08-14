# Contributing to LLM Wiki

Thank you for your interest in contributing to LLM Wiki! This document provides guidelines and information for contributors.

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/YOUR_USERNAME/LLM-WIKI.git`
3. Create a feature branch: `git checkout -b feat/your-feature`
4. Make your changes
5. Commit with a descriptive message following [Conventional Commits](https://www.conventionalcommits.org/)
6. Push and open a Pull Request

## Development Setup

### Prerequisites

- JDK 17+
- Maven 3.9+
- Node.js 18+
- MySQL 8.x
- Elasticsearch 8.x

### Backend

```bash
cd llmwiki
mvn clean package -DskipTests -pl app/bootstrap -am
java -jar target/boot/llmwiki-bootstrap-1.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

### Frontend

```bash
cd llmwiki-web-ui
npm install
npm run dev
```

## Code Style

- **Java**: Follow existing DDD layering (`web` → `biz/service` → `domain` → `common`). No comments unless explicitly needed — code should be self-documenting through naming.
- **TypeScript/Vue**: Follow existing patterns in `llmwiki-web-ui/src/`. Use Composition API with `<script setup>`.
- **Commits**: Use Conventional Commits format (`feat:`, `fix:`, `docs:`, `refactor:`, etc.)

## Architecture Rules

- All data queries must enforce `scope_id` filtering (multi-tenant isolation)
- Never write to `raw/` directory — it is immutable source of truth
- All LLM calls go through Spring AI abstraction, never direct API calls
- Wiki page writes must go through the full pipeline (no bypassing to DB/FS/ES directly)

## Pull Request Guidelines

- Keep PRs focused — one logical change per PR
- Ensure `mvn clean package -DskipTests` passes
- Ensure `npm run type-check` passes for frontend changes
- Update documentation if your change affects architecture or public APIs

## Reporting Issues

Use the provided issue templates:
- **Bug Report**: for unexpected behavior or errors
- **Feature Request**: for new functionality proposals

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
