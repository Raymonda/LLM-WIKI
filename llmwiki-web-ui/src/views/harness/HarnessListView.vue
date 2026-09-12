<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  listExecutionsPaged, cancelExecution, retryExecution, createExecutionSSE,
  type ExecutionRecord, type ExecutionStepInfo, type PageResult,
} from '@/api/harness'
import {
  Activity, Clock, CheckCircle, AlertTriangle, Loader2,
  FileText, Search, ShieldCheck, Settings, ChevronLeft, ChevronRight, GitMerge,
  Scale, Bookmark, RefreshCw, RotateCw, XCircle,
} from 'lucide-vue-next'
import { statusLabel as statusLabelUtil, stepLabel as stepLabelUtil } from '@/utils/executionLabels'

const router = useRouter()
const authStore = useAuthStore()
const { t, locale } = useI18n()

const loading = ref(true)
const pageResult = ref<PageResult<ExecutionRecord> | null>(null)
const currentPage = ref(1)
const pageSize = 20
const typeFilter = ref('')

const activeTasks = ref<ExecutionRecord[]>([])
const expandedId = ref<number | null>(null)
const expandedSteps = ref<ExecutionStepInfo[]>([])
const cancellingId = ref<number | null>(null)
const retryingId = ref<number | null>(null)

const ACTIVE_PAGE_SIZE = 50
const ACTIVE_POLL_MS = 10_000

const nowTs = ref(Date.now())
let elapsedTimer: number | null = null
let activeTimer: number | null = null
let expandedEs: EventSource | null = null

const typeOptions = [
  { value: '', labelKey: 'harness.typeAll' },
  { value: 'ingest', labelKey: 'harness.typeIngestFull' },
  { value: 'lint', labelKey: 'harness.typeLintFull' },
  { value: 'page_merge', labelKey: 'harness.typePageMerge' },
  { value: 'schema_change', labelKey: 'harness.typeSchemaChange' },
  { value: 'config_change', labelKey: 'harness.typeConfigChange' },
  { value: 'conflict_ruling', labelKey: 'harness.typeConflictRuling' },
  { value: 'query_save', labelKey: 'harness.typeQuerySave' },
  { value: 'index_rebuild', labelKey: 'harness.typeIndexRebuild' },
]

const typeOptionsLocalized = computed(() =>
  typeOptions.map(o => ({ ...o, label: t(o.labelKey) }))
)

const typeConfig: Record<string, { icon: any; color: string; bgColor: string; labelKey: string }> = {
  ingest: { icon: FileText, color: 'var(--success)', bgColor: 'var(--success-light)', labelKey: 'harness.typeIngestFull' },
  lint: { icon: Search, color: 'var(--warning)', bgColor: 'var(--warning-light)', labelKey: 'harness.typeLintFull' },
  schema_change: { icon: ShieldCheck, color: 'var(--accent-primary)', bgColor: 'var(--accent-light)', labelKey: 'harness.typeSchemaChange' },
  config_change: { icon: Settings, color: 'var(--text-secondary)', bgColor: 'var(--bg-tertiary)', labelKey: 'harness.typeConfigChange' },
  page_merge: { icon: GitMerge, color: 'var(--accent-primary)', bgColor: 'var(--accent-light)', labelKey: 'harness.typePageMerge' },
  conflict_ruling: { icon: Scale, color: 'var(--warning)', bgColor: 'var(--warning-light)', labelKey: 'harness.typeConflictRuling' },
  query_save: { icon: Bookmark, color: 'var(--accent-primary)', bgColor: 'var(--accent-light)', labelKey: 'harness.typeQuerySave' },
  index_rebuild: { icon: RefreshCw, color: 'var(--success)', bgColor: 'var(--success-light)', labelKey: 'harness.typeIndexRebuild' },
}

const statusIcon = (status: string) => {
  switch (status) {
    case 'completed': return CheckCircle
    case 'failed': return AlertTriangle
    case 'running': return Loader2
    case 'cancelled': return AlertTriangle
    default: return Clock
  }
}

const statusColor = (status: string) => {
  switch (status) {
    case 'completed': return 'var(--success)'
    case 'failed': return 'var(--error)'
    case 'running': return 'var(--accent-primary)'
    case 'cancelled': return 'var(--text-tertiary)'
    default: return 'var(--text-secondary)'
  }
}

