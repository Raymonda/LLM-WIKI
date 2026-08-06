/**
 * 执行记录中的技术字段中文标签映射。
 *
 * 设计原则：把英文/技术语义翻译成可阅读的中文，原英文用括号在右侧标注，
 * 方便用户对照排障，又不让纯技术词汇直接糊在界面上。
 */

const STEP_LABEL_MAP: Record<string, string> = {
  // ===== Ingest 主步骤（IngestStep 枚举） =====
  UPLOAD: '上传与解析',
  ANALYZE: '内容分析',
  WRITE: '编译写入',
  COMPLETE: '收尾与索引',

  // ===== Ingest 历史步骤名（兼容旧数据） =====
  PARSE_DOCUMENT: '解析文档',
  READ_SOURCE: '读取来源',
  SPLIT_CHUNKS: '切分块',
  ANALYZE_CHUNKS: '分块分析',
  MERGE_RESULTS: '合并结果',
  EXTRACT_METADATA: '提取元数据',
  PLANNING: '写作规划',
  WRITE_SUMMARY: '写入摘要页',
  WRITE_ENTITY_PAGES: '写入实体页',
  UPDATE_RELATED: '更新关联页',
  UPDATE_LINKS: '更新链接',

  // ===== Lint 步骤 =====
  PROBE_AND_VALIDATE: '探查与验证',
  AUTO_FIX_ORPHANS: '自动修复孤立页',
  AUTO_FIX_CROSSREFS: '自动修复交叉引用',
  GENERATE_RULING_BRIEFS: '生成裁决简报',
  EXECUTE_RULINGS: '执行裁决',
  WRITE_HEALTH_STATUS: '回写健康状态',
  GENERATE_REPORT: '生成体检报告',
  SUGGEST_ACTIONS: '生成处置建议',
  PROPOSE_SCHEMA_PATCH: '提议 Schema 补丁',

  // ===== Query 保存到知识库步骤 =====
  FORMAT_ANSWER: '格式化答案',
  WRITE_SAVED_PAGE: '写入收藏页',
  SAVE_LINKS: '保存关联链接',
  DETECT_SAVE_CONFLICTS: '检测内容冲突',

  // ===== Schema / 配置变更（单步） =====
  SCHEMA_CHANGE: 'Schema 变更',
  CONFIG_CHANGE: '配置变更',

  // ===== 页面修改步骤（ModifyStep） =====
  ANALYZE_FEEDBACK: '分析反馈意见',
  WRITE_PAGES: '写入页面',
}

const OPERATION_TYPE_LABEL_MAP: Record<string, string> = {
  ingest: '资料摄入',
  lint: '知识体检',
  schema_change: 'Schema 变更',
  config_change: '系统配置变更',
  page_modify: '页面修改',
  page_merge: '知识合并',
  query_save: '保存查询结果',
}

const STATUS_LABEL_MAP: Record<string, string> = {
  pending: '等待中',
  running: '运行中',
  completed: '已完成',
  failed: '失败',
  cancelled: '已取消',
  paused: '已暂停',
  skipped: '已跳过',
}

const APPROVAL_LEVEL_LABEL_MAP: Record<string, string> = {
  AUTO: '自动',
  auto: '自动',
  REVIEW: '需审核',
  review: '需审核',
  CONFIRM: '需确认',
  confirm: '需确认',
  skipped: '已跳过',
}

/**
 * 步骤名翻译。默认追加英文括号便于排障；
 * 若不需要括号（例如已经在 tooltip 显示原值），传 withTechnical=false。
 */
export function stepLabel(name: string | null | undefined, withTechnical = true): string {
  if (!name) return ''
  const zh = STEP_LABEL_MAP[name]
  if (!zh) return name
  return withTechnical ? `${zh}（${name}）` : zh
}

/** 仅返回中文标签，没匹配时返回原值。 */
export function stepLabelPlain(name: string | null | undefined): string {
  if (!name) return ''
  return STEP_LABEL_MAP[name] || name
}

/** 操作类型标签，例如 ingest -> 资料摄入。 */
export function operationTypeLabel(type: string | null | undefined): string {
  if (!type) return ''
  return OPERATION_TYPE_LABEL_MAP[type] || type
}

/** 状态标签，例如 running -> 运行中。 */
export function statusLabel(status: string | null | undefined): string {
  if (!status) return ''
  return STATUS_LABEL_MAP[status] || status
}

/** 审批级别标签，例如 AUTO -> 自动。 */
export function approvalLevelLabel(level: string | null | undefined): string {
  if (!level) return ''
  return APPROVAL_LEVEL_LABEL_MAP[level] || level
}
