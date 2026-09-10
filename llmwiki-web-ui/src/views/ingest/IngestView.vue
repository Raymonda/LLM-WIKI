<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter, useRoute } from 'vue-router'
import { uploadSource, listSources, deleteSource, type SourceInfo, type DuplicateInfo } from '@/api/source'
import { useAuthStore } from '@/stores/auth'
import {
  Upload, FileText, CheckCircle, ChevronRight,
  Loader2, AlertTriangle, Trash2, ArrowRight, Sparkles,
  Search, FilePlus, FileEdit, XCircle, RotateCcw, AlertCircle, X,
  PauseCircle, PlayCircle, ChevronDown, GitBranch, ClipboardCheck,
  Layers
} from 'lucide-vue-next'
import IngestProgressBar from './components/IngestProgressBar.vue'
import IngestStageNav, { type StageItem } from './components/IngestStageNav.vue'
import IngestStepTimeline from './components/IngestStepTimeline.vue'
import EntityDiscoveryWall from './components/EntityDiscoveryWall.vue'
import AnalysisSummaryPanel from './components/AnalysisSummaryPanel.vue'
import BatchOverviewPanel from './components/BatchOverviewPanel.vue'
import ReviewInbox from './components/ReviewInbox.vue'
import { useIngestProgressStore } from '@/stores/ingestProgress'
import { useIngestBatchStore } from '@/stores/ingestBatch'
import { useConfirmDialog } from '@/composables/useConfirmDialog'

const { state: confirmState, showConfirm, onConfirm, onCancel, ConfirmDialog } = useConfirmDialog()

const { t } = useI18n()
const router = useRouter()
const authStore = useAuthStore()
const route = useRoute()
const store = useIngestProgressStore()
const batchStore = useIngestBatchStore()

const uploadError = ref('')
const isUploading = ref(false)
const existingSources = ref<SourceInfo[]>([])
const loadingSources = ref(false)
const duplicateWarning = ref<DuplicateInfo | null>(null)
const batchPendingSources = ref<Array<{ id: number; name: string; size: number; format: string }>>([])
const batchUploadFailures = ref<string[]>([])
const batchDuplicateNames = ref<string[]>([])
const batchGuidance = ref('')
const batchBusy = ref(false)
const batchError = ref('')

const currentStep = computed(() => store.currentStep)
const uploadedFile = computed(() => store.uploadedFile)
const batchInbox = computed(() => batchStore.inbox)
const batchMode = computed(() => batchStore.selectedBatchId != null)
const selectedBatchInfo = computed(
  () => batchStore.inbox.find(b => b.batchId === batchStore.selectedBatchId) ?? null,
)
const batchItems = computed(() => batchStore.currentBatch?.items ?? [])
const userGuidance = computed({
  get: () => store.userGuidance,
  set: (v: string) => {
    if (store.activeTaskId != null) {
      const task = store.tasks.get(store.activeTaskId)
      if (task) task.userGuidance = v
    } else {
      store.pendingUserGuidance = v
    }
  },
})
const stepStates = computed(() => store.stepStates)
const pipelineError = computed(() => store.pipelineError)
const isPhaseRunning = computed(() => store.isPhaseRunning)
const affectedPages = computed(() => store.affectedPages)
const entityPages = computed(() => store.entityPages)
const chapterPages = computed(() => store.chapterPages)
const updatedAffectedPages = computed(() => affectedPages.value.filter(p => p.action === '更新' || p.action === '补充'))
const newEntities = computed(() => entityPages.value.filter(p => p.action === '新建'))
const supplementedEntities = computed(() => entityPages.value.filter(p => p.action === '补充'))
const pendingEntities = computed(() => entityPages.value.filter(p => !['新建', '补充', '更新'].includes(p.action)))
const hasScanWarning = computed(() => store.hasScanWarning)
const scanWarningMessage = computed(() => store.scanWarningMessage)
const effectiveProgress = computed(() => store.effectiveProgress)
const computedRemainingMs = computed(() => store.computedRemainingMs)
const remainingMs = computedRemainingMs
const progressBarStatus = computed(() => store.progressBarStatus)
const stageErrorKey = computed(() => store.stageErrorKey)
const currentTip = computed(() => store.computedCurrentTip)
const includeParsePhase = computed(() => store.includeParsePhase)
const totalTokens = computed(() => store.totalTokens)
const nowMs = computed(() => store.nowMs)
const chunkPreviews = computed(() => store.chunkPreviews)
const aiAnalysis = computed(() => store.aiAnalysis)
const metadataRaw = computed(() => store.metadataRaw)
const allTaskSummaries = computed(() => store.allTaskSummaries)
const activeTaskId = computed(() => store.activeTaskId)
const setActiveTask = store.setActiveTask
const complianceSummary = computed(() => store.complianceSummary)
const schemaViolation = computed(() => store.schemaViolation)
const summaryPage = computed(() => store.summaryPage)
const activeTask = computed(() => store.activeTaskId != null ? store.tasks.get(store.activeTaskId) : null)
const conflictCount = computed(() => activeTask.value?.conflictCount ?? 0)
const conflictRouteSummary = computed(() => activeTask.value?.conflictRouteSummary ?? null)
const conflictSummaryText = computed(() => {
  const s = conflictRouteSummary.value
  if (!s) return t('ingest.conflictDefault')
  const parts: string[] = []
  if (s.auto > 0) parts.push(t('ingest.conflictAuto', [s.auto]))
  if (s.review > 0) parts.push(t('ingest.conflictReview', [s.review]))
  if (s.deferred > 0) parts.push(t('ingest.conflictDeferred', [s.deferred]))
  return parts.join(t('common.commaSeparator')) || t('ingest.conflictFallback')
})

function openPatchDrawerToConflict() {
  window.dispatchEvent(new CustomEvent('open-patch-drawer', { detail: { section: 'conflict' } }))
}

function openPatchDrawer() {
  window.dispatchEvent(new CustomEvent('open-patch-drawer'))
}
const qualityCritical = computed(() => activeTask.value?.qualityCritical ?? 0)
const qualityWarnings = computed(() => activeTask.value?.qualityWarnings ?? 0)
const schemaGaps = computed(() => activeTask.value?.schemaGaps ?? [])

interface ParsedViolation {
  severity: string
  type: string
  description: string
  suggestion?: string
  severityLabel: string
  typeLabel: string
}

const severityLabelKeyMap: Record<string, string> = { HIGH: 'ingest.severityHigh', MEDIUM: 'ingest.severityMedium', LOW: 'ingest.severityLow' }
const violationTypeLabelKeyMap: Record<string, string> = {
  CATEGORY: 'ingest.typeCategory',
  PAGE_STRUCTURE: 'ingest.typePageStructure',
  NAMING: 'ingest.typeNaming',
}

const expandedGroups = ref<Set<string>>(new Set())

function toggleGroup(type: string) {
  if (expandedGroups.value.has(type)) {
    expandedGroups.value.delete(type)
  } else {
    expandedGroups.value.add(type)
  }
}

const severityBadgeClass = (severity: string) => {
  switch (severity) {
    case 'HIGH': return 'ingest-view__severity--high'
    case 'MEDIUM': return 'ingest-view__severity--medium'
    default: return 'ingest-view__severity--low'
  }
}

const parsedViolations = computed<ParsedViolation[]>(() => {
  if (!schemaViolation.value?.complianceViolations) return []
  const text = schemaViolation.value.complianceViolations
  const violations: ParsedViolation[] = []
  const lines = text.split('\n')
  for (const line of lines) {
    const match = line.match(/-\s*\[(HIGH|MEDIUM|LOW)\]\s*(\w+):\s*(.+)/)
    if (match) {
      const rawDesc = match[3].trim()
      const pipeIdx = rawDesc.lastIndexOf(' | ')
      const description = pipeIdx >= 0 ? rawDesc.substring(0, pipeIdx).trim() : rawDesc
      const suggestion = pipeIdx >= 0 ? rawDesc.substring(pipeIdx + 3).trim() : undefined
      violations.push({
        severity: match[1],
        type: match[2],
        description,
        suggestion,
        severityLabel: t(severityLabelKeyMap[match[1]]) || match[1],
        typeLabel: t(violationTypeLabelKeyMap[match[2]]) || match[2],
      })
    }
  }
  return violations
})

