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

export function listActiveIngest(scopeId: number): Promise<ExecutionInfo[]> {
  return api.get('/ingest/active', { params: { scopeId } })
}