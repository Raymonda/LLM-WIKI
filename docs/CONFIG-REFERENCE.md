# LLM Wiki 配置参考

> `application.yml` 只保留部署必改项。本文档收录两类内容：
> 1. **多 Provider 模式**配置示例（需要改 YAML 结构）
> 2. **内部调优参数**完整清单（全部由代码默认值驱动，用环境变量覆盖即可，无需改 YAML）

## 多 Provider 模式

在 `application.yml`（或 profile 文件）的 `llmwiki.ai` 下添加 `providers` + `slots`。
`providers` 为空时自动回退 `spring.ai.openai.*` 单 key 模式。

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
      moonshot:
        base-url: https://api.moonshot.cn
        api-key: ${AI_MOONSHOT_API_KEY:}
      openai:
        base-url: https://api.openai.com
        api-key: ${AI_OPENAI_API_KEY:}
      local:
        base-url: ${AI_LOCAL_BASE_URL:http://localhost:11434}
        api-key: ${AI_LOCAL_API_KEY:ollama}
    slots:
      main:
        provider: deepseek
        model: deepseek-v4-flash-0731
      multimodal:
        provider: dashscope
        model: qwen3.7-plus
      query-multimodal:
        provider: dashscope
        model: qwen3.6-flash
      deep-analysis:
        provider: deepseek
        model: deepseek-v4-pro
      deep-multimodal:
        provider: dashscope
        model: qwen3.7-plus
      ocr:
        provider: dashscope
        model: qwen-vl-ocr
      diagram:
        provider: moonshot
        model: kimi-k2.6
```

Slot 说明：`main` 主对话；`multimodal` / `query-multimodal` / `deep-multimodal` 图像理解；
`deep-analysis` 深度分析；`ocr` 扫描件识别；`diagram` 图表生成。
未配置的 slot 回退到单 key 模式的对应分档模型。

## 内部调优参数（环境变量覆盖）

以下参数均有代码默认值，仅在需要调整时设置对应环境变量。

### LLM 调用与并发

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `LLM_TIMEOUT_MS` | 300000 | 单次 LLM 调用超时（ms） |
| `LLM_RETRY_MAX` | 3 | 重试次数 |
| `LLM_RETRY_DELAY` | 2000 | 重试基础延迟（ms） |
| `LLM_CONCURRENCY_PLAN` | 2 | Plan 阶段并发许可 |
| `LLM_CONCURRENCY_SUMMARY` | 2 | 摘要页并发许可 |
| `LLM_CONCURRENCY_ENTITY` | 4 | 实体页并发许可 |
| `LLM_CONCURRENCY_ANALYZE` | 6 | 分析阶段并发许可 |
| `LLM_EXECUTOR_CORE` | 8 | LLM 线程池核心大小 |
| `LLM_EXECUTOR_MAX` | 12 | LLM 线程池最大大小 |
| `llmwiki.llm.budget.*.max-input-tokens` | ingest 120000 / query 32000 / lint 64000 / default 64000 | 各场景输入 token 预算（仅代码默认值） |

### Harness 实验特性（默认全部关闭）

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `SPILL_MAX_INLINE_BYTES` | 65536 | 工具大结果内联上限，超出落盘 |
| `TOOL_PIPELINE_ENABLED` | false | 工具流水线开关 |
| `TOOL_PIPELINE_TIMEOUT_MS` | 30000 | 流水线超时 |
| `TOOL_PIPELINE_SLOW_WARN_MS` | 10000 | 慢工具告警阈值 |
| `TOOL_PIPELINE_SCOPE_MAX_CONCURRENT` | 8 | 单 scope 工具并发上限 |
| `COMPACTION_ENABLED` | false | 上下文压缩开关 |
| `COMPACTION_SUMMARIZE` | true | 压缩时是否摘要 |
| `COMPACTION_SUMMARY_SLOT` | summary | 摘要使用的 slot |
| `COMPACTION_MAX_FIELD_TOKENS` | 16000 | 单字段 token 上限 |
| `COMPACTION_SPILL_PRUNED` | true | 被裁剪内容是否落盘 |
| `EXECUTION_EVENT_LOG_ENABLED` | false | 执行事件日志开关 |
| `EXECUTION_EVENT_REPLAY_LIMIT` | 500 | 回放事件上限 |
| `LOOP_HYGIENE_ENABLED` | false | 防死循环检测开关 |
| `LOOP_HYGIENE_MAX_REPEATS` | 3 | 相同调用最大重复次数 |
| `LOOP_HYGIENE_MAX_CALLS` | 200 | 单执行最大工具调用数 |
| `LOOP_HYGIENE_MAX_TRACKED_SCOPES` | 1000 | 跟踪 scope 数上限 |
| `PLUGIN_ECHO_ENABLED` | true | Echo 示例插件开关 |

### Ingest / Writer / Edit

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `INGEST_SINGLE_PASS_MAX_CHARS` | 800000 | 单遍分析字符上限 |
| `INGEST_ANALYSIS_POOL` | 8 | 分析并行度 |
| `WRITER_POOL_CORE` | 6 | Writer 线程池核心大小 |
| `WRITER_POOL_MAX` | 16 | Writer 线程池最大大小 |
| `EDIT_CONTEXT_MAX_CHARS` | 24000 | AI 编辑上下文裁剪上限 |

### Lint 调度

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `LINT_PERSONAL_CRON` | `0 0 3 ? * MON` | 个人库 Lint 定时 |
| `LINT_TEAM_CRON` | `0 0 4 * * *` | 团队库 Lint 定时 |
| `LINT_SKIP_WINDOW_HOURS` | 1 | 跳过近期活跃 scope 的窗口（小时） |
| `LINT_SCHEDULED_CONCURRENCY` | 3 | 定时 Lint 并发 |
| `LINT_MAX_SCOPES_PER_RUN` | 50 | 单次最大 scope 数 |
| `SCHEMA_LINT_ENABLED` | true | SchemaLint 开关 |
| `SCHEMA_LINT_CRON` | `0 30 * * * ?` | SchemaLint 定时 |

### OCR / 图表生成

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `OCR_ENABLED` | true | 视觉模型 OCR 开关 |
| `OCR_MODEL` | qwen-vl-ocr | OCR 模型 |
| `OCR_SCAN_THRESHOLD` | 0.3 | 扫描件预警阈值 |
| `OCR_MAX_PAGES` | 20 | 单次 OCR 页数上限 |
| `OCR_TIMEOUT_MS` | 120000 | OCR 超时 |
| `DIAGRAM_ENABLED` | false | 图表生成开关 |
| `DIAGRAM_MODEL` | kimi-k2.6 | 图表生成模型 |
| `DIAGRAM_API_KEY` | 空 | 图表生成 API Key |
| `DIAGRAM_MAX_IMAGES` | 15 | 单文档最大处理图片数 |
| `DIAGRAM_TIMEOUT_MS` | 180000 | 单图超时 |
| `DIAGRAM_DPI` | 200 | 渲染 DPI |
| `DIAGRAM_JPEG_QUALITY` | 85 | JPEG 质量 |
| `DIAGRAM_CONCURRENCY` | 4 | 渲染并发 |
| `DIAGRAM_SCORE_THRESHOLD` | 5.0 | 评分阈值 |
| `DIAGRAM_LARGE_DRAWING_RATIO` | 0.05 | 大图判定比例 |
| `DIAGRAM_IMAGE_AREA_RATIO` | 0.05 | 显著图片面积比例 |
| `DIAGRAM_PAYLOAD_GATE_MB` | 2.0 | 请求体门限（MB） |
