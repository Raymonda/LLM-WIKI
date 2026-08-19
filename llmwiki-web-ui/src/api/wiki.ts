import api from './index'
import { useAuthStore } from '@/stores/auth'

export interface WikiPageInfo {
  id: number
  title: string
  path: string
  content: string
  category: string
  summary: string
  scopeId: number
  sourceCount: number
  healthStatus: string
  pageType: string | null
  visibility: string
  promotedFromScopeId: number | null
  promotedFromPageId: number | null
  promotedFromUsername: string | null
  lastCheckedAt: string | null
  contentUpdatedAt: string | null
  lastModified: string | null
  lifecycleStatus: string | null
  deprecatedAt: string | null
  deprecatedReason: string | null
  deletedAt: string | null
  mergedIntoPageId: number | null
  inboundLinkCount: number
  outboundLinkCount: number
  postSaveStatus: string | null
  postSaveError: string | null
  sourceScopeName: string | null
  tags: string[]
  keywords: string[]
  sources: SourceInfo[]
  relatedPages: WikiPageInfo[]
  linkResolution: Record<string, number>
}

export interface SourceInfo {
  id: number
  name: string
  filePath: string
  format: string
  size: number
  status: string
  createdAt: string
}

export interface RelatedPageInfo {
  id: number
  title: string
  path: string
  summary: string
  linkType: string
  direction: string
}

export interface GraphData {
  nodes: GraphNode[]
  edges: GraphEdge[]
  stats: GraphStats
  requiresOverview?: boolean
}

export interface GraphNode {
  id: number
  title: string
  path: string
  category: string
  healthStatus: string
  pageType: string | null
  lifecycleStatus: string | null
  sourceCount: number
  inDegree: number
  outDegree: number
}

export interface GraphEdge {
  fromId: number
  toId: number
  linkType: string
  linkContext: string | null
}

export interface GraphStats {
  totalNodes: number
  totalEdges: number
  orphanCount: number
  hubCount: number
  conflictCount: number
  needsUpdateCount: number
  hasProblemsCount: number
  deprecatedCount: number
  mergedCount: number
  linkDensity: number
  categoryCounts: CategoryCount[]
}

export interface CategoryCount {
  category: string
  count: number
}

export interface HealthInfo {
  status: string
  lastCheckedAt: string | null
  issues: string[]
  suggestions: string[]
}

export type SearchResultType = 'WIKI_PAGE'

export interface SearchResultInfo {
  id: number | null
  title: string
  path: string | null
  category: string | null
  summary: string | null
  healthStatus: string | null
  score: number
  highlightedTitle: string[] | null
  highlightedSummary: string[] | null
  highlightedContent: string[] | null
  sourceScopeId: number | null
  sourceScopeName: string | null
  resultType: SearchResultType
}

export function listPages(page = 1, size = 50, category?: string): Promise<WikiPageInfo[]> {
  const params: Record<string, string | number> = { page, size }
  if (category) params.category = category
  return api.get('/wiki/pages', { params })
}

export interface PageStats {
  entityCount: number
  healthyCount: number
  needsUpdateCount: number
  hasProblemsCount: number
  unknownCount: number
}

export function getPageStats(): Promise<PageStats> {
  return api.get('/wiki/stats')
}

export function getPage(id: number): Promise<WikiPageInfo> {
  return api.get(`/wiki/page/${id}`)
}

export function getPageByPath(filePath: string): Promise<WikiPageInfo> {
  return api.get('/wiki/page-by-path', { params: { filePath } })
}

export function getRelatedPages(id: number): Promise<RelatedPageInfo[]> {
  return api.get('/wiki/related', { params: { id } })
}

export function searchPages(query: string, category?: string, includeSubscribed?: boolean, scopeIds?: string): Promise<SearchResultInfo[]> {
  const params: Record<string, any> = { query, category, includeSubscribed: includeSubscribed || false }
  if (scopeIds) params.scopeIds = scopeIds
  return api.get('/wiki/search', { params })
}

export function searchSuggest(prefix: string): Promise<WikiPageInfo[]> {
  return api.get('/wiki/search/suggest', { params: { prefix } })
}

export function rebuildSearchIndex(): Promise<string> {
  return api.post('/wiki/search/rebuild-index')
}

export function listCategories(): Promise<string[]> {
  return api.get('/wiki/categories')
}

export function recentPages(): Promise<WikiPageInfo[]> {
  return api.get('/wiki/recent')
}

export function getIndex(): Promise<string> {
  return api.get('/wiki/index')
}

export function getLog(): Promise<string> {
  return api.get('/wiki/log')
}

export function getGraph(): Promise<GraphData> {
  return api.get('/wiki/graph')
}

export function getLocalGraph(pageId: number): Promise<GraphData> {
  return api.get('/wiki/graph/local', { params: { pageId } })
}

export interface GraphOverviewData {
  categories: CategoryNode[]
  edges: CategoryEdge[]
  stats: GraphStats
}

export interface CategoryNode {
  category: string
  pageCount: number
  orphanCount: number
  conflictCount: number
  needsUpdateCount: number
  hasProblemsCount: number
  hubCount: number
  avgDegree: number
}