const stages = computed<StageItem[]>(() => [
  { key: 'upload', label: t('ingest.stageUpload'), icon: Upload, hint: t('ingest.stageUploadHint') },
  { key: 'analyzing', label: t('ingest.stageAnalyzing'), icon: Search, hint: t('ingest.stageAnalyzingHint') },
  { key: 'review', label: t('ingest.stageReview'), icon: ClipboardCheck, hint: t('ingest.stageReviewHint') },
  { key: 'executing', label: t('ingest.stageExecuting'), icon: FilePlus, hint: t('ingest.stageExecutingHint') },
  { key: 'done', label: t('ingest.stageDone'), icon: CheckCircle },
])

const stageNavKey = computed(() => {
  if (currentStep.value === 'paused') {
    return stepStates.value.some(s => s.name === 'ANALYZE' && s.status === 'completed') ? 'executing' : 'analyzing'
  }
  return currentStep.value
})

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function actionIcon(action: string) {
  if (action === '新建') return FilePlus
  if (action === '补充') return FileEdit
  return FileText
}

const actionLabelKeyMap: Record<string, string> = {
  '新建': 'ingest.actionNew',
  '补充': 'ingest.actionSupplement',
  '更新': 'ingest.actionUpdate',
  '待定': 'ingest.actionPending',
}

function actionLabel(action: string): string {
  return t(actionLabelKeyMap[action] || 'ingest.actionPending')
}

async function handleFileUpload(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  if (files.length === 0) return
  isUploading.value = true
  uploadError.value = ''
  duplicateWarning.value = null
  batchUploadFailures.value = []
  batchDuplicateNames.value = []
  const uploaded: Array<{ id: number; name: string; size: number; format: string }> = []
  try {
    for (const file of files) {
      try {
        const result = await uploadSource(file)
        uploaded.push({ id: result.id, name: result.name, size: result.size, format: result.format })
        if (result.duplicateInfo) {
          if (files.length === 1) {
            duplicateWarning.value = result.duplicateInfo
          } else {
            batchDuplicateNames.value.push(result.name)
          }
        }
      } catch (e: any) {
        if (files.length === 1) {
          uploadError.value = e.message || t('ingest.uploadFailed')
        } else {
          batchUploadFailures.value.push(file.name)
        }
      }
    }
    if (files.length === 1 && uploaded.length === 1) {
      store.pendingUploadedFile = uploaded[0]
    } else if (uploaded.length > 0) {
      batchPendingSources.value = uploaded
    }
    await loadExistingSources()
  } finally {
    isUploading.value = false
    input.value = ''
  }
}

async function loadExistingSources() {
  loadingSources.value = true
  try {
    existingSources.value = await listSources()
  } catch (e) {
    console.error('Failed to load sources:', e)
  } finally {
    loadingSources.value = false
  }
}

async function handleDeleteSource(id: number) {
  try {
    await deleteSource(id)
    existingSources.value = existingSources.value.filter(s => s.id !== id)
  } catch (e) {
    console.error('Failed to delete source:', e)
  }
}

async function startAnalysis() {
  if (duplicateWarning.value) {
    const confirmed = await showConfirm({
      title: t('ingest.duplicateTitle'),
      message: t('ingest.duplicateMessage', [duplicateWarning.value.existingSourceName]),
      confirmText: t('ingest.continueProcess'),
      cancelText: t('ingest.cancel'),
      type: 'warning',
    })
    if (confirmed) {
      duplicateWarning.value = null
      await store.startAnalysis(authStore.scopeId)
    }
  } else {
    await store.startAnalysis(authStore.scopeId)
  }
}

async function handlePauseExecution() {
  await store.pauseExecution()
}

async function handleCloseTask(task: { executionId: number; isPhaseRunning: boolean; status: string }) {
  if (task.isPhaseRunning) {
    await store.pauseExecution()
    const confirmed = await showConfirm({
      title: t('ingest.deleteConfirmTitle'),
      message: t('ingest.deleteRunningMsg'),
      confirmText: t('ingest.deleteBtn'),
      cancelText: t('ingest.keepPaused'),
      type: 'danger',
      confirmVariant: 'danger',
    })
    if (confirmed) {
      store.deleteExecution(task.executionId)
    }
  } else {
    const confirmed = await showConfirm({
      title: t('ingest.deleteConfirmTitle'),
      message: t('ingest.deleteMsg'),
      confirmText: t('ingest.deleteBtn'),
      cancelText: t('ingest.cancel'),
      type: 'warning',
      confirmVariant: 'danger',
    })
    if (confirmed) {
      store.deleteExecution(task.executionId)
    }
  }
}

async function handleResumeIngest() {
  await store.resumeIngestExecution()
}

async function handleConfirmWrite() {
  await store.confirmExecution()
}

async function handleReanalysis() {
  await store.requestReanalysis()
}

function handleNewSource() {
  store.startNewSource()
}

function handleBackToWiki() {
  store.dismissFloating()
  router.push('/')
}

async function openBatch(batchId: number) {
  await batchStore.selectBatch(batchId)
  router.replace({ path: '/ingest', query: { batch: String(batchId) } })
}

function exitBatch() {
  batchStore.selectBatch(null)
  router.replace({ path: '/ingest' })
}

async function runBatchAction(action: () => Promise<unknown>) {
  batchBusy.value = true
  batchError.value = ''
  try {
    await action()
  } catch (e: any) {
    batchError.value = e.message || t('common.operationFailed')
  } finally {
    batchBusy.value = false
  }
}

async function handleBatchConfirmAll() {
  const batch = selectedBatchInfo.value
  if (!batch) return
  const confirmed = await showConfirm({
    title: t('ingest.batchConfirmAll'),
    message: t('ingest.batchConfirmAllMessage', [batch.awaitingCount]),
    confirmText: t('ingest.batchConfirmAll'),
    cancelText: t('ingest.cancel'),
    type: 'warning',
  })
  if (!confirmed) return
  await runBatchAction(() => batchStore.confirmAll(batch.batchId))
}

async function handleBatchPause() {
  const batchId = batchStore.selectedBatchId
  if (batchId == null) return
  await runBatchAction(() => batchStore.pause(batchId))
}

async function handleBatchResume() {
  const batchId = batchStore.selectedBatchId
  if (batchId == null) return
  await runBatchAction(() => batchStore.resume(batchId))
}

async function handleBatchCancel() {
  const batchId = batchStore.selectedBatchId
  if (batchId == null) return
  const confirmed = await showConfirm({
    title: t('ingest.batchCancelBatch'),
    message: t('ingest.batchCancelMessage'),
    confirmText: t('ingest.batchCancelBatch'),
    cancelText: t('ingest.cancel'),
    type: 'danger',
    confirmVariant: 'danger',
  })
  if (!confirmed) return
  await runBatchAction(() => batchStore.cancel(batchId))
}

async function handleItemConfirm(executionId: number, guidance?: string) {
  await runBatchAction(() => batchStore.confirmOne(executionId, guidance))
}

async function handleItemReanalyze(executionId: number, guidance?: string) {
  await runBatchAction(() => batchStore.reanalyzeOne(executionId, guidance))
}

async function handleItemRetry(executionId: number) {
  await runBatchAction(() => batchStore.retryOne(executionId))
}

async function createBatchFromPending() {
  if (batchPendingSources.value.length === 0) return
  await runBatchAction(async () => {
    const response = await batchStore.createBatchAndStart(
      authStore.scopeId,
      batchPendingSources.value.map(s => s.id),
      batchGuidance.value || undefined,
    )
    batchPendingSources.value = []
    batchGuidance.value = ''
    await openBatch(response.batchId)
  })
}

