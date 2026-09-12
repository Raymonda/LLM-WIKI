import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  analyzeIngest,
  createIngestSSE,
  cancelIngest,
  deleteIngest,
  executeIngest,
  reanalyzeIngest,
  pauseIngest,
  resumeIngest,
  listActiveIngest,
  type ExecutionInfo,
  type StepEvent,
  type StepProgressEvent,
} from '@/api/ingest'
import { useAuthStore } from '@/stores/auth'
import { listPages, type WikiPageInfo } from '@/api/wiki'
import {
  computeRawProgress,
  estimateRemainingMs,
  monotonicProgress,
  STEP_CASCADE,
  INGEST_STEPS,
  type StepState,
  type StepStatus,
  type BaselineProfile,
} from '@/views/ingest/progressModel'
import type { SourceInfo } from '@/api/source'

const MAX_SSE_RETRIES = 5
const SSE_BASE_RETRY_MS = 3000
const SSE_STALE_TIMEOUT_MS = 5 * 60 * 1000

export type IngestFloatingPhase = 'analyzing' | 'review' | 'executing' | 'done' | 'failed' | 'cancelled' | 'paused'
export type PageFlow = 'upload' | 'analyzing' | 'review' | 'executing' | 'done' | 'paused'

export interface AffectedPage {
  title: string
  path: string
  action: string
  id?: number | string
}

export interface EntityWithType {
  name: string
  type: string
  description?: string
}

export interface AnalysisMetadata {
  title: string
  summary: string
  category: string
  tags: string[]
  keywords: string[]
  entities: EntityWithType[]
  affectedPages: AffectedPage[]
}

export interface ChunkPreviewItem {
  chunkIndex: number
  entities: string[]
}

export interface SummaryPageInfo {
  id: number | string
  title: string
  path: string
}

export interface ComplianceGroupInfo {
  type: string
  typeLabel: string
  count: number
  severity: string
  affectedPages: string[]
  overview: string
}

export interface ComplianceSummaryInfo {
  totalViolations: number
  highCount: number
  mediumCount: number
  lowCount: number
  groups: ComplianceGroupInfo[]
  overallMessage: string
}

export interface SchemaViolationInfo {
  status: 'skipped'
  reason: string
  complianceViolations: string
  complianceSummary?: ComplianceSummaryInfo | null
}

interface IngestTask {
  executionId: number
  currentStep: PageFlow
  stepStates: StepState[]
  executionStatus: string
  aiAnalysis: string
  metadataRaw: AnalysisMetadata | null
  affectedPages: AffectedPage[]
  entityPages: AffectedPage[]
  chapterPages: AffectedPage[]
  uploadedFile: Pick<SourceInfo, 'id' | 'name' | 'size' | 'format'> | null
  userGuidance: string
  pipelineError: string
  totalTokens: number
  displayProgress: number
  baselineProfile: BaselineProfile
  hasScanWarning: boolean
  scanWarningMessage: string
  isPhaseRunning: boolean
  floatingDismissed: boolean
  chunkPreviews: ChunkPreviewItem[]
  eventSource: EventSource | null
  tickTimer: number | null
  nowMs: number
  sourceId: number | null
  retryCount: number
  retryTimer: number | null
  lastEventMs: number
  hasReceivedEvent: boolean
  schemaViolation: SchemaViolationInfo | null
  complianceSummary: ComplianceSummaryInfo | null
  summaryPage: SummaryPageInfo | null
  conflictCount: number
  conflictRouteSummary: { auto: number; review: number; deferred: number } | null
  conflictPages: string[]
  qualityCritical: number
  qualityWarnings: number
  schemaGaps: string[]
  pendingCloseConfirm: boolean
  cleanupTimer: number | null
}

function createEmptyTask(): IngestTask {
  return {
    executionId: 0,
    currentStep: 'upload',
    stepStates: [],
    executionStatus: 'pending',
    aiAnalysis: '',
    metadataRaw: null,
    affectedPages: [],
    entityPages: [],
    chapterPages: [],
    uploadedFile: null,
    userGuidance: '',
    pipelineError: '',
    totalTokens: 0,
    displayProgress: 0,
    baselineProfile: {},
    hasScanWarning: false,
    scanWarningMessage: '',
    isPhaseRunning: false,
    floatingDismissed: false,
    chunkPreviews: [],
    eventSource: null,
    tickTimer: null,
    nowMs: Date.now(),
    sourceId: null,
    retryCount: 0,
    retryTimer: null,
    lastEventMs: Date.now(),
    hasReceivedEvent: false,
    schemaViolation: null,
    complianceSummary: null,
    summaryPage: null,
    conflictCount: 0,
    conflictRouteSummary: null,
    conflictPages: [],
    qualityCritical: 0,
    qualityWarnings: 0,
    schemaGaps: [],
    pendingCloseConfirm: false,
    cleanupTimer: null,
  }
}