const totalPages = computed(() => pageResult.value?.totalPages ?? 0)

async function loadExecutions() {
  loading.value = true
  try {
    const params: { scopeId: number; type?: string; page: number; size: number } = {
      scopeId: authStore.scopeId,
      page: currentPage.value,
      size: pageSize,
    }
    if (typeFilter.value) {
      params.type = typeFilter.value
    }
    pageResult.value = await listExecutionsPaged(params)
  } catch (e) {
    console.error('Failed to load executions:', e)
  } finally {
    loading.value = false
  }
}

async function loadActiveTasks() {
  try {
    const params: { scopeId: number; status: string; type?: string; page: number; size: number } = {
      scopeId: authStore.scopeId,
      status: 'active',
      page: 1,
      size: ACTIVE_PAGE_SIZE,
    }
    if (typeFilter.value) {
      params.type = typeFilter.value
    }
    const result = await listExecutionsPaged(params)
    activeTasks.value = result.items
  } catch (e) {
    console.error('Failed to load active tasks:', e)
  }
}

function closeExpanded() {
  if (expandedEs) {
    expandedEs.close()
    expandedEs = null
  }
  expandedId.value = null
  expandedSteps.value = []
}

function toggleExpand(exec: ExecutionRecord) {
  if (expandedId.value === exec.executionId) {
    closeExpanded()
    return
  }
  closeExpanded()
  expandedId.value = exec.executionId
  const es = createExecutionSSE(exec.executionId)
  expandedEs = es

  es.addEventListener('init', (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data) as ExecutionRecord
      expandedSteps.value = data.steps ?? []
    } catch {}
  })

  es.addEventListener('step', (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data) as ExecutionStepInfo
      const idx = expandedSteps.value.findIndex(s => s.stepId === data.stepId)
      if (idx >= 0) {
        expandedSteps.value[idx] = { ...expandedSteps.value[idx], ...data }
      } else {
        expandedSteps.value.push(data)
      }
    } catch {}
  })

  es.addEventListener('done', () => {
    closeExpanded()
    loadActiveTasks()
    loadExecutions()
  })

  es.addEventListener('pause', () => {
    loadActiveTasks()
  })
}

const expandedCompletedCount = computed(() =>
  expandedSteps.value.filter(s => s.status === 'completed').length
)

const expandedProgressPercent = computed(() =>
  expandedSteps.value.length
    ? Math.round((expandedCompletedCount.value / expandedSteps.value.length) * 100)
    : 0
)

async function cancelTask(exec: ExecutionRecord) {
  cancellingId.value = exec.executionId
  try {
    await cancelExecution(exec.executionId)
    await loadActiveTasks()
  } catch (e) {
    ElMessage.error((e as Error).message || t('harness.actionFailed'))
  } finally {
    cancellingId.value = null
  }
}

async function retryTask(exec: ExecutionRecord) {
  retryingId.value = exec.executionId
  try {
    await retryExecution(exec.executionId)
    await loadActiveTasks()
    await loadExecutions()
  } catch (e) {
    ElMessage.error((e as Error).message || t('harness.actionFailed'))
  } finally {
    retryingId.value = null
  }
}

function goToPage(page: number) {
  if (page < 1 || page > totalPages.value) return
  currentPage.value = page
}

function formatTime(dateStr: string | null): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return t('harness.justNow')
  if (diffMin < 60) return t('harness.minutesAgo', [diffMin])
  const diffHour = Math.floor(diffMin / 60)
  if (diffHour < 24) return t('harness.hoursAgo', [diffHour])
  const diffDay = Math.floor(diffHour / 24)
  if (diffDay < 7) return t('harness.daysAgo', [diffDay])
  return date.toLocaleDateString(locale.value === 'en' ? 'en-US' : 'zh-CN')
}

