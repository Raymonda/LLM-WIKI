import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  getHealthOverview, listScopeFindingsPaged, startLint, getLintProgress, createLintSSE,
  getActiveLintExecution, getFindings,
  updateFindingStatus, autoResolveFinding, triggerRepair, approveFinding,
  executeConflictRuling,
  rejectFinding, rollbackFinding, recordFindingFeedback, retryFailedFinding,
  batchApproveFindings, batchRejectFindings, batchRollbackFindings, retryOrphanFix, enrichPage,
  approveLink, rejectLink, batchApproveLinks, batchRejectLinks,
  resolveSchemaCompliance,
  type LintExecutionInfo, type LintFindingInfo, type HealthOverview, type PageResult
} from '@/api/lint'

export interface CompletionSummary {
  totalFound: number
  autoResolved: number
  awaitingApproval: number
  openPending: number
  byType: Record<string, { total: number; autoResolved: number; open: number; awaiting: number }>
}

export type MainTabKey = 'manual' | 'ai_processed' | 'archived'

export const MANUAL_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: 'orphan', label: '孤立' },
  { value: 'duplicate_orphan', label: '重复' },
  { value: 'stale', label: '过时' },
  { value: 'missing_crossref', label: '缺引用' },
  { value: 'conflict', label: '矛盾' },
  { value: 'gap', label: '缺口' },
  { value: 'web_gap', label: '外部缺口' },
  { value: 'action', label: '建议' },
  { value: 'schema_compliance', label: '合规' },
  { value: 'content_thin', label: '过薄' }
]

const MAIN_TAB_STATUS_FILTER: Record<MainTabKey, string> = {
  manual: 'open,awaiting_approval,repairing,deferred',
  ai_processed: 'auto_resolved',
  archived: 'resolved,dismissed,rolled_back'
}

const PAGE_SIZE_OPTIONS = [10, 20, 50]