onMounted(async () => {
  await loadExistingSources()
  await batchStore.refreshInbox().catch((e) => console.error('Failed to refresh batch inbox:', e))
  const batchId = Number(route.query.batch)
  if (Number.isFinite(batchId) && batchId > 0) {
    await openBatch(batchId)
  }
})
</script>

<template>
  <div class="ingest-view">
    <h1 class="ingest-view__title">{{ t('ingest.title') }}</h1>
    <p class="ingest-view__subtitle">{{ t('ingest.subtitle') }}</p>

    <div v-if="!batchMode && (allTaskSummaries.length > 0 || activeTaskId === null)" class="ingest-view__task-tabs">
      <button
        v-for="task in allTaskSummaries"
        :key="task.executionId"
        :class="['ingest-view__task-tab', { 'ingest-view__task-tab--active': task.executionId === activeTaskId, 'ingest-view__task-tab--cancelled': task.status === 'cancelled' }]"
        @click="setActiveTask(task.executionId)"
      >
        <Loader2 v-if="task.isPhaseRunning" :size="12" class="ingest-view__stepper-spin" />
        <AlertTriangle v-else-if="task.status === 'failed' || task.status === 'budget_exhausted'" :size="12" style="color: var(--error)" />
        <XCircle v-else-if="task.status === 'cancelled'" :size="12" style="color: var(--text-tertiary)" />
        <PauseCircle v-else-if="task.status === 'paused'" :size="12" style="color: var(--warning)" />
        <CheckCircle v-else-if="task.currentStep === 'done'" :size="12" style="color: var(--success)" />
        <ClipboardCheck v-else-if="task.currentStep === 'review'" :size="12" style="color: var(--accent-primary)" />
        <FileText v-else :size="12" />
        <span>{{ task.sourceName || t('ingest.materialFallback', [task.executionId]) }}</span>
        <span v-if="task.isPhaseRunning" class="ingest-view__task-tab-progress">{{ Math.round(task.progress * 100) }}%</span>
        <span class="ingest-view__task-tab-close" @click.stop="handleCloseTask(task)" :title="t('ingest.closeTask')" role="button">
          <X :size="10" />
        </span>
      </button>
      <button class="ingest-view__task-tab ingest-view__task-tab--new" @click="handleNewSource">
        <Upload :size="12" />
        <span>{{ t('ingest.newTask') }}</span>
      </button>
    </div>

    <IngestStageNav
      v-if="!batchMode"
      class="ingest-view__stage-nav"
      :stages="stages"
      :current-key="stageNavKey"
      :error-key="stageErrorKey"
    />

    <IngestProgressBar
      v-if="!batchMode && currentStep !== 'upload'"
      class="ingest-view__progress"
      :progress="effectiveProgress"
      :remaining-ms="remainingMs"
      :status="progressBarStatus"
    />

    <div v-if="batchInbox.length > 0" class="ingest-view__batch-bar">
      <button
        v-for="batch in batchInbox"
        :key="batch.batchId"
        :class="['ingest-view__batch-chip', { 'ingest-view__batch-chip--active': batch.batchId === batchStore.selectedBatchId }]"
        @click="openBatch(batch.batchId)"
      >
        <Layers :size="12" />
        <span>{{ t('ingest.batchSelectLabel') }} #{{ batch.batchId }}</span>
        <span v-if="batch.awaitingCount > 0" class="ingest-view__batch-chip-badge">{{ batch.awaitingCount }}</span>
      </button>
      <button v-if="batchMode" class="ingest-view__btn-ghost" @click="exitBatch">
        {{ t('ingest.batchExitView') }}
      </button>
    </div>

    <div v-if="batchMode" class="ingest-view__content">
      <div v-if="batchError" class="ingest-view__error-banner">
        <AlertTriangle :size="16" />
        {{ batchError }}
      </div>

      <BatchOverviewPanel
        :batch="selectedBatchInfo"
        :busy="batchBusy"
        @confirm-all="handleBatchConfirmAll"
        @pause="handleBatchPause"
        @resume="handleBatchResume"
        @cancel="handleBatchCancel"
      />

      <ReviewInbox
        :items="batchItems"
        :busy="batchBusy"
        @confirm="handleItemConfirm"
        @reanalyze="handleItemReanalyze"
        @retry="handleItemRetry"
      />
    </div>

    <div v-else class="ingest-view__content">
      <!-- 上传 -->
      <div v-if="currentStep === 'upload'" class="ingest-view__panel">
        <h2 class="ingest-view__panel-title">{{ t('ingest.uploadPanelTitle') }}</h2>
        <p class="ingest-view__panel-hint">{{ t('ingest.uploadPanelHint') }}</p>

        <div v-if="uploadError" class="ingest-view__error-banner">
          <AlertTriangle :size="16" />
          {{ uploadError }}
        </div>

        <div v-if="uploadedFile" class="ingest-view__uploaded-file">
          <FileText :size="20" />
          <div class="ingest-view__uploaded-info">
            <span class="ingest-view__uploaded-name">{{ uploadedFile.name }}</span>
            <span class="ingest-view__uploaded-meta">{{ formatSize(uploadedFile.size) }} · {{ uploadedFile.format }}</span>
          </div>
          <CheckCircle :size="16" class="ingest-view__uploaded-check" />
        </div>

        <div v-if="duplicateWarning" class="ingest-view__duplicate-warning">
          <AlertTriangle :size="16" />
          <span>{{ duplicateWarning.message }}</span>
          <button class="ingest-view__duplicate-dismiss" @click="duplicateWarning = null" :title="t('ingest.dismissWarning')">
            <X :size="14" />
          </button>
        </div>
        <label v-else class="ingest-view__upload-area" :class="{ 'ingest-view__upload-area--disabled': isUploading }">
          <Upload :size="32" class="ingest-view__upload-icon" />
          <p>{{ isUploading ? t('ingest.uploading') : t('ingest.dragDropOrClick') }}</p>
          <p class="ingest-view__upload-hint">{{ t('ingest.supportedFormatsFull') }}</p>
          <input type="file" multiple accept=".pdf,.md,.txt,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.csv,.json" @change="handleFileUpload" :disabled="isUploading" class="ingest-view__file-input" />
        </label>

        <div v-if="batchUploadFailures.length > 0" class="ingest-view__error-banner">
          <AlertTriangle :size="16" />
          {{ t('ingest.batchUploadPartialFail', [batchUploadFailures.length]) }}
        </div>

        <div v-if="batchDuplicateNames.length > 0" class="ingest-view__duplicate-warning">
          <AlertTriangle :size="16" />
          <span>{{ t('ingest.batchDuplicateHint', [batchDuplicateNames.join(t('common.commaSeparator'))]) }}</span>
          <button class="ingest-view__duplicate-dismiss" @click="batchDuplicateNames = []" :title="t('ingest.dismissWarning')">
            <X :size="14" />
          </button>
        </div>

        <div v-if="batchPendingSources.length > 0" class="ingest-view__batch-pending">
          <h3 class="ingest-view__batch-pending-title">{{ t('ingest.batchPendingTitle', [batchPendingSources.length]) }}</h3>
          <div class="ingest-view__existing-list">
            <div v-for="source in batchPendingSources" :key="source.id" class="ingest-view__existing-item">
              <FileText :size="14" class="ingest-view__existing-icon" />
              <span class="ingest-view__existing-name">{{ source.name }}</span>
              <span class="ingest-view__existing-meta">{{ formatSize(source.size) }} · {{ source.format }}</span>
            </div>
          </div>
          <div class="ingest-view__guidance">
            <label class="ingest-view__guidance-label">
              <Sparkles :size="14" />
              {{ t('ingest.batchGuidanceLabel') }}
            </label>
            <textarea
              v-model="batchGuidance"
              class="ingest-view__guidance-input"
              :placeholder="t('ingest.guidancePlaceholder')"
              rows="3"
            ></textarea>
          </div>
          <div class="ingest-view__panel-actions">
            <button class="ingest-view__btn-primary" :disabled="batchBusy" @click="createBatchFromPending">
              <Sparkles :size="16" />
              {{ t('ingest.batchCreateAndStart') }}
              <ArrowRight :size="16" />
            </button>
          </div>
        </div>

        <div v-if="uploadedFile" class="ingest-view__guidance">
          <label class="ingest-view__guidance-label">
            <Sparkles :size="14" />
            {{ t('ingest.guidanceLabel') }}
          </label>
          <textarea
            v-model="userGuidance"
            class="ingest-view__guidance-input"
            :placeholder="t('ingest.guidancePlaceholder')"
            rows="3"
          ></textarea>
        </div>

        <div v-if="batchPendingSources.length === 0" class="ingest-view__panel-actions">
          <button class="ingest-view__btn-primary" :disabled="!uploadedFile || isPhaseRunning" @click="startAnalysis">
            <Sparkles :size="16" />
            {{ t('ingest.startAiAnalysis') }}
            <ArrowRight :size="16" />
          </button>
        </div>

        <div v-if="existingSources.length > 0" class="ingest-view__existing-section">
          <div class="ingest-view__divider"></div>
          <div class="ingest-view__existing-list">
            <div v-for="source in existingSources" :key="source.id" class="ingest-view__existing-item">
              <FileText :size="14" class="ingest-view__existing-icon" />
              <span class="ingest-view__existing-name">{{ source.name }}</span>
              <span class="ingest-view__existing-meta">{{ formatSize(source.size) }} · {{ source.format }}</span>
              <button class="ingest-view__existing-delete" @click="handleDeleteSource(source.id)" :title="t('ingest.deleteSource')">
                <Trash2 :size="14" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 分析中 -->
      <div v-if="currentStep === 'analyzing'" class="ingest-view__panel">
        <h2 class="ingest-view__panel-title">{{ t('ingest.analyzingTitle') }}</h2>
        <p class="ingest-view__panel-hint">{{ currentTip }}</p>

        <div v-if="pipelineError" class="ingest-view__error-banner">
          <AlertTriangle :size="16" />
          {{ pipelineError }}
        </div>

        <div v-if="hasScanWarning" class="ingest-view__scan-warning">
          <AlertTriangle :size="16" />
          <span>{{ scanWarningMessage }}</span>
        </div>

        <IngestStepTimeline
          :step-states="stepStates"
          phase="phase1"
          :include-parse="includeParsePhase"
          :now-ms="nowMs"
        />

        <EntityDiscoveryWall :previews="chunkPreviews" />

        <div v-if="!pipelineError" class="ingest-view__panel-actions">
          <button class="ingest-view__btn-danger" @click="handlePauseExecution">
            <PauseCircle :size="14" />
            {{ t('ingest.pauseTask') }}
          </button>
        </div>
        <div v-else class="ingest-view__panel-actions">
          <button class="ingest-view__btn-secondary" @click="handleNewSource">
            <RotateCcw :size="14" />
            {{ t('ingest.reUpload') }}
          </button>
        </div>
      </div>

      <!-- 审阅确认 -->
      <div v-if="currentStep === 'review'" class="ingest-view__panel">
        <div class="ingest-view__review-header">
          <div class="ingest-view__review-icon-wrap">
            <ClipboardCheck :size="36" />
          </div>
          <h2 class="ingest-view__review-title">{{ t('ingest.reviewTitle') }}</h2>
          <p class="ingest-view__review-sub">{{ t('ingest.reviewSub') }}</p>
        </div>

        <AnalysisSummaryPanel :ai-analysis="aiAnalysis" :metadata="metadataRaw" />

        <EntityDiscoveryWall :previews="chunkPreviews" />

        <div v-if="affectedPages.length > 0" class="ingest-view__affected-group">
          <h4 class="ingest-view__affected-group-title">{{ t('ingest.reviewAffectedTitle', [affectedPages.length]) }}</h4>
          <div class="ingest-view__affected-items">
            <div v-for="page in affectedPages" :key="page.path" class="ingest-view__affected-row">
              <component :is="actionIcon(page.action)" :size="14" class="ingest-view__affected-row-icon" />
              <span class="ingest-view__affected-row-title">{{ page.title }}</span>
              <span class="ingest-view__affected-row-action" :class="'ingest-view__affected-row-action--' + page.action">{{ actionLabel(page.action) }}</span>
            </div>
          </div>
        </div>

        <div class="ingest-view__guidance">
          <label class="ingest-view__guidance-label">
            <Sparkles :size="14" />
            {{ t('ingest.reviewGuidanceLabel') }}
          </label>
          <textarea
            v-model="userGuidance"
            class="ingest-view__guidance-input"
            :placeholder="t('ingest.reviewGuidancePlaceholder')"
            rows="3"
          ></textarea>
        </div>

        <div class="ingest-view__panel-actions">
          <button class="ingest-view__btn-primary" @click="handleConfirmWrite">
            <CheckCircle :size="16" />
            {{ t('ingest.confirmWrite') }}
          </button>
          <button class="ingest-view__btn-secondary" @click="handleReanalysis">
            <RotateCcw :size="16" />
            {{ t('ingest.reanalyze') }}
          </button>
          <button class="ingest-view__btn-ghost" @click="handleNewSource">
            <Upload :size="16" />
            {{ t('ingest.reviewBack') }}
          </button>
        </div>
      </div>

      <!-- 执行写入中 -->
      <div v-if="currentStep === 'executing'" class="ingest-view__panel">
        <h2 class="ingest-view__panel-title">{{ t('ingest.writingTitle') }}</h2>
        <p class="ingest-view__panel-hint">{{ currentTip }}</p>

        <IngestStepTimeline
          :step-states="stepStates"
          phase="phase2"
          :now-ms="nowMs"
        />

        <div class="ingest-view__executing-hint">
          <Loader2 :size="14" class="ingest-view__stepper-spin" />
          <span>{{ t('ingest.executingHint') }}</span>
        </div>

        <div v-if="pipelineError" class="ingest-view__error-banner">
          <AlertTriangle :size="16" />
          {{ pipelineError }}
        </div>

        <div v-if="!pipelineError" class="ingest-view__panel-actions">
          <button class="ingest-view__btn-danger" @click="handlePauseExecution">
            <PauseCircle :size="14" />
            {{ t('ingest.pauseTask') }}
          </button>
        </div>
        <div v-else class="ingest-view__panel-actions">
          <button class="ingest-view__btn-secondary" @click="handleNewSource">
            <RotateCcw :size="14" />
            {{ t('ingest.reUpload') }}
          </button>
        </div>
      </div>

      <!-- 任务已暂停 -->
      <div v-if="currentStep === 'paused'" class="ingest-view__panel">
        <div class="ingest-view__paused-header">
          <PauseCircle :size="36" style="color: var(--warning)" />
          <h2 class="ingest-view__paused-title">{{ t('ingest.pausedTitle') }}</h2>
          <p class="ingest-view__paused-sub">{{ t('ingest.pausedSub') }}</p>
        </div>

        <IngestStepTimeline
          :step-states="stepStates"
          :phase="stepStates.some(s => s.name === 'ANALYZE' && s.status === 'completed') ? 'phase2' : 'phase1'"
          :include-parse="includeParsePhase"
          :now-ms="nowMs"
          paused
        />

        <div class="ingest-view__panel-actions">
          <button class="ingest-view__btn-primary" @click="handleResumeIngest">
            <PlayCircle :size="16" />
            {{ t('ingest.resumeTask') }}
          </button>
          <button class="ingest-view__btn-secondary" @click="handleNewSource">
            <Upload :size="16" />
            {{ t('ingest.newTask') }}
          </button>
        </div>
      </div>

      <!-- 处理完成 -->
      <div v-if="currentStep === 'done'" class="ingest-view__panel">
        <div class="ingest-view__done-header">
          <div class="ingest-view__done-icon-wrap">
            <CheckCircle :size="36" />
          </div>
          <h2 class="ingest-view__done-title">{{ t('ingest.doneTitle') }}</h2>
          <p class="ingest-view__done-sub">{{ t('ingest.doneSub') }}</p>
        </div>

        <div v-if="complianceSummary || schemaViolation" class="ingest-view__schema-section">
          <div class="ingest-view__schema-section-header">
            <AlertCircle :size="18" />
            <div>
              <span class="ingest-view__schema-section-title">{{ t('ingest.schemaCheckTitle') }}</span>
              <span class="ingest-view__schema-section-sub">{{ complianceSummary?.overallMessage || schemaViolation?.reason }}</span>
            </div>
          </div>

          <!-- 结构化汇总展示 -->
          <template v-if="complianceSummary && complianceSummary.groups.length > 0">
            <div class="ingest-view__summary-stats">
              <span class="ingest-view__summary-total">{{ t('ingest.violationsCount', [complianceSummary.totalViolations]) }}</span>
              <span v-if="complianceSummary.highCount > 0" class="ingest-view__summary-badge ingest-view__summary-badge--high">{{ t('ingest.highPriority', [complianceSummary.highCount]) }}</span>
              <span v-if="complianceSummary.mediumCount > 0" class="ingest-view__summary-badge ingest-view__summary-badge--medium">{{ t('ingest.mediumPriority', [complianceSummary.mediumCount]) }}</span>
              <span v-if="complianceSummary.lowCount > 0" class="ingest-view__summary-badge ingest-view__summary-badge--low">{{ t('ingest.lowPriority', [complianceSummary.lowCount]) }}</span>
            </div>
            <div class="ingest-view__summary-groups">
              <div
                v-for="group in complianceSummary.groups"
                :key="group.type"
                class="ingest-view__summary-group"
                :class="'ingest-view__summary-group--' + group.severity.toLowerCase()"
              >
                <button
                  class="ingest-view__summary-group-header"
                  @click="toggleGroup(group.type)"
                >
                  <span class="ingest-view__summary-group-label">{{ group.typeLabel }}</span>
                  <span class="ingest-view__summary-group-count">{{ t('ingest.countSuffix', [group.count]) }}</span>
                  <span class="ingest-view__summary-group-severity" :class="severityBadgeClass(group.severity)">{{ t(severityLabelKeyMap[group.severity]) || group.severity }}</span>
                  <ChevronDown
                    :size="14"
                    class="ingest-view__summary-group-chevron"
                    :class="{ 'ingest-view__summary-group-chevron--expanded': expandedGroups.has(group.type) }"
                  />
                </button>
                <div class="ingest-view__summary-group-overview">{{ group.overview }}</div>
                <div v-if="expandedGroups.has(group.type) && group.affectedPages.length > 0" class="ingest-view__summary-group-pages">
                  <span class="ingest-view__summary-group-pages-label">{{ t('ingest.affectedLabel') }}</span>
                  <span v-for="(page, idx) in group.affectedPages" :key="page" class="ingest-view__summary-group-page">
                    {{ page }}<span v-if="idx < group.affectedPages.length - 1">、</span>
                  </span>
                  <span v-if="group.count > group.affectedPages.length" class="ingest-view__summary-group-pages-more">{{ t('ingest.countMore', [group.count]) }}</span>
                </div>
              </div>
            </div>
          </template>

          <!-- 兼容旧格式：无结构化数据时 fallback -->
          <template v-else-if="schemaViolation">
            <div v-if="parsedViolations.length > 0" class="ingest-view__schema-list">
              <div v-for="(v, i) in parsedViolations" :key="i" class="ingest-view__schema-row">
                <span class="ingest-view__schema-row-badge" :class="'ingest-view__schema-row-badge--' + v.severity.toLowerCase()">{{ v.severityLabel }}</span>
                <div class="ingest-view__schema-row-body">
                  <span class="ingest-view__schema-row-type">{{ v.typeLabel }}</span>
                  <span class="ingest-view__schema-row-desc">{{ v.description }}</span>
                  <span v-if="v.suggestion" class="ingest-view__schema-row-fix">{{ v.suggestion }}</span>
                </div>
              </div>
            </div>
            <p v-else class="ingest-view__schema-warning-detail">{{ schemaViolation.complianceViolations }}</p>
          </template>

          <p class="ingest-view__schema-section-hint">
            {{ t('ingest.schemaOptHint') }}
          </p>
          <button class="ingest-view__schema-action-btn" @click="openPatchDrawer">
            <GitBranch :size="14" />
            {{ t('ingest.viewSchemaOpt') }}
          </button>
        </div>

        <div v-if="conflictCount > 0 || qualityCritical > 0 || qualityWarnings > 0 || schemaGaps.length > 0" class="ingest-view__quality-section">
          <div class="ingest-view__quality-header">
            <AlertTriangle :size="16" />
            <span>{{ t('ingest.qualityIssuesFound', [(conflictCount || 0) + (qualityCritical || 0) + (qualityWarnings || 0) + (schemaGaps?.length || 0)]) }}</span>
          </div>
          <div v-if="conflictCount > 0" class="ingest-view__quality-item ingest-view__quality-item--actionable">
            <span class="ingest-view__quality-badge ingest-view__quality-badge--warn">{{ conflictCount }}</span>
            <span>{{ t('ingest.conflictLabel', [conflictSummaryText]) }}</span>
            <button
              v-if="conflictRouteSummary && conflictRouteSummary.review > 0"
              class="ingest-view__quality-link"
              @click="openPatchDrawerToConflict"
            >{{ t('ingest.viewAndAdjudicate') }}</button>
          </div>
          <div v-if="qualityCritical > 0" class="ingest-view__quality-item">
            <span class="ingest-view__quality-badge ingest-view__quality-badge--critical">{{ qualityCritical }}</span>
            <span>{{ t('ingest.criticalIssue') }}</span>
          </div>
          <div v-if="qualityWarnings > 0" class="ingest-view__quality-item">
            <span class="ingest-view__quality-badge ingest-view__quality-badge--warn">{{ qualityWarnings }}</span>
            <span>{{ t('ingest.qualityWarningLabel') }}</span>
          </div>
          <div v-if="schemaGaps && schemaGaps.length > 0" class="ingest-view__quality-item">
            <span class="ingest-view__quality-badge ingest-view__quality-badge--info">{{ schemaGaps.length }}</span>
            <span>{{ t('ingest.schemaGap') }}</span>
          </div>
        </div>

        <div class="ingest-view__done-summary">
          <div class="ingest-view__done-stat">
            <span class="ingest-view__done-stat-value">{{ (summaryPage ? 1 : 0) + entityPages.length + chapterPages.length + updatedAffectedPages.length }}</span>
            <span class="ingest-view__done-stat-label">{{ t('ingest.affectedPages') }}</span>
          </div>
          <div class="ingest-view__done-stat">
            <span class="ingest-view__done-stat-value">{{ totalTokens.toLocaleString() }}</span>
            <span class="ingest-view__done-stat-label">{{ t('ingest.tokenUsage') }}</span>
          </div>
          <div class="ingest-view__done-stat">
            <span class="ingest-view__done-stat-value">{{ stepStates.filter(s => s.status === 'completed').length }}</span>
            <span class="ingest-view__done-stat-label">{{ t('ingest.completedSteps') }}</span>
          </div>
        </div>

        <div v-if="summaryPage || newEntities.length > 0 || supplementedEntities.length > 0 || pendingEntities.length > 0 || chapterPages.length > 0 || updatedAffectedPages.length > 0" class="ingest-view__affected-pages">
          <h3 class="ingest-view__affected-heading">{{ t('ingest.resultHeading') }}</h3>
          <div v-if="summaryPage" class="ingest-view__affected-group">
            <h4 class="ingest-view__affected-group-title">{{ t('ingest.summaryPage') }}</h4>
            <div class="ingest-view__affected-items">
              <div class="ingest-view__affected-row">
                <FileText :size="14" class="ingest-view__affected-row-icon" />
                <span class="ingest-view__affected-row-title">{{ summaryPage.title }}</span>
                <span class="ingest-view__affected-row-action ingest-view__affected-row-action--摘要">{{ t('ingest.summaryLabel') }}</span>
                <router-link v-if="summaryPage.id != null" :to="'/wiki/' + summaryPage.id" class="ingest-view__affected-row-link">
                  {{ t('ingest.viewLink') }}
                  <ChevronRight :size="12" />
                </router-link>
              </div>
            </div>
          </div>
          <div v-if="newEntities.length > 0" class="ingest-view__affected-group">
            <h4 class="ingest-view__affected-group-title">{{ t('ingest.newEntities') }}</h4>
            <div class="ingest-view__affected-items">
              <div v-for="page in newEntities" :key="page.title" class="ingest-view__affected-row">
                <component :is="actionIcon(page.action)" :size="14" class="ingest-view__affected-row-icon" />
                <span class="ingest-view__affected-row-title">{{ page.title }}</span>
                <span class="ingest-view__affected-row-action" :class="'ingest-view__affected-row-action--' + page.action">{{ actionLabel(page.action) }}</span>
                <router-link v-if="page.id != null" :to="'/wiki/' + page.id" class="ingest-view__affected-row-link">
                  {{ t('ingest.viewLink') }}
                  <ChevronRight :size="12" />
                </router-link>
              </div>
            </div>
          </div>
          <div v-if="supplementedEntities.length > 0" class="ingest-view__affected-group">
            <h4 class="ingest-view__affected-group-title">{{ t('ingest.supplementedEntities') }}</h4>
            <div class="ingest-view__affected-items">
              <div v-for="page in supplementedEntities" :key="page.title" class="ingest-view__affected-row">
                <component :is="actionIcon(page.action)" :size="14" class="ingest-view__affected-row-icon" />
                <span class="ingest-view__affected-row-title">{{ page.title }}</span>
                <span class="ingest-view__affected-row-action" :class="'ingest-view__affected-row-action--' + page.action">{{ actionLabel(page.action) }}</span>
                <router-link v-if="page.id != null" :to="'/wiki/' + page.id" class="ingest-view__affected-row-link">
                  {{ t('ingest.viewLink') }}
                  <ChevronRight :size="12" />
                </router-link>
              </div>
            </div>
          </div>
          <div v-if="pendingEntities.length > 0" class="ingest-view__affected-group">
            <h4 class="ingest-view__affected-group-title">{{ t('ingest.pendingEntities') }}</h4>
            <div class="ingest-view__affected-items">
              <div v-for="page in pendingEntities" :key="page.title" class="ingest-view__affected-row">
                <component :is="actionIcon(page.action)" :size="14" class="ingest-view__affected-row-icon" />
                <span class="ingest-view__affected-row-title">{{ page.title }}</span>
                <span class="ingest-view__affected-row-action" :class="'ingest-view__affected-row-action--' + page.action">{{ actionLabel(page.action) }}</span>
              </div>
            </div>
            <p v-if="schemaViolation" class="ingest-view__affected-group-hint">{{ t('ingest.pendingEntitiesHint') }}</p>
          </div>
          <div v-if="chapterPages.length > 0" class="ingest-view__affected-group">
            <h4 class="ingest-view__affected-group-title">{{ t('ingest.referencePages') }}</h4>
            <div class="ingest-view__affected-items">
              <div v-for="page in chapterPages" :key="page.title" class="ingest-view__affected-row">
                <component :is="actionIcon(page.action)" :size="14" class="ingest-view__affected-row-icon" />
                <span class="ingest-view__affected-row-title">{{ page.title }}</span>
                <span class="ingest-view__affected-row-action" :class="'ingest-view__affected-row-action--' + page.action">{{ actionLabel(page.action) }}</span>
                <router-link v-if="page.id != null" :to="'/wiki/' + page.id" class="ingest-view__affected-row-link">
                  {{ t('ingest.viewLink') }}
                  <ChevronRight :size="12" />
                </router-link>
              </div>
            </div>
          </div>
          <div v-if="updatedAffectedPages.length > 0" class="ingest-view__affected-group">
            <h4 class="ingest-view__affected-group-title">{{ t('ingest.updatedPages') }}</h4>
            <div class="ingest-view__affected-items">
              <div v-for="page in updatedAffectedPages" :key="page.title" class="ingest-view__affected-row">
                <component :is="actionIcon(page.action)" :size="14" class="ingest-view__affected-row-icon" />
                <span class="ingest-view__affected-row-title">{{ page.title }}</span>
                <span class="ingest-view__affected-row-action" :class="'ingest-view__affected-row-action--' + page.action">{{ actionLabel(page.action) }}</span>
                <router-link v-if="page.id != null" :to="'/wiki/' + page.id" class="ingest-view__affected-row-link">
                  {{ t('ingest.viewLink') }}
                  <ChevronRight :size="12" />
                </router-link>
              </div>
            </div>
          </div>
        </div>

        <div class="ingest-view__panel-actions">
          <button class="ingest-view__btn-secondary" @click="handleNewSource">
            <Upload :size="16" />
            {{ t('ingest.continueAdding') }}
          </button>
          <button class="ingest-view__btn-primary" @click="handleBackToWiki">
            {{ t('ingest.backToWiki') }}
            <ArrowRight :size="16" />
          </button>
        </div>
      </div>
    </div>

    <ConfirmDialog
      :open="confirmState.open"
      :title="confirmState.title"
      :message="confirmState.message"
      :confirm-text="confirmState.confirmText"
      :cancel-text="confirmState.cancelText"
      :type="confirmState.type"
      :confirm-variant="confirmState.confirmVariant"
      @confirm="onConfirm"
      @cancel="onCancel"
    />
  </div>
