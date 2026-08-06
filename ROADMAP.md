# LLM Wiki — 开发路线图

## 项目状态概览

**当前阶段：C5 部署交付已完成，第四阶段调整为务实优化（按需实施）**

| 阶段 | 状态 | 说明 |
|------|------|------|
| 第一阶段：基础框架搭建 | ✅ 已完成 | 认证、Wiki 页面浏览、来源上传、AI 问答、前端 UI |
| 第二阶段 A：设计优化 + 存储架构 | ✅ 已完成 | 来源管理合并、文档修正、StorageProvider 抽象 |
| 第二阶段 B1：Harness 引擎基础框架 | ✅ 已完成 | HarnessEngine、ExecutionTracker、ApprovalService、PipelineOrchestrator、AgentRunner |
| 第二阶段 B2：Wiki 工具注册表 | ✅ 已完成 | readFile、writeFile、searchWiki、listPages、getRelatedPages、updateIndex、appendLog、updateLinks、extractMetadata |
| 第二阶段 B3：Ingest Pipeline 粘合 | ✅ 已完成 | PipelineOrchestrator 对接真实工具+数据库、AgentRunner 注册 Wiki 工具、BudgetGuard Token 预算检查、SSE 实时推送 |
| C1-1：IngestView SSE 对接 | ✅ 已完成 | 前端 5 步用户交互（上传→讨论→预览→确认→结果）对接后端 8 步 Pipeline SSE + 异步执行 |
| C1-2：SearchView SSE 对接 | ✅ 已完成 | 搜索问答对接真实 ReactAgent + SSE 流式输出 |
| C1-3：保存到知识库 | ✅ 已完成 | 问答结果保存为 Wiki 页面（AI 格式化 + 写入文件+数据库） |
| C1-4：Schema 编辑器 UI | ✅ 已完成 | Markdown 编辑器 + AI 建议优化（对接真实 API） |
| 第三阶段 C2：团队知识库 | ✅ 已完成 | RBAC + 多租户 + 团队 UI + 知识库切换 |
| 第三阶段 C2-b：知识晋升 | ✅ 已完成 | AI 自动提炼晋升 + 荣誉激励 + 隐私保护（两层防护+软召回）+ 通知系统 + 知识共享协议 UI |
| 第三阶段 C3：搜索升级 | ✅ 已完成 | Elasticsearch 全文检索 + MySQL fallback + 搜索建议 + 高亮匹配 + 分类筛选 |
| 第三阶段 C4：AI 限流 | ✅ 已完成 | RateLimiter + Semaphore 并发控制 + 文件大小限制 |
| 第三阶段 C5：部署交付 | ✅ 已完成 | Docker 容器化 + docker-compose + S3/MinIO 生产部署 + nginx 反向代理 |
| Ingest 进度跟踪两期演进 | ✅ 已完成 | 第一期：静态权重进度条 + 阶段导航 + 步骤时间轴（前端纯本地改造）；第二期：后端 StepProgressEvent + SSE `step_progress` 子进度推送 + `step_baseline` 表（scope × step × docFormat 的滑动平均 + EWMA p95）+ analyze 返回 baselineProfile + 前端 progressModel 支持 current/total/avgMsPerUnit 与 baseline 预估 + 全局悬浮进度卡（跨路由感知） |
| 知识矛盾检测与处置 | ✅ 已完成 | IngestContext 新增 ConflictAnnotation record + conflictAnnotations/schemaPatchHints；WriterAgent writingPlan prompt 输出 conflictAnnotations[] + extractConflictMeta/processConflictAnnotations + healthStatus conflict-warning；IndexerAgent createContradictionLinks() linkType="contradiction"；Query Save DETECT_SAVE_CONFLICTS 步骤 LLM 逐页比对；Lint AUTO_FIX_CROSSREFS 矛盾链接增强；GlobalSummary 新增 conflictLinkGraph + conflictWarningPages；QueryPrompts 矛盾呈现规则 + detectSaveConflictsPrompt；前端 WikiPageView 矛盾告警横幅 + HealthIndicator conflict-warning 状态 + KnowledgePulsePanel 矛盾警告样式 |
| 交叉引用优化 Phase 1-2 | ✅ 实施中 | 增强 AI 判断能力 + 人类审批闭环 + 纯 DB 存储（详见下方“交叉引用优化”章节） |
| 第四阶段：务实优化 | ⏳ 未来 | SSE 重连优化、AI 延迟优化、多节点 SSE 广播、MySQL 紧急优化预案 |

---

## 第一阶段：已完成工作

### 后端（已验证通过）