export interface CategoryEdge {
  fromCategory: string
  toCategory: string
  linkCount: number
}

export function getGraphOverview(): Promise<GraphOverviewData> {
  return api.get('/wiki/graph/overview')
}

export function getGraphByCategory(category: string): Promise<GraphData> {
  return api.get('/wiki/graph/category', { params: { category } })
}

export function searchGraphNodes(keyword: string, limit?: number): Promise<GraphData> {
  return api.get('/wiki/graph/search', { params: { keyword, limit: limit || 50 } })
}

export function getRecommended(): Promise<WikiPageInfo[]> {
  return api.get('/wiki/recommended')
}

export function getHealth(id: number): Promise<HealthInfo> {
  return api.get(`/wiki/health/${id}`)
}

export interface PromotionStats {
  adoptedTeamCount: number
  promotedPageCount: number
  recentPromotedPages: PromotedPageInfo[]
}

export interface PromotedPageInfo {
  pageId: number
  title: string
  path: string
  targetScopeId: number
  targetScopeName: string
  promotedAt: string
}

export function updateVisibility(id: number, visibility: string): Promise<void> {
  return api.put(`/wiki/visibility?id=${id}`, { visibility })
}

export function getPromotionStats(): Promise<PromotionStats> {
  return api.get('/wiki/promotion-stats')
}

export interface ContributorInfo {
  userId: number | null
  userName: string
  promotedPageCount: number
}

export function getContributors(scopeId: number): Promise<ContributorInfo[]> {
  return api.get(`/scope/${scopeId}/contributors`)
}

export function recallPromotedPages(scopeId: number, pagePath: string): Promise<void> {
  return api.post(`/scope/${scopeId}/recall`, { pagePath })
}

// ===== 页面操作 API (标记过期 / 删除 / 合并) =====

export interface DeleteImpactInfo {
  inboundLinks: number
  outboundLinks: number
  sourceCount: number
}

export interface MergeResult {
  executionId: number
  sourceId: number
  status: string
  mergedPageIds: number[]
}

export function deprecatePage(pageId: number, reason: string): Promise<void> {
  return api.put('/wiki/pages/deprecated', { pageId, reason })
}

export function undeprecatePage(pageId: number): Promise<void> {
  return api.delete('/wiki/pages/deprecated', { params: { pageId } })
}

export function softDeletePage(id: number): Promise<void> {
  return api.delete(`/wiki/pages/${id}`)
}

export function restorePage(id: number): Promise<void> {
  return api.post(`/wiki/pages/${id}/restore`)
}

export function permanentDeletePage(id: number): Promise<void> {
  return api.delete(`/wiki/pages/${id}/permanent`)
}

export function listTrashPages(): Promise<WikiPageInfo[]> {
  return api.get('/wiki/trash')
}

export function getDeleteImpact(id: number): Promise<DeleteImpactInfo> {
  return api.get(`/wiki/pages/${id}/delete-impact`)
}

export function mergePages(pageIds: number[], targetTitle?: string, instruction?: string): Promise<MergeResult> {
  return api.post('/wiki/merge', { pageIds, targetTitle, instruction })
}

// ===== 编辑器 API (草稿 / 编辑会话 / AI 编辑 / 保存) =====

export interface WikiPageDraftInfo {
  id: number
  scopeId: number
  pageId: number | null
  baseContentHash: string | null
  title: string
  content: string
  category: string | null
  tags: string[] | null
  pageType: string
  status: string
  createdAt: string
  updatedAt: string
}

export interface EditSessionInfo {
  id: number
  scopeId: number
  pageId: number | null
  draftId: number | null
  currentContent: string | null
  contentHash: string
  outline: string | null
  stepCount: number
  status: string
  steps?: EditStepInfo[]
  createdAt: string
  updatedAt: string
}

export interface EditStepInfo {
  id: number
  sessionId: number
  stepNumber: number
  selectedLines: string | null
  instruction: string
  diffRemoved: string | null
  diffAdded: string | null
  createdAt: string
}

export interface FailedBlockInfo {
  searchPreview: string
  reason: string
  matchCount?: number
  matchLines?: number[]
}

export interface AiEditEvent {
  type: 'token' | 'edit-token' | 'patch' | 'diff' | 'done' | 'error' | 'knowledge_results' | 'anchor' | 'retry' | 'progress'
  content: string
  diffRemoved?: string
  diffAdded?: string
  stepNumber?: number
  knowledgeResults?: SearchResultInfo[]
  mode?: 'edit' | 'create'
  explanation?: string
  code?: string
  anchorLines?: string
  anchorStatus?: 'exact' | 'fuzzy' | 'fallback-line' | 'fallback-full'
  retryRound?: number
  failedBlockCount?: number
  failedBlocks?: FailedBlockInfo[]
  phase?: string
}

export interface CommitStepResult {
  session: EditSessionInfo
  versionBehind: boolean
}

export interface SavePageRequest {
  draftId?: number
  pageId?: number
  sessionId?: number
  title: string
  content: string
  category?: string
  tags?: string[]
  saveMode: 'draft' | 'save'
}

