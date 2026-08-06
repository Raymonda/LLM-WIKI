<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { getExecution, getSchemaVersion, type ExecutionRecord } from '@/api/harness'
import {
  ArrowLeft, Clock, CheckCircle, AlertTriangle, Loader2, XCircle,
  FileText, Search, ShieldCheck, Settings, Activity, Layers, GitMerge, ExternalLink
} from 'lucide-vue-next'
import {
  stepLabel,
  statusLabel as statusLabelUtil,
  operationTypeLabel,
  approvalLevelLabel,
} from '@/utils/executionLabels'

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()

const loading = ref(true)
const execution = ref<ExecutionRecord | null>(null)
const schemaVersionContent = ref<string | null>(null)

const typeConfig: Record<string, { icon: any; color: string; bgColor: string; labelKey: string }> = {
  ingest: { icon: FileText, color: 'var(--success)', bgColor: 'var(--success-light)', labelKey: 'harness.typeIngestFull' },
  lint: { icon: Search, color: 'var(--warning)', bgColor: 'var(--warning-light)', labelKey: 'harness.typeLintFull' },
  schema_change: { icon: ShieldCheck, color: 'var(--accent-primary)', bgColor: 'var(--accent-light)', labelKey: 'harness.typeSchemaChange' },
  config_change: { icon: Settings, color: 'var(--text-secondary)', bgColor: 'var(--bg-tertiary)', labelKey: 'harness.typeConfigChange' },
  page_merge: { icon: GitMerge, color: 'var(--accent-primary)', bgColor: 'var(--accent-light)', labelKey: 'harness.typePageMerge' },
}

const stepStatusIcon = (status: string) => {
  switch (status) {
    case 'completed': return CheckCircle
    case 'failed': return XCircle
    case 'running': return Loader2
    default: return Clock
  }
}

const stepStatusColor = (status: string) => {
  switch (status) {
    case 'completed': return 'var(--success)'
    case 'failed': return 'var(--error)'
    case 'running': return 'var(--accent-primary)'
    default: return 'var(--text-tertiary)'
  }
}

const statusLabel = (status: string) => statusLabelUtil(status) || status

const statusColor = (status: string) => {
  switch (status) {
    case 'completed': return 'var(--success)'
    case 'failed': return 'var(--error)'
    case 'running': return 'var(--accent-primary)'
    case 'cancelled': return 'var(--text-tertiary)'
    default: return 'var(--text-secondary)'
  }
}

const totalDuration = computed(() => {
  if (!execution.value?.steps) return 0
  return execution.value.steps.reduce((sum, s) => sum + (s.durationMs ?? 0), 0)
})

