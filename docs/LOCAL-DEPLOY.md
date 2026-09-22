# Local Deployment Guide

Get LLM Wiki running on your machine in under 5 minutes.

## Option A: Docker Compose (Recommended)

Two modes available:
- **Minimal** (default): MySQL + Elasticsearch + App + Web UI — 4 containers, ~2.5GB RAM
- **Full**: adds MinIO + RocketMQ — 7 containers, ~4GB RAM (for distributed/production use)

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) 24+ & Docker Compose v2
- An API key from any OpenAI-compatible provider (e.g. [DashScope](https://dashscope.console.aliyun.com/)) — configured in System Settings after first login; not required for startup

### Steps

```bash
git clone https://github.com/Raymonda/LLM-WIKI.git
cd LLM-WIKI

# Optional: copy .env.example to .env to override defaults
# (AI model is configured in System Settings -> General after first login)
cp .env.example .env

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

On first startup, the app auto-creates an `admin` user with a random temporary password printed in the container log (shown only once):

```bash
docker logs llmwiki-app 2>&1 | grep -A 5 "INITIAL ADMIN CREATED"
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

Dev mode only needs MySQL + Elasticsearch (file storage defaults to local, RocketMQ is disabled):

```bash
cd llmwiki
docker-compose up -d mysql elasticsearch
```

### 2. Backend

Prerequisites: JDK 17+, Maven 3.9+

```bash
cd llmwiki

# Build
mvn clean package -DskipTests -pl app/bootstrap -am

# Run (dev profile uses localhost services)
java -jar target/boot/llmwiki-bootstrap-1.1.0-SNAPSHOT.jar --spring.profiles.active=dev
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

LLM Wiki works with **any OpenAI-compatible API** — DashScope (Alibaba Cloud), DeepSeek, Moonshot, OpenAI, Ollama, or any compatible endpoint.

AI providers, models, and slot routing are configured by the admin in **System Settings -> General** after login:

- Stored encrypted in the database (`system_config`), applied immediately without restart (hot refresh)
- The app starts normally with no AI configured; AI features return a setup guidance message until configured
- After the first login, open **System Settings -> General**, add a provider (base URL + API key + model), and save

Available slots (5 total): `main` (fast model, the default entry for all LLM calls; enable multimodal on it to reuse it for query image understanding), `multimodal` (image understanding), `ocr` (scanned-document OCR), plus optional scenario overrides `deep-analysis` and `diagram` (fall back to `main` when omitted).

---

## Environment Variables

All variables have sensible defaults. AI providers are configured in **System Settings -> General** (stored encrypted in DB), not via environment variables.

| Variable | Default | Description |
|----------|---------|-------------|
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

### AI features return a "not configured" guidance message

No AI provider has been configured yet. Log in as admin and complete the setup in **System Settings -> General** — it takes effect immediately, no restart needed.

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