</template>

<style scoped>
.ingest-view {
  max-width: 720px;
}

.ingest-view__title {
  font-size: var(--font-h1);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
  margin-bottom: var(--space-2);
}

.ingest-view__subtitle {
  font-size: var(--font-body);
  color: var(--text-secondary);
  margin-bottom: var(--space-6);
}

.ingest-view__task-tabs {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border-default);
}

.ingest-view__task-tab {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
  font-size: var(--font-small);
  cursor: pointer;
  transition: all 200ms ease;
}

.ingest-view__task-tab--active {
  background: var(--accent-light);
  color: var(--text-primary);
  border-color: var(--accent-primary);
}

.ingest-view__task-tab:hover {
  border-color: var(--accent-primary);
}

.ingest-view__task-tab-progress {
  font-size: var(--font-xs);
  color: var(--accent-primary);
  margin-left: var(--space-1);
}

.ingest-view__task-tab--new {
  background: transparent;
  color: var(--accent-primary);
  border-color: var(--accent-primary);
  border-style: dashed;
}

.ingest-view__task-tab--new:hover {
  background: var(--accent-light);
}

.ingest-view__task-tab--cancelled {
  opacity: 0.7;
}

.ingest-view__task-tab-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--text-tertiary);
  cursor: pointer;
  transition: background-color 150ms ease, color 150ms ease;
  margin-left: var(--space-1);
}