export const useIngestProgressStore = defineStore('ingestProgress', () => {
  const tasks = ref<Map<number, IngestTask>>(new Map())
  const activeTaskId = ref<number | null>(null)
  const initializing = ref(false)

  const pendingUploadedFile = ref<Pick<SourceInfo, 'id' | 'name' | 'size' | 'format'> | null>(null)
  const pendingUserGuidance = ref('')

  const currentTask = computed<IngestTask | null>(() => {
    if (activeTaskId.value == null) return null
    return tasks.value.get(activeTaskId.value) || null
  })

  const currentStep = computed<PageFlow>(() => currentTask.value?.currentStep || 'upload')
  const stepStates = computed<StepState[]>(() => currentTask.value?.stepStates || [])
  const executionId = computed<number | null>(() => currentTask.value?.executionId || null)
  const executionStatus = computed<string>(() => currentTask.value?.executionStatus || 'pending')
  const aiAnalysis = computed<string>(() => currentTask.value?.aiAnalysis || '')
  const metadataRaw = computed<AnalysisMetadata | null>(() => currentTask.value?.metadataRaw || null)
  const affectedPages = computed<AffectedPage[]>(() => currentTask.value?.affectedPages || [])
  const entityPages = computed<AffectedPage[]>(() => currentTask.value?.entityPages || [])
  const chapterPages = computed<AffectedPage[]>(() => currentTask.value?.chapterPages || [])
  const uploadedFile = computed(() => {
    if (currentTask.value) return currentTask.value.uploadedFile
    return pendingUploadedFile.value
  })
  const userGuidance = computed(() => {
    if (currentTask.value) return currentTask.value.userGuidance
    return pendingUserGuidance.value
  })
  const pipelineError = computed<string>(() => currentTask.value?.pipelineError || '')
  const totalTokens = computed<number>(() => currentTask.value?.totalTokens || 0)
  const displayProgress = computed<number>(() => currentTask.value?.displayProgress || 0)
  const baselineProfile = computed<BaselineProfile>(() => currentTask.value?.baselineProfile || {})
  const hasScanWarning = computed<boolean>(() => currentTask.value?.hasScanWarning || false)
  const scanWarningMessage = computed<string>(() => currentTask.value?.scanWarningMessage || '')
  const isPhaseRunning = computed<boolean>(() => currentTask.value?.isPhaseRunning || false)
  const floatingDismissed = computed(() => currentTask.value?.floatingDismissed || false)
  const chunkPreviews = computed<ChunkPreviewItem[]>(() => currentTask.value?.chunkPreviews || [])
  const nowMs = computed<number>(() => currentTask.value?.nowMs || Date.now())
  const schemaViolation = computed<SchemaViolationInfo | null>(() => currentTask.value?.schemaViolation || null)
  const complianceSummary = computed<ComplianceSummaryInfo | null>(() => currentTask.value?.complianceSummary || null)
  const summaryPage = computed<SummaryPageInfo | null>(() => currentTask.value?.summaryPage || null)

  function autoAdvanceStep(task: IngestTask, stepName: string, status: StepStatus) {
      const existing = task.stepStates.find(s => s.name === stepName)
      const now = Date.now()
      if (existing) {
        if (existing.status === 'pending' && status !== 'pending') {
          existing.status = status
          if (status === 'running' && !existing.startedAtMs) existing.startedAtMs = now
          if ((status === 'completed' || status === 'failed') && !existing.completedAtMs) {
            existing.completedAtMs = now
            if (!existing.startedAtMs) existing.startedAtMs = now
          }
        } else if (existing.status === 'running' && (status === 'completed' || status === 'failed')) {
          existing.status = status
          if (!existing.completedAtMs) existing.completedAtMs = now
        }
      } else {
        task.stepStates.push({
          name: stepName,
          status,
          startedAtMs: status !== 'pending' ? now : undefined,
          completedAtMs: status === 'completed' || status === 'failed' ? now : undefined,
        })
      }
    }

    function isPhase1Completed(task: IngestTask): boolean {
      const parseDoc = task.stepStates.find(s => s.name === 'UPLOAD')
      const analyzeChunks = task.stepStates.find(s => s.name === 'ANALYZE')
      return parseDoc?.status === 'completed' && analyzeChunks?.status === 'completed'
    }

    function getOrCreateTask(id: number): IngestTask {
    let task = tasks.value.get(id)
    if (!task) {
      const plain = createEmptyTask()
      plain.executionId = id
      tasks.value.set(id, plain)
      task = tasks.value.get(id)!
    }
    return task
  }

  function setActiveTask(id: number | null) {
    activeTaskId.value = id
    if (id != null) {
      const task = getOrCreateTask(id)
      if (task.isPhaseRunning) {
        cancelCleanup(task)
        if (!task.eventSource) {
          connectTaskSSE(task)
        }
        if (!task.tickTimer) {
          startTaskTick(task)
        }
      }
    }
  }

  const includeParsePhase = computed(() => stepStates.value.some(s => s.name === 'UPLOAD'))

  const rawProgress = computed(() =>
    computeRawProgress(stepStates.value, nowMs.value, includeParsePhase.value, baselineProfile.value),
  )

  const effectiveProgress = computed(() => {
    if (currentStep.value === 'done') return 1
    return displayProgress.value
  })

  const computedRemainingMs = computed(() =>
    estimateRemainingMs(stepStates.value, nowMs.value, includeParsePhase.value, baselineProfile.value),
  )

  const tipLabelMap: Record<string, string> = {
    UPLOAD: '正在解析文档格式，提取可读文本...',
    ANALYZE: 'AI 正在分析全文，提取关键实体、概念和关系...',
    WRITE: 'AI 正在规划并生成摘要页、实体页、更新关联页...',
    COMPLETE: '正在校验一致性、生成交叉引用、更新搜索索引...',
  }

  const computedCurrentTip = computed(() => {
    for (const step of [...stepStates.value].reverse()) {
      if (step.status === 'running') {
        return tipLabelMap[step.name] || 'AI 正在处理...'
      }
    }
    return currentStep.value === 'analyzing' ? 'AI 正在分析文档...' : 'AI 正在写入知识库...'
  })

  const active = computed(() => {
    for (const task of tasks.value.values()) {
      if (task.currentStep !== 'upload' && !task.floatingDismissed) return true
    }
    return false
  })

  const phase = computed<IngestFloatingPhase>(() => {
    if (currentTask.value?.executionStatus === 'cancelled') return 'cancelled'
    if (currentTask.value?.executionStatus === 'paused') return 'paused'
    if (pipelineError.value) return 'failed'
    if (currentStep.value === 'done') return 'done'
    if (currentStep.value === 'review') return 'review'
    if (currentStep.value === 'executing') return 'executing'
    return 'analyzing'
  })

  const visible = computed(() => active.value)
  const progress = computed(() => effectiveProgress.value)
  const remainingMs = computed(() => computedRemainingMs.value)
  const currentTip = computed(() => {
    const p = phase.value
    if (p === 'done' || p === 'failed' || p === 'paused') return ''
    return computedCurrentTip.value
  })
  const errorMessage = computed(() => pipelineError.value)
  const sourceName = computed(() => uploadedFile.value?.name || '')

  const progressBarStatus = computed<'running' | 'waiting' | 'done' | 'failed' | 'paused'>(() => {
    if (pipelineError.value) return 'failed'
    if (currentTask.value?.executionStatus === 'paused') return 'paused'
    if (currentStep.value === 'done') return 'done'
    if (currentStep.value === 'review') return 'waiting'
    return 'running'
  })

  const stageErrorKey = computed(() => (pipelineError.value ? currentStep.value : undefined))

  const runningTaskCount = computed(() =>
    Array.from(tasks.value.values()).filter(t =>
      t.isPhaseRunning || t.currentStep === 'analyzing' || t.currentStep === 'executing'
    ).length
  )

  const allTaskSummaries = computed(() =>
    Array.from(tasks.value.values())
      .filter(t => t.executionId > 0 && t.currentStep !== 'upload')
      .map(t => ({
      executionId: t.executionId,
      sourceName: t.uploadedFile?.name || '',
      currentStep: t.currentStep,
      status: t.executionStatus,
      isPhaseRunning: t.isPhaseRunning,
      progress: t.displayProgress,
      floatingDismissed: t.floatingDismissed,
      pipelineError: t.pipelineError,
    }))
  )

  watch(() => [...tasks.value.keys()], (ids) => {
    for (const id of ids) {
      const task = tasks.value.get(id)
      if (task && task.isPhaseRunning && task.executionId > 0 && !task.eventSource) {
        connectTaskSSE(task)
        startTaskTick(task)
      }
    }
  })

  function startTaskTick(task: IngestTask) {
    stopTaskTick(task)
    task.tickTimer = window.setInterval(() => {
      task.nowMs = Date.now()
      const raw = computeRawProgress(
        task.stepStates, task.nowMs,
        task.stepStates.some(s => s.name === 'UPLOAD'),
        task.baselineProfile,
      )
      task.displayProgress = monotonicProgress(task.displayProgress, raw)
      if (task.hasReceivedEvent && task.isPhaseRunning && (task.nowMs - task.lastEventMs > SSE_STALE_TIMEOUT_MS)) {
        stopTaskTick(task)
        task.pipelineError = '服务器响应超时，请刷新页面重试'
        task.isPhaseRunning = false
        closeTaskSSE(task)
        clearRetryTimer(task)
      }
    }, 300)
  }

  function stopTaskTick(task: IngestTask) {
    if (task.tickTimer) {
      clearInterval(task.tickTimer)
      task.tickTimer = null
    }
  }

  function closeTaskSSE(task: IngestTask) {
    if (task.eventSource) {
      task.eventSource.close()
      task.eventSource = null
    }
  }

  const COMPLETED_TASK_CLEANUP_MS = 5 * 60 * 1000

  function scheduleCleanup(task: IngestTask) {
    cancelCleanup(task)
    task.cleanupTimer = window.setTimeout(() => {
      task.cleanupTimer = null
      if (tasks.value.has(task.executionId) && !task.isPhaseRunning) {
        removeTask(task.executionId)
      }
    }, COMPLETED_TASK_CLEANUP_MS)
  }

  function cancelCleanup(task: IngestTask) {
    if (task.cleanupTimer) {
      clearTimeout(task.cleanupTimer)
      task.cleanupTimer = null
    }
  }

  function connectTaskSSE(task: IngestTask) {
    closeTaskSSE(task)
    clearRetryTimer(task)
    const authStore = useAuthStore()
    if (!authStore.token) {
      task.pipelineError = '登录已过期，请重新登录'
      task.isPhaseRunning = false
      stopTaskTick(task)
      const router = useRouter()
      router.push('/login')
      return
    }

    const es = createIngestSSE(task.executionId)
    task.eventSource = es
    task.lastEventMs = Date.now()
    task.hasReceivedEvent = false

    es.addEventListener('init', (e: MessageEvent) => {
      task.lastEventMs = Date.now()
      task.hasReceivedEvent = true
      task.retryCount = 0
      const data = JSON.parse(e.data) as ExecutionInfo
      task.executionStatus = data.status
      task.totalTokens = data.totalTokens || 0
      if (data.status === 'failed') {
        closeTaskSSE(task)
        stopTaskTick(task)
        task.pipelineError = data.errorMessage || '处理失败，请重试'
        task.isPhaseRunning = false
      } else if (data.status === 'completed' || data.status === 'budget_exhausted') {
        task.isPhaseRunning = false
        task.pipelineError = ''
        if (data.status === 'budget_exhausted') {
          task.pipelineError = 'Token 用量已达月度参考值，操作不受限制'
        }
      } else if (data.status === 'paused') {
        task.isPhaseRunning = false
        task.currentStep = 'paused'
        stopTaskTick(task)
      } else if (data.status === 'awaiting_confirmation' || data.status === 'awaiting_review') {
        task.isPhaseRunning = false
        task.currentStep = 'review'
        stopTaskTick(task)
      } else if (data.status === 'running') {
        task.isPhaseRunning = true
        task.currentStep = isPhase1Completed(task) ? 'executing' : 'analyzing'
        if (!task.tickTimer) startTaskTick(task)
      }
    })

    es.addEventListener('step', (e: MessageEvent) => {
      task.lastEventMs = Date.now()
      task.hasReceivedEvent = true
      task.retryCount = 0
      const data = JSON.parse(e.data) as StepEvent
      const status = data.status as StepStatus
      const existing = task.stepStates.find(s => s.name === data.stepName)
      if (existing) {
        existing.status = status
        if (status === 'running' && !existing.startedAtMs) existing.startedAtMs = Date.now()
        if ((status === 'completed' || status === 'failed') && !existing.completedAtMs) {
          existing.completedAtMs = Date.now()
          if (!existing.startedAtMs) existing.startedAtMs = existing.completedAtMs
        }
      } else {
        const now = Date.now()
        task.stepStates.push({
          name: data.stepName,
          status,
          startedAtMs: status === 'running' || status === 'completed' || status === 'failed' ? now : undefined,
          completedAtMs: status === 'completed' || status === 'failed' ? now : undefined,
        })
      }

      if (data.stepName === 'ANALYZE' && data.status === 'completed' && data.outputData) {
        try {
          const analysisOutput = JSON.parse(data.outputData)
          if (analysisOutput.mergedAnalysis) {
            task.aiAnalysis = analysisOutput.mergedAnalysis
          }
          if (analysisOutput.metadata) {
            const metadata = analysisOutput.metadata
            task.metadataRaw = {
              title: metadata.title || '',
              summary: metadata.summary || '',
              category: metadata.category || '',
              tags: Array.isArray(metadata.tags) ? metadata.tags : [],
              keywords: Array.isArray(metadata.keywords) ? metadata.keywords : [],
              entities: Array.isArray(metadata.entities)
                ? metadata.entities.map((e: any) => ({
                    name: e.name || '',
                    type: e.type || 'concept',
                    description: e.description || undefined,
                  }))
                : [],
              affectedPages: Array.isArray(metadata.affectedPages)
                ? metadata.affectedPages.map((p: any) => ({
                    title: p.title,
                    path: p.path || p.title,
                    action: p.action || '更新',
                    id: p.id ?? undefined,
                  }))
                : [],
            }
            if (metadata.affectedPages && Array.isArray(metadata.affectedPages)) {
              task.affectedPages = metadata.affectedPages.map((p: any) => ({
                title: p.title,
                path: p.path || p.title,
                action: p.action || '更新',
                id: p.id ?? undefined,
              }))
            }
            if (metadata.entities && Array.isArray(metadata.entities)) {
              task.entityPages = metadata.entities.map((e: any) => ({
                title: e.name || '',
                path: e.path || 'pages/' + (e.name || '') + '.md',
                action: e.action || '待定',
                id: e.id ?? undefined,
              }))
            }
          }
        } catch {
          if (!task.aiAnalysis) {
            task.aiAnalysis = data.outputData
          }
        }
      }

      if (data.stepName === 'UPLOAD' && data.status === 'completed' && data.outputData) {
        try {
          const parsed = JSON.parse(data.outputData)
          if (parsed.warning === 'scan_warning') {
            task.hasScanWarning = true
            task.scanWarningMessage = parsed.warningMessage || '检测到扫描件，文字识别准确度可能降低'
          }
        } catch {
          task.hasScanWarning = false
        }
      }

      if (data.stepName === 'WRITE' && data.status === 'completed' && data.outputData) {
        try {
          const writeOutput = JSON.parse(data.outputData)
          if (writeOutput.status === 'skipped') {
            task.schemaViolation = {
              status: 'skipped',
              reason: writeOutput.reason || 'Schema 规则冲突',
              complianceViolations: writeOutput.complianceViolations || '',
              complianceSummary: writeOutput.complianceSummary || null,
            }
          } else {
            task.schemaViolation = null
          }
          if (writeOutput.complianceSummary) {
            task.complianceSummary = writeOutput.complianceSummary
          }
          if (writeOutput.entityPages && Array.isArray(writeOutput.entityPages)) {
            task.entityPages = writeOutput.entityPages.map((ep: any) => ({
              title: ep.title || ep.name || '',
              path: ep.path || 'pages/' + (ep.name || '') + '.md',
              action: ep.action || (ep.isNew ? '新建' : '补充'),
              id: ep.id ?? undefined,
            }))
          } else if (writeOutput.status !== 'skipped') {
            task.entityPages = []
          }
          if (writeOutput.chapterPages && Array.isArray(writeOutput.chapterPages)) {
            task.chapterPages = writeOutput.chapterPages.map((cp: any) => ({
              title: cp.title || cp.name || '',
              path: cp.path || 'pages/' + (cp.name || '') + '.md',
              action: cp.action || (cp.isNew ? '新建' : '补充'),
              id: cp.id ?? undefined,
            }))
          }
          if (writeOutput.summaryPageId != null || writeOutput.summaryPageTitle) {
            task.summaryPage = {
              id: writeOutput.summaryPageId,
              title: writeOutput.summaryPageTitle || '',
              path: writeOutput.summaryPagePath || '',
            }
          }
          const actualUpdatedPaths = new Set<string>()
          const actualUpdatedTitles = new Set<string>()
          if (writeOutput.updatedPages && Array.isArray(writeOutput.updatedPages)) {
            for (const up of writeOutput.updatedPages) {
              if (up.path) actualUpdatedPaths.add(up.path)
              if (up.filePath) actualUpdatedPaths.add(up.filePath)
              if (up.title) actualUpdatedTitles.add(up.title)
            }
            task.affectedPages = task.affectedPages.filter(p => {
              if (p.action !== '更新' && p.action !== '补充') return true
              return actualUpdatedPaths.has(p.path) || actualUpdatedTitles.has(p.title)
            })
            for (const up of writeOutput.updatedPages) {
              const existing = task.affectedPages.find(p => p.path === up.path || p.path === up.filePath || p.title === up.title)
              if (existing) {
                existing.action = up.action || '更新'
                existing.id = up.id ?? existing.id
              } else {
                task.affectedPages.push({
                  title: up.title || '',
                  path: up.filePath || up.path || '',
                  action: up.action || '更新',
                  id: up.id ?? undefined,
                })
              }
            }
          } else if (writeOutput.status !== 'skipped') {
            task.affectedPages = task.affectedPages.filter(p => p.action !== '更新' && p.action !== '补充')
          }
        } catch {
          task.schemaViolation = null
        }
      }

      if (data.stepName === 'COMPLETE' && data.status === 'completed' && data.outputData) {
        try {
          const completionOutput = JSON.parse(data.outputData)
          if (completionOutput.conflictCount != null) {
            task.conflictCount = completionOutput.conflictCount
          }
          if (completionOutput.conflictRouteSummary != null) {
            task.conflictRouteSummary = completionOutput.conflictRouteSummary
          }
          if (completionOutput.qualityCritical != null) {
            task.qualityCritical = completionOutput.qualityCritical
          }
          if (completionOutput.qualityWarnings != null) {
            task.qualityWarnings = completionOutput.qualityWarnings
          }
          if (Array.isArray(completionOutput.schemaGaps)) {
            task.schemaGaps = completionOutput.schemaGaps
          }
        } catch {
          // COMPLETE output parse failure is non-critical
        }
      }

      const cascade = STEP_CASCADE[data.stepName]
      if (cascade) {
        if (status === 'running') {
          for (const pre of cascade.preCompletedOnRunning || []) autoAdvanceStep(task, pre, 'completed')
          for (const child of cascade.runningChildren || []) autoAdvanceStep(task, child, 'running')
        } else if (status === 'completed') {
          for (const child of cascade.completedChildren || []) autoAdvanceStep(task, child, 'completed')
        } else if (status === 'failed') {
          for (const child of cascade.completedChildren || []) autoAdvanceStep(task, child, 'failed')
          for (const child of cascade.runningChildren || []) autoAdvanceStep(task, child, 'failed')
        }
      }

    })

    es.addEventListener('phase1_done', (e: MessageEvent) => {
      task.lastEventMs = Date.now()
      task.retryCount = 0
      const data = JSON.parse(e.data) as ExecutionInfo
      task.executionStatus = data.status || 'awaiting_confirmation'
      task.totalTokens = data.totalTokens || task.totalTokens
      task.isPhaseRunning = false
      task.currentStep = 'review'
      stopTaskTick(task)
      task.floatingDismissed = false
    })

    es.addEventListener('done', (e: MessageEvent) => {
      task.lastEventMs = Date.now()
      task.retryCount = 0
      const data = JSON.parse(e.data) as ExecutionInfo
      closeTaskSSE(task)
      stopTaskTick(task)
      task.executionStatus = data.status
      task.totalTokens = data.totalTokens || 0
      task.isPhaseRunning = false
      if (data.status === 'completed') {
        const firstTimeDone = task.currentStep !== 'done'
        task.currentStep = 'done'
        if (firstTimeDone) task.floatingDismissed = false
        for (const meta of INGEST_STEPS) {
          autoAdvanceStep(task, meta.name, 'completed')
        }
        resolveAffectedPageIds(task)
        scheduleCleanup(task)
      } else if (data.status === 'cancelled') {
        task.pipelineError = data.errorMessage || '任务已终止'
        task.isPhaseRunning = false
        if (task.pendingCloseConfirm) {
          task.pendingCloseConfirm = false
        } else {
          task.currentStep = 'upload'
          removeTask(task.executionId)
        }
      } else if (data.status === 'budget_exhausted') {
        task.pipelineError = 'Token 用量已达月度参考值，操作不受限制'
        const firstTimeDone = task.currentStep !== 'done'
        task.currentStep = 'done'
        if (firstTimeDone) task.floatingDismissed = false
        scheduleCleanup(task)
      } else if (data.status === 'failed') {
        task.pipelineError = data.errorMessage || '处理失败，请重试'
        task.isPhaseRunning = false
        scheduleCleanup(task)
      } else {
        task.pipelineError = '处理失败，请重试'
        task.isPhaseRunning = false
      }
    })

    es.addEventListener('pause', () => {
      task.lastEventMs = Date.now()
      task.executionStatus = 'paused'
      task.isPhaseRunning = false
      task.currentStep = 'paused'
      stopTaskTick(task)
      // Keep SSE alive for future resume — don't close emitter
    })

    es.addEventListener('step_progress', (e: MessageEvent) => {
      task.lastEventMs = Date.now()
      const data = JSON.parse(e.data) as StepProgressEvent
      const existing = task.stepStates.find(s => s.name === data.stepName)
      if (existing) {
        existing.current = data.current
        existing.total = data.total
        existing.avgMsPerUnit = data.avgMsPerUnit
      }
      if (data.chunkIndex != null && data.chunkPreview) {
        const entities = data.chunkPreview.split(',').map(s => s.trim()).filter(Boolean)
        if (entities.length > 0) {
          const existingPreview = task.chunkPreviews.find(p => p.chunkIndex === data.chunkIndex)
          if (!existingPreview) {
            task.chunkPreviews.push({ chunkIndex: data.chunkIndex, entities })
          }
        }
      }
    })

    es.onerror = () => {
      closeTaskSSE(task)
      if (task.currentStep === 'done' || task.currentStep === 'upload' || task.currentStep === 'review') {
        stopTaskTick(task)
        return
      }
      if (!task.hasReceivedEvent) {
        const authStore = useAuthStore()
        authStore.clearAuth()
        stopTaskTick(task)
        task.pipelineError = '登录已过期，请重新登录'
        task.isPhaseRunning = false
        const router = useRouter()
        router.push('/login')
        return
      }
      if (task.retryCount >= MAX_SSE_RETRIES) {
        stopTaskTick(task)
        task.pipelineError = '连接中断，请刷新页面重试'
        task.isPhaseRunning = false
        return
      }
      retryConnectSSE(task)
    }
  }

  function clearRetryTimer(task: IngestTask) {
    if (task.retryTimer) {
      clearTimeout(task.retryTimer)
      task.retryTimer = null
    }
  }

  function retryConnectSSE(task: IngestTask) {
    clearRetryTimer(task)
    task.retryCount++
    const delay = SSE_BASE_RETRY_MS * Math.pow(2, task.retryCount - 1)
    task.retryTimer = window.setTimeout(() => {
      task.retryTimer = null
      if (!task.eventSource && tasks.value.has(task.executionId)) {
        const authStore = useAuthStore()
        if (!authStore.token) {
          task.pipelineError = '登录已过期，请重新登录'
          task.isPhaseRunning = false
          stopTaskTick(task)
          const router = useRouter()
          router.push('/login')
          return
        }
        connectTaskSSE(task)
        startTaskTick(task)
      }
    }, delay)
  }

  function removeTask(id: number) {
    const task = tasks.value.get(id)
    if (task) {
      closeTaskSSE(task)
      stopTaskTick(task)
      clearRetryTimer(task)
      cancelCleanup(task)
    }
    tasks.value.delete(id)
    if (activeTaskId.value === id) {
      const remaining = Array.from(tasks.value.keys())
      activeTaskId.value = remaining.length > 0 ? remaining[remaining.length - 1] : null
    }
  }

  async function resolveAffectedPageIds(task: IngestTask) {
    if (task.affectedPages.length === 0 && task.entityPages.length === 0 && task.chapterPages.length === 0) return
    try {
      const pages: WikiPageInfo[] = await listPages()
      for (const ap of task.affectedPages) {
        if (ap.id != null) continue
        const match = pages.find(p => p.path === ap.path || p.title === ap.title)
        if (match) ap.id = match.id
      }
      for (const ep of task.entityPages) {
        if (ep.id != null) continue
        const match = pages.find(p => p.path === ep.path || p.title === ep.title)
        if (match) ep.id = match.id
      }
      for (const cp of task.chapterPages) {
        if (cp.id != null) continue
        const match = pages.find(p => p.path === cp.path || p.title === cp.title)
        if (match) cp.id = match.id
      }
    } catch (e) {
      console.error('Failed to resolve affected page IDs:', e)
    }
  }

  async function recoverActiveTasks(scopeId: number) {
    if (initializing.value) return
    initializing.value = true
    try {
      const activeList: ExecutionInfo[] = await listActiveIngest(scopeId)
      const activeIdSet = new Set(activeList.map(e => e.executionId))

      for (const [id, existingTask] of tasks.value.entries()) {
        if (activeIdSet.has(id)) continue
        if (existingTask.currentStep === 'upload' && existingTask.executionId === 0) continue
        closeTaskSSE(existingTask)
        stopTaskTick(existingTask)
        clearRetryTimer(existingTask)
        tasks.value.delete(id)
      }

      for (const execInfo of activeList) {
        let task = tasks.value.get(execInfo.executionId)
        if (!task) {
          const plain = createEmptyTask()
          plain.executionId = execInfo.executionId
          plain.executionStatus = execInfo.status
          plain.totalTokens = execInfo.totalTokens || 0
          plain.sourceId = execInfo.sourceId || null
          if (execInfo.sourceId && execInfo.sourceName) {
            plain.uploadedFile = {
              id: execInfo.sourceId,
              name: execInfo.sourceName,
              size: 0,
              format: '',
            }
          }
          tasks.value.set(execInfo.executionId, plain)
          task = tasks.value.get(execInfo.executionId)!
        }

        if (execInfo.status === 'running') {
          task.currentStep = isPhase1Completed(task) ? 'executing' : 'analyzing'
          task.isPhaseRunning = true
          connectTaskSSE(task)
          startTaskTick(task)
        } else if (execInfo.status === 'pending') {
          task.currentStep = 'upload'
          task.isPhaseRunning = false
        } else if (execInfo.status === 'completed' || execInfo.status === 'budget_exhausted') {
          task.executionStatus = execInfo.status
          task.totalTokens = execInfo.totalTokens || 0
          task.isPhaseRunning = false
          task.currentStep = 'done'
          task.floatingDismissed = true
          if (execInfo.status === 'budget_exhausted') {
            task.pipelineError = 'Token 用量已达月度参考值，操作不受限制'
          }
          if (execInfo.sourceId && execInfo.sourceName && !task.uploadedFile) {
            task.uploadedFile = {
              id: execInfo.sourceId,
              name: execInfo.sourceName,
              size: 0,
              format: '',
            }
          }
          for (const meta of INGEST_STEPS) {
            autoAdvanceStep(task, meta.name, 'completed')
          }
          if (task.entityPages.length === 0 && task.chapterPages.length === 0 && task.affectedPages.length === 0 && !task.summaryPage) {
            connectTaskSSE(task)
          } else {
            scheduleCleanup(task)
          }
        } else if (execInfo.status === 'failed') {
          task.pipelineError = execInfo.errorMessage || '处理失败，请重试'
          task.isPhaseRunning = false
        } else if (execInfo.status === 'awaiting_confirmation' || execInfo.status === 'awaiting_review') {
          task.executionStatus = execInfo.status
          task.currentStep = 'review'
          task.isPhaseRunning = false
          connectTaskSSE(task)
        } else if (execInfo.status === 'paused') {
          task.executionStatus = 'paused'
          task.currentStep = 'paused'
          task.isPhaseRunning = false
        }
      }

      if (activeTaskId.value == null) {
        const runningTask = activeList.find(e => e.status === 'running')
        const reviewTask = activeList.find(e => e.status === 'awaiting_confirmation' || e.status === 'awaiting_review')
        const completedTask = activeList.find(e => e.status === 'completed' || e.status === 'budget_exhausted')
        const pendingTask = activeList.find(e => e.status === 'pending')
        const target = runningTask || reviewTask || completedTask || pendingTask
        if (target) setActiveTask(target.executionId)
      } else {
        const current = tasks.value.get(activeTaskId.value)
        if (!current || current.currentStep === 'upload' && current.executionId === 0) {
          const runningTask = activeList.find(e => e.status === 'running')
          const reviewTask = activeList.find(e => e.status === 'awaiting_confirmation' || e.status === 'awaiting_review')
          const completedTask = activeList.find(e => e.status === 'completed' || e.status === 'budget_exhausted')
          const pendingTask = activeList.find(e => e.status === 'pending')
          const target = runningTask || reviewTask || completedTask || pendingTask
          if (target) setActiveTask(target.executionId)
        }
      }
    } catch (e) {
      console.error('Failed to recover active tasks:', e)
    } finally {
      initializing.value = false
    }
  }

  function clear() {
    for (const task of tasks.value.values()) {
      closeTaskSSE(task)
      stopTaskTick(task)
      clearRetryTimer(task)
    }
    tasks.value.clear()
    activeTaskId.value = null
    pendingUploadedFile.value = null
    pendingUserGuidance.value = ''
  }

  async function startAnalysis(scopeId: number) {
    const fileToUpload = pendingUploadedFile.value || uploadedFile.value
    if (!fileToUpload) return
    const guidanceToUse = pendingUserGuidance.value || userGuidance.value
    const task = createEmptyTask()
    task.uploadedFile = fileToUpload
    task.userGuidance = guidanceToUse
    task.isPhaseRunning = true
    task.stepStates = []
    task.nowMs = Date.now()
    task.currentStep = 'analyzing'

    pendingUploadedFile.value = null
    pendingUserGuidance.value = ''

    try {
      const execution = await analyzeIngest(scopeId, fileToUpload.id, guidanceToUse || undefined)
      task.executionId = execution.executionId
      task.executionStatus = execution.status
      task.baselineProfile = execution.baselineProfile || {}
      tasks.value.set(execution.executionId, task)
      const reactiveTask = tasks.value.get(execution.executionId)!
      if (activeTaskId.value == null || tasks.value.get(activeTaskId.value)?.currentStep === 'upload') {
        setActiveTask(execution.executionId)
      }
      startTaskTick(reactiveTask)
      connectTaskSSE(reactiveTask)
    } catch (e: any) {
      task.pipelineError = e.message || '启动分析失败'
      task.isPhaseRunning = false
      task.currentStep = 'upload'
      tasks.value.set(task.executionId, task)
      if (activeTaskId.value == null) {
        setActiveTask(task.executionId)
      }
    }
  }

  async function resumeIngestExecution() {
    const task = currentTask.value
    if (!task || !task.executionId) return
    closeTaskSSE(task)
    clearRetryTimer(task)
    task.pipelineError = ''
    task.isPhaseRunning = true
    task.nowMs = Date.now()
    task.retryCount = 0
    task.lastEventMs = Date.now()
    task.hasReceivedEvent = false
    task.chunkPreviews = []

    const phase1Completed = isPhase1Completed(task)

    for (const s of task.stepStates) {
      if (s.status === 'failed' || s.status === 'paused' || s.status === 'running') {
        s.status = 'pending'
        s.startedAtMs = undefined
        s.completedAtMs = undefined
        s.current = undefined
        s.total = undefined
        s.avgMsPerUnit = undefined
      }
    }

    task.currentStep = phase1Completed ? 'executing' : 'analyzing'
    startTaskTick(task)

    try {
      const execution = await resumeIngest(task.executionId, task.userGuidance || undefined)
      task.executionStatus = execution.status
      task.baselineProfile = execution.baselineProfile || task.baselineProfile
      connectTaskSSE(task)
    } catch (e: any) {
      task.pipelineError = e.message || '断点续传失败'
      task.isPhaseRunning = false
    }
  }

  async function confirmExecution(guidance?: string) {
    const task = currentTask.value
    if (!task || !task.executionId) return
    if (guidance != null) task.userGuidance = guidance
    task.pipelineError = ''
    task.isPhaseRunning = true
    task.nowMs = Date.now()
    task.retryCount = 0
    task.lastEventMs = Date.now()
    task.hasReceivedEvent = false
    task.currentStep = 'executing'
    startTaskTick(task)
    try {
      await executeIngest(task.executionId, task.userGuidance || undefined)
      task.executionStatus = 'running'
      if (!task.eventSource) connectTaskSSE(task)
    } catch (e: any) {
      task.pipelineError = e.message || '启动写入失败'
      task.isPhaseRunning = false
      task.currentStep = 'review'
      stopTaskTick(task)
    }
  }

  async function requestReanalysis(guidance?: string) {
    const task = currentTask.value
    if (!task || !task.executionId) return
    if (guidance != null) task.userGuidance = guidance
    task.pipelineError = ''
    task.isPhaseRunning = true
    task.nowMs = Date.now()
    task.retryCount = 0
    task.lastEventMs = Date.now()
    task.hasReceivedEvent = false
    task.chunkPreviews = []
    for (const s of task.stepStates) {
      if (s.name === 'ANALYZE') {
        s.status = 'pending'
        s.startedAtMs = undefined
        s.completedAtMs = undefined
        s.current = undefined
        s.total = undefined
        s.avgMsPerUnit = undefined
      }
    }
    task.currentStep = 'analyzing'
    startTaskTick(task)
    try {
      await reanalyzeIngest(task.executionId, task.userGuidance || undefined)
      task.executionStatus = 'running'
      if (!task.eventSource) connectTaskSSE(task)
    } catch (e: any) {
      task.pipelineError = e.message || '重新分析失败'
      task.isPhaseRunning = false
      task.currentStep = 'review'
      stopTaskTick(task)
    }
  }

  async function pauseExecution() {
    const task = currentTask.value
    if (!task || !task.executionId) return
    try {
      await pauseIngest(task.executionId)
      task.executionStatus = 'paused'
      task.isPhaseRunning = false
      task.currentStep = 'paused'
      stopTaskTick(task)
    } catch (e: any) {
      task.pipelineError = e.message || '暂停失败'
    }
  }

  async function deleteExecution(executionId: number) {
    try {
      await deleteIngest(executionId)
    } catch (e: any) {
      console.error('删除任务失败:', e.message)
    }
    removeTask(executionId)
  }

  async function cancelExecution() {
    const task = currentTask.value
    if (!task || !task.executionId) return
    try {
      await cancelIngest(task.executionId)
    } catch (e: any) {
      task.pipelineError = e.message || '取消失败'
    }
    removeTask(task.executionId)
  }

  async function cancelTaskForClose(executionId: number) {
    const task = tasks.value.get(executionId)
    if (!task) return
    task.pendingCloseConfirm = true
    task.isPhaseRunning = false
    try {
      await cancelIngest(executionId)
    } catch (e: any) {
      task.pipelineError = e.message || '终止失败'
      task.pendingCloseConfirm = false
    }
  }

  function goBackToUpload() {
    const task = currentTask.value
    if (!task) return
    closeTaskSSE(task)
    stopTaskTick(task)
    clearRetryTimer(task)
    task.isPhaseRunning = false
    task.pipelineError = ''
    task.stepStates = []
    task.displayProgress = 0
    task.aiAnalysis = ''
    task.metadataRaw = null
    task.affectedPages = []
    task.entityPages = []
    task.chapterPages = []
    task.chunkPreviews = []
    task.summaryPage = null
    task.currentStep = 'upload'
    task.floatingDismissed = false
    task.retryCount = 0
  }

  function startNewSource() {
    for (const task of [...tasks.value.values()]) {
      if (task.currentStep === 'upload' || task.currentStep === 'done') {
        closeTaskSSE(task)
        stopTaskTick(task)
        clearRetryTimer(task)
        tasks.value.delete(task.executionId)
      }
    }
    activeTaskId.value = null
    pendingUploadedFile.value = null
    pendingUserGuidance.value = ''
  }

  function dismissFloating() {
    const task = currentTask.value
    if (task) task.floatingDismissed = true
  }

  function dismissFloatingById(executionId: number) {
    const task = tasks.value.get(executionId)
    if (task) task.floatingDismissed = true
  }

  function dismissAllFloating() {
    for (const task of tasks.value.values()) {
      if (task.currentStep === 'done' || task.executionStatus === 'failed'
          || task.executionStatus === 'budget_exhausted' || task.executionStatus === 'cancelled'
          || task.executionStatus === 'paused') {
        task.floatingDismissed = true
      }
    }
  }

  function closeEventSource() {
    const task = currentTask.value
    if (task) closeTaskSSE(task)
  }

  return {
    active,
    phase,
    progress,
    remainingMs,
    currentTip,
    errorMessage,
    sourceName,
    executionId,
    visible,
    currentStep,
    stepStates,
    executionStatus,
    aiAnalysis,
    metadataRaw,
    affectedPages,
    entityPages,
    chapterPages,
    uploadedFile,
    userGuidance,
    pipelineError,
    totalTokens,
    displayProgress,
    baselineProfile,
    hasScanWarning,
    scanWarningMessage,
    isPhaseRunning,
    includeParsePhase,
    rawProgress,
    effectiveProgress,
    computedRemainingMs,
    computedCurrentTip,
    nowMs,
    progressBarStatus,
    stageErrorKey,
    chunkPreviews,
    floatingDismissed,
    runningTaskCount,
    allTaskSummaries,
    tasks,
    activeTaskId,
    pendingUploadedFile,
    pendingUserGuidance,
    schemaViolation,
    complianceSummary,
    summaryPage,
    dismissFloating,
    dismissFloatingById,
    dismissAllFloating,
    clear,
    startAnalysis,
    pauseExecution,
    deleteExecution,
    cancelExecution,
    resumeIngestExecution,
    confirmExecution,
    requestReanalysis,
    goBackToUpload,
    startNewSource,
    closeEventSource,
    recoverActiveTasks,
    setActiveTask,
    removeTask,
    cancelTaskForClose,
  }
})
