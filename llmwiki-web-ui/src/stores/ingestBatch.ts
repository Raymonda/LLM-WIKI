import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  createIngestBatch,
  fetchBatchInbox,
  getBatchDetail,
  confirmBatchItems,
  pauseBatch,
  resumeBatch,
  cancelBatch,
  cancelBatchItems,
  deprecateBatchOutputs,
  executeIngest,
  reanalyzeIngest,
  resumeIngest,
  type IngestBatchInfo,
  type IngestBatchItemInfo,
  type IngestBatchDetailInfo,
  type IngestBatchCreateInfo,
} from '@/api/ingest'
import { useAuthStore } from '@/stores/auth'

export type InboxGroupKey = 'awaiting' | 'queued' | 'analyzing' | 'writing' | 'completed' | 'failed'

export interface InboxGroup {
  key: InboxGroupKey
  items: IngestBatchItemInfo[]
}

export function groupInboxItems(items: IngestBatchItemInfo[]): InboxGroup[] {
  const groups: Record<InboxGroupKey, IngestBatchItemInfo[]> = {
    awaiting: [],
    queued: [],
    analyzing: [],
    writing: [],
    completed: [],
    failed: [],
  }
  for (const item of items) {
    if (item.status === 'awaiting_confirmation' || item.status === 'awaiting_review') {
      groups.awaiting.push(item)
    } else if (item.status === 'pending' || item.status === 'paused') {
      groups.queued.push(item)
    } else if (item.status === 'running') {
      if (item.phase1Completed) {
        groups.writing.push(item)
      } else {
        groups.analyzing.push(item)
      }
    } else if (item.status === 'confirmed') {
      groups.writing.push(item)
    } else if (item.status === 'completed') {
      groups.completed.push(item)
    } else {
      groups.failed.push(item)
    }
  }
  return (Object.keys(groups) as InboxGroupKey[]).map((key) => ({ key, items: groups[key] }))
}

export const useIngestBatchStore = defineStore('ingestBatch', () => {
  const inbox = ref<IngestBatchInfo[]>([])
  const currentBatch = ref<IngestBatchDetailInfo | null>(null)
  const selectedBatchId = ref<number | null>(null)
  const lastError = ref<string | null>(null)
  let pollTimer: number | null = null

  const awaitingTotal = computed(() =>
    inbox.value.reduce((sum, batch) => sum + batch.awaitingCount, 0),
  )

  const failedTotal = computed(() =>
    inbox.value.reduce((sum, batch) => sum + batch.failedCount, 0),
  )

  const openBatches = computed(() =>
    inbox.value.filter((batch) => batch.status === 'active' || batch.status === 'paused'),
  )

  const openFailedTotal = computed(() =>
    openBatches.value.reduce((sum, batch) => sum + batch.failedCount, 0),
  )

  const inProgressTotal = computed(() =>
    openBatches.value.reduce(
      (sum, batch) => sum + batch.pendingCount + batch.runningCount + batch.confirmedCount,
      0,
    ),
  )

  const openItemTotal = computed(() =>
    openBatches.value.reduce((sum, batch) => sum + batch.totalCount, 0),
  )

  async function refreshInbox() {
    const authStore = useAuthStore()
    const scopeId = authStore.scopeId
    if (!scopeId || scopeId <= 0) return
    inbox.value = await fetchBatchInbox(scopeId)
  }

  async function refreshCurrentBatch() {
    if (selectedBatchId.value == null) return
    currentBatch.value = await getBatchDetail(selectedBatchId.value)
  }

  async function refreshAll() {
    await refreshInbox()
    await refreshCurrentBatch()
  }

  async function selectBatch(batchId: number | null) {
    selectedBatchId.value = batchId
    if (batchId == null) {
      currentBatch.value = null
      return
    }
    await refreshCurrentBatch()
  }

  async function createBatchAndStart(scopeId: number, sourceIds: number[], guidance?: string, mode?: string, forceReingest?: boolean): Promise<IngestBatchCreateInfo> {
    const response = await createIngestBatch(scopeId, sourceIds, guidance, mode, forceReingest)
    await refreshInbox()
    if (response.batchId != null) {
      await selectBatch(response.batchId)
    }
    return response
  }

  async function confirmAll(batchId: number): Promise<number> {
    const count = await confirmBatchItems(batchId)
    await refreshAll()
    return count
  }

  async function confirmOne(executionId: number, guidance?: string) {
    await executeIngest(executionId, guidance)
    await refreshAll()
  }

  async function reanalyzeOne(executionId: number, guidance?: string) {
    await reanalyzeIngest(executionId, guidance)
    await refreshAll()
  }

  async function retryOne(executionId: number) {
    await resumeIngest(executionId)
    await refreshAll()
  }

  async function pause(batchId: number) {
    await pauseBatch(batchId)
    await refreshAll()
  }

  async function resume(batchId: number) {
    await resumeBatch(batchId)
    await refreshAll()
  }

  async function cancel(batchId: number) {
    await cancelBatch(batchId)
    await refreshAll()
  }

  async function cancelItems(batchId: number, executionIds?: number[]): Promise<{ cancelled: number; skipped: number }> {
    const result = await cancelBatchItems(batchId, executionIds)
    await refreshAll()
    return result
  }

  async function deprecateOutputs(batchId: number): Promise<{ deprecatedPages: number; skippedPages: number }> {
    const result = await deprecateBatchOutputs(batchId)
    await refreshAll()
    return result
  }

  function handleVisibilityChange() {
    if (document.hidden) return
    refreshAll().catch((error: Error) => {
      lastError.value = error.message
    })
  }

  function startPolling() {
    stopPolling()
    document.addEventListener('visibilitychange', handleVisibilityChange)
    pollTimer = window.setInterval(() => {
      if (document.hidden) return
      refreshAll().catch((error: Error) => {
        lastError.value = error.message
      })
    }, 5000)
  }

  function stopPolling() {
    if (pollTimer != null) {
      window.clearInterval(pollTimer)
      pollTimer = null
    }
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  }

  return {
    inbox,
    currentBatch,
    selectedBatchId,
    lastError,
    awaitingTotal,
    failedTotal,
    openBatches,
    openFailedTotal,
    inProgressTotal,
    openItemTotal,
    refreshInbox,
    refreshCurrentBatch,
    refreshAll,
    selectBatch,
    createBatchAndStart,
    confirmAll,
    confirmOne,
    reanalyzeOne,
    retryOne,
    pause,
    resume,
    cancel,
    cancelItems,
    deprecateOutputs,
    startPolling,
    stopPolling,
  }
})
