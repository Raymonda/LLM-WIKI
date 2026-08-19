import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { createExecutionSSE, listActiveTasks, type ExecutionRecord } from '@/api/harness'
import { useAuthStore } from '@/stores/auth'

export type BackgroundTaskType = 'merge' | 'conflict_resolve'
export type BackgroundTaskStatus = 'running' | 'completed' | 'failed' | 'cancelled'

export interface BackgroundTask {
  executionId: number
  type: BackgroundTaskType
  title: string
  status: BackgroundTaskStatus
  currentStepName: string
  completedSteps: number
  totalSteps: number
  progress: number
  errorMessage: string | null
  floatingDismissed: boolean
  eventSource: EventSource | null
  cleanupTimer: number | null
  retryCount: number
  retryTimer: number | null
}

export const useTaskProgressStore = defineStore('taskProgress', () => {
  const tasks = ref<Map<number, BackgroundTask>>(new Map())

  const visibleTasks = computed(() => {
    return [...tasks.value.values()].filter(t => !t.floatingDismissed)
  })

  const hasVisibleTasks = computed(() => visibleTasks.value.length > 0)

  const runningTaskCount = computed(() => {
    return [...tasks.value.values()].filter(
      t => t.status === 'running' && !t.floatingDismissed
    ).length
  })

  function addTask(executionId: number, type: BackgroundTaskType, title: string) {
    if (tasks.value.has(executionId)) return

    const task: BackgroundTask = {
      executionId,
      type,
      title,
      status: 'running',
      currentStepName: '',
      completedSteps: 0,
      totalSteps: 0,
      progress: 0,
      errorMessage: null,
      floatingDismissed: false,
      eventSource: null,
      cleanupTimer: null,
      retryCount: 0,
      retryTimer: null,
    }
    tasks.value.set(executionId, task)
    connectSSE(executionId)
  }

  function connectSSE(executionId: number) {
    const task = tasks.value.get(executionId)
    if (!task) return

    if (task.eventSource) {
      task.eventSource.close()
    }

    const es = createExecutionSSE(executionId)
    task.eventSource = es

    es.addEventListener('init', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data)
        updateTaskFromExecution(executionId, data)
      } catch {}
    })

    es.addEventListener('step', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data)
        const t = tasks.value.get(executionId)
        if (!t) return
        t.currentStepName = data.stepName || t.currentStepName

        if (data.status === 'completed' || data.status === 'running' || data.status === 'pending') {
          recomputeProgress(t)
        }
      } catch {}
    })

    es.addEventListener('done', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data)
        const t = tasks.value.get(executionId)
        if (!t) return
        t.status = mapStatus(data.status)
        t.progress = 1
        t.currentStepName = t.status === 'completed' ? '完成' : t.currentStepName
        t.errorMessage = data.errorMessage || null
        closeSSE(executionId)
        scheduleAutoCleanup(t)
        window.dispatchEvent(new CustomEvent('task-completed', { detail: { type: t.type, executionId, status: t.status } }))
      } catch {}
    })

    es.addEventListener('pause', () => {
      const t = tasks.value.get(executionId)
      if (t) t.currentStepName = '已暂停'
    })

    es.onerror = () => {
      const t = tasks.value.get(executionId)
      if (t && t.status === 'running') {
        closeSSE(executionId)
        const delay = Math.min(5000 * Math.pow(2, t.retryCount), 60_000)
        t.retryCount++
        t.retryTimer = window.setTimeout(() => {
          t.retryTimer = null
          pollOnce(executionId)
        }, delay)
      }
    }
  }

  function closeSSE(executionId: number) {
    const task = tasks.value.get(executionId)
    if (task?.eventSource) {
      task.eventSource.close()
      task.eventSource = null
    }
    if (task?.retryTimer) {
      clearTimeout(task.retryTimer)
      task.retryTimer = null
    }
  }

  function pollOnce(executionId: number) {
    const authStore = useAuthStore()
    if (!authStore.scopeId) return

    import('@/api/harness').then(({ getExecution }) => {
      getExecution(executionId).then((exec) => {
        if (!exec) return
        updateTaskFromExecution(executionId, exec)
        const t = tasks.value.get(executionId)
        if (t && (t.status === 'completed' || t.status === 'failed' || t.status === 'cancelled')) {
          scheduleAutoCleanup(t)
        } else if (t && t.status === 'running') {
          const delay = Math.min(5000 * Math.pow(2, t.retryCount), 60_000)
          t.retryCount++
          t.retryTimer = window.setTimeout(() => {
            t.retryTimer = null
            connectSSE(executionId)
          }, delay)
        }
      }).catch(() => {
        const t = tasks.value.get(executionId)
        if (!t || t.status !== 'running') return
        const delay = Math.min(5000 * Math.pow(2, t.retryCount), 60_000)
        t.retryCount++
        t.retryTimer = window.setTimeout(() => {
          t.retryTimer = null
          connectSSE(executionId)
        }, delay)
      })
    })
  }

  function updateTaskFromExecution(executionId: number, data: ExecutionRecord | any) {
    const t = tasks.value.get(executionId)
    if (!t) return

    const status = mapStatus(data.status)
    t.status = status
    t.retryCount = 0
    t.totalSteps = data.totalSteps || data.steps?.length || 0
    t.completedSteps = data.completedSteps || (data.steps?.filter((s: any) => s.status === 'completed').length ?? 0)
    t.currentStepName = data.currentStepName || t.currentStepName
    t.errorMessage = data.errorMessage || null
    recomputeProgress(t)

    if (status !== 'running') {
      closeSSE(executionId)
      if (!t.cleanupTimer) scheduleAutoCleanup(t)
      window.dispatchEvent(new CustomEvent('task-completed', { detail: { type: t.type, executionId, status } }))
    }
  }

  function recomputeProgress(t: BackgroundTask) {
    if (t.totalSteps > 0) {
      t.progress = Math.min(1, t.completedSteps / t.totalSteps)
    }
  }

  function scheduleAutoCleanup(t: BackgroundTask) {
    if (t.cleanupTimer) return
    t.cleanupTimer = window.setTimeout(() => {
      t.floatingDismissed = true
      t.cleanupTimer = null
    }, 30_000)
  }

  function dismissFloating(executionId: number) {
    const t = tasks.value.get(executionId)
    if (t) {
      t.floatingDismissed = true
      if (t.cleanupTimer) {
        clearTimeout(t.cleanupTimer)
        t.cleanupTimer = null
      }
    }
  }

  function removeTask(executionId: number) {
    closeSSE(executionId)
    const t = tasks.value.get(executionId)
    if (t?.cleanupTimer) clearTimeout(t.cleanupTimer)
    tasks.value.delete(executionId)
  }

  function clear() {
    for (const [executionId, t] of tasks.value.entries()) {
      closeSSE(executionId)
      if (t.cleanupTimer) clearTimeout(t.cleanupTimer)
    }
    tasks.value.clear()
  }

  async function recoverActiveTasks() {
    const authStore = useAuthStore()
    if (!authStore.scopeId) return

    try {
      const activeList = await listActiveTasks(authStore.scopeId)
      const activeIdSet = new Set(activeList.map(e => e.executionId))
      for (const executionId of [...tasks.value.keys()]) {
        if (!activeIdSet.has(executionId)) {
          removeTask(executionId)
        }
      }
      for (const exec of activeList) {
        const existing = tasks.value.get(exec.executionId)
        if (existing) continue

        const type: BackgroundTaskType = exec.operationType === 'page_merge' ? 'merge' : 'conflict_resolve'
        const title = exec.sourceName || (type === 'merge' ? '知识合并' : '冲突处理')

        const task: BackgroundTask = {
          executionId: exec.executionId,
          type,
          title,
          status: mapStatus(exec.status),
          currentStepName: exec.currentStepName || '',
          completedSteps: exec.completedSteps || 0,
          totalSteps: exec.totalSteps || 0,
          progress: 0,
          errorMessage: exec.errorMessage,
          floatingDismissed: exec.status === 'completed',
          eventSource: null,
          cleanupTimer: null,
          retryCount: 0,
          retryTimer: null,
        }
        recomputeProgress(task)
        tasks.value.set(exec.executionId, task)

        if (task.status === 'running') {
          connectSSE(exec.executionId)
        } else if (task.status === 'completed' || task.status === 'failed') {
          task.floatingDismissed = true
        }
      }
    } catch (e) {
      console.warn('Failed to recover active tasks:', e)
    }
  }

  function mapStatus(s: string | undefined): BackgroundTaskStatus {
    switch (s) {
      case 'completed': return 'completed'
      case 'failed':
      case 'budget_exhausted': return 'failed'
      case 'cancelled': return 'cancelled'
      default: return 'running'
    }
  }

  const pendingRemoveIds = new Set<number>()

  watch(() => [...tasks.value.keys()], (ids) => {
    for (const id of ids) {
      if (pendingRemoveIds.has(id)) continue
      const t = tasks.value.get(id)
      if (t && t.floatingDismissed && t.cleanupTimer === null && t.eventSource === null) {
        pendingRemoveIds.add(id)
        setTimeout(() => {
          pendingRemoveIds.delete(id)
          removeTask(id)
        }, 60_000)
      }
    }
  })

  return {
    tasks,
    visibleTasks,
    hasVisibleTasks,
    runningTaskCount,
    addTask,
    dismissFloating,
    removeTask,
    recoverActiveTasks,
    clear,
  }
})
