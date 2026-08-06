# Local Deployment Guide

Get LLM Wiki running on your machine in under 5 minutes.

## Option A: Docker Compose (Recommended)

Two modes available:
- **Minimal** (default): MySQL + Elasticsearch + App + Web UI — 4 containers, ~2.5GB RAM
- **Full**: adds MinIO + RocketMQ — 6 containers, ~4GB RAM (for distributed/production use)

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) 24+ & Docker Compose v2
- A DashScope API key — [get one free](https://dashscope.console.aliyun.com/)

### Steps

```bash
git clone https://github.com/Raymonda/LLM-WIKI.git
cd LLM-WIKI

# Configure your API key (the only required variable)
cp .env.example .env
# Edit .env and set AI_DASHSCOPE_API_KEY=sk-your-key-here

# Start (minimal mode)
docker-compose up -d --build

# OR: full distributed mode (adds MinIO + RocketMQ)
docker-compose -f docker-compose.full.yml up -d --build
```

First build takes ~5–10 minutes (Maven dependency download + npm ci). Subsequent starts are instant.

### Access

| Service | URL | Notes |
|---------|-----|-------|
| **Web UI** | http://localhost:3000 | Main entry point |
| Backend API | http://localhost:8080 | REST + SSE |
| Elasticsearch | http://localhost:9200 | No auth (dev mode) |
| MySQL | localhost:3306 | llmwiki / llmwiki_2024 |
| MinIO Console | http://localhost:9001 | Full mode only, minioadmin / minioadmin |

### First Login

On first startup, the app auto-creates an `admin` user with a random temporary password printed in the container log:

```bash
docker logs llmwiki-app 2>&1 | grep -i "temporary password"
```

Login at http://localhost:3000 with `admin` + the printed password, then change it immediately.

### Stop / Cleanup

```bash
docker-compose down          # Stop containers (data preserved in volumes)
docker-compose down -v       # Stop + delete all data (fresh start)
```

---

## Option B: Local Development (No Docker for App)

Run infrastructure via Docker but develop the backend/frontend natively for hot-reload.

### 1. Start Infrastructure Only

```bash
cd llmwiki
docker-compose up -d mysql elasticsearch minio rocketmq-namesrv rocketmq-broker
```

### 2. Backend

Prerequisites: JDK 17+, Maven 3.9+

```bash
cd llmwiki

# Build
mvn clean package -DskipTests -pl app/bootstrap -am

# Run (dev profile uses localhost services)
java -jar target/boot/llmwiki-bootstrap-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  --AI_DASHSCOPE_API_KEY=sk-your-key-here
```

Or with Maven directly:

```bash
mvn clean install -DskipTests
cd app/bootstrap
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Backend starts on http://localhost:8080. Flyway migrations run automatically on first connect.

### 3. Frontend

Prerequisites: Node.js 18+

```bash
cd llmwiki-web-ui
npm install
npm run dev
```

Dev server starts on http://localhost:5173 with API proxy to `localhost:8080`.

### 4. Verify

Open http://localhost:5173 → login with admin (check backend console for the temporary password).

---

## AI Provider Configuration

LLM Wiki works with **any OpenAI-compatible API**. By default it uses DashScope (Alibaba Cloud), but you can switch to DeepSeek, Moonshot, OpenAI, Ollama, or any compatible endpoint.

### Single Provider (default)

Just set the API key in `.env`:

```bash
AI_DASHSCOPE_API_KEY=sk-your-key-here
```

To use a different single provider, override the base URL and model in `application.yml` or via environment variables:

```bash
# Example: use DeepSeek as the sole provider
AI_DASHSCOPE_API_KEY=sk-your-deepseek-key
# Then in application.yml, change spring.ai.openai.base-url to https://api.deepseek.com
```

### Multi-Provider Mode

For advanced users who want to route different tasks to different providers, uncomment the `providers` and `slots` sections in `application.yml`:

```yaml
llmwiki:
  ai:
    providers:
      dashscope:
        base-url: https://dashscope.aliyuncs.com/compatible-mode
        api-key: ${AI_DASHSCOPE_API_KEY:}
      deepseek:
        base-url: https://api.deepseek.com
        api-key: ${AI_DEEPSEEK_API_KEY:}
    slots:
      main:
        provider: deepseek
        model: deepseek-v4-flash
      multimodal:
        provider: dashscope
        model: qwen3.7-plus
      ocr:
        provider: dashscope
        model: qwen-vl-ocr
```

Available slots: `main`, `multimodal`, `query-multimodal`, `deep-analysis`, `deep-multimodal`, `ocr`, `diagram`.

---

## Environment Variables

At least one AI provider API key is required. Everything else has sensible defaults.

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_DASHSCOPE_API_KEY` | *(required*)* | DashScope API key (default provider) |
| `AI_DEEPSEEK_API_KEY` | *(empty)* | DeepSeek API key (multi-provider mode) |
| `AI_MOONSHOT_API_KEY` | *(empty)* | Moonshot/Kimi API key (multi-provider mode) |
| `AI_OPENAI_API_KEY` | *(empty)* | OpenAI API key (multi-provider mode) |
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/llmwiki?...` | MySQL JDBC URL |
| `MYSQL_USER` | `llmwiki` | MySQL username |
| `MYSQL_PASSWORD` | `llmwiki_2024` | MySQL password |
| `ES_URIS` | `http://localhost:9200` | Elasticsearch URI(s) |
| `ES_USERNAME` | *(empty)* | ES username (if auth enabled) |
| `ES_PASSWORD` | *(empty)* | ES password (if auth enabled) |
| `STORAGE_PROVIDER` | `local` | `local` or `s3` |
| `S3_ENDPOINT` | `http://localhost:9000` | MinIO/S3 endpoint |
| `S3_ACCESS_KEY` | `minioadmin` | S3 access key |
| `S3_SECRET_KEY` | `minioadmin` | S3 secret key |
| `ROCKETMQ_ENABLED` | `false` | Enable distributed mode |
| `ROCKETMQ_NAME_SERVER` | *(empty)* | RocketMQ nameserver address |
| `JWT_SECRET` | `llmwiki-default-secret-...` | **Change in production!** |
| `WIKI_DATA_PATH` | `./wiki-data` | File storage root |
| `ADMIN_USERNAMES` | `admin` | Comma-separated bootstrap admins |
| `ALLOW_SELF_REGISTER` | `false` | Allow public registration |

---

## Troubleshooting

### Port conflicts

If 3306/9200/9000/9876 are already in use, either stop the conflicting service or edit the port mappings in `docker-compose.yml`.

### Elasticsearch won't start (vm.max_map_count)

On Linux, ES requires:

```bash
sudo sysctl -w vm.max_map_count=262144
```

To persist, add `vm.max_map_count=262144` to `/etc/sysctl.conf`.

### Backend fails to connect to MySQL

- Ensure the MySQL container is healthy: `docker ps` (check STATUS column)
- Default credentials: `llmwiki` / `llmwiki_2024`
- Flyway runs migrations automatically — check logs for migration errors

### "placeholder-not-configured" API key error

You forgot to set `AI_DASHSCOPE_API_KEY`. The app starts but all AI features (Ingest, Query, Lint) will fail.

### Frontend shows blank page / 502

- Ensure the backend is running on port 8080
- In Docker mode, check `docker logs llmwiki-app` for startup errors
- In dev mode, verify `vite.config.ts` proxy target matches your backend port

### Windows-specific

- Use PowerShell or Git Bash (not cmd.exe) for `export` syntax
- If Docker Desktop WSL2 backend is slow, ensure the project is on a WSL2 filesystem (`\\wsl$\...`) or exclude it from Windows Defender real-time scanning

---

## Production Notes

For production deployment, at minimum:

1. Set a strong `JWT_SECRET`
2. Set `MYSQL_ROOT_PASSWORD` and `MYSQL_PASSWORD` to strong values
3. Enable Elasticsearch authentication (`xpack.security.enabled=true`)
4. Use external MySQL/ES instances or managed services
5. Set `STORAGE_PROVIDER=s3` with a real S3/MinIO endpoint for shared storage
6. Put nginx/Traefik in front with TLS termination