| 功能 | API 端点 | 状态 |
|------|---------|------|
| 用户认证 | POST /api/auth/login, POST /api/auth/register, GET /api/auth/info | ✅ |
| 用户管理（系统级） | GET/POST/PATCH /api/users/* | ✅ |
| 冷启动管理员 | AdminBootstrap 自动创建/提升 | ✅ |
| Wiki 页面列表 | GET /api/wiki/pages | ✅ |
| Wiki 页面详情 | GET /api/wiki/page/** | ✅ |
| Wiki 搜索 | GET /api/wiki/search | ✅ |
| Wiki 分类 | GET /api/wiki/categories | ✅ |
| Wiki 最近更新 | GET /api/wiki/recent | ✅ |
| Wiki 初始化 | POST /api/wiki/init | ✅ |
| Wiki 相关页面 | GET /api/wiki/page/{path}/related | ✅ |
| Wiki 索引 | GET /api/wiki/index | ✅ |
| Wiki 日志 | GET /api/wiki/log | ✅ |
| Wiki 图谱 | GET /api/wiki/graph | ✅ |
| Wiki 推荐 | GET /api/wiki/recommended | ✅ |
| Wiki 健康状态 | GET /api/wiki/health/{path} | ✅ |
| Wiki 请求修改 | POST /api/wiki/page/{path}/modify | ✅ |
| Schema 列表 | GET /api/harness/schema | ✅ |
| Schema 读取 | GET /api/harness/schema/{key} | ✅ |
| Schema 更新 | PUT /api/harness/schema/{key} | ✅ |
| Token 使用（真实） | GET /api/harness/token-usage | ✅ |
| Token 使用（按类型） | GET /api/harness/token-usage/by-type | ✅ |
| 来源上传 | POST /api/source/upload | ✅ |
| 来源列表 | GET /api/source/list | ✅ |
| 来源详情 | GET /api/source/{id} | ✅ |
| 来源下载 | GET /api/source/{id}/download | ✅ |
| 来源解析内容 | GET /api/source/{id}/content | ✅ |
| 来源删除 | DELETE /api/source/{id} | ✅ |
| 摄入启动 | POST /api/ingest/start | ✅ |
| 摄入 SSE | GET /api/ingest/{id}/stream | ✅ |
| AI 问答 | POST /api/query/ask | ✅ |
| AI 问答 SSE | GET /api/query/stream | ✅ |
| 保存到知识库 | POST /api/query/save | ✅ |
| Token 使用 | GET /api/harness/token-usage | ✅ stub |
| 执行记录 | GET /api/harness/executions | ✅ stub |
| 知识库列表 | GET /api/scope/list | ✅ |
| 知识库详情 | GET /api/scope/{scopeId} | ✅ |
| 创建团队知识库 | POST /api/scope | ✅ |
| 更新知识库配置 | PUT /api/scope/{scopeId} | ✅ |
| 删除知识库 | DELETE /api/scope/{scopeId} | ✅ |
| 添加成员 | POST /api/scope/{scopeId}/members | ✅ |
| 移除成员 | DELETE /api/scope/{scopeId}/members/{userId} | ✅ |
| 更新成员角色 | PUT /api/scope/{scopeId}/members/{userId}/role | ✅ |
| 成员列表 | GET /api/scope/{scopeId}/members | ✅ |
| 页面 visibility 切换 | PUT /api/wiki/page/{path}/visibility | ✅ |
| 个人晋升统计 | GET /api/wiki/promotion-stats | ✅ |
| 团队贡献榜 | GET /api/scope/{scopeId}/contributors | ✅ |
| 全局同意开关 | PUT /api/auth/consent-promotion | ✅ |
| 通知列表 | GET /api/notifications | ✅ |
| 通知未读数 | GET /api/notifications/unread-count | ✅ |
| 标记通知已读 | PUT /api/notifications/{id}/read | ✅ |
| 全部标记已读 | PUT /api/notifications/read-all | ✅ |
| 搜索（升级版） | GET /api/wiki/search?query=&category= | ✅ |
| 搜索建议 | GET /api/wiki/search/suggest?prefix= | ✅ |
| 索引重建 | POST /api/wiki/search/rebuild-index | ✅ |

### 前端（已验证通过）

| 页面 | 状态 |
|------|------|
| 登录页（动态图谱背景） | ✅ |
| 知识库首页（目录+最近更新+最近来源+推荐） | ✅ |
| Wiki 页面详情（健康指示器+来源标注） | ✅ |
| 搜索问答（双模式：搜索+AI问答+保存到知识库） | ✅ |
| 添加资料（8步协作流程+内嵌来源管理+SSE实时进度） | ✅ |
| 执行记录 | ✅ stub |
| Token 监控 | ✅ stub |
| 系统配置（Schema+用户管理） | ✅ |
| 团队知识库管理 | ✅ |
| 知识库切换组件 | ✅ |
| 通知铃铛+下拉面板 | ✅ |
| 注册时知识共享协议 | ✅ |
| 个人设置页知识共享开关 | ✅ |
| 加入团队时协议确认 | ✅ |
| 搜索问答升级（分类筛选+搜索建议+高亮匹配） | ✅ |

### 关键技术决策

1. **AI 模型接入**：从 `spring-ai-alibaba-starter-dashscope` 切换到 `spring-ai-starter-model-openai`，通过 DashScope 兼容模式接入 qwen3.6-plus。Query 操作从 spring-ai-alibaba ReactAgent 切换为 spring-ai 同源的 ChatClient.defaultTools()
2. **来源管理合并**：独立"来源管理"页面合并进"添加资料"流程，侧栏导航从 5 项简化为 3 项
3. **存储架构**：引入 `StorageProvider` 接口抽象，支持 Local（开发）、NAS（默认生产）、S3/MinIO（可选生产）三层切换
4. **搜索双模式架构**：`SearchService` 接口 + 两套实现，MySQL LIKE（默认 fallback，`@ConditionalOnProperty(matchIfMissing=true)`）和 Elasticsearch（`@ConditionalOnProperty(enabled=true)`），通过 `llmwiki.elasticsearch.enabled` 配置切换。ES 实现放在 biz-service 模块（需 ES 依赖），MySQL 实现放在 domain-service 模块（无额外依赖）。ES 未启用时系统零影响正常运行

---

## 第二阶段 A：已完成工作

### 设计文档修正

| 文档 | 修正内容 |
|------|---------|
| ARCHITECTURE.md | 前端架构重写为 3 项导航；数据库统一为 MySQL（移除 PostgreSQL 残留）；明确"数据库是真相来源"原则；Schema 层混合存储方案；~~index.md/log.md 已废弃~~（索引/日志统一由 DB 管理）；AI 技术栈更新为 OpenAI 兼容模式；Pinia Store 与实际代码对齐；数据库 Schema 与 V2 migration 对齐；新增存储架构章节 |
| DESIGN.md | "讨论要点"交互设计细化（异步批注式、左右分栏布局、AI 发现区 + 用户引导区） |
| AGENTS.md | 技术栈更新（spring-ai + OpenAI 兼容模式 + MinIO）；存储原则补充；Spring AI 集成编码约定更新；关键库描述更新；前端项目结构更新 |

### 存储架构实现

| 文件 | 说明 |
|------|------|
| StorageProvider.java | 接口定义（write/read/exists/delete/getUrl/ensureBucket） |
| NASStorageProvider.java | NAS 共享卷实现，FileLock 并发安全，`@ConditionalOnProperty(havingValue="nas")`，默认生产 |
| LocalStorageProvider.java | 本地文件系统实现，`@ConditionalOnProperty(matchIfMissing=true)` 开发默认激活 |
| S3StorageProvider.java | S3/MinIO 实现，AWS SDK v2，`@ConditionalOnProperty(havingValue="s3")`，可选生产 |
| WikiFileServiceImpl.java | 重构：所有文件操作通过 StorageProvider，移除 java.nio.file 直接调用 |
| SourceService.java | 重构：上传/删除通过 StorageProvider，移除 java.nio.file 直接调用 |
| application.yml | 新增 `llmwiki.storage.*` 配置项，环境变量覆盖 |
| pom.xml | 新增 AWS S3 SDK v2 依赖（optional） |
| LocalStorageProviderTest.java | 6 个单元测试全部通过 |

### 配置说明

```yaml
# 开发环境（默认，零依赖）
llmwiki.storage.provider=local

# 生产环境（NAS 共享卷）
llmwiki.storage.provider=nas
llmwiki.storage.nas.path=/data/llmwiki/wiki-data

# 生产环境（S3/MinIO，可选）
llmwiki.storage.provider=s3
llmwiki.storage.s3.endpoint=http://localhost:9000
llmwiki.storage.s3.access-key=minioadmin
llmwiki.storage.s3.secret-key=minioadmin
llmwiki.storage.s3.region=us-east-1
llmwiki.storage.s3.bucket-prefix=llmwiki
llmwiki.storage.s3.path-style=true
```

---

## 第二阶段 B：核心功能实现

### 任务清单

#### B1：Harness 引擎基础框架 ✅ 已完成

| 任务 | 优先级 | 说明 | 状态 |
|------|--------|------|------|
| 实现 HarnessEngine 接口 | 高 | 替换 HarnessEngineStub，重新设计方法签名（scopeId/sourceId） | ✅ |
| 实现 PipelineOrchestrator | 高 | 基于 spring-ai-alibaba-graph-core 的 Pipeline 编排（Ingest 8步/Lint 15步） | ✅ |
| 实现 AgentRunner | 高 | 基于 spring-ai-alibaba-agent-framework ReactAgent 的查询 Agent | ✅ |
| 实现 ExecutionTracker | 高 | 执行状态持久化到数据库（ExecutionDO/ExecutionStepDO/Mapper） | ✅ |
| 实现 ApprovalService | 中 | 三级审批模型（AUTO/CONFIRM/REVIEW），默认审批级别配置 | ✅ |

#### B2：Wiki 工具注册表 ✅ 已完成

| 任务 | 优先级 | 说明 | 状态 |
|------|--------|------|------|
| readFile 工具 | 高 | 读取 wiki-data 中的文件（通过 StorageProvider） | ✅ |
| writeFile 工具 | 高 | 写入 Wiki 页面文件（通过 StorageProvider） | ✅ |
| searchWiki 工具 | 高 | 通过数据库元数据搜索 Wiki 页面 | ✅ |
| updateIndex 工具 | 中 | ~~已废弃~~ 更新 index.md（不再维护 FS 副本） | ✅ |
| appendLog 工具 | 中 | ~~已废弃~~ 追加条目到 log.md（不再维护 FS 副本） | ✅ |
| listPages 工具 | 中 | 从数据库列出所有 Wiki 页面 | ✅ |
| getRelatedPages 工具 | 中 | 通过链接图谱获取相关页面 | ✅ |
| extractMetadata 工具 | 中 | LLM 从页面内容提取关键词/标签/摘要 | ✅ |
| updateLinks 工具 | 中 | 在链接图谱数据库中添加/更新链接 | ✅ |

#### B3：Ingest Pipeline（摄入流程）— 后端粘合 ✅ 已完成

| 任务 | 优先级 | 说明 | 状态 |
|------|--------|------|------|
| 步骤 1: READ_SOURCE | 高 | 通过 SourceMapper 查询 + StorageProvider 读取文件内容 | ✅ |
| 步骤 2: ANALYZE | 高 | LLM 分析来源文本，识别实体/概念/关系 | ✅ |
| 步骤 3: EXTRACT_METADATA | 高 | LLM 提取 JSON 格式元数据（title/summary/category/tags/keywords） | ✅ |
| 步骤 4: WRITE_SUMMARY | 高 | LLM 生成深入 Wiki 页面（详细概述+概念详解+关键细节+双链接+参考来源） | ✅ |
| 步骤 4.5: WRITE_ENTITY_PAGES | 高 | 为关键实体生成独立知识页面（新建或增量融合） | ✅ |
| 步骤 5: UPDATE_RELATED | 高 | LLM 判断哪些现有页面需要更新（查询 WikiPageMapper 获取现有页面列表） | ✅ |
| 步骤 6: UPDATE_LINKS | 中 | LLM 建议链接关系 | ✅ |
| ~~步骤 6: UPDATE_INDEX~~ | 中 | ~~已废弃~~ StorageProvider 读取/写入 index.md | ✅ |
| ~~步骤 8: APPEND_LOG~~ | 中 | ~~已废弃~~ StorageProvider 读取/写入 log.md → 更新 source 状态为 processed | ✅ |
| SSE 实时推送 | 中 | StepStatusEvent + SseEmitter 推送步骤状态 | ✅ |
| BudgetGuard Token 预算 | 中 | 每步前检查预算，耗尽标记 budget_exhausted | ✅ |
| 前端 IngestView 对接 | 中 | 5 步用户交互对接后端 8 步 Pipeline SSE，步骤进度实时展示 | ✅ |

#### B4：Query Agent（查询问答）— 后端粘合 ✅ 已完成

| 任务 | 优先级 | 说明 | 状态 |
|------|--------|------|------|
| ReactAgent 配置 | 高 | ChatClient + MethodToolCallback 包装 4 个 Wiki 工具 + scopeId 上下文注入 | ✅ |
| 多轮工具调用 | 高 | ReactAgent 自动循环调用 readFile/searchWiki/listPages/getRelatedPages | ✅ |
| BudgetGuard 预算检查 | 中 | 查询前检查 Token 预算，耗尽返回提示 | ✅ |
| SSE 流式输出 | 中 | 逐字推送答案到前端 | ✅ |
| 保存到知识库 | 中 | 将好答案保存为 Wiki 页面（AI 格式化 + 写入文件+数据库） | ✅ |
| 前端 SearchView 对接 | 中 | 对接真实 ChatClient 工具调用 SSE 流式输出 | ✅ |

#### B5：Lint Pipeline（健康检查）— 后端粘合 ✅ 已完成

| 任务 | 优先级 | 说明 | 状态 |
|------|--------|------|------|
| 步骤 1: CHECK_ORPHANS | 中 | WikiPageLinkMapper 查询链接 → 找出孤立页面 | ✅ |
| 步骤 2: CHECK_STALE | 中 | 零 Token SQL + 批量 LLM 检测过时页面 | ✅ |
| 步骤 3: CHECK_MISSING_CROSSREFS | 中 | LLM 检测共享关键词但缺链接的页面对 | ✅ |
| 步骤 4: CHECK_CONFLICTS | 中 | LLM 分析矛盾内容 | ✅ |
| 步骤 5: CHECK_GAPS | 中 | LLM 分析概念缺口 | ✅ |
| 步骤 6: AUTO_FIX_ORPHANS | 中 | LLM 自动修复孤立页面链接（受 LlmConcurrencyBarrier 约束） | ✅ |
| 步骤 7: AUTO_FIX_CROSSREFS | 中 | LLM 自动修复缺失交叉引用（受 LlmConcurrencyBarrier 约束） | ✅ |
| 步骤 8: AUTO_FILL_GAP | 中 | LLM 自动填充概念缺口页面 + wiki_page_source 关系 + schemaVersion 溯源（受 LlmConcurrencyBarrier 约束，sanitizePathSegmentUnique 碰撞检测） | ✅ |
| 步骤 9: EXECUTE_RULINGS | 中 | 根据 LintRulesConfig 处置策略执行裁决（受 LlmConcurrencyBarrier 约束，前置 AI 可用性检查） | ✅ |
| 步骤 10: WRITE_HEALTH_STATUS | 中 | 回写 wiki_page.health_status + 同步 ES 索引 | ✅ |
| 步骤 11: GENERATE_REPORT | 中 | 汇总所有诊断步骤结果 + 生成结构化报告 | ✅ |
| 步骤 12: SUGGEST_ACTIONS | 中 | LLM 建议修复动作（CONFIRM 级别） | ✅ |
| 步骤 13: PROPOSE_SCHEMA_PATCH | 中 | 基于 Lint 结果提议 Schema 增补 | ✅ |
| ~~步骤 14: UPDATE_INDEX~~ | 中 | ~~已废弃~~ 重建 index.md | ✅ |
| ~~步骤 15: APPEND_LOG~~ | 中 | ~~已废弃~~ 追加执行记录到 log.md | ✅ |
| 定时调度 | 高 | @Scheduled 个人每周一 3:00、团队每天 4:00 | ✅ |

#### B6：Schema 管理

| 任务 | 优先级 | 说明 | 依赖 |
|------|--------|------|------|
| Schema 读取 API | 中 | GET /api/harness/schema | B1 |
| Schema 更新 API | 中 | PUT /api/harness/schema（Markdown 编辑器） | B1 |
| Schema 文件缓存生成 | 中 | 数据库变更 → 异步生成 CLAUDE.md/AGENTS.md | B1 |
| 前端 Schema 编辑器 | 低 | Markdown 编辑器 UI | B6 |

### 建议的开发顺序

```
B1 ✅ → B2 ✅ → B3/B4/B5 后端粘合 ✅ → C1（前端对接）✅ → C2（团队知识库）✅ → C2-b（知识晋升）✅ 核心 → C3（搜索升级） → C4（AI限流） → C5（部署交付） → 第四阶段（技术深度改造）
```

当前进度：C2-b 知识晋升核心已完成，下一步 C3 搜索升级。

第三阶段结束时 = **功能完整、可部署的产品**。第四阶段 = **让产品跑得更快更稳**。

---

## 第三阶段：产品功能完备（功能闭环，可部署交付）

目标：所有业务需求在此阶段闭环，交付一个功能完整、可部署的产品。

### C1：前端对接 + Wiki API 补全

| 任务 | 优先级 | 说明 | 状态 |
|------|--------|------|------|
| IngestView SSE 对接 | 高 | 8 步协作流程对接真实后端 SSE 流式推送 | ✅ |
| SearchView ReactAgent 对接 | 高 | 搜索问答对接真实 ReactAgent + SSE 流式输出 | ✅ |
| 保存到知识库 | 中 | 问答结果保存为 Wiki 页面（CONFIRM 级别） | ✅ |
| Wiki 页面相关页面 API | 中 | GET /api/wiki/page/{path}/related — 链接图谱查询 | ✅ |
| Wiki 索引/日志 API | 中 | GET /api/wiki/index + GET /api/wiki/log — 数据库缓存导出 | ✅ |
| Wiki 图谱 API | 中 | GET /api/wiki/graph — 链接图谱可视化数据 | ✅ |
| Wiki 推荐 API | 低 | GET /api/wiki/recommended — 推荐阅读 | ✅ |
| Wiki 健康状态 API | 低 | GET /api/wiki/health/{path} — 页面健康详情 | ✅ |
| Wiki 请求修改 API | 中 | POST /api/wiki/page/{path}/modify — 用户下指令，AI 执行 | ✅ |
| Schema 编辑器 UI | 低 | Markdown 编辑器 + AI 建议优化 | ✅ |
| Token 监控 API 补全 | 低 | /api/harness/token-usage + token-budget 真实实现（当前 stub） | ✅ |
| readImage Wiki 工具 | 低 | 多模态图片描述工具（二期优先级） | 🔜 |

### C2：团队知识库（RBAC + 多租户）

| 任务 | 优先级 | 说明 | 状态 |
|------|--------|------|------|
| scope 表 + scope_member 表 | 高 | 多租户 RBAC（Owner/Admin/Editor/Viewer） | ✅ |
| 团队知识库 UI | 高 | 成员管理、权限配置、知识库切换 | ✅ |
| 团队审批级别调整 | 中 | 团队知识库默认 CONFIRM，个人默认 AUTO | ✅ |
| Token 预算分级 | 中 | 个人 100W、团队 1000W | ✅ |

### C2-b：知识晋升（AI 自动提炼 + 荣誉激励 + 隐私保护）

| 任务 | 优先级 | 说明 | 状态 |
|------|--------|------|------|
| **数据库迁移 V4** | | | |
| wiki_page 增加晋升字段 | 高 | visibility(open/private), promoted_from_scope_id, promoted_from_page_id, promoted_from_username | ✅ |
| user 增加全局同意字段 | 高 | consent_knowledge_promotion (default 1) | ✅ |
| scope 增加 upstream_scope_ids | 高 | JSON 数组，存储上游 scope ID 列表 | ✅ |
| **后端 — 晋升引擎** | | | |
| PROMOTE_FROM_UPSTREAM 步骤 | 高 | Lint Pipeline 新增步骤 2：扫描上游 scope 成熟页面 + AI 蒸馏提炼 + 写入本 scope | ✅ |
| AI 蒸馏 Prompt | 高 | 团队视角提炼：移除个人语境，保留知识结构，不搬运原文 | ✅ |
| 晋升通知机制 | 中 | 晋升完成后通知贡献者："你的知识《xxx》已被 AI 提炼纳入「团队名」" | ✅ |
| **后端 — 隐私保护** | | | |
| visibility 切换 API | 高 | PUT /api/wiki/page/{path}/visibility — open ↔ private 切换 | ✅ |
| 软召回流程 | 高 | private 触发：查询下游 promoted 页面 → 替换为存根 → 移除署名和溯源 → 标记 recalled → 通知下游 Owner | ✅ |
| 全局同意开关 API | 中 | 修改用户注册/信息 API，支持 consent_knowledge_promotion 字段读写 | ✅ |
| **后端 — 荣誉统计** | | | |
| 个人晋升统计 API | 中 | GET /api/wiki/promotion-stats — 被采纳团队数 + 提炼页面数 | ✅ |
| 团队贡献榜 API | 中 | GET /api/scope/{scopeId}/contributors — Top 贡献者列表 | ✅ |
| **前端 — 晋升交互** | | | |
| 页面 visibility 开关 | 高 | Wiki 页面详情右上角 open ↔ private 切换，private 时弹出软召回确认提示 | ✅ |
| 提炼页面荣誉标注 | 中 | 页面底部"本知识提炼自 {贡献者} 的个人研究"标注 | ✅ |
| **前端 — 荣誉激励 UI** | | | |
| 个人影响力区域 | 中 | 个人知识库首页：被采纳团队数 + 提炼页面数 + 最近被晋升列表 | ✅ |
| 团队贡献榜 | 中 | 团队知识库首页：Top 贡献者用户名+晋升页面数 | ✅ |
| **前端 — 隐私协议 UI** | | | |
| 注册时知识共享协议 | 中 | 注册表单增加复选框（默认勾选），展示协议文本 | ✅ |
| 加入团队时协议 | 低 | 加入团队流程中展示知识共享协议确认 | ✅ |
| 个人设置页全局开关 | 低 | "知识共享设置"开关：全局同意/不同意自动晋升 | ✅ |
| **前端 — 软召回展示** | | | |
| 召回存根页面渲染 | 低 | 被召回页面显示存根提示"因贡献者隐私保护暂停展示"，不可编辑 | ✅ |
| **Lint Pipeline 编号调整** | | | |
| Pipeline 步骤重编号 | 高 | 6 步 → 7 步（插入 PROMOTE_FROM_UPSTREAM 为步骤 2，后续步骤重编号 3-7） | ✅ |

### C3：搜索体验升级（全文检索）

| 任务 | 优先级 | 说明 | 状态 |
|------|--------|------|------|
| **后端 — Elasticsearch 集成** | | | |
| spring-data-elasticsearch 依赖 | 高 | integration 模块添加 ES 依赖（optional），biz-service 添加 ES 依赖（optional） | ✅ |
| WikiPageDocument ES 文档模型 | 高 | @Document + ik_max_word 中文分词（title/summary/content） | ✅ |
| WikiPageSearchRepository | 高 | ElasticsearchRepository<WikiPageDocument, Long> | ✅ |
| ElasticsearchConfig | 高 | @ConditionalOnProperty(enabled=true)，配置 RestClient + ElasticsearchClient | ✅ |
| ElasticsearchProperties | 高 | enabled/uris/indexName 配置属性，默认 enabled=false | ✅ |
| SearchService 接口 | 高 | search/suggest/indexPage/removePage/rebuildIndex | ✅ |
| MySqlSearchServiceImpl | 高 | MySQL LIKE fallback（@ConditionalOnProperty(enabled=false, matchIfMissing=true)） | ✅ |
| ElasticsearchSearchServiceImpl | 高 | ES 全文检索+中文分词+模糊匹配+高亮（biz-service 模块，需 ES 依赖） | ✅ |
| 数据同步 | 高 | WikiFileServiceImpl/PipelineOrchestrator/QueryService 写入后调用 searchService.indexPage | ✅ |
| 索引重建 API | 中 | POST /api/wiki/search/rebuild-index — 全量同步 scope 下所有页面到 ES | ✅ |
| **后端 — 搜索 API 升级** | | | |
| WikiController.searchPages | 高 | 改用 SearchService，增加 category 参数，返回 SearchResultInfo（含高亮+评分） | ✅ |
| 搜索建议 API | 中 | GET /api/wiki/search/suggest?prefix= — 输入联想推荐 | ✅ |
| SearchWikiTool 升级 | 高 | 改用 SearchService.search，描述更新为"支持中文分词和模糊匹配" | ✅ |
| SearchResultInfo DTO | 中 | 含 score/highlightedTitle/highlightedSummary/highlightedContent | ✅ |
| **前端 — 搜索体验优化** | | | |
| SearchView 分类筛选 | 中 | 下拉分类筛选器 + 清除按钮 | ✅ |
| SearchView 搜索建议 | 中 | 输入 2 字符后 300ms debounce 联想推荐，下拉面板选择 | ✅ |
| SearchView 高亮匹配 | 中 | ES 高亮片段渲染 + 匹配度评分显示 | ✅ |
| SearchView 输入优化 | 中 | 清除按钮 + 建议面板 mousedown 选择 + blur 延迟关闭 | ✅ |

### C4：AI 限流与安全

| 任务 | 优先级 | 说明 | 状态 |
|------|--------|------|------|
| **限流核心实现** | | | |
| RateLimitService | 高 | AI 调用频率检查（checkCallRate，2s 最小间隔）+ 并发控制（tryAcquireConcurrent/releaseConcurrent，Semaphore）+ 文件大小限制（checkFileSize） | ✅ |
| Ingest Pipeline 并发控制 | 高 | runIngestPipelineWithExecution 入口添加 tryAcquireConcurrent + 失败/成功时 releaseConcurrent | ✅ |
| Lint Pipeline 并发控制 | 高 | runLintPipeline 入口添加 tryAcquireConcurrent + 失败/成功时 releaseConcurrent | ✅ |
| AgentRunner 调用频率限制 | 高 | runQueryAgent 入口添加 checkCallRate | ✅ |
| 文件上传大小限制 | 中 | SourceController.uploadSource 添加 checkFileSize 检查 | ✅ |
| **限流默认配置** | | | |
| 个人并发=1 | 中 | scope.type=personal → maxConcurrent=1, maxFileSize=10MB | ✅ |
| 团队并发=3 | 中 | scope.type=team → maxConcurrent=3, maxFileSize=50MB | ✅ |
| 部门并发=5 | 中 | scope.type=department → maxConcurrent=5, maxFileSize=100MB | ✅ |
| ScopeDO 自定义配置 | 中 | scope 表已有 maxConcurrent/maxFileSize 字段，支持按团队自定义 | ✅ |

### C5：部署交付

| 任务 | 优先级 | 说明 | 状态 |
|------|--------|------|------|
| **后端容器化** | | | |
| Dockerfile 更新 | 高 | JDK17 多阶段构建（maven:3.9-eclipse-temurin-17 → eclipse-temurin:17-jre-alpine），依赖缓存优化 | ✅ |
| .dockerignore | 中 | 排除 target/node_modules/.git 等减少构建上下文 | ✅ |
| docker-compose.yml | 高 | MySQL 8 + Elasticsearch 8.15 + MinIO + App + WebUI 全栈编排 | ✅ |
| application-prod.yml | 高 | 生产配置：HikariCP 连接池优化 + S3 存储 + ES 启用 + 环境变量全覆盖 | ✅ |
| **前端容器化** | | | |
| 前端 Dockerfile | 高 | Node 20 多阶段构建 → Nginx Alpine 静态资源托管 | ✅ |
| nginx.conf | 高 | 反向代理 /api/ → App:8080 + SPA fallback + gzip | ✅ |
| **生产部署模式** | | | |
| 全栈 docker-compose | 高 | 一键启动：MySQL + ES + MinIO + App + WebUI，健康检查 + depends_on | ✅ |
| NAS 模式 | 高 | NASStorageProvider 默认激活（NAS 挂载 + FileLock），适合传统部署 | ✅ |
| S3/MinIO 模式 | 高 | S3StorageProvider 云原生部署（MinIO 兼容 S3 API），docker-compose 默认模式 | ✅ |

---

## 交叉引用优化（2026-05 启动）

### 背景与问题

**第一性原理剖析**：交叉引用的本质是“在知识网络中建立语义关联路径”，而非简单的关键词匹配。当前实现存在以下问题：

1. **检测算法粗糙**：基于关键词共享数量（≥3 个），误报/漏报率高
2. **修复机制盲目**：不问“是否应该链接”，全自动创建链接
3. **链接质量低**：没有上下文说明，用户不知道“为什么建立这个链接”
4. **缺乏人类审核**：违背了“人机协同”的知识治理原则
5. **可能破坏知识库**：过度链接导致“链接泛滥”，降低导航价值

### Phase 1：增强 AI 判断能力（✅ 已完成）

**目标**：让 AI 基于页面内容进行语义判断，而非仅凭关键词匹配。

| 改动项 | 说明 | 文件 |
|--------|------|------|
| **数据库增强** | `wiki_page_link` 表增加 `link_context`（上下文说明）、`created_by`（来源追踪）、`confidence`（AI 置信度）、`execution_id`（执行记录）、`created_at`（创建时间） | `V19__enhance_wiki_page_link.sql` |
| **Prompt 增强** | `generateCrossrefLink` 输入页面内容（前 3000 字符），输出 `shouldLink`（是否链接）、`linkContext`（关系说明）、`confidence`（置信度 0-1）、`reason`（判断理由） | `LintPrompts.java` |
| **解析器增强** | `parseSuggestions` 支持新字段，过滤 `shouldLink=false` 的建议 | `LinkWritingService.java` |
| **写入增强** | `upsertLinkRecord` 保存 `linkContext`、`confidence`、`createdBy`、`executionId` | `LinkWritingService.java` |
| **移除 FS 同步** | 不再自动同步链接到 Markdown 文件，改为纯 DB 存储（`syncWikiLinkToFile` 标记 `@Deprecated`） | `LinkWritingService.java` |
| **内容读取** | `AUTO_FIX_CROSSREFS` 步骤读取页面内容并传递给 AI | `PipelineOrchestrator.java` |
| **拒绝机制** | AI 判断“不应链接”时，标记 finding 为 `dismissed` 并记录理由 | `LintFindingService.java` |

**效果**：
- AI 可基于语义相关性判断是否应该链接（而非仅关键词匹配）
- 每个链接都有上下文说明（“为什么建立这个链接”）
- 链接可溯源（`created_by='lint_ai'` + `execution_id`）
- 置信度 < 0.5 的建议可标记为需人工审核

### Phase 2：人类审批闭环（⏳ 实施中）

**目标**：引入人类审批机制，AI 建议 + 人工确认。

| 任务 | 说明 | 优先级 |
|------|------|--------|
| **前端展示** | 知识体检页面展示 AI 建议的链接列表 + 上下文说明 + 置信度 | P0 |
| **批量审批** | 支持批量确认（高置信度 > 0.8）/ 逐条审批（中置信度 0.5-0.8）/ 批量忽略（低置信度 < 0.5） | P0 |
| **API 扩展** | 新增 `/api/lint/findings/{id}/approve-link` 和 `/api/lint/findings/{id}/reject-link` | P0 |
| **反馈学习** | 用户决策（确认/拒绝）反哺 AI 模型，提升下次判断准确度 | P1 |
| **链接管理** | 知识库页面底部显示“相关页面”列表（从 DB 查询），支持手动添加/删除链接 | P1 |

### Phase 3：语义向量嵌入（⏳ 未来路线）

**目标**：使用 Embedding 模型替代关键词匹配，从根源上提升检测质量。

| 任务 | 说明 | 触发条件 |
|------|------|---------|
| **Embedding 集成** | 集成 OpenAI `text-embedding-3-small` 或同类模型 | Phase 2 完成后 |
| **页面向量生成** | 所有 Wiki 页面生成 Embedding 向量，存储到 MySQL 或专用向量数据库 | Phase 2 完成后 |
| **语义相似度计算** | 替代关键词匹配，使用 `cos(向量A, 向量B) > 0.7` 作为候选条件 | Phase 2 完成后 |
| **混合检测** | 语义相似度（主）+ 关键词匹配（辅）+ AI 判断（终） | Phase 2 完成后 |

**预期效果**：
- 误报率降低 70%+（解决“同词异义”问题）
- 漏报率降低 80%+（解决“同义异词”问题）
- Token 消耗增加 ~20%（Embedding API 调用）

### Phase 4：全局链接优化（⏳ 未来路线）

**目标**：控制链接密度，避免“链接泛滥”，优化知识图谱结构。

| 任务 | 说明 | 触发条件 |
|------|------|---------|
| **链接密度控制** | 限制单页面最大链接数（如出度 ≤ 20，入度 ≤ 50） | Phase 3 完成后 |
| **孤立簇识别** | 识别知识图谱中的“孤立簇”，优先桥接跨簇链接 | Phase 3 完成后 |
| **马太效应抑制** | 避免热门页面链接越来越多，冷门页面越来越孤立 | Phase 3 完成后 |
| **图谱质量指标** | 计算平均链接密度、簇系数、中心性分布等指标 | Phase 3 完成后 |
| **可视化增强** | 知识图谱页面展示链接质量指标（高/中/低置信度分层展示） | Phase 3 完成后 |

**预期效果**：
- 链接质量提升（高价值链接占比 > 80%）
- 知识图谱导航价值提升（用户点击率 +50%）
- 避免“链接疲劳”（单页面相关页面列表 ≤ 15 个）

### 技术决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| **链接存储** | A. 纯 DB / B. DB + FS 双写 / C. 纯 FS | A. 纯 DB | 知识图谱查询零额外成本，单一真相源，消除双写一致性风险 |
| **检测算法** | A. 关键词匹配 / B. 语义向量 / C. 混合 | C. 混合（Phase 3） | Phase 1-2 先用关键词+AI 判断快速上线，Phase 3 引入 Embedding |
| **修复模式** | A. 全自动 / B. AI 建议 + 人工审批 / C. 纯人工 | B. AI 建议 + 人工审批 | 平衡效率与质量，符合“人机协同”原则 |
| **FS 同步** | A. 继续同步 / B. 移除同步 / C. 按需同步 | B. 移除同步 | 链接是“关系数据”，不应污染“内容数据”（Markdown 文件） |

---

## 第四阶段：务实优化（按需实施）

目标：针对实际出现的瓶颈做精准优化，不做过度架构。所有改造项以实测数据为依据，不预设性能问题。

> 评估结论：当前系统是"写多读少、低 QPS、高延迟（AI 调用）"的内部知识库应用。
> 原 ROADMAP 中的 Redis 缓存、MySQL 主从/分库分表、CDN、K8s、SSE→轮询均属过度架构或功能降级，已移除。

| 任务 | 说明 | 触发条件 | 优先级 |
|------|------|---------|--------|
| SSE 断线重连 | 前端 EventSource 重连机制 + 后端重连恢复状态 | 当前功能缺陷 | 高 |
| AI 调用延迟优化 | 流式输出 chunk 化（ChatClient.stream 替代 .call）、超时重试 | 当前用户体验瓶颈 | 高 |
| 多节点 SSE 广播 | Redis Pub/Sub 或 Hazelcast 广播 StepStatusEvent | 需要多实例部署时才实施 | 中 |
| MySQL 紧急优化预案 | 索引优化 + 查询分析 + 分区表（按 scope_id），不分库分表 | 单表超过 100 万行或查询 P99 > 100ms 时才实施 | 低（预案） |

**已移除的过度架构项（不再实施）**：

| 原计划 | 移除原因 |
|--------|---------|
| Redis 缓存层（Schema/页面） | 数据量小，MySQL 索引查询毫秒级响应，缓存减少的延迟在整体 AI 延迟中占比不到 1% |
| MySQL 主从/读写分离 | 读 QPS 低，单节点够用；主从引入的运维复杂度（延迟同步、主从切换）远超收益 |
| MySQL 分库分表 | 50 万行数据量不需要分片；分库分表改动面巨大，跨 scope 查询需特殊处理 |
| CDN | 内部知识库非公开站，页面浏览量低；动态内容不适合静态化+CDN |
| K8s 部署 | docker-compose 已满足生产需求；AI 调用瓶颈不在 App 实例数量，扩容无意义 |
| SSE→数据库轮询 | 功能降级而非优化；轮询增加服务器持续压力，无法替代流式输出的用户体验 |

---

## 关键架构原则（新 session 必读）

1. **数据库是真相来源（source of truth），文件系统是 LLM 友好的缓存/导出层**
   - 所有写操作先写数据库，再同步到文件系统
   - LLM 工具 readFile 优先读文件系统缓存（快），fallback 到数据库
   - ~~index.md / log.md 已废弃~~ 索引由 GlobalSummaryService 从 DB 实时构建，执行记录直接查 execution 表

2. **Schema 层存储**
   - 真相来源：MySQL schema_config 表，config_value 存储 Markdown 格式内容
   - ~~文件缓存：wiki-data/{scopeId}/schema/CLAUDE.md 和 AGENTS.md~~ **已废弃**：无读取路径，DB 是唯一 source of truth

3. **StorageProvider 抽象**
   - 开发环境：LocalStorageProvider（本地文件系统，零依赖，matchIfMissing=true）
   - 生产默认：NASStorageProvider（NAS 共享卷 + FileLock，多节点安全）
   - 可选生产：S3StorageProvider（MinIO / 任意 S3 兼容服务，云原生场景）
   - 切换只需改配置：`llmwiki.storage.provider=local` → `nas` → `s3`

4. **AI 模型接入**
   - 使用 `spring-ai-starter-model-openai` 通过 DashScope 兼容模式
   - base-url: `https://dashscope.aliyuncs.com/compatible-mode`（不含 /v1）
   - 当前模型：kimi-k2.6
   - ChatModel 注入：`@Autowired(required=false)` setter 方式，手动构建 ChatClient

5. **导航结构：3 项导航**
   - 知识库（首页）— 目录+最近更新+最近来源+推荐+添加资料按钮
   - 搜索问答 — 双模式（搜索+问答+保存到知识库）
   - 设置管理 ▾ — 执行记录/Token监控/系统配置

6. **来源管理已合并进添加资料**
   - 不再有独立来源管理页面
   - IngestView 上传步骤下方展示已有来源列表（支持删除）
   - WikiListView 侧栏增加"最近来源"区域

7. **多租户数据隔离**
   - 所有 Wiki/Harness 表含 scope_id 字段
   - 一期 scope_id = user_id（个人知识库）
   - 文件系统路径包含 scopeId：wiki-data/{scopeId}/
   - S3 bucket 命名：llmwiki-{scopeId}

8. **SSE 实时通信**
   - Ingest SSE：`GET /api/ingest/{id}/stream?token=JWT` — 事件：init, step, done
   - Query SSE：`GET /api/query/stream?question=xxx&token=JWT` — 事件：start, answer-chunk, answer-complete
   - EventSource 无法发送自定义 header → JWT 通过 query param `?token=` 传递
   - JwtAuthenticationFilter 已扩展支持 query param token

9. **保存到知识库流程**
   - 前端调用 `POST /api/query/save`（question + answer + sessionId）
   - 后端 QueryService.saveAnswerToWiki：AI 格式化 → JSON 解析 → 写入文件（StorageProvider）+ 写入数据库（WikiPageMapper）
   - AI 不可用时 fallback：直接用 question 作标题，answer 作内容

---

## 当前代码状态

### 后端关键文件

| 文件 | 路径 | 说明 |
|------|------|------|
| StorageProvider.java | integration/storage/ | 接口定义 |
| NASStorageProvider.java | integration/storage/ | NAS 共享卷实现（生产默认） |
| LocalStorageProvider.java | integration/storage/ | 本地实现（开发默认激活） |
| S3StorageProvider.java | integration/storage/ | S3/MinIO 实现（可选生产） |
| DashScopeChatClient.java | integration/ai/ | AI 调用封装 |
| WikiFileServiceImpl.java | domain/service/wiki/ | 已重构使用 StorageProvider |
| SourceService.java | domain/service/wiki/ | 已重构使用 StorageProvider |
| HarnessEngineStub.java | domain/service/harness/ | 旧 stub（已移除 @Component，保留为参考） |
| HarnessEngineImpl.java | domain/service/harness/ | ✅ HarnessEngine 实现（委托 PipelineOrchestrator/AgentRunner） |
| PipelineOrchestrator.java | domain/service/harness/ | ✅ Ingest/Lint Pipeline 编排（8步/15步，对接真实工具+数据库+BudgetGuard） |
| AgentRunner.java | domain/service/harness/ | ✅ Query Agent（ChatClient.defaultTools() 注册 8 个 Wiki 工具 + 三层回答架构 Prompt + BudgetGuard） |
| ExecutionTrackerImpl.java | domain/service/harness/tracker/ | ✅ 执行状态持久化到数据库 + StepStatusEvent 发布 |
| StepStatusEvent.java | domain/service/harness/tracker/ | ✅ Spring ApplicationEvent（步骤状态变化通知） |
| ApprovalServiceImpl.java | domain/service/harness/governance/ | ✅ 三级审批模型（AUTO/CONFIRM/REVIEW） |
| BudgetGuard.java | domain/service/harness/governance/ | ✅ Token 预算检查（hasBudget/recordUsage/getOrCreateBudget） |
| ReadFileTool.java | domain/service/harness/tool/ | ✅ @Tool 读取文件（StorageProvider） |
| WriteFileTool.java | domain/service/harness/tool/ | ✅ @Tool 写入文件（StorageProvider） |
| SearchWikiTool.java | domain/service/harness/tool/ | ✅ @Tool 搜索 Wiki 页面（SearchService，支持 ES/MySQL 双模式） |
| ListPagesTool.java | domain/service/harness/tool/ | ✅ @Tool 列出 Wiki 页面（WikiPageMapper） |
| GetRelatedPagesTool.java | domain/service/harness/tool/ | ✅ @Tool 获取相关页面（WikiPageLinkMapper） |
| UpdateIndexTool.java | domain/service/harness/tool/ | ✅ ~~已废弃~~ @Tool 更新 index.md（StorageProvider） |
| AppendLogTool.java | domain/service/harness/tool/ | ✅ ~~已废弃~~ @Tool 追加执行日志（StorageProvider） |
| UpdateLinksTool.java | domain/service/harness/tool/ | ✅ @Tool 更新链接图谱（WikiPageLinkMapper） |
| ExtractMetadataTool.java | domain/service/harness/tool/ | ✅ @Tool LLM 提取元数据（DashScopeChatClient） |
| ExecutionDO.java | common/dal/dataobject/ | ✅ execution 表 DO |
| ExecutionStepDO.java | common/dal/dataobject/ | ✅ execution_step 表 DO |
| ExecutionMapper.java | common/dal/mapper/ | ✅ execution 表 Mapper |
| ExecutionStepMapper.java | common/dal/mapper/ | ✅ execution_step 表 Mapper |
| QueryService.java | biz/service/query/ | ✅ AI 问答 + saveAnswerToWiki（AI 格式化 → 写入文件+数据库） |
| SaveAnswerRequest.java | facade/model/ | ✅ 保存到知识库请求 DTO |
| AuthController.java | web/controller/ | 已实现认证 |
| WikiController.java | web/controller/ | 已实现 Wiki 页面操作 |
| SourceController.java | web/controller/ | 已实现来源管理 |
| QueryController.java | web/controller/ | ✅ SSE 流式对接 ChatClient 工具调用（/stream）+ 保存到知识库（/save） |
| IngestController.java | web/controller/ | ✅ 异步 pipeline + SSE 实时推送（/{id}/stream） |
| JwtAuthenticationFilter.java | web/security/ | ✅ 支持 query param token（SSE 端点认证） |
| IngestService.java | biz/service/ingest/ | ✅ 异步执行（createExecution + runIngestPipeline） |
| PipelineOrchestrator.java | domain/service/harness/ | ✅ runIngestPipelineWithExecution（接受已有 executionId） |
| HarnessController.java | web/controller/ | ✅ 对接 ExecutionTracker/ApprovalService/TokenUsage |
| LintController.java | web/controller/ | ✅ 对接 HarnessEngine（scopeId） |
| SearchService.java | domain/service/search/ | ✅ 搜索接口（search/suggest/indexPage/removePage/rebuildIndex） |
| MySqlSearchServiceImpl.java | domain/service/search/ | ✅ MySQL LIKE fallback（默认激活） |
| ElasticsearchSearchServiceImpl.java | biz/service/ | ✅ ES 全文检索实现（@ConditionalOnProperty ES_ENABLED=true） |
| WikiPageDocument.java | integration/search/ | ✅ ES 文档模型（ik_max_word 中文分词） |
| WikiPageSearchRepository.java | integration/search/ | ✅ ES Repository |
| ElasticsearchConfig.java | integration/search/ | ✅ ES 配置（@ConditionalOnProperty） |
| ElasticsearchProperties.java | integration/search/ | ✅ ES 配置属性（enabled/uris/indexName） |
| SearchResultInfo.java | facade/model/ | ✅ 搜索结果 DTO（含 score + 高亮片段） |
| RateLimitService.java | domain/service/harness/governance/ | ✅ AI 限流 + 并发控制 + 文件大小限制 |

### 前端关键文件

| 文件 | 路径 | 说明 |
|------|------|------|
| AppSidebar.vue | components/layout/ | 3 项导航（已移除来源管理/添加资料子菜单） |
| WikiListView.vue | views/wiki/ | 知识库首页（已增加最近来源区域） |
| IngestView.vue | views/ingest/ | ✅ 8 步协作流程 + SSE 实时进度 + AI 发现区 |
| SearchView.vue | views/search/ | ✅ 双模式搜索问答 + SSE 流式 + 保存到知识库 |
| ingest.ts | api/ | ✅ startIngest + createIngestSSE（EventSource + token query param） |
| query.ts | api/ | ✅ createQuerySSE + saveAnswer |
| execution.ts | stores/ | ✅ 8 步 ingest 步骤定义 + SSE 状态管理 |
| WikiPageView.vue | views/wiki/ | Wiki 页面详情 |
| LoginView.vue | views/system/ | 登录页（动态图谱背景） |
| router/index.ts | router/ | 已移除 /source 路由 |
| wiki.ts | api/ | Wiki API |
| source.ts | api/ | 来源 API |
| auth.ts | api/ | 认证 API |

### 数据库

| 表 | 说明 | 状态 |
|------|------|------|
| user | 用户表（含 scope_id） | ✅ V2 migration |
| system_config | 系统配置 | ✅ V2 migration |
| source | 来源文件 | ✅ V2 migration |
| wiki_page | Wiki 页面元数据 | ✅ V2 migration |
| wiki_page_tag | 页面标签 | ✅ V2 migration |
| wiki_page_keyword | 页面关键词 | ✅ V2 migration |
| wiki_page_link | 链接图谱 | ✅ V2 migration |
| wiki_page_source | 页面-来源关联 | ✅ V2 migration |
| schema_config | Schema 配置 | ✅ V2 migration |
| execution | 执行追踪 | ✅ V2 migration |
| execution_step | 执行步骤 | ✅ V2 migration |
| scope_budget | Token 预算 | ✅ V2 migration |
| scope | 知识库范围定义 | ✅ V3 migration |
| scope_member | 范围成员（RBAC） | ✅ V3 migration |

### 待创建的数据库变更（C2-b）

| 表 | 变更 | 说明 | 状态 |
|------|------|------|------|
| wiki_page | 增加字段 | visibility, promoted_from_scope_id, promoted_from_page_id, promoted_from_username | ⏳ V4 migration |
| user | 增加字段 | consent_knowledge_promotion (default 1) | ⏳ V4 migration |
| scope | 增加字段 | upstream_scope_ids (JSON) | ⏳ V4 migration |

---

## 构建与运行命令

### 后端

```bash
cd llmwiki
mvn clean install -DskipTests

# 本地模式运行（默认）
cd app/bootstrap
mvn spring-boot:run

# NAS 模式运行（生产默认）
$env:STORAGE_PROVIDER="nas"
$env:NAS_PATH="/data/llmwiki/wiki-data"
mvn spring-boot:run

# S3 模式运行（可选，需要 MinIO）
$env:STORAGE_PROVIDER="s3"
$env:S3_ENDPOINT="http://localhost:9000"
$env:S3_ACCESS_KEY="minioadmin"
$env:S3_SECRET_KEY="minioadmin"
mvn spring-boot:run

# 运行测试
mvn test -pl app/test -Dtest=LocalStorageProviderTest
```

### 前端

```bash
cd llmwiki-web-ui
npm install
npm run dev
npm run type-check
```

### MinIO 本地部署（测试 S3 模式）

```bash
# Docker 部署 MinIO
docker run -p 9000:9000 -p 9001:9001 \
  minio/minio server /data --console-address ":9001"

# 或下载 Windows 版本直接运行
# https://dl.min.io/server/minio/release/windows-amd64/minio.exe
```