.ingest-view__task-tab-close:hover {
  color: var(--error);
  background: var(--error-light);
}

/* Stage nav 与顶部进度容器 */
.ingest-view__stage-nav {
  margin-bottom: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
}

.ingest-view__progress {
  margin-bottom: var(--space-5);
  padding: var(--space-3) var(--space-4);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
}

.ingest-view__stepper-spin {
  animation: ingest-spin 1s linear infinite;
}

@keyframes ingest-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Content panel */
.ingest-view__content {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-6);
  box-shadow: var(--shadow-sm);
}

.ingest-view__panel-title {
  font-size: var(--font-h2);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-bottom: var(--space-2);
}

.ingest-view__panel-hint {
  font-size: var(--font-body);
  color: var(--text-secondary);
  margin-bottom: var(--space-5);
  line-height: 1.6;
}

/* Upload area */
.ingest-view__upload-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-8);
  border: 2px dashed var(--border-default);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: border-color 200ms ease, background-color 200ms ease;
  margin-bottom: var(--space-5);
}

.ingest-view__upload-area:hover {
  border-color: var(--accent-primary);
  background: var(--accent-light);
}

.ingest-view__upload-area--disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.ingest-view__upload-area p {
  color: var(--text-secondary);
  font-size: var(--font-body);
}

