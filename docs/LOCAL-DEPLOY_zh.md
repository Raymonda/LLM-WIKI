# 本地部署指南

5 分钟内在你自己的机器上跑起 LLM Wiki。

## 方式 A：Docker Compose 一键部署（推荐）

两种模式可选：
- **极简模式**（默认）：MySQL + Elasticsearch + 后端 + 前端 — 4 个容器，约 2.5GB 内存
- **完整模式**：额外启动 MinIO + RocketMQ — 7 个容器，约 4GB 内存（面向分布式/生产）

### 前置条件

- [Docker](https://docs.docker.com/get-docker/) 24+ & Docker Compose v2
- DashScope API Key（[免费获取](https://dashscope.console.aliyun.com/)）

### 操作步骤

```bash
git clone https://github.com/Raymonda/LLM-WIKI.git
cd LLM-WIKI

# 配置 API Key（唯一必填项）
cp .env.example .env
# 编辑 .env，填入 AI_DASHSCOPE_API_KEY=sk-your-key-here

# 一键启动（极简模式）
docker-compose up -d --build

# 或者：完整分布式模式（额外启动 MinIO + RocketMQ）
docker-compose -f docker-compose.full.yml up -d --build
```

首次构建约 5–10 分钟（Maven 依赖下载 + npm ci），后续启动秒级。

### 访问地址

| 服务 | 地址 | 备注 |
|------|------|------|
| **Web UI** | http://localhost:3000 | 主入口 |
| 后端 API | http://localhost:8080 | REST + SSE |
| Elasticsearch | http://localhost:9200 | 无认证（开发模式） |
| MySQL | localhost:3306 | llmwiki / llmwiki_2024 |
| MinIO 控制台 | http://localhost:9001 | 仅完整模式，minioadmin / minioadmin |

### 首次登录

首次启动时，应用自动创建 `admin` 用户并生成随机临时密码，打印在容器日志中（只显示一次）：

```bash
docker logs llmwiki-app 2>&1 | grep -A 5 "INITIAL ADMIN CREATED"
```

在 http://localhost:3000 使用 `admin` + 打印的密码登录，登录后立即修改密码。

### 停止 / 清理

```bash
docker-compose down          # 停止容器（数据保留在 volume 中）
docker-compose down -v       # 停止 + 删除所有数据（全新开始）
```

---

## 方式 B：本地开发模式（应用不用 Docker）

基础设施用 Docker 跑，后端/前端本地运行以获得热重载体验。

### 1. 仅启动基础设施

开发模式只需要 MySQL + Elasticsearch（文件存储默认本地，RocketMQ 默认关闭）：

```bash
cd llmwiki
docker-compose up -d mysql elasticsearch
```

### 2. 启动后端

前置条件：JDK 17+、Maven 3.9+

```bash
cd llmwiki

# 构建
mvn clean package -DskipTests -pl app/bootstrap -am

# 运行（dev profile 连接 localhost 服务，API Key 通过环境变量传入）
AI_DASHSCOPE_API_KEY=sk-your-key-here \
  java -jar target/boot/llmwiki-bootstrap-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

或者用 Maven 直接运行：

```bash
mvn clean install -DskipTests
cd app/bootstrap
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

后端启动在 http://localhost:8080，Flyway 迁移在首次连接时自动执行。

### 3. 启动前端

前置条件：Node.js 18+

```bash
cd llmwiki-web-ui
npm install
npm run dev
```

开发服务器启动在 http://localhost:5173，API 请求自动代理到 `localhost:8080`。

### 4. 验证

打开 http://localhost:5173 → 使用 admin 登录（临时密码见后端控制台输出）。

---

## AI 提供商配置

LLM Wiki 支持**任意 OpenAI 兼容 API**。默认使用 DashScope（阿里云），也可切换为 DeepSeek、Moonshot、OpenAI、Ollama 或任何兼容端点。

### 单提供商（默认）

在 `.env` 中设置 API Key 即可：

```bash
AI_DASHSCOPE_API_KEY=sk-your-key-here
```

如需使用其他提供商作为唯一后端，修改 `application.yml` 中的 `spring.ai.openai.base-url` 和模型名：

```bash
# 示例：使用 DeepSeek 作为唯一提供商
AI_DASHSCOPE_API_KEY=sk-your-deepseek-key
# 然后在 application.yml 中将 spring.ai.openai.base-url 改为 https://api.deepseek.com
```

### 多提供商模式

高级用户可将不同任务路由到不同提供商，取消 `application.yml` 中 `providers` 和 `slots` 段的注释：

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

可用槽位：`main`、`multimodal`、`query-multimodal`、`deep-analysis`、`deep-multimodal`、`ocr`、`diagram`。

---

## 环境变量

至少需要一个 AI 提供商的 API Key，其余均有合理默认值。

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `AI_DASHSCOPE_API_KEY` | *（必填）* | DashScope API Key（默认提供商） |
| `AI_DEEPSEEK_API_KEY` | *（空）* | DeepSeek API Key（多提供商模式） |
| `AI_MOONSHOT_API_KEY` | *（空）* | Moonshot/Kimi API Key（多提供商模式） |
| `AI_OPENAI_API_KEY` | *（空）* | OpenAI API Key（多提供商模式） |
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/llmwiki?...` | MySQL JDBC 连接串 |
| `MYSQL_USER` | `llmwiki` | MySQL 用户名 |
| `MYSQL_PASSWORD` | `llmwiki_2024` | MySQL 密码 |
| `ES_URIS` | `http://localhost:9200` | Elasticsearch 地址 |
| `ES_USERNAME` | *（空）* | ES 用户名（启用认证时） |
| `ES_PASSWORD` | *（空）* | ES 密码（启用认证时） |
| `STORAGE_PROVIDER` | `local` | `local` 或 `s3` |
| `S3_ENDPOINT` | `http://localhost:9000` | MinIO/S3 端点 |
| `S3_ACCESS_KEY` | `minioadmin` | S3 访问密钥 |
| `S3_SECRET_KEY` | `minioadmin` | S3 秘密密钥 |
| `ROCKETMQ_ENABLED` | `false` | 启用分布式模式 |
| `ROCKETMQ_NAME_SERVER` | *（空）* | RocketMQ NameServer 地址 |
| `JWT_SECRET` | `llmwiki-default-secret-...` | **生产环境必须修改！** |
| `WIKI_DATA_PATH` | `./wiki-data` | 文件存储根目录 |
| `ADMIN_USERNAMES` | `admin` | 逗号分隔的初始管理员 |
| `ALLOW_SELF_REGISTER` | `false` | 是否允许公开注册 |

---

## 常见问题

### 端口冲突

如果 3306/9200/9000/9876 已被占用，停止冲突服务或修改 `docker-compose.yml` 中的端口映射。

### Elasticsearch 启动失败（vm.max_map_count）

Linux 环境需要：

```bash
sudo sysctl -w vm.max_map_count=262144
```

持久化：将 `vm.max_map_count=262144` 写入 `/etc/sysctl.conf`。

### 后端连接 MySQL 失败

- 确认 MySQL 容器健康：`docker ps`（查看 STATUS 列）
- 默认凭据：`llmwiki` / `llmwiki_2024`
- Flyway 自动执行迁移——检查日志中的迁移错误

### "placeholder-not-configured" API Key 错误

忘记设置 `AI_DASHSCOPE_API_KEY`。应用可以启动，但所有 AI 功能（Ingest、Query、Lint）将不可用。

### 前端白屏 / 502

- 确认后端已在 8080 端口运行
- Docker 模式：检查 `docker logs llmwiki-app` 启动错误
- 开发模式：确认 `vite.config.ts` 代理目标与后端端口一致

### Windows 特有

- 使用 PowerShell 或 Git Bash（不要用 cmd.exe）执行 `export` 语法
- Docker Desktop WSL2 后端较慢时，将项目放在 WSL2 文件系统（`\\wsl$\...`）或排除 Windows Defender 实时扫描

---

## 生产部署注意事项

生产环境至少需要：

1. 设置强 `JWT_SECRET`
2. 设置强 `MYSQL_ROOT_PASSWORD` 和 `MYSQL_PASSWORD`
3. 启用 Elasticsearch 认证（`xpack.security.enabled=true`）
4. 使用外部 MySQL/ES 实例或托管服务
5. 设置 `STORAGE_PROVIDER=s3` 配合真实 S3/MinIO 端点实现共享存储
6. 前置 nginx/Traefik 做 TLS 终止
