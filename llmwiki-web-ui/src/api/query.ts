import api from './index'
import { useAuthStore } from '@/stores/auth'
import type { WikiPageInfo } from './wiki'

export type QueryAnalysisMode = 'quick' | 'deep'

export function createQuerySSE(
  question: string,
  sessionId?: string,
  mode: QueryAnalysisMode = 'quick',
  assumedIntent?: string
): EventSource {
  const authStore = useAuthStore()
  const token = authStore.token || ''
  const params = new URLSearchParams({ question, token, mode })
  if (sessionId) params.set('sessionId', sessionId)
  if (assumedIntent) params.set('assumedIntent', assumedIntent)
  const scopes = authStore.effectiveQueryScopes()
  if (scopes.length > 0) params.set('scopeIds', scopes.join(','))
  else if (authStore.scopeId && authStore.scopeId > 0) params.set('scopeIds', String(authStore.scopeId))
  const url = `/api/query/stream?${params.toString()}`
  return new EventSource(url)
}

export function saveAnswer(question: string, answer: string, sessionId?: string): Promise<WikiPageInfo> {
  return api.post('/query/save', { question, answer, sessionId }, { timeout: 120000 })
}

export function resolveLinks(content: string): Promise<Record<string, number>> {
  return api.post('/query/resolve-links', { content })
}
