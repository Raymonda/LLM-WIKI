import api from './index'
import { useAuthStore } from '@/stores/auth'

export interface LintExecutionInfo {
  executionId: number
  operationType: string
  status: string
  scopeId: number
  startTime: string | null
  endTime: string | null
  totalTokens: number
  totalSteps: number
  completedSteps: number
  currentStepName: string | null
}

export interface LintFindingInfo {
  id: number
  scopeId: number
  pagePath: string | null
  assetId: number | null
  findingType: string
  priority: string
  title: string
  detail: string | null
  extra: string | null
  rulingBriefJson: string | null
  handlingMethod: string | null
  riskScore: number | null
  autoResolvedAt: string | null
  repairExecutionId: number | null
  status: string
  executionId: number
  createdAt: string | null
  updatedAt: string | null
  userFeedback: string | null
  feedbackCount: number | null
  archivedAt: string | null
  orphanDiagnosis: string | null
  
  // Phase 2 新增：交叉引用建议详情（后端返回 JSON 字符串，前端解析为数组）
  crossrefSuggestions?: Array<{
    sourceTitle: string
    targetTitle: string
    sourcePath: string
    targetPath: string
    sourceAssetId: number
    targetAssetId: number
    linkContext: string
    confidence: number
    reason: string
    linkType: string
  }>
}

export interface HealthSummary {
  total: number
  high: number
  medium: number
  low: number
}

export interface HealthOverview {
  healthDistribution: Record<string, number>
  findingCountsByType: Record<string, number>
  findingCountsByPriority: Record<string, number>
  totalActiveFindings: number
  totalPages: number
  lastLintTime: string | null
  topFindings: LintFindingInfo[]
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

export function startLint(scopeId: number, fullScan?: boolean): Promise<LintExecutionInfo> {
  const params = new URLSearchParams({ scopeId: scopeId.toString() })
  if (fullScan !== undefined) params.set('fullScan', fullScan.toString())
  return api.post(`/lint/start?${params.toString()}`)
}

export function getActiveLintExecution(scopeId: number): Promise<LintExecutionInfo | null> {
  return api.get('/lint/active-execution', { params: { scopeId } })
}

export function getLintProgress(executionId: number): Promise<LintExecutionInfo> {
  return api.get(`/lint/${executionId}/progress`)
}

export function createLintSSE(executionId: number): EventSource {
  const authStore = useAuthStore()
  const token = authStore.token || ''
  const scopeQs = authStore.scopeId && authStore.scopeId > 0 ? `&scopeId=${authStore.scopeId}` : ''
  const url = `/api/lint/${executionId}/stream?token=${encodeURIComponent(token)}${scopeQs}`
  return new EventSource(url)
}

export function getLintReport(executionId: number): Promise<LintExecutionInfo> {
  return api.get(`/lint/${executionId}/report`)
}

export function getFindings(executionId: number): Promise<LintFindingInfo[]> {
  return api.get(`/lint/${executionId}/findings`)
}

export function listScopeFindings(scopeId: number, type?: string, status?: string, priority?: string): Promise<LintFindingInfo[]> {
  return api.get('/lint/scope/findings', { params: { scopeId, type, status, priority } })
}

export function listScopeFindingsPaged(scopeId: number, page: number, size: number, type?: string, status?: string, priority?: string, archived?: boolean): Promise<PageResult<LintFindingInfo>> {
  return api.get('/lint/scope/findings', { params: { scopeId, type, status, priority, archived, page, size } })
}

export function getHealthOverview(scopeId: number): Promise<HealthOverview> {
  return api.get('/lint/scope/health-overview', { params: { scopeId } })
}

export function updateFindingStatus(id: number, status: string): Promise<void> {
  return api.patch(`/lint/findings/${id}`, { status })
}

export function getLatestReport(scopeId: number): Promise<HealthSummary> {
  return api.get('/lint/latest-report', { params: { scopeId } })
}

export function autoResolveFinding(id: number, handlingMethod?: string): Promise<void> {
  const body = handlingMethod ? { handlingMethod } : {}
  return api.post(`/lint/findings/${id}/auto-resolve`, body)
}

export function resolveSchemaCompliance(id: number): Promise<void> {
  return api.post(`/lint/findings/${id}/resolve-compliance`)
}

export function batchAutoResolveStale(scopeId: number): Promise<Record<string, unknown>> {
  return api.post(`/lint/scope/${scopeId}/batch-auto-resolve-stale`)
}

export function triggerRepair(id: number, scopeId: number): Promise<Record<string, unknown>> {
  const params = new URLSearchParams({ scopeId: scopeId.toString() })
  return api.post(`/lint/findings/${id}/trigger-repair?${params.toString()}`)
}

export function approveFinding(id: number, scopeId: number): Promise<Record<string, unknown>> {
  const params = new URLSearchParams({ scopeId: scopeId.toString() })
  return api.post(`/lint/findings/${id}/approve?${params.toString()}`)
}

export function executeConflictRuling(id: number, scopeId: number, action: string): Promise<Record<string, unknown>> {
  const params = new URLSearchParams({ scopeId: scopeId.toString() })
  return api.post(`/lint/findings/${id}/execute-ruling?${params.toString()}`, { action })
}

export function rejectFinding(id: number): Promise<void> {
  return api.post(`/lint/findings/${id}/reject`)
}

export function rollbackFinding(id: number): Promise<void> {
  return api.post(`/lint/findings/${id}/rollback`)
}

export function retryFailedFinding(id: number): Promise<void> {
  return api.post(`/lint/findings/${id}/retry`)
}

export function batchApproveFindings(ids: number[], scopeId: number): Promise<Record<string, unknown>> {
  return api.post(`/lint/findings/batch/approve`, { ids, scopeId })
}

export function batchRejectFindings(ids: number[]): Promise<void> {
  return api.post(`/lint/findings/batch/reject`, { ids })
}

export function batchRollbackFindings(ids: number[]): Promise<void> {
  return api.post(`/lint/findings/batch/rollback`, { ids })
}

export function recordFindingFeedback(id: number, feedback: string): Promise<void> {
  return api.post(`/lint/findings/${id}/feedback`, { feedback })
}

export function retryOrphanFix(id: number, scopeId: number): Promise<Record<string, unknown>> {
  const params = new URLSearchParams({ scopeId: scopeId.toString() })
  return api.post(`/lint/findings/${id}/retry-orphan?${params.toString()}`)
}

export function enrichPage(id: number, scopeId: number, userSupplement: string): Promise<Record<string, unknown>> {
  const params = new URLSearchParams({ scopeId: scopeId.toString() })
  return api.post(`/lint/findings/${id}/enrich-page?${params.toString()}`, { userSupplement })
}

// Phase 2 新增：交叉引用审批 API
export function approveLink(id: number, _sourceTitle?: string, _targetTitle?: string): Promise<void> {
  return api.post(`/lint/findings/${id}/approve-link`, {})
}

export function rejectLink(id: number, sourceTitle: string, targetTitle: string, reason?: string): Promise<void> {
  return api.post(`/lint/findings/${id}/reject-link`, { sourceTitle, targetTitle, reason })
}

export function batchApproveLinks(ids: number[]): Promise<Record<string, unknown>> {
  return api.post(`/lint/findings/batch/approve-links`, { ids })
}

export function batchRejectLinks(ids: number[]): Promise<Record<string, unknown>> {
  return api.post(`/lint/findings/batch/reject-links`, { ids })
}