.ingest-view__upload-hint {
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
  margin-top: var(--space-1);
}

.ingest-view__upload-icon {
  color: var(--text-tertiary);
  margin-bottom: var(--space-3);
}

.ingest-view__file-input {
  display: none;
}

.ingest-view__uploaded-file {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4);
  background: var(--success-light);
  border: 1px solid var(--success);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-5);
}

.ingest-view__uploaded-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.ingest-view__uploaded-name {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}

.ingest-view__uploaded-meta {
  font-size: var(--font-caption);
  color: var(--text-secondary);
}

.ingest-view__uploaded-check {
  color: var(--success);
}

.ingest-view__duplicate-warning {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  background: var(--warning-light, #fff8e1);
  color: var(--warning, #ed6c02);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-4);
  font-size: var(--font-body-sm);
}

.ingest-view__duplicate-dismiss {
  display: flex;
  align-items: center;
  padding: var(--space-1);
  background: none;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  margin-left: auto;
}

/* Error & warning */
.ingest-view__error-banner {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  background: var(--error-light);
  color: var(--error);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-4);
  font-size: var(--font-body);
}

.ingest-view__scan-warning {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3);
  background: var(--warning-light, #fff8e1);
  color: var(--warning, #ed6c02);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-4);
  font-size: var(--font-body-sm);
}

/* Guidance */
.ingest-view__guidance {
  margin-bottom: var(--space-5);
}

.ingest-view__guidance-label {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  margin-bottom: var(--space-2);
}

.ingest-view__guidance-input {
  width: 100%;
  padding: var(--space-3);
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: var(--font-body);
  resize: vertical;
  outline: none;
  line-height: 1.5;
}

.ingest-view__guidance-input:focus {
  border-color: var(--input-focus-border);
  box-shadow: 0 0 0 2px var(--accent-light);
}

.ingest-view__guidance-input::placeholder {
  color: var(--text-tertiary);
}

/* Step Pipeline Waterfall — 已废弃，迁移到 IngestStepTimeline 组件 */


/* Affected pages */
.ingest-view__affected-pages {
  margin-bottom: var(--space-5);
}

.ingest-view__affected-group {
  margin-bottom: var(--space-4);
}

.ingest-view__affected-group:last-child {
  margin-bottom: 0;
}

.ingest-view__affected-group-title {
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: var(--space-2);
  padding-left: var(--space-1);
}

.ingest-view__affected-heading {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  margin-bottom: var(--space-3);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.ingest-view__affected-items {
  display: flex;
  flex-direction: column;
  gap: 0;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.ingest-view__affected-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-4);
  font-size: var(--font-body);
  color: var(--text-primary);
  border-bottom: 1px solid var(--border-default);
}

.ingest-view__affected-row:last-child {
  border-bottom: none;
}

.ingest-view__affected-row-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.ingest-view__affected-row-title {
  flex: 1;
}

.ingest-view__affected-row-action {
  font-size: var(--font-caption);
  padding: 1px var(--space-2);
  border-radius: var(--radius-sm);
  font-weight: var(--weight-medium);
}

.ingest-view__affected-row-action--更新 {
  background: var(--info-light, #e3f2fd);
  color: var(--info, #1976d2);
}

.ingest-view__affected-row-action--新建 {
  background: var(--success-light);
  color: var(--success);
}

.ingest-view__affected-row-action--补充 {
  background: var(--warning-light, #fff8e1);
  color: var(--warning, #ed6c02);
}

.ingest-view__affected-row-action--待定 {
  background: var(--border-default);
  color: var(--text-tertiary);
}

.ingest-view__affected-row-action--摘要 {
  background: var(--accent-light, #e8eaf6);
  color: var(--accent-primary);
}

.ingest-view__affected-group-hint {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  padding: var(--space-2) var(--space-3);
  margin-top: var(--space-1);
}

.ingest-view__affected-row-link {
  font-size: var(--font-caption);
  color: var(--accent-primary);
  text-decoration: none;
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-weight: var(--weight-medium);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  transition: background-color 200ms ease;
}

.ingest-view__affected-row-link:hover {
  background: var(--accent-light);
}

/* Buttons */
.ingest-view__panel-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.ingest-view__btn-primary {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-5);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: background-color 200ms ease, opacity 200ms ease;
}

.ingest-view__btn-primary:hover:not(:disabled) {
  background: var(--accent-hover, #1d4ed8);
}

.ingest-view__btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ingest-view__btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-5);
  background: transparent;
  color: var(--text-primary);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: border-color 200ms ease, background-color 200ms ease;
}

.ingest-view__btn-secondary:hover {
  border-color: var(--accent-primary);
  background: var(--accent-light);
}

.ingest-view__btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  background: transparent;
  color: var(--text-secondary);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  cursor: pointer;
  transition: color 200ms ease, background-color 200ms ease;
}

.ingest-view__btn-ghost:hover {
  color: var(--text-primary);
  background: var(--hover-bg, rgba(0,0,0,0.04));
}

.ingest-view__btn-danger {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-5);
  background: var(--error-light);
  color: var(--error);
  border: 1px solid var(--error);
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: background-color 200ms ease, color 200ms ease;
}

.ingest-view__btn-danger:hover {
  background: var(--error);
  color: var(--text-on-accent);
}

/* Existing sources */
.ingest-view__existing-section {
  margin-top: var(--space-6);
}

.ingest-view__divider {
  height: 1px;
  background: var(--border-default);
  margin-bottom: var(--space-4);
}

.ingest-view__existing-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.ingest-view__existing-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  transition: background-color 150ms ease;
}

.ingest-view__existing-item:hover {
  background: var(--hover-bg, rgba(0,0,0,0.02));
}

.ingest-view__existing-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.ingest-view__existing-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ingest-view__existing-meta {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.ingest-view__existing-delete {
  display: flex;
  align-items: center;
  padding: var(--space-1);
  background: none;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: color 150ms ease, background-color 150ms ease;
}

.ingest-view__existing-delete:hover {
  color: var(--error);
  background: var(--error-light);
}

/* Executing hint */
.ingest-view__executing-hint {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

/* Review page */
.ingest-view__review-header {
  text-align: center;
  padding: var(--space-4) 0 var(--space-6);
}

.ingest-view__review-icon-wrap {
  display: inline-flex;
  color: var(--accent-primary);
  margin-bottom: var(--space-3);
}

.ingest-view__review-title {
  font-size: var(--font-h2);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-bottom: var(--space-1);
}

.ingest-view__review-sub {
  font-size: var(--font-body);
  color: var(--text-secondary);
}

.ingest-view__panel > .analysis-panel {
  margin-bottom: var(--space-2);
}

/* Paused page */
.ingest-view__paused-header {
  text-align: center;
  padding: var(--space-4) 0 var(--space-6);
}

.ingest-view__paused-title {
  font-size: var(--font-h2);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-bottom: var(--space-1);
}

.ingest-view__paused-sub {
  font-size: var(--font-body);
  color: var(--text-secondary);
}

/* Done page */
.ingest-view__done-header {
  text-align: center;
  padding: var(--space-4) 0 var(--space-6);
}

.ingest-view__done-icon-wrap {
  display: inline-flex;
  color: var(--success);
  margin-bottom: var(--space-3);
}

.ingest-view__done-title {
  font-size: var(--font-h2);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-bottom: var(--space-1);
}

.ingest-view__done-sub {
  font-size: var(--font-body);
  color: var(--text-secondary);
}

.ingest-view__done-summary {
  display: flex;
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.ingest-view__done-stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--space-4);
  background: var(--accent-light, #f8f9fa);
  border-radius: var(--radius-md);
}

.ingest-view__done-stat-value {
  font-size: var(--font-h2);
  font-weight: var(--weight-bold);
  color: var(--accent-primary);
}

.ingest-view__done-stat-label {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  margin-top: var(--space-1);
}

/* Schema section */
.ingest-view__schema-section {
  margin-bottom: var(--space-5);
  padding-bottom: var(--space-5);
  border-bottom: 1px solid var(--border-default);
}

.ingest-view__schema-section-header {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.ingest-view__schema-section-header > svg {
  color: var(--warning, #ed6c02);
  flex-shrink: 0;
  margin-top: 2px;
}

.ingest-view__schema-section-title {
  display: block;
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.ingest-view__schema-section-sub {
  display: block;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  margin-top: var(--space-1);
}

.ingest-view__schema-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.ingest-view__schema-row {
  display: flex;
  gap: var(--space-3);
  padding: var(--space-3);
  background: var(--bg-tertiary);
  border-radius: var(--radius-md);
  border: 1px solid var(--border-default);
}

.ingest-view__schema-row-badge {
  display: inline-flex;
  align-items: center;
  padding: 1px var(--space-2);
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  font-weight: var(--weight-bold);
  text-transform: uppercase;
  height: fit-content;
  flex-shrink: 0;
  margin-top: 1px;
}

.ingest-view__schema-row-badge--high {
  background: var(--error-light);
  color: var(--error);
}

.ingest-view__schema-row-badge--medium {
  background: var(--warning-light, #fff8e1);
  color: var(--warning, #ed6c02);
}

.ingest-view__schema-row-badge--low {
  background: var(--info-light, #e3f2fd);
  color: var(--info, #1976d2);
}

.ingest-view__schema-row-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0;
}

.ingest-view__schema-row-type {
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.ingest-view__schema-row-desc {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  line-height: 1.6;
}

.ingest-view__schema-row-fix {
  font-size: var(--font-body-sm);
  color: var(--accent-primary);
  margin-top: var(--space-1);
}

.ingest-view__schema-section-hint {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  margin-bottom: var(--space-2);
}

.ingest-view__schema-action-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  background: var(--accent-primary, #2563eb);
  color: white;
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: background 200ms ease;
  margin-top: var(--space-2);
}

.ingest-view__schema-action-btn:hover {
  background: var(--accent-hover, #1d4ed8);
}

.ingest-view__schema-warning-detail {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  white-space: pre-wrap;
  line-height: 1.6;
  margin-bottom: var(--space-4);
}

/* Compliance summary card */
.ingest-view__summary-stats {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
  flex-wrap: wrap;
}

.ingest-view__summary-total {
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.ingest-view__summary-badge {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
}

.ingest-view__summary-badge--high {
  background: var(--error-light, #fef2f2);
  color: var(--error, #dc2626);
}

.ingest-view__summary-badge--medium {
  background: var(--warning-light, #fffbeb);
  color: var(--warning, #d97706);
}

.ingest-view__summary-badge--low {
  background: var(--info-light, #eff6ff);
  color: var(--info, #3b82f6);
}

.ingest-view__summary-groups {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.ingest-view__summary-group {
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.ingest-view__summary-group--high {
  border-left: 3px solid var(--error, #dc2626);
}

.ingest-view__summary-group--medium {
  border-left: 3px solid var(--warning, #d97706);
}

.ingest-view__summary-group--low {
  border-left: 3px solid var(--info, #3b82f6);
}

.ingest-view__summary-group-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-2) var(--space-3);
  background: none;
  border: none;
  cursor: pointer;
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  text-align: left;
}

.ingest-view__summary-group-header:hover {
  background: var(--surface-secondary);
}

.ingest-view__summary-group-label {
  font-weight: var(--weight-medium);
}

.ingest-view__summary-group-count {
  color: var(--text-secondary);
  margin-left: auto;
}

.ingest-view__summary-group-severity {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}

.ingest-view__severity--high {
  background: var(--error-light, #fef2f2);
  color: var(--error, #dc2626);
}

.ingest-view__severity--medium {
  background: var(--warning-light, #fffbeb);
  color: var(--warning, #d97706);
}

.ingest-view__severity--low {
  background: var(--info-light, #eff6ff);
  color: var(--info, #3b82f6);
}

.ingest-view__summary-group-chevron {
  transition: transform 200ms ease;
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.ingest-view__summary-group-chevron--expanded {
  transform: rotate(180deg);
}

.ingest-view__summary-group-overview {
  padding: 0 var(--space-3) var(--space-2);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.5;
}

.ingest-view__summary-group-pages {
  padding: var(--space-2) var(--space-3);
  border-top: 1px solid var(--border-default);
  background: var(--surface-secondary);
  font-size: var(--font-caption);
  color: var(--text-secondary);
  line-height: 1.5;
}

.ingest-view__summary-group-pages-label {
  font-weight: var(--weight-medium);
  color: var(--text-tertiary);
}

.ingest-view__summary-group-page {
  color: var(--text-primary);
}

.ingest-view__summary-group-pages-more {
  color: var(--text-tertiary);
  font-style: italic;
}

.ingest-view__quality-section {
  margin: 0 0 16px;
  padding: 12px 16px;
  border-radius: 8px;
  background: var(--surface-secondary, #f8f9fa);
  border: 1px solid var(--border-light, #e5e7eb);
}

.ingest-view__quality-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary, #6b7280);
  margin-bottom: 10px;
}

.ingest-view__quality-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  color: var(--text-primary, #374151);
}
.ingest-view__quality-item--actionable {
  flex-wrap: wrap;
}
.ingest-view__quality-link {
  margin-left: auto;
  padding: 2px 8px;
  border: none;
  background: var(--accent-primary, #2563eb);
  color: white;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}
.ingest-view__quality-link:hover {
  background: var(--accent-hover, #1d4ed8);
}

.ingest-view__quality-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 22px;
  border-radius: 11px;
  font-size: 12px;
  font-weight: 600;
  padding: 0 6px;
  flex-shrink: 0;
}

.ingest-view__quality-badge--critical {
  background: var(--danger-light, #fef2f2);
  color: var(--danger, #ef4444);
}

.ingest-view__quality-badge--warn {
  background: var(--warning-light, #fffbeb);
  color: var(--warning, #f59e0b);
}

.ingest-view__quality-badge--info {
  background: var(--info-light, #eff6ff);
  color: var(--info, #3b82f6);
}

.ingest-view__batch-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.ingest-view__batch-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  height: var(--btn-height-sm);
  padding: 0 var(--space-3);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-full);
  background: var(--surface-card);
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: border-color var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}

.ingest-view__batch-chip:hover {
  border-color: var(--accent-primary);
  color: var(--text-primary);
}

.ingest-view__batch-chip--active {
  border-color: var(--accent-primary);
  background: var(--accent-light);
  color: var(--accent-primary);
}

.ingest-view__batch-chip-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 var(--space-1);
  border-radius: var(--radius-full);
  background: var(--warning);
  color: var(--text-on-accent);
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
}

.ingest-view__batch-pending {
  margin-top: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  background: var(--bg-secondary);
}

.ingest-view__batch-pending-title {
  margin: 0 0 var(--space-3);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}
</style>