const formatDuration = (ms: number) => {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`
}

const formatTime = (dateStr: string | null) => {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleString(locale.value === 'en' ? 'en-US' : 'zh-CN')
}

const schemaSummary = computed(() => {
  if (!execution.value?.steps?.length) return null
  try {
    return JSON.parse(execution.value.steps[0].outputData ?? '{}')
  } catch {
    return null
  }
})

const configSummary = computed(() => {
  if (!execution.value?.steps?.length) return null
  try {
    return JSON.parse(execution.value.steps[0].outputData ?? '{}')
  } catch {
    return null
  }
})

const isPipelineType = computed(() =>
  execution.value?.operationType === 'ingest' || execution.value?.operationType === 'lint' || execution.value?.operationType === 'page_merge'
)

interface WriteOutputPage {
  id: number
  title: string
  path?: string
  filePath?: string
  isNew?: boolean
  action?: string
  name?: string
}

const writeStepOutput = computed(() => {
  if (!execution.value?.steps?.length) return null
  const writeStep = execution.value.steps.find(s => s.stepName === 'WRITE' || s.stepName === 'WRITE_SUMMARY')
  if (!writeStep?.outputData) return null
  try {
    return JSON.parse(writeStep.outputData) as {
      status?: string
      summaryPageTitle?: string
      summaryPagePath?: string
      summaryPageId?: number
      entityPages?: WriteOutputPage[]
      updatedPages?: WriteOutputPage[]
      chapterPages?: WriteOutputPage[]
    }
  } catch {
    return null
  }
})

const mergeSourceNames = computed(() => {
  if (!execution.value?.sourceName) return []
  const sn = execution.value.sourceName
  if (sn.startsWith('合并: ') || sn.startsWith('合并至: ')) {
    const raw = sn.replace(/^合并(?:至)?:\s*/, '')
    if (sn.startsWith('合并: ')) {
      return raw.split(' + ').map(s => s.trim()).filter(Boolean)
    }
    return [raw]
  }
  return [sn]
})

const isMerge = computed(() => execution.value?.operationType === 'page_merge')

async function loadExecution() {
  loading.value = true
  try {
    const id = Number(route.params.id)
    execution.value = await getExecution(id)

    if (execution.value?.operationType === 'schema_change' && execution.value.schemaConfigId) {
      try {
        const version = await getSchemaVersion(execution.value.schemaConfigId)
        schemaVersionContent.value = version.configValue
      } catch {
        schemaVersionContent.value = null
      }
    }
  } catch (e) {
    console.error('Failed to load execution:', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadExecution()
})
</script>

<template>
  <div class="harness-detail">
    <button class="harness-detail__back" @click="router.push('/harness')">
      <ArrowLeft :size="16" />
      {{ t('harness.backToExecutions') }}
    </button>

    <div v-if="loading" class="harness-detail__loading">
      <Loader2 :size="24" class="harness-detail__loading-icon" />
      <span>{{ t('harness.loading') }}</span>
    </div>

    <template v-else-if="execution">
      <div class="harness-detail__header">
        <div class="harness-detail__header-left">
          <component
            :is="typeConfig[execution.operationType]?.icon ?? Activity"
            :size="24"
            :style="{ color: typeConfig[execution.operationType]?.color ?? 'var(--text-secondary)' }"
          />
          <div>
            <h1 class="harness-detail__title">
              {{ operationTypeLabel(execution.operationType) || (typeConfig[execution.operationType] ? t(typeConfig[execution.operationType].labelKey) : execution.operationType) }}
              <span class="harness-detail__id">#{{ execution.executionId }}</span>
            </h1>
            <span class="harness-detail__status" :style="{ color: statusColor(execution.status) }">
              {{ statusLabel(execution.status) }}
              <span v-if="execution.createdAt"> · {{ formatTime(execution.createdAt) }}</span>
            </span>
          </div>
        </div>
        <div v-if="isPipelineType" class="harness-detail__header-stats">
          <div class="harness-detail__stat">
            <span class="harness-detail__stat-value">{{ formatDuration(totalDuration) }}</span>
            <span class="harness-detail__stat-label">{{ t('harness.totalDuration') }}</span>
          </div>
          <div class="harness-detail__stat">
            <span class="harness-detail__stat-value">{{ (execution.totalTokens ?? 0).toLocaleString() }}</span>
            <span class="harness-detail__stat-label">Token</span>
          </div>
        </div>
      </div>

      <div v-if="execution.errorMessage" class="harness-detail__error">
        <AlertTriangle :size="16" />
        {{ execution.errorMessage }}
      </div>

      <div v-if="isPipelineType && execution.steps?.length" class="harness-detail__steps">
        <h2 class="harness-detail__section-title">
          <Layers :size="16" />
          {{ t('harness.pipelineSteps') }}
        </h2>
        <div class="harness-detail__step-list">
          <div
            v-for="step in execution.steps"
            :key="step.stepId"
            class="harness-detail__step"
          >
            <div class="harness-detail__step-indicator" :style="{ background: stepStatusColor(step.status) }">
              <component :is="stepStatusIcon(step.status)" :size="14" />
            </div>
            <div class="harness-detail__step-line"></div>
            <div class="harness-detail__step-card">
              <div class="harness-detail__step-top">
                <span class="harness-detail__step-name" :title="step.stepName">{{ stepLabel(step.stepName) }}</span>
                <span class="harness-detail__step-status" :style="{ color: stepStatusColor(step.status) }">
                  {{ statusLabel(step.status) }}
                </span>
              </div>
              <div class="harness-detail__step-meta">
                <span v-if="step.durationMs" class="harness-detail__step-duration">
                  <Clock :size="12" />
                  {{ formatDuration(step.durationMs) }}
                </span>
                <span v-if="step.tokensUsed" class="harness-detail__step-tokens">
                  {{ step.tokensUsed.toLocaleString() }} Token
                </span>
                <span
                  v-if="step.approvalLevel && step.approvalLevel.toLowerCase() !== 'auto'"
                  class="harness-detail__step-approval"
                >
                  {{ approvalLevelLabel(step.approvalLevel) }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="isMerge && (mergeSourceNames.length > 0 || writeStepOutput)" class="harness-detail__merge-summary">
        <h2 class="harness-detail__section-title">
          <GitMerge :size="16" />
          {{ t('harness.mergeOverview') }}
        </h2>
        <div class="harness-detail__merge-flow">
          <div v-if="mergeSourceNames.length" class="harness-detail__merge-sources">
            <span class="harness-detail__merge-label">{{ t('harness.sourcePages') }}</span>
            <ul class="harness-detail__merge-source-list">
              <li v-for="(name, idx) in mergeSourceNames" :key="idx" class="harness-detail__merge-source-item">
                <FileText :size="14" />
                {{ name }}
              </li>
            </ul>
          </div>
          <div v-if="writeStepOutput?.summaryPageTitle" class="harness-detail__merge-target">
            <span class="harness-detail__merge-label">{{ t('harness.mergeResult') }}</span>
            <router-link
              v-if="writeStepOutput.summaryPageId"
              :to="`/wiki/${writeStepOutput.summaryPageId}`"
              class="harness-detail__merge-target-link"
            >
              <CheckCircle :size="14" />
              {{ writeStepOutput.summaryPageTitle }}
              <ExternalLink :size="12" />
            </router-link>
            <span v-else class="harness-detail__merge-target-text">
              <CheckCircle :size="14" />
              {{ writeStepOutput.summaryPageTitle }}
            </span>
          </div>
        </div>
      </div>

      <div v-if="writeStepOutput && (writeStepOutput.entityPages?.length || writeStepOutput.updatedPages?.length)" class="harness-detail__write-output">
        <h2 class="harness-detail__section-title">
          <Layers :size="16" />
          {{ t('harness.compileOutput') }}
        </h2>
        <div v-if="writeStepOutput.entityPages?.length" class="harness-detail__write-group">
          <span class="harness-detail__write-label">{{ t('harness.entityPages', [writeStepOutput.entityPages.length]) }}</span>
          <ul class="harness-detail__write-list">
            <li v-for="page in writeStepOutput.entityPages" :key="page.id" class="harness-detail__write-item">
              <router-link v-if="page.id" :to="`/wiki/${page.id}`" class="harness-detail__write-link">
                {{ page.title || page.name }}
                <span class="harness-detail__write-action">{{ page.action || (page.isNew ? t('harness.newPage') : t('harness.updatedPage')) }}</span>
                <ExternalLink :size="11" />
              </router-link>
            </li>
          </ul>
        </div>
        <div v-if="writeStepOutput.updatedPages?.length" class="harness-detail__write-group">
          <span class="harness-detail__write-label">{{ t('harness.relatedUpdates', [writeStepOutput.updatedPages.length]) }}</span>
          <ul class="harness-detail__write-list">
            <li v-for="page in writeStepOutput.updatedPages" :key="page.id" class="harness-detail__write-item">
              <router-link v-if="page.id" :to="`/wiki/${page.id}`" class="harness-detail__write-link">
                {{ page.title }}
                <span class="harness-detail__write-action">{{ t('harness.updatedPage') }}</span>
                <ExternalLink :size="11" />
              </router-link>
            </li>
          </ul>
        </div>
      </div>

      <div v-else-if="execution.operationType === 'schema_change' && schemaSummary" class="harness-detail__schema">
        <h2 class="harness-detail__section-title">
          <ShieldCheck :size="16" />
          {{ t('harness.schemaChangeDetail') }}
        </h2>
        <div class="harness-detail__schema-info">
          <div class="harness-detail__schema-row">
            <span class="harness-detail__schema-label">{{ t('harness.configKey') }}</span>
            <span class="harness-detail__schema-value">{{ schemaSummary.configKey }}</span>
          </div>
          <div class="harness-detail__schema-row">
            <span class="harness-detail__schema-label">{{ t('harness.versionNumber') }}</span>
            <span class="harness-detail__schema-value">v{{ schemaSummary.versionNumber }}</span>
          </div>
          <div class="harness-detail__schema-row">
            <span class="harness-detail__schema-label">{{ t('harness.changeSource') }}</span>
            <span class="harness-detail__schema-value">{{ schemaSummary.sourceLabel }}</span>
          </div>
        </div>
        <div v-if="schemaVersionContent" class="harness-detail__schema-content">
          <h3 class="harness-detail__schema-content-title">{{ t('harness.currentSchema') }}</h3>
          <pre class="harness-detail__schema-pre">{{ schemaVersionContent }}</pre>
        </div>
      </div>

      <div v-else-if="execution.operationType === 'config_change' && configSummary" class="harness-detail__config">
        <h2 class="harness-detail__section-title">
          <Settings :size="16" />
          {{ t('harness.configChangeDetail') }}
        </h2>
        <div class="harness-detail__config-table">
          <div class="harness-detail__config-header">
            <span>{{ t('harness.configItem') }}</span>
            <span>{{ t('harness.oldValue') }}</span>
            <span>{{ t('harness.newValue') }}</span>
          </div>
          <div class="harness-detail__config-row">
            <span class="harness-detail__config-key">{{ configSummary.configKey }}</span>
            <span class="harness-detail__config-old">{{ configSummary.oldValue || t('harness.emptyValue') }}</span>
            <span class="harness-detail__config-new">{{ configSummary.newValue }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.harness-detail {
  max-width: 720px;
}

.harness-detail__back {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) 0;
  margin-bottom: var(--space-4);
  background: none;
  border: none;
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: color var(--transition-fast);
}

.harness-detail__back:hover {
  color: var(--accent-primary);
}

.harness-detail__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-8);
  color: var(--text-secondary);
}

.harness-detail__loading-icon {
  animation: spin 1s linear infinite;
  color: var(--accent-primary);
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.harness-detail__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--space-6);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--border-default);
}

.harness-detail__header-left {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
}

.harness-detail__title {
  font-size: var(--font-h2);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
  margin: 0 0 var(--space-1) 0;
}

.harness-detail__id {
  font-weight: var(--weight-normal);
  color: var(--text-tertiary);
  font-size: var(--font-body);
}

.harness-detail__status {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
}

.harness-detail__header-stats {
  display: flex;
  gap: var(--space-6);
}

.harness-detail__stat {
  text-align: center;
}

.harness-detail__stat-value {
  display: block;
  font-size: var(--font-h3);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
}

.harness-detail__stat-label {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.harness-detail__error {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  background: var(--error-light);
  border: 1px solid var(--error);
  border-radius: var(--radius-md);
  color: var(--error);
  font-size: var(--font-body-sm);
  margin-bottom: var(--space-6);
}

.harness-detail__section-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin: 0 0 var(--space-4) 0;
}

.harness-detail__step-list {
  position: relative;
  display: flex;
  flex-direction: column;
}

.harness-detail__step {
  position: relative;
  display: flex;
  gap: var(--space-4);
  padding-bottom: var(--space-3);
}

.harness-detail__step:last-child {
  padding-bottom: 0;
}

.harness-detail__step-indicator {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-on-accent);
  flex-shrink: 0;
  z-index: 1;
}

.harness-detail__step-line {
  position: absolute;
  left: 11px;
  top: 24px;
  bottom: 0;
  width: 2px;
  background: var(--border-default);
}

.harness-detail__step:last-child .harness-detail__step-line {
  display: none;
}

.harness-detail__step-card {
  flex: 1;
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-3);
}

.harness-detail__step-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-1);
}

.harness-detail__step-name {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}

.harness-detail__step-status {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.harness-detail__step-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.harness-detail__step-duration {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.harness-detail__step-tokens {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.harness-detail__step-approval {
  font-size: var(--font-caption);
  color: var(--warning);
  background: var(--warning-light);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}

.harness-detail__schema-info {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-4);
  margin-bottom: var(--space-4);
}

.harness-detail__schema-row {
  display: flex;
  align-items: center;
  padding: var(--space-2) 0;
}

.harness-detail__schema-row + .harness-detail__schema-row {
  border-top: 1px solid var(--border-default);
}

.harness-detail__schema-label {
  width: 100px;
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.harness-detail__schema-value {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  font-weight: var(--weight-medium);
}

.harness-detail__schema-content {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.harness-detail__schema-content-title {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  padding: var(--space-3) var(--space-4);
  margin: 0;
  border-bottom: 1px solid var(--border-default);
}

.harness-detail__schema-pre {
  padding: var(--space-4);
  margin: 0;
  font-size: var(--font-caption);
  font-family: var(--font-mono, 'Consolas', monospace);
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 500px;
  overflow-y: auto;
  line-height: 1.6;
}

.harness-detail__config-table {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.harness-detail__config-header {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--surface-secondary);
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-default);
}

.harness-detail__config-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-body-sm);
}

.harness-detail__config-key {
  color: var(--text-primary);
  font-weight: var(--weight-medium);
}

.harness-detail__config-old {
  color: var(--text-tertiary);
}

.harness-detail__config-new {
  color: var(--accent-primary);
  font-weight: var(--weight-medium);
}

.harness-detail__merge-summary,
.harness-detail__write-output {
  margin-bottom: var(--space-6);
}

.harness-detail__merge-flow {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.harness-detail__merge-label {
  display: block;
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: var(--space-2);
}

.harness-detail__merge-source-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.harness-detail__merge-source-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  padding: var(--space-1) var(--space-2);
  background: var(--surface-secondary);
  border-radius: var(--radius-sm);
}

.harness-detail__merge-source-item svg {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.harness-detail__merge-target-link,
.harness-detail__merge-target-text {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--success);
  padding: var(--space-2) var(--space-3);
  background: var(--success-light, rgba(34, 197, 94, 0.08));
  border-radius: var(--radius-sm);
  text-decoration: none;
}

.harness-detail__merge-target-link:hover {
  text-decoration: underline;
}

.harness-detail__merge-target-link svg:first-child,
.harness-detail__merge-target-text svg:first-child {
  color: var(--success);
}

.harness-detail__merge-target-link svg:last-child {
  color: var(--text-tertiary);
  margin-left: auto;
}

.harness-detail__write-group {
  margin-bottom: var(--space-3);
}

.harness-detail__write-label {
  display: block;
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-tertiary);
  margin-bottom: var(--space-2);
}

.harness-detail__write-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.harness-detail__write-item {
  display: inline-flex;
}

.harness-detail__write-link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-body-sm);
  color: var(--accent-primary);
  text-decoration: none;
  padding: var(--space-1) var(--space-2);
  background: var(--accent-light, rgba(59, 130, 246, 0.08));
  border-radius: var(--radius-sm);
}

.harness-detail__write-link:hover {
  text-decoration: underline;
}

.harness-detail__write-link svg {
  color: var(--text-tertiary);
}

.harness-detail__write-action {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  padding: 0 var(--space-1);
  background: var(--surface-secondary);
  border-radius: var(--radius-xs, 2px);
}
</style>