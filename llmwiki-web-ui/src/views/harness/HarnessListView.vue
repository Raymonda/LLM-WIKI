<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { listExecutionsPaged, type ExecutionRecord, type PageResult } from '@/api/harness'
import {
  Activity, Clock, CheckCircle, AlertTriangle, Loader2,
  FileText, Search, ShieldCheck, Settings, ChevronLeft, ChevronRight, GitMerge
} from 'lucide-vue-next'
import { statusLabel as statusLabelUtil } from '@/utils/executionLabels'

const router = useRouter()
const authStore = useAuthStore()
const { t, locale } = useI18n()

const loading = ref(true)
const pageResult = ref<PageResult<ExecutionRecord> | null>(null)
const currentPage = ref(1)
const pageSize = 20
const typeFilter = ref('')

const typeOptions = [
  { value: '', labelKey: 'harness.typeAll' },
  { value: 'ingest', labelKey: 'harness.typeIngestFull' },
  { value: 'lint', labelKey: 'harness.typeLintFull' },
  { value: 'page_merge', labelKey: 'harness.typePageMerge' },
  { value: 'schema_change', labelKey: 'harness.typeSchemaChange' },
  { value: 'config_change', labelKey: 'harness.typeConfigChange' },
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

function getSummary(exec: ExecutionRecord): string {
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
})

watch(currentPage, () => {
  loadExecutions()
})

onMounted(() => {
  loadExecutions()
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
            </div>
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
</style>