export interface PreValidateResult {
  valid: boolean
  requiresReview: boolean
  violations: Array<{ type: string; description: string; severity: string }>
  warning?: string
}

// --- 草稿 CRUD ---

export function createDraft(data: {
  pageId?: number
  title: string
  content: string
  category?: string
  tags?: string[]
  pageType?: string
}): Promise<WikiPageDraftInfo> {
  return api.post('/wiki/drafts', data)
}

export function listDrafts(): Promise<WikiPageDraftInfo[]> {
  return api.get('/wiki/drafts')
}

export function getDraft(id: number): Promise<WikiPageDraftInfo> {
  return api.get(`/wiki/drafts/${id}`)
}

export function updateDraft(id: number, data: {
  title?: string
  content?: string
  category?: string
  tags?: string[]
}): Promise<WikiPageDraftInfo> {
  return api.put(`/wiki/drafts/${id}`, data)
}

export function deleteDraft(id: number): Promise<void> {
  return api.delete(`/wiki/drafts/${id}`)
}

// --- 编辑会话 ---

export function createEditSession(data: {
  pageId?: number
  draftId?: number
}): Promise<EditSessionInfo> {
  return api.post('/wiki/edit-sessions', data)
}

export function getEditSession(id: number): Promise<EditSessionInfo> {
  return api.get(`/wiki/edit-sessions/${id}`)
}

export function abandonEditSession(id: number): Promise<void> {
  return api.delete(`/wiki/edit-sessions/${id}`)
}

// --- AI 编辑 (SSE via fetch POST) ---

export function aiEditStream(
  sessionId: number,
  instruction: string,
  selectedLines?: string,
  selectedText?: string,
  confirmedKnowledgeIds?: number[],
  expectedHash?: string,
): EventStreamResult {
  const authStore = useAuthStore()
  const controller = new AbortController()

  const fetchPromise = fetch('/api/wiki/ai/edit', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${authStore.token || ''}`,
    },
    body: JSON.stringify({ sessionId, instruction, selectedLines, selectedText, confirmedKnowledgeIds, expectedHash }),
    signal: controller.signal,
  })

  return {
    controller,
    consume: async (onEvent: (event: AiEditEvent) => void) => {
      const response = await fetchPromise
      if (!response.ok) {
        onEvent({ type: 'error', content: `HTTP ${response.status}: ${response.statusText}` })
        return
      }
      const reader = response.body?.getReader()
      if (!reader) {
        onEvent({ type: 'error', content: '无法读取响应流' })
        return
      }
      const decoder = new TextDecoder()
      let buffer = ''
      let eventName = 'token'
      let streamDone = false
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''
        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.substring(6).trim()
          } else if (line.startsWith('data:')) {
            try {
              const data = JSON.parse(line.substring(5))
              const evt = { ...data, type: eventName }
              onEvent(evt)
              if (evt.type === 'done' || evt.type === 'error') {
                streamDone = true
              }
            } catch {
              onEvent({ type: eventName as any, content: line.substring(5) })
            }
            eventName = 'token'
          }
        }
        if (streamDone) {
          try { reader.cancel() } catch { /* ignore */ }
          break
        }
      }
      if (buffer.trim()) {
        const lines = buffer.split('\n')
        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.substring(6).trim()
          } else if (line.startsWith('data:')) {
            try {
              const data = JSON.parse(line.substring(5))
              onEvent({ ...data, type: eventName })
            } catch {
              onEvent({ type: eventName as any, content: line.substring(5) })
            }
            eventName = 'token'
          }
        }
      }
    },
  }
}

export interface EventStreamResult {
  controller: AbortController
  consume: (onEvent: (event: AiEditEvent) => void) => Promise<void>
}

// --- 步骤管理 ---

export function commitEditStep(sessionId: number, data: {
  newContent: string
  instruction: string
  selectedLines?: string
  selectedText?: string
  expectedHash?: string
}): Promise<CommitStepResult> {
  return api.post(`/wiki/edit-sessions/${sessionId}/commit-step`, data)
}

export function listEditSteps(sessionId: number): Promise<EditStepInfo[]> {
  return api.get(`/wiki/edit-sessions/${sessionId}/steps`)
}

export function undoEditStep(sessionId: number, stepId: number): Promise<EditSessionInfo> {
  return api.post(`/wiki/edit-sessions/${sessionId}/steps/${stepId}/undo`)
}

export function undoAllSteps(sessionId: number): Promise<EditSessionInfo> {
  return api.post(`/wiki/edit-sessions/${sessionId}/undo-all`)
}

// --- 保存 ---

export function savePage(request: SavePageRequest): Promise<WikiPageInfo> {
  return api.post('/wiki/pages/save', request)
}

export function preValidatePage(title: string, content: string, category?: string): Promise<PreValidateResult> {
  return api.post('/wiki/pages/pre-validate', { title, content, category })
}

// --- AI 状态 ---

export function getAiStatus(): Promise<{ available: boolean }> {
  return api.get('/wiki/ai/status')
}