import api from './index'
import { useAuthStore } from '@/stores/auth'

export interface ExecutionInfo {
  executionId: number
  operationType: string
  status: string
  scopeId: number
  sourceId: number | null
  startTime: string | null
  endTime: string | null
  totalTokens: number | null
  errorMessage: string | null
  sourceName: string | null
  batchId?: number | null
  baselineProfile?: Record<string, number> | null
}

export interface StepEvent {
  stepId: number
  stepName: string
  status: string
  outputData: string
}

export interface StepProgressEvent {
  stepId: number
  stepName: string
  current: number
  total: number
  avgMsPerUnit: number
  chunkIndex?: number
  chunkPreview?: string
}

export function startIngest(scopeId: number, sourceId: number, guidance?: string): Promise<ExecutionInfo> {
  return api.post('/ingest/start', { scopeId, sourceId, guidance })
}

export function analyzeIngest(scopeId: number, sourceId: number, guidance?: string): Promise<ExecutionInfo> {
  return api.post('/ingest/analyze', { scopeId, sourceId, guidance })
}

export function getIngestProgress(executionId: number): Promise<ExecutionInfo> {
  return api.get(`/ingest/${executionId}/progress`)
}

export function getIngestResult(executionId: number): Promise<ExecutionInfo> {
  return api.get(`/ingest/${executionId}/result`)
}

export function createIngestSSE(executionId: number): EventSource {
  const authStore = useAuthStore()
  const token = authStore.token || ''
  const scopeQs = authStore.scopeId && authStore.scopeId > 0 ? `&scopeId=${authStore.scopeId}` : ''
  const url = `/api/ingest/${executionId}/stream?token=${encodeURIComponent(token)}${scopeQs}`
  return new EventSource(url)
}

export function cancelIngest(executionId: number): Promise<void> {
  return api.post(`/ingest/${executionId}/cancel`)
}

export function deleteIngest(executionId: number): Promise<void> {
  return api.delete(`/ingest/${executionId}`)
}

export function pauseIngest(executionId: number): Promise<void> {
  return api.post(`/ingest/${executionId}/pause`)
}

export function resumeIngest(executionId: number, guidance?: string): Promise<ExecutionInfo> {
  return api.post(`/ingest/${executionId}/resume`, { guidance })
}

export function executeIngest(executionId: number, guidance?: string): Promise<void> {
  return api.post(`/ingest/${executionId}/execute`, { guidance })
}

export function reanalyzeIngest(executionId: number, guidance?: string): Promise<void> {
  return api.post(`/ingest/${executionId}/reanalyze`, { guidance })
}

export function listActiveIngest(scopeId: number): Promise<ExecutionInfo[]> {
  return api.get('/ingest/active', { params: { scopeId } })
}

export interface IngestBatchItemInfo {
  executionId: number
  sourceId: number | null
  sourceName: string | null
  sourceFormat: string | null
  status: string
  totalTokens: number | null
  errorMessage: string | null
  analyzeOutput: string | null
  guidance: string | null
  startedAt: string | null
  completedAt: string | null
  phase1Completed: boolean | null
}

export interface IngestBatchInfo {
  batchId: number
  status: string
  totalCount: number
  guidance: string | null
  createdAt: string | null
  completedAt: string | null
  awaitingCount: number
  pendingCount: number
  runningCount: number
  confirmedCount: number
  completedCount: number
  failedCount: number
  cancelledCount: number
}

export interface IngestBatchDetailInfo {
  batchId: number
  status: string
  totalCount: number
  guidance: string | null
  createdAt: string | null
  completedAt: string | null
  items: IngestBatchItemInfo[]
  total: number
  page: number
  size: number
}

export interface IngestBatchCreateResponse {
  batchId: number
  executionIds: number[]
  warnings: string[]
}

export function createIngestBatch(scopeId: number, sourceIds: number[], guidance?: string): Promise<IngestBatchCreateResponse> {
  return api.post('/ingest/batch', { scopeId, sourceIds, guidance })
}

export function fetchBatchInbox(scopeId: number): Promise<IngestBatchInfo[]> {
  return api.get('/ingest/batch/inbox', { params: { scopeId } })
}

export function getBatchDetail(batchId: number, page = 1, size = 20): Promise<IngestBatchDetailInfo> {
  return api.get(`/ingest/batch/${batchId}`, { params: { page, size } })
}

export function confirmBatchItems(batchId: number, executionIds?: number[]): Promise<number> {
  return api.post(`/ingest/batch/${batchId}/confirm`, { executionIds: executionIds ?? null })
}

export function pauseBatch(batchId: number): Promise<void> {
  return api.post(`/ingest/batch/${batchId}/pause`)
}

export function resumeBatch(batchId: number): Promise<void> {
  return api.post(`/ingest/batch/${batchId}/resume`)
}

export function cancelBatch(batchId: number): Promise<void> {
  return api.post(`/ingest/batch/${batchId}/cancel`)
}