export const useLintStore = defineStore('lint', () => {
  const authStore = useAuthStore()
  const scopeId = computed(() => authStore.scopeId)

  const overview = ref<HealthOverview | null>(null)
  const overviewLoading = ref(true)

  const findingsPaged = ref<PageResult<LintFindingInfo> | null>(null)
  const findingsLoading = ref(false)

  const execution = ref<LintExecutionInfo | null>(null)
  const running = ref(false)
  const error = ref<string | null>(null)
  const lintStartTime = ref<number | null>(null)
  const latestExecutionId = ref<number | null>(null)

  const mainTab = ref<MainTabKey>('manual')
  const manualTypeFilter = ref<string | undefined>(undefined)
  const currentPage = ref(1)
  const pageSize = ref(20)

  const actionError = ref<string | null>(null)
  const selectedIds = ref<Set<number>>(new Set())
  const processingIds = ref<Set<number>>(new Set())
  const batchProcessing = ref(false)

  const mainTabCounts = ref<Record<MainTabKey, number>>({ manual: 0, ai_processed: 0, archived: 0 })

  const completionSummary = ref<CompletionSummary | null>(null)

  function dismissCompletionSummary() {
    completionSummary.value = null
  }

  let eventSource: EventSource | null = null
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let pollInterval = 2000
  const MAX_POLL_INTERVAL = 15000
  let watchdogTimer: ReturnType<typeof setInterval> | null = null
  const WATCHDOG_INTERVAL = 8000

  function setActionError(msg: string | null) {
    actionError.value = msg
    if (msg) {
      setTimeout(() => { actionError.value = null }, 5000)
    }
  }

  const healthDistribution = computed(() => overview.value?.healthDistribution ?? {})
  const healthyCount = computed(() => healthDistribution.value['healthy'] ?? 0)
  const needsUpdateCount = computed(() => healthDistribution.value['needs-update'] ?? 0)
  const hasProblemsCount = computed(() => healthDistribution.value['has-problems'] ?? 0)
  const totalCount = computed(() => healthyCount.value + needsUpdateCount.value + hasProblemsCount.value)
  const totalActiveFindings = computed(() => overview.value?.totalActiveFindings ?? 0)
  const lastLintTime = computed(() => overview.value?.lastLintTime ?? null)
  const findingCountsByType = computed(() => overview.value?.findingCountsByType ?? {})

  const healthScore = computed(() => {
    const total = totalCount.value
    if (total === 0) return '--' as const
    return Math.round((healthyCount.value / total) * 100)
  })

  const healthScoreDisplay = computed(() => {
    if (healthScore.value === '--') return '--'
    return healthScore.value + '%'
  })

  const currentItems = computed(() => findingsPaged.value?.items ?? [])

  const selectedCount = computed(() => selectedIds.value.size)

  const allCurrentSelected = computed(() => {
    if (currentItems.value.length === 0) return false
    return currentItems.value.every(f => selectedIds.value.has(f.id))
  })

  const selectedItems = computed(() =>
    currentItems.value.filter(f => selectedIds.value.has(f.id))
  )

  const selectedOpenIds = computed(() =>
    selectedItems.value.filter(f => f.status === 'open').map(f => f.id)
  )

  const selectedAwaitingIds = computed(() =>
    selectedItems.value.filter(f => f.status === 'awaiting_approval').map(f => f.id)
  )

  const selectedAutoResolvedIds = computed(() =>
    selectedItems.value.filter(f => f.status === 'auto_resolved').map(f => f.id)
  )

  
  const selectedFailedIds = computed(() =>
    selectedItems.value.filter(f => f.status === 'failed').map(f => f.id)
  )
  
  const selectedStaleOpenIds = computed(() =>
    selectedItems.value.filter(f => f.status === 'open' && f.findingType === 'stale').map(f => f.id)
  )
  
  const selectedCrossrefOpenIds = computed(() =>
    selectedItems.value.filter(f => f.findingType === 'missing_crossref' && f.status === 'open').map(f => f.id)
  )
  
  const hasSelectedOpen = computed(() => selectedOpenIds.value.length > 0)
  const hasSelectedAwaiting = computed(() => selectedAwaitingIds.value.length > 0)
  const hasSelectedAutoResolved = computed(() => selectedAutoResolvedIds.value.length > 0)
  const hasSelectedFailed = computed(() => selectedFailedIds.value.length > 0)
  const hasSelectedStale = computed(() => selectedStaleOpenIds.value.length > 0)
  const hasSelectedCrossrefOpen = computed(() => selectedCrossrefOpenIds.value.length > 0)

  const selectedOpenCount = computed(() => selectedOpenIds.value.length)
  const selectedAwaitingCount = computed(() => selectedAwaitingIds.value.length)
  const selectedStaleCount = computed(() => selectedStaleOpenIds.value.length)
  const selectedAutoResolvedCount = computed(() => selectedAutoResolvedIds.value.length)
  const selectedCrossrefOpenCount = computed(() => selectedCrossrefOpenIds.value.length)

  const currentTabTotal = computed(() => findingsPaged.value?.total ?? 0)

  const pageSizeOptions = computed(() => PAGE_SIZE_OPTIONS)

  async function checkActiveLint(): Promise<boolean> {
    if (!scopeId.value) return false
    try {
      const activeExec = await getActiveLintExecution(scopeId.value)
      if (activeExec && (activeExec.status === 'running' || activeExec.status === 'pending')) {
        running.value = true
        execution.value = activeExec
        overview.value = null
        overviewLoading.value = true
        findingsPaged.value = null
        findingsLoading.value = true
        mainTabCounts.value = { manual: 0, ai_processed: 0, archived: 0 }
        completionSummary.value = null
        connectSSE(activeExec.executionId)
        startWatchdog(activeExec.executionId)
        return true
      }
      return false
    } catch (e) {
      console.error('Failed to check active lint:', e)
      return false
    }
  }

  async function loadOverview() {
    overviewLoading.value = true
    if (!scopeId.value) {
      overviewLoading.value = false
      return
    }
    try {
      overview.value = await getHealthOverview(scopeId.value)
    } catch (e) {
      console.error('Failed to load health overview:', e)
    } finally {
      overviewLoading.value = false
    }
  }

  async function loadTabCounts() {
    if (!scopeId.value) return
    try {
      const mainTabs = Object.keys(MAIN_TAB_STATUS_FILTER) as MainTabKey[]
      const mainResults = await Promise.allSettled(
        mainTabs.map(tab =>
          listScopeFindingsPaged(scopeId.value!, 1, 1, undefined, MAIN_TAB_STATUS_FILTER[tab], undefined, tab === 'archived')
        )
      )
      const newMainCounts: Record<MainTabKey, number> = { manual: 0, ai_processed: 0, archived: 0 }
      mainResults.forEach((result, i) => {
        if (result.status === 'fulfilled') {
          newMainCounts[mainTabs[i]] = result.value.total
        }
      })
      mainTabCounts.value = newMainCounts
    } catch (e) {
      console.error('Failed to load tab counts:', e)
    }
  }

  async function loadFindings() {
    if (!scopeId.value) return
    findingsLoading.value = true
    try {
      const statusFilter = MAIN_TAB_STATUS_FILTER[mainTab.value]
      const typeFilter = mainTab.value === 'manual' ? manualTypeFilter.value : undefined
      const isArchived = mainTab.value === 'archived'
      findingsPaged.value = await listScopeFindingsPaged(
        scopeId.value, currentPage.value, pageSize.value,
        typeFilter, statusFilter, undefined, isArchived
      )
      if (findingsPaged.value.total > 0 && findingsPaged.value.items.length === 0 && currentPage.value > 1) {
        currentPage.value = findingsPaged.value.totalPages
        findingsPaged.value = await listScopeFindingsPaged(
          scopeId.value, currentPage.value, pageSize.value,
          typeFilter, statusFilter, undefined, isArchived
        )
      }
      parseCrossrefSuggestions()
    } catch (e) {
      console.error('Failed to load findings:', e)
    } finally {
      findingsLoading.value = false
    }
  }

  function parseCrossrefSuggestions() {
    if (!findingsPaged.value?.items) return
    for (const item of findingsPaged.value.items) {
      if (item.crossrefSuggestions && typeof item.crossrefSuggestions === 'string') {
        try {
          item.crossrefSuggestions = JSON.parse(item.crossrefSuggestions)
        } catch {
          item.crossrefSuggestions = undefined
        }
      }
    }
  }

  function switchMainTab(tab: MainTabKey) {
    if (mainTab.value === tab) return
    mainTab.value = tab
    manualTypeFilter.value = undefined
    currentPage.value = 1
    selectedIds.value.clear()
    loadFindings()
  }

  function setManualTypeFilter(type: string | undefined) {
    manualTypeFilter.value = type
    currentPage.value = 1
    selectedIds.value.clear()
    loadFindings()
  }

  function goToPage(page: number) {
    const maxPage = findingsPaged.value?.totalPages ?? 1
    if (page < 1 || page > maxPage) return
    currentPage.value = page
    selectedIds.value.clear()
    loadFindings()
  }

  function setPageSize(size: number) {
    pageSize.value = size
    currentPage.value = 1
    selectedIds.value.clear()
    loadFindings()
  }

  async function triggerLint() {
    if (running.value || !scopeId.value) return
    overview.value = null
    overviewLoading.value = true
    findingsPaged.value = null
    findingsLoading.value = true
    mainTabCounts.value = { manual: 0, ai_processed: 0, archived: 0 }
    completionSummary.value = null
    running.value = true
    error.value = null
    lintStartTime.value = Date.now()
    try {
      const result = await startLint(scopeId.value)
      execution.value = result
      connectSSE(result.executionId)
      startWatchdog(result.executionId)
    } catch (e: any) {
      error.value = e.message || '启动体检失败'
      running.value = false
      lintStartTime.value = null
      overviewLoading.value = false
      findingsLoading.value = false
    }
  }

  function connectSSE(execId: number) {
    disconnectSSE()
    stopPolling()

    const es = createLintSSE(execId)
    eventSource = es

    es.addEventListener('init', (e: MessageEvent) => {
      const data = JSON.parse(e.data) as LintExecutionInfo
      execution.value = data
      if (data.status === 'completed' || data.status === 'failed') {
        onLintComplete()
      }
    })

    es.addEventListener('step', () => {
      getLintProgress(execId).then(progress => {
        execution.value = progress
      }).catch(() => {})
    })

    es.addEventListener('step_progress', () => {
      getLintProgress(execId).then(progress => {
        execution.value = progress
      }).catch(() => {})
    })

    es.addEventListener('done', (e: MessageEvent) => {
      const data = JSON.parse(e.data) as LintExecutionInfo
      execution.value = data
      onLintComplete()
    })

    es.onerror = () => {
      disconnectSSE()
      startPolling(execId)
    }
  }

  function disconnectSSE() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  function startWatchdog(execId: number) {
    stopWatchdog()
    watchdogTimer = setInterval(async () => {
      if (!running.value) { stopWatchdog(); return }
      try {
        const progress = await getLintProgress(execId)
        execution.value = progress
        if (progress.status === 'completed' || progress.status === 'failed') {
          stopWatchdog()
          onLintComplete()
        }
      } catch {}
    }, WATCHDOG_INTERVAL)
  }

  function stopWatchdog() {
    if (watchdogTimer) {
      clearInterval(watchdogTimer)
      watchdogTimer = null
    }
  }

  async function onVisibilityChange() {
    if (document.visibilityState !== 'visible') return
    if (running.value && execution.value?.executionId) {
      try {
        const progress = await getLintProgress(execution.value.executionId)
        execution.value = progress
        if (progress.status === 'completed' || progress.status === 'failed') {
          onLintComplete()
          return
        }
      } catch {}
    }
    if (!running.value && overview.value) {
      await Promise.allSettled([loadOverview(), loadTabCounts(), loadFindings()])
    }
  }

  document.addEventListener('visibilitychange', onVisibilityChange)

  function cleanup() {
    disconnectSSE()
    stopPolling()
    stopWatchdog()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  }

  async function onLintComplete() {
    if (!running.value) return
    disconnectSSE()
    stopPolling()
    stopWatchdog()
    running.value = false
    if (execution.value?.executionId) {
      latestExecutionId.value = execution.value.executionId
      await buildCompletionSummary(execution.value.executionId)
    }
    await loadOverview()
    await loadTabCounts()
    await loadFindings()
  }

  async function buildCompletionSummary(execId: number) {
    try {
      const findings = await getFindings(execId)
      const byType: CompletionSummary['byType'] = {}
      let autoResolved = 0
      let awaitingApproval = 0
      let openPending = 0

      for (const f of findings) {
        const t = f.findingType
        if (!byType[t]) byType[t] = { total: 0, autoResolved: 0, open: 0, awaiting: 0 }
        byType[t].total++
        if (f.status === 'auto_resolved' || f.status === 'resolved') {
          autoResolved++
          byType[t].autoResolved++
        } else if (f.status === 'awaiting_approval') {
          awaitingApproval++
          byType[t].awaiting++
        } else if (f.status === 'deferred') {
          byType[t].open++
        } else if (f.status === 'open' || f.status === 'repairing') {
          openPending++
          byType[t].open++
        }
      }

      completionSummary.value = {
        totalFound: findings.length,
        autoResolved,
        awaitingApproval,
        openPending,
        byType
      }
    } catch (e) {
      console.error('Failed to build completion summary:', e)
    }
  }

  function startPolling(execId: number) {
    stopPolling()
    pollInterval = 2000

    const poll = async () => {
      try {
        const progress = await getLintProgress(execId)
        execution.value = progress
        if (progress.status === 'completed' || progress.status === 'failed') {
          stopPolling()
          running.value = false
          if (progress.status === 'completed') {
            latestExecutionId.value = progress.executionId
            await buildCompletionSummary(progress.executionId)
          }
          await loadOverview()
          await loadTabCounts()
          await loadFindings()
          return
        }
        pollInterval = Math.min(Math.round(pollInterval * 1.5), MAX_POLL_INTERVAL)
      } catch (e) {
        pollInterval = Math.min(Math.round(pollInterval * 2), MAX_POLL_INTERVAL)
      }
      pollTimer = setTimeout(poll, pollInterval)
    }

    pollTimer = setTimeout(poll, pollInterval)
  }

  function stopPolling() {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  async function withProcessing(id: number, fn: () => Promise<void>) {
    if (processingIds.value.has(id)) return
    processingIds.value = new Set([...processingIds.value, id])
    try {
      await fn()
    } finally {
      const next = new Set(processingIds.value)
      next.delete(id)
      processingIds.value = next
    }
  }

  async function refreshAfterAction() {
    await Promise.all([loadFindings(), loadOverview(), loadTabCounts()])
  }

  async function dismissFinding(id: number) {
    await withProcessing(id, async () => {
      try {
        await updateFindingStatus(id, 'dismissed')
        await recordFindingFeedback(id, 'ignored')
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '忽略失败')
      }
    })
  }

  async function reassessFinding(id: number) {
    await withProcessing(id, async () => {
      try {
        await updateFindingStatus(id, 'open')
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '重新评估失败')
      }
    })
  }

  async function autoResolveAction(id: number) {
    await withProcessing(id, async () => {
      try {
        await autoResolveFinding(id)
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '自动解决失败')
      }
    })
  }

  async function resolveComplianceAction(id: number) {
    await withProcessing(id, async () => {
      try {
        await resolveSchemaCompliance(id)
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '标记已修复失败')
      }
    })
  }

  async function triggerRepairAction(id: number) {
    if (!scopeId.value) return
    await withProcessing(id, async () => {
      try {
        await triggerRepair(id, scopeId.value!)
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '触发修复失败')
      }
    })
  }

  async function approveFindingAction(id: number, feedbackType: string = 'accepted') {
    if (!scopeId.value) return
    await withProcessing(id, async () => {
      try {
        await approveFinding(id, scopeId.value!)
        await recordFindingFeedback(id, feedbackType)
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '审批失败')
      }
    })
  }

  async function executeConflictRulingAction(id: number, action: string) {
    if (!scopeId.value) return
    await withProcessing(id, async () => {
      try {
        const result = await executeConflictRuling(id, scopeId.value!, action)
        if (result.status === 'failed') {
          throw new Error((result.error as string) || '裁决执行失败')
        }
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '裁决执行失败')
      }
    })
  }

  async function rejectFindingAction(id: number) {
    await withProcessing(id, async () => {
      try {
        await rejectFinding(id)
        await recordFindingFeedback(id, 'ignored')
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '拒绝失败')
      }
    })
  }

  async function rollbackFindingAction(id: number) {
    await withProcessing(id, async () => {
      try {
        await rollbackFinding(id)
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '回滚失败')
      }
    })
  }

  async function retryFailedFindingAction(id: number) {
    await withProcessing(id, async () => {
      try {
        await retryFailedFinding(id)
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '重试失败')
      }
    })
  }

  async function retryOrphanFixAction(id: number) {
    if (!scopeId.value) return
    await withProcessing(id, async () => {
      try {
        await retryOrphanFix(id, scopeId.value!)
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '孤儿修复失败')
      }
    })
  }

  async function enrichPageAction(id: number, userSupplement: string) {
    if (!scopeId.value) return
    await withProcessing(id, async () => {
      try {
        await enrichPage(id, scopeId.value!, userSupplement)
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '页面充实失败')
      }
    })
  }

  // Phase 2 新增：交叉引用审批 actions
  async function approveLinkAction(id: number, suggestion: any) {
    if (!scopeId.value) return
    await withProcessing(id, async () => {
      try {
        await approveLink(id, suggestion.sourceTitle, suggestion.targetTitle)
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '确认链接失败')
      }
    })
  }

  async function rejectLinkAction(id: number, suggestion: any) {
    if (!scopeId.value) return
    await withProcessing(id, async () => {
      try {
        await rejectLink(id, suggestion.sourceTitle, suggestion.targetTitle, '用户认为不相关')
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '拒绝链接失败')
      }
    })
  }

  async function ignoreLinkAction(id: number) {
    if (!scopeId.value) return
    await withProcessing(id, async () => {
      try {
        await updateFindingStatus(id, 'dismissed')
        await refreshAfterAction()
      } catch (e: any) {
        setActionError(e.message || '忽略失败')
      }
    })
  }

  async function batchApproveLinksAction() {
    const crossrefIds = selectedItems.value
      .filter(f => f.findingType === 'missing_crossref' && f.status === 'open')
      .map(f => f.id)
    if (crossrefIds.length === 0 || batchProcessing.value) return
    
    batchProcessing.value = true
    try {
      await batchApproveLinks(crossrefIds)
      await refreshAfterAction()
      clearSelection()
    } catch (e: any) {
      setActionError(e.message || '批量确认链接失败')
    } finally {
      batchProcessing.value = false
    }
  }

  async function batchRejectLinksAction() {
    const crossrefIds = selectedItems.value
      .filter(f => f.findingType === 'missing_crossref' && f.status === 'open')
      .map(f => f.id)
    if (crossrefIds.length === 0 || batchProcessing.value) return
    
    batchProcessing.value = true
    try {
      await batchRejectLinks(crossrefIds)
      await refreshAfterAction()
      clearSelection()
    } catch (e: any) {
      setActionError(e.message || '批量拒绝链接失败')
    } finally {
      batchProcessing.value = false
    }
  }

  function toggleSelect(id: number) {
    const next = new Set(selectedIds.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    selectedIds.value = next
  }

  function toggleSelectAll() {
    const items = findingsPaged.value?.items ?? []
    const allIds = items.map(f => f.id)
    const allSelected = allIds.length > 0 && allIds.every(id => selectedIds.value.has(id))
    const next = new Set(selectedIds.value)
    if (allSelected) {
      for (const id of allIds) next.delete(id)
    } else {
      for (const id of allIds) next.add(id)
    }
    selectedIds.value = next
  }

  function clearSelection() {
    selectedIds.value = new Set()
  }

  async function batchAutoResolveSelected() {
    if (selectedOpenIds.value.length === 0 || batchProcessing.value) return
    batchProcessing.value = true
    try {
      const ids = selectedOpenIds.value
      const results = await Promise.allSettled(ids.map(id => autoResolveFinding(id)))
      await Promise.allSettled(ids.map(id => recordFindingFeedback(id, 'accepted').catch(() => {})))
      clearSelection()
      await refreshAfterAction()
      const failed = results.filter(r => r.status === 'rejected').length
      if (failed > 0) setActionError(`${failed} 条修复失败`)
    } catch (e: any) {
      setActionError(e.message || '批量修复失败')
    } finally {
      batchProcessing.value = false
    }
  }

  async function batchDismissSelected() {
    if (selectedIds.value.size === 0 || batchProcessing.value) return
    batchProcessing.value = true
    try {
      const ids = Array.from(selectedIds.value)
      await batchRejectFindings(ids)
      await Promise.allSettled(ids.map(id => recordFindingFeedback(id, 'ignored').catch(() => {})))
      clearSelection()
      await refreshAfterAction()
    } catch (e: any) {
      setActionError(e.message || '批量忽略失败')
    } finally {
      batchProcessing.value = false
    }
  }

  async function batchTriggerRepairSelected() {
    if (!scopeId.value || selectedStaleOpenIds.value.length === 0 || batchProcessing.value) return
    batchProcessing.value = true
    try {
      const ids = selectedStaleOpenIds.value
      const results = await Promise.allSettled(ids.map(id => triggerRepair(id, scopeId.value!)))
      clearSelection()
      await refreshAfterAction()
      const failed = results.filter(r => r.status === 'rejected').length
      if (failed > 0) setActionError(`${failed} 条刷新触发失败`)
    } catch (e: any) {
      setActionError(e.message || '批量刷新失败')
    } finally {
      batchProcessing.value = false
    }
  }

  async function batchApprove() {
    if (!scopeId.value || selectedAwaitingIds.value.length === 0 || batchProcessing.value) return
    batchProcessing.value = true
    try {
      const ids = selectedAwaitingIds.value
      await batchApproveFindings(ids, scopeId.value)
      await Promise.allSettled(ids.map(id => recordFindingFeedback(id, 'accepted').catch(() => {})))
      clearSelection()
      await refreshAfterAction()
    } catch (e: any) {
      setActionError(e.message || '批量审批失败')
    } finally {
      batchProcessing.value = false
    }
  }

  async function batchReject() {
    if (selectedAwaitingIds.value.length === 0 || batchProcessing.value) return
    batchProcessing.value = true
    try {
      const ids = selectedAwaitingIds.value
      await batchRejectFindings(ids)
      await Promise.allSettled(ids.map(id => recordFindingFeedback(id, 'ignored').catch(() => {})))
      clearSelection()
      await refreshAfterAction()
    } catch (e: any) {
      setActionError(e.message || '批量拒绝失败')
    } finally {
      batchProcessing.value = false
    }
  }

  async function batchRollback() {
    if (selectedAutoResolvedIds.value.length === 0 || batchProcessing.value) return
    batchProcessing.value = true
    try {
      const ids = selectedAutoResolvedIds.value
      await batchRollbackFindings(ids)
      clearSelection()
      await refreshAfterAction()
    } catch (e: any) {
      setActionError(e.message || '批量回滚失败')
    } finally {
      batchProcessing.value = false
    }
  }

  return {
    overview, overviewLoading, findingsPaged, findingsLoading,
    execution, running, error, actionError, lintStartTime, latestExecutionId,
    mainTab, manualTypeFilter, currentPage, pageSize, pageSizeOptions,
    selectedIds, processingIds, batchProcessing,
    mainTabCounts,
    completionSummary, dismissCompletionSummary,
    healthDistribution, healthyCount, needsUpdateCount,
    hasProblemsCount, totalCount, totalActiveFindings, lastLintTime,
    findingCountsByType, healthScore, healthScoreDisplay, currentItems,
    selectedCount, allCurrentSelected,
    selectedItems, selectedOpenIds, selectedAwaitingIds, selectedAutoResolvedIds, selectedFailedIds, selectedStaleOpenIds, selectedCrossrefOpenIds,
    hasSelectedOpen, hasSelectedAwaiting, hasSelectedAutoResolved, hasSelectedFailed, hasSelectedStale, hasSelectedCrossrefOpen,
    selectedOpenCount, selectedAwaitingCount, selectedStaleCount, selectedAutoResolvedCount, selectedCrossrefOpenCount,
    currentTabTotal,
    loadOverview, loadTabCounts, loadFindings,
    switchMainTab, setManualTypeFilter, goToPage, setPageSize,
    triggerLint, startPolling, stopPolling, disconnectSSE, checkActiveLint, cleanup,
    dismissFinding, reassessFinding, autoResolveAction, resolveComplianceAction, triggerRepairAction,
    approveFindingAction, executeConflictRulingAction, rejectFindingAction, rollbackFindingAction, retryFailedFindingAction, retryOrphanFixAction, enrichPageAction,
    approveLinkAction, rejectLinkAction, ignoreLinkAction,
    batchApproveLinksAction, batchRejectLinksAction,
    toggleSelect, toggleSelectAll, clearSelection,
    batchAutoResolveSelected, batchDismissSelected, batchTriggerRepairSelected,
    batchApprove, batchReject, batchRollback,
    setActionError,
  }
})