function elapsedText(exec: ExecutionRecord): string {
  if (!exec.startTime) return ''
  const ms = nowTs.value - new Date(exec.startTime).getTime()
  if (ms <= 0) return ''
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m${s % 60}s`
  const h = Math.floor(m / 60)
  return `${h}h${m % 60}m`
}

function getSummary(exec: ExecutionRecord): string {
  if (exec.payloadTitle) return exec.payloadTitle
  const cfg = typeConfig[exec.operationType]
  const typeLabel = cfg ? t(cfg.labelKey) : exec.operationType

  if (exec.operationType === 'ingest' && exec.sourceName) {
    return t('harness.sourcePrefix') + exec.sourceName
  }
  if (exec.operationType === 'page_merge' && exec.sourceName) {
    return exec.sourceName
  }
  if (exec.operationType === 'lint') {
    return t('harness.tokenUsage') + (exec.totalTokens ?? 0).toLocaleString()
  }
  if (exec.operationType === 'schema_change' && exec.steps?.length) {
    try {
      const data = JSON.parse(exec.steps[0].outputData ?? '{}')
      return `${data.sourceLabel ?? t('harness.typeSchemaChange')} · v${data.versionNumber ?? '?'}`
    } catch {
      return t('harness.schemaUpdated')
    }
  }
  if (exec.operationType === 'config_change' && exec.steps?.length) {
    try {
      const data = JSON.parse(exec.steps[0].outputData ?? '{}')
      return `${data.configKey}: ${data.oldValue || t('harness.emptyValue')} → ${data.newValue}`
    } catch {
      return t('harness.configUpdated')
    }
  }
  if (exec.totalTokens) {
    return t('harness.tokenUsage') + exec.totalTokens.toLocaleString()
  }
  return typeLabel
}

function getTypeIcon(type: string) {
  return typeConfig[type]?.icon ?? Activity
}

function getTypeBadgeStyle(type: string) {
  const cfg = typeConfig[type]
  return { background: cfg?.bgColor ?? 'var(--bg-tertiary)', color: cfg?.color ?? 'var(--text-secondary)' }
}

watch(typeFilter, () => {
  currentPage.value = 1
  loadExecutions()
  loadActiveTasks()
})

watch(currentPage, () => {
  loadExecutions()
})

onMounted(() => {
  loadExecutions()
  loadActiveTasks()
  elapsedTimer = window.setInterval(() => { nowTs.value = Date.now() }, 1000)
  activeTimer = window.setInterval(() => { loadActiveTasks() }, ACTIVE_POLL_MS)
})

onUnmounted(() => {
  if (elapsedTimer) window.clearInterval(elapsedTimer)
  if (activeTimer) window.clearInterval(activeTimer)
  closeExpanded()
})
</script>

<template>
  <div class="harness-list">
    <div class="harness-list__header">
      <h1 class="harness-list__title">{{ t('harness.title') }}</h1>
      <div class="harness-list__filter">
        <select v-model="typeFilter" class="harness-list__filter-select">
          <option v-for="opt in typeOptionsLocalized" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
      </div>
    </div>

    <div v-if="activeTasks.length" class="harness-list__active">
      <h2 class="harness-list__section-title">
        {{ t('harness.activeTasks') }}
        <span class="harness-list__active-count">{{ activeTasks.length }}</span>
      </h2>
      <div class="harness-list__active-list">
        <div
          v-for="exec in activeTasks"
          :key="exec.executionId"
          class="harness-list__active-card"
        >
          <div class="harness-list__active-top">
            <span class="harness-list__item-type-badge" :style="getTypeBadgeStyle(exec.operationType)">
              <component :is="getTypeIcon(exec.operationType)" :size="12" />
              {{ typeConfig[exec.operationType] ? t(typeConfig[exec.operationType].labelKey) : exec.operationType }}
            </span>
            <span class="harness-list__item-status" :style="{ color: statusColor(exec.status) }">
              {{ statusLabelUtil(exec.status) || exec.status }}
            </span>
          </div>
          <p class="harness-list__active-title" @click="toggleExpand(exec)">{{ getSummary(exec) }}</p>
          <div class="harness-list__active-meta">
            <span v-if="exec.startTime" class="harness-list__active-elapsed">
              <Clock :size="12" />
              {{ elapsedText(exec) }}
            </span>
            <span class="harness-list__active-actions">
              <router-link class="harness-list__action-link" :to="`/harness/${exec.executionId}`">
                {{ t('harness.viewTaskDetail') }}
              </router-link>
              <button
                class="harness-list__action-link harness-list__action-link--danger"
                :disabled="cancellingId === exec.executionId"
                @click.stop="cancelTask(exec)"
              >
                {{ t('harness.cancelExecution') }}
              </button>
            </span>
          </div>
          <div v-if="expandedId === exec.executionId" class="harness-list__active-steps">
            <div class="harness-list__active-progress">
              <div
                class="harness-list__active-progress-fill"
                :style="{ width: expandedProgressPercent + '%' }"
              ></div>
            </div>
            <div
              v-for="step in expandedSteps"
              :key="step.stepId"
              class="harness-list__active-step"
            >
              <component
                :is="step.status === 'completed' ? CheckCircle : step.status === 'failed' ? XCircle : Loader2"
                :size="12"
                :style="{ color: step.status === 'completed' ? 'var(--success)' : step.status === 'failed' ? 'var(--error)' : 'var(--accent-primary)' }"
              />
              <span class="harness-list__active-step-name">{{ stepLabelUtil(step.stepName) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <h2 v-if="activeTasks.length" class="harness-list__section-title">{{ t('harness.taskHistory') }}</h2>

    <div v-if="loading" class="harness-list__loading">
      <Loader2 :size="24" class="harness-list__loading-icon" />
      <span>{{ t('harness.loading') }}</span>
    </div>

    <div v-else-if="!pageResult || pageResult.items.length === 0" class="harness-list__empty">
      <Activity :size="48" class="harness-list__empty-icon" />
      <p>{{ t('harness.noExecutions') }}</p>
      <p class="harness-list__empty-hint">{{ t('harness.noExecutionsHintFull') }}</p>
    </div>

    <div v-else class="harness-list__content">
      <div class="harness-list__timeline">
        <div
          v-for="exec in pageResult.items"
          :key="exec.executionId"
          class="harness-list__item"
          @click="router.push(`/harness/${exec.executionId}`)"
        >
          <div class="harness-list__item-dot" :style="{ background: statusColor(exec.status) }">
            <component :is="statusIcon(exec.status)" :size="14" :style="{ color: 'var(--text-on-accent)' }" />
          </div>
          <div class="harness-list__item-line"></div>
          <div class="harness-list__item-card">
            <div class="harness-list__item-top">
              <span class="harness-list__item-type-badge" :style="getTypeBadgeStyle(exec.operationType)">
                <component :is="getTypeIcon(exec.operationType)" :size="12" />
                {{ typeConfig[exec.operationType] ? t(typeConfig[exec.operationType].labelKey) : exec.operationType }}
              </span>
              <span class="harness-list__item-time">{{ formatTime(exec.createdAt) }}</span>
            </div>
            <p class="harness-list__item-summary">{{ getSummary(exec) }}</p>
            <div class="harness-list__item-meta">
              <span v-if="exec.totalTokens" class="harness-list__item-token">
                {{ exec.totalTokens.toLocaleString() }} Token
              </span>
              <span class="harness-list__item-status" :style="{ color: statusColor(exec.status) }">
                {{ statusLabelUtil(exec.status) || exec.status }}
              </span>
              <button
                v-if="exec.status === 'failed'"
                class="harness-list__retry-btn"
                :disabled="retryingId === exec.executionId"
                @click.stop="retryTask(exec)"
              >
                <RotateCw :size="12" />
                {{ t('harness.retryExecution') }}
              </button>
            </div>
            <p
              v-if="exec.status === 'failed' && exec.errorMessage"
              class="harness-list__item-error"
            >
              {{ exec.errorMessage }}
            </p>
          </div>
        </div>
      </div>

      <div v-if="totalPages > 1" class="harness-list__pagination">
        <button
          class="harness-list__page-btn"
          :disabled="currentPage <= 1"
          @click="goToPage(currentPage - 1)"
        >
          <ChevronLeft :size="16" />
        </button>
        <span class="harness-list__page-info">{{ currentPage }} / {{ totalPages }}</span>
        <button
          class="harness-list__page-btn"
          :disabled="currentPage >= totalPages"
          @click="goToPage(currentPage + 1)"
        >
          <ChevronRight :size="16" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.harness-list {
  max-width: 720px;
}

.harness-list__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-6);
}

.harness-list__title {
  font-size: var(--font-h1);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
  margin: 0;
}

.harness-list__filter-select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  color: var(--text-primary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  outline: none;
}

.harness-list__filter-select:focus {
  border-color: var(--accent-primary);
}

.harness-list__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-8);
  color: var(--text-secondary);
}

.harness-list__loading-icon {
  animation: spin 1s linear infinite;
  color: var(--accent-primary);
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.harness-list__empty {
  text-align: center;
  padding: var(--space-8);
}

.harness-list__empty-icon {
  color: var(--text-tertiary);
  margin-bottom: var(--space-4);
}

.harness-list__empty p {
  color: var(--text-secondary);
  font-size: var(--font-body);
}

.harness-list__empty-hint {
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
  margin-top: var(--space-2);
}

.harness-list__timeline {
  position: relative;
  display: flex;
  flex-direction: column;
}

.harness-list__item {
  position: relative;
  display: flex;
  gap: var(--space-4);
  padding-bottom: var(--space-4);
}

.harness-list__item:last-child {
  padding-bottom: 0;
}

.harness-list__item-dot {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  z-index: 1;
}

.harness-list__item-line {
  position: absolute;
  left: 13px;
  top: 28px;
  bottom: 0;
  width: 2px;
  background: var(--border-default);
}

.harness-list__item:last-child .harness-list__item-line {
  display: none;
}

.harness-list__item-card {
  flex: 1;
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-3) var(--space-4);
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.harness-list__item-card:hover {
  background: var(--accent-light);
  border-color: var(--accent-primary);
}

.harness-list__item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}

.harness-list__item-type-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  line-height: 1.4;
}

.harness-list__item-time {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.harness-list__item-summary {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin: 0 0 var(--space-2) 0;
  line-height: 1.4;
}

.harness-list__item-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.harness-list__item-token {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.harness-list__item-status {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.harness-list__pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  margin-top: var(--space-6);
  padding-top: var(--space-4);
  border-top: 1px solid var(--border-default);
}

.harness-list__page-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  color: var(--text-primary);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.harness-list__page-btn:hover:not(:disabled) {
  background: var(--accent-light);
}

.harness-list__page-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.harness-list__page-info {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.harness-list__section-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin: 0 0 var(--space-3) 0;
}

.harness-list__active {
  margin-bottom: var(--space-6);
}

.harness-list__active-count {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  background: var(--surface-secondary);
  padding: 1px 7px;
  border-radius: var(--radius-sm);
}

.harness-list__active-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.harness-list__active-card {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-3) var(--space-4);
}

.harness-list__active-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}

.harness-list__active-title {
  font-size: var(--font-body);
  color: var(--text-primary);
  margin: 0 0 var(--space-2) 0;
  cursor: pointer;
}

.harness-list__active-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.harness-list__active-elapsed {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
}

.harness-list__active-actions {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3);
}

.harness-list__action-link {
  background: none;
  border: none;
  padding: 0;
  font-size: var(--font-caption);
  color: var(--accent-primary);
  text-decoration: none;
  cursor: pointer;
}

.harness-list__action-link:hover:not(:disabled) {
  text-decoration: underline;
}

.harness-list__action-link:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.harness-list__action-link--danger {
  color: var(--error);
}

.harness-list__active-steps {
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-default);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.harness-list__active-progress {
  height: 4px;
  background: var(--border-default);
  border-radius: 2px;
  overflow: hidden;
  margin-bottom: var(--space-2);
}

.harness-list__active-progress-fill {
  height: 100%;
  background: var(--accent-primary);
  border-radius: 2px;
  transition: width 0.4s ease;
}

.harness-list__active-step {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-caption);
  color: var(--text-secondary);
}

.harness-list__active-step-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.harness-list__retry-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  padding: 2px 8px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
  background: var(--surface-card);
  color: var(--text-secondary);
  font-size: var(--font-caption);
  cursor: pointer;
}

.harness-list__retry-btn:hover:not(:disabled) {
  color: var(--accent-primary);
  border-color: var(--accent-primary);
}

.harness-list__retry-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.harness-list__item-error {
  margin: var(--space-2) 0 0;
  font-size: var(--font-caption);
  color: var(--error);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}
</style>