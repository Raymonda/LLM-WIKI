import api from './index'
import { useAuthStore } from '@/stores/auth'

export interface SchemaInfo {
  id: number
  configKey: string
  configValue: string
  configGroup: string
  scopeId: number
  description: string
  updatedAt: string
}

export interface TokenUsageInfo {
  totalTokens: number
  ingestTokens: number
  queryTokens: number
  lintTokens: number
  budget: number
  remaining: number
  usagePercent: number
  alertLevel: 'normal' | 'warning' | 'exceeded'
}

export interface ExecutionStepInfo {
  stepId: number
  stepName: string
  stepOrder: number
  status: string
  inputData: string | null
  outputData: string | null
  tokensUsed: number
  durationMs: number
  approvalLevel: string
  startedAt: string | null
  completedAt: string | null
}

export interface ExecutionRecord {
  executionId: number
  operationType: string
  status: string
  scopeId: number
  sourceId: number | null
  schemaConfigId: number | null
  startTime: string | null
  endTime: string | null
  createdAt: string | null
  totalTokens: number | null
  errorMessage: string | null
  sourceName: string | null
  totalSteps: number | null
  completedSteps: number | null
  currentStepName: string | null
  steps: ExecutionStepInfo[] | null
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

export function listExecutionsPaged(params: {
  scopeId: number
  type?: string
  page?: number
  size?: number
}): Promise<PageResult<ExecutionRecord>> {
  return api.get('/harness/executions/paged', { params })
}

export function getExecution(id: number): Promise<ExecutionRecord> {
  return api.get(`/harness/executions/${id}`)
}

export function cancelExecution(id: number): Promise<void> {
  return api.post(`/harness/executions/${id}/cancel`)
}

export function createExecutionSSE(id: number): EventSource {
  const authStore = useAuthStore()
  const token = authStore.token || ''
  const scopeQs = authStore.scopeId && authStore.scopeId > 0 ? `&scopeId=${authStore.scopeId}` : ''
  const url = `/api/harness/executions/${id}/stream?token=${encodeURIComponent(token)}${scopeQs}`
  return new EventSource(url)
}

export interface ActiveTaskInfo {
  executionId: number
  operationType: string
  status: string
  sourceName: string | null
  currentStepName: string | null
  totalSteps: number | null
  completedSteps: number | null
}

export function listActiveTasks(scopeId: number): Promise<ExecutionRecord[]> {
  return api.get('/harness/active-tasks', { params: { scopeId } })
}

export function listSchemas(): Promise<SchemaInfo[]> {
  return api.get('/harness/schema')
}

export function getSchema(key: string): Promise<SchemaInfo> {
  return api.get(`/harness/schema/${key}`)
}

export function listParadigms(): Promise<{ id: string; label: string; description: string; icon: string }[]> {
  return api.get('/harness/schema/paradigms')
}

export function getTaxonomy(): Promise<{
  narrative: string
  roots: Array<{ id: string; label: string; description: string; children?: any[] }>
}> {
  return api.get('/harness/schema/taxonomy')
}

export function getTokenUsage(): Promise<TokenUsageInfo> {
  return api.get('/harness/token-usage')
}

export function getTokenUsageByType(): Promise<TokenUsageInfo> {
  return api.get('/harness/token-usage/by-type')
}

export interface TokenUsageTrendItem {
  date: string
  ingestTokens: number
  queryTokens: number
  lintTokens: number
  schemaTokens: number
  modifyTokens: number
}

export function getTokenUsageTrend(days?: number): Promise<TokenUsageTrendItem[]> {
  return api.get('/harness/token-usage/trend', { params: { days: days || 30 } })
}

export interface BootstrapStatus {
  required: boolean
  scopeId: number
  aiConfigured: boolean
}

export function getBootstrapStatus(): Promise<BootstrapStatus> {
  return api.get('/harness/schema/bootstrap/status')
}

// ===== V2 Bootstrap API =====

export interface CapabilityInfo {
  id: string
  label: string
  description: string
  icon: string
  tags: string[]
  sampleDocTypes: string[]
  affectedSections: number[]
}

export interface CategoryTreeNode {
  id: string
  label: string
  description: string
  children?: CategoryTreeNode[]
}

export interface PageBlueprintBlock {
  id: string
  label: string
  enabled: boolean
  order: number
  description: string
}

export interface AutonomyRule {
  id: string
  description: string
  enabled: boolean
  icon: string
}

export interface AdvisorSuggestion {
  type: 'info' | 'warning' | 'recommendation'
  message: string
  action?: { type: string; payload: Record<string, any> }
}

export interface V2StartResult {
  sessionId: string
  capabilities: CapabilityInfo[]
}

export interface V2CapabilitiesResult {
  selectedCapabilities: string[]
  domainNarrative: string
  categoryCount: number
  templateCount: number
  draftJson: string
}

export interface V2CategoryTreeResult {
  tree: { narrative: string; roots: CategoryTreeNode[] }
  nodeCount: number
}

export interface V2AutonomyResult {
  level: string
  workflow: { narrative: string; defaultApproval: string; confirmTriggers: string[] }
  rules: AutonomyRule[]
}

export interface AdvisorChatResult {
  reply: string
  actions: Array<{ type: string; payload: Record<string, any> }>
}

export function startBootstrapV2(): Promise<V2StartResult> {
  return api.post('/harness/schema/bootstrap/v2/start', {}, { timeout: 30000 })
}

export function selectCapabilitiesV2(sessionId: string, capabilityIds: string[]): Promise<V2CapabilitiesResult> {
  return api.post(`/harness/schema/bootstrap/v2/${sessionId}/capabilities`, { capabilityIds }, { timeout: 30000 })
}

export function submitCategoryTreeV2(sessionId: string, tree: any): Promise<V2CategoryTreeResult> {
  return api.post(`/harness/schema/bootstrap/v2/${sessionId}/category-tree`, { tree }, { timeout: 30000 })
}

export function submitPageBlueprintV2(sessionId: string, blueprint: any): Promise<any> {
  return api.post(`/harness/schema/bootstrap/v2/${sessionId}/page-blueprint`, { blueprint }, { timeout: 30000 })
}

export function submitAutonomyV2(sessionId: string, level: string, overrides?: Record<string, any>): Promise<V2AutonomyResult> {
  return api.post(`/harness/schema/bootstrap/v2/${sessionId}/autonomy`, { level, overrides }, { timeout: 30000 })
}

export function finalizeBootstrapV2(sessionId: string): Promise<SchemaInfo> {
  return api.post(`/harness/schema/bootstrap/v2/${sessionId}/finalize`, {}, { timeout: 120000 })
}

export function advisorCheckV2(sessionId: string, step: string, data: any, capabilityIds: string[], extra?: Record<string, any>): Promise<AdvisorSuggestion[]> {
  return api.post(`/harness/schema/bootstrap/v2/${sessionId}/advisor/check`, { step, data, capabilityIds, ...extra }, { timeout: 30000 })
}

export function advisorChatV2(sessionId: string, step: string, message: string, stepData: any, capabilityIds: string[]): Promise<AdvisorChatResult> {
  return api.post(`/harness/schema/bootstrap/v2/${sessionId}/advisor/chat`, { step, message, stepData, capabilityIds }, { timeout: 60000 })
}

export function advisorQualityCheckV2(sessionId: string, step: string, stepData: any, capabilityIds: string[]): Promise<AdvisorSuggestion[]> {
  return api.post(`/harness/schema/bootstrap/v2/${sessionId}/advisor/quality-check`, { step, stepData, capabilityIds }, { timeout: 60000 })
}

export interface SchemaPatchInfo {
  id: number
  scopeId: number
  sourceExecutionId: number | null
  sourceType: 'INGEST' | 'QUERY' | 'LINT' | 'MANUAL'
  sectionTitle: string
  operation: 'ADD' | 'MODIFY' | 'DELETE'
  diffBefore: string | null
  diffAfter: string | null
  rationale: string | null
  evidenceJson: string | null
  confidence: number | null
  status: 'PENDING' | 'OBSERVING' | 'ACCEPTED' | 'REJECTED' | 'IGNORED' | 'SUPERSEDED'
  decidedBy: number | null
  decidedAt: string | null
  appliedSchemaId: number | null
  gatekeeperDecision: 'APPROVE' | 'OBSERVE' | 'REJECT' | null
  gatekeeperReason: string | null
  createdAt: string
}

export function listPendingPatches(): Promise<SchemaPatchInfo[]> {
  return api.get('/harness/schema/patches')
}

export function listObservingPatches(): Promise<SchemaPatchInfo[]> {
  return api.get('/harness/schema/patches/observing')
}

export function countPendingPatches(): Promise<{ pending: number; observing: number; conflictRulings: number; currentVersion: number }> {
  return api.get('/harness/schema/patches/count')
}

export function loadPatchDiff(id: number): Promise<SchemaPatchInfo> {
  return api.get(`/harness/schema/patches/${id}/diff`)
}

export function acceptPatch(id: number): Promise<SchemaPatchInfo> {
  return api.post(`/harness/schema/patches/${id}/accept`)
}

export function rejectPatch(id: number): Promise<SchemaPatchInfo> {
  return api.post(`/harness/schema/patches/${id}/reject`)
}

export function ignorePatch(id: number): Promise<SchemaPatchInfo> {
  return api.post(`/harness/schema/patches/${id}/ignore`)
}

export function promotePatch(id: number): Promise<SchemaPatchInfo> {
  return api.post(`/harness/schema/patches/${id}/promote`)
}

export function batchAcceptPatches(ids: number[]): Promise<{ processed: number; failed: number }> {
  return api.post('/harness/schema/patches/batch/accept', { ids })
}

export function batchRejectPatches(ids: number[]): Promise<{ processed: number; failed: number }> {
  return api.post('/harness/schema/patches/batch/reject', { ids })
}

export function batchIgnorePatches(ids: number[]): Promise<{ processed: number; failed: number }> {
  return api.post('/harness/schema/patches/batch/ignore', { ids })
}

export interface SchemaVersionInfo {
  id: number
  scopeId: number
  configKey: string
  configValue: string
  parentVersionId: number | null
  versionNumber: number
  sourceType: 'BOOTSTRAP' | 'PATCH' | 'MANUAL' | 'ROLLBACK'
  sourceOpId: number | null
  createdBy: number | null
  createdAt: string
}

export function listSchemaVersions(key: string): Promise<SchemaVersionInfo[]> {
  return api.get(`/harness/schema/${key}/versions`)
}

export function getSchemaVersion(versionId: number): Promise<SchemaVersionInfo> {
  return api.get(`/harness/schema/versions/${versionId}`)
}

export function rollbackSchemaVersion(versionId: number): Promise<SchemaVersionInfo> {
  return api.post(`/harness/schema/versions/${versionId}/rollback`)
}

export interface SchemaMigrationLegacyPage {
  pageId: number
  title: string
  category: string | null
  schemaVersion: number | null
  versionNumber: number | null
  updatedAt: string | null
}

export interface SchemaMigrationReport {
  currentVersionId: number | null
  currentVersionNumber: number | null
  untaggedCount: number
  outdatedCount: number
  legacyPages: SchemaMigrationLegacyPage[]
}

export function getSchemaMigrationReport(key = 'wiki_schema'): Promise<SchemaMigrationReport> {
  return api.get('/harness/schema/migration-report', { params: { key } })
}

export interface SchemaGovernanceHealth {
  pendingCount: number
  observingCount: number
  acceptedCount: number
  rejectedCount: number
  ignoredCount: number
  supersededCount: number
  gatekeeperEvaluated: number
  gatekeeperMatched: number
  gatekeeperMatchRate: number | null
  currentVersionNumber: number | null
  migrationUntaggedCount: number
  migrationOutdatedCount: number
}

export function getSchemaGovernanceHealth(): Promise<SchemaGovernanceHealth> {
  return api.get('/harness/schema/governance-health')
}

export interface ConflictReviewInfo {
  id: number
  scopeId: number
  sourceType: string
  sourceExecutionId: number | null
  fromPageId: number
  toPageId: number
  fromPageTitle: string
  toPageTitle: string
  conflictType: string | null
  strategyKey: string
  strategyLabel: string
  routeReason: string
  rulingAction: string | null
  rulingDetail: string | null
  status: string
  createdAt: string | null
  decidedAt: string | null
  executedAt: string | null
  executionError: string | null
}

export function listConflictRulings(): Promise<ConflictReviewInfo[]> {
  return api.get('/harness/conflict-rulings')
}

export function countConflictRulings(): Promise<{ pending: number }> {
  return api.get('/harness/conflict-rulings/count')
}

export function executeRuling(id: number, action: string, detail?: string): Promise<ConflictReviewInfo> {
  return api.post(`/harness/conflict-rulings/${id}/execute`, { action, detail: detail ?? null })
}

export function cancelRuling(id: number): Promise<void> {
  return api.post(`/harness/conflict-rulings/${id}/cancel`)
}