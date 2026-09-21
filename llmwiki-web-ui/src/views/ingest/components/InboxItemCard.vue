<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowRight, ChevronDown, FileText } from 'lucide-vue-next'
import type { IngestBatchItemInfo } from '@/api/ingest'
import { parseAnalyzeOutput } from '@/utils/parseAnalyzeOutput'
import AnalysisSummaryPanel from './AnalysisSummaryPanel.vue'

const props = defineProps<{
  item: IngestBatchItemInfo
  busy?: boolean
  selectable?: boolean
  selected?: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm', executionId: number, guidance?: string): void
  (e: 'reanalyze', executionId: number, guidance?: string): void
  (e: 'retry', executionId: number): void
  (e: 'view', executionId: number): void
  (e: 'toggle-select', executionId: number): void
}>()

const { t } = useI18n()
const expanded = ref(false)
const guidance = ref('')

const isAwaiting = computed(
  () => props.item.status === 'awaiting_confirmation' || props.item.status === 'awaiting_review',
)
const isFailed = computed(() => props.item.status === 'failed')

const statusMeta = computed(() => {
  switch (props.item.status) {
    case 'awaiting_confirmation':
    case 'awaiting_review':
      return { label: t('ingest.waitingHint'), tone: 'accent' }
    case 'pending':
      return { label: t('ingest.statusQueued'), tone: 'muted' }
    case 'paused':
      return { label: t('ingest.statusPaused'), tone: 'muted' }
    case 'confirmed':
      return { label: t('ingest.statusConfirmed'), tone: 'info' }
    case 'completed':
      return { label: t('ingest.statusDone'), tone: 'success' }
    case 'cancelled':
      return { label: t('ingest.statusCancelledItem'), tone: 'muted' }
    case 'failed':
      return { label: t('ingest.inboxGroupFailed'), tone: 'error' }
    case 'budget_exhausted':
      return { label: t('ingest.statusBudgetExhausted'), tone: 'error' }
    case 'running':
      return props.item.phase1Completed
        ? { label: t('ingest.statusWriting'), tone: 'info' }
        : { label: t('ingest.statusAnalyzing'), tone: 'info' }
    default:
      return { label: props.item.status, tone: 'muted' }
  }
})

const visibleErrorMessage = computed(() => {
  if (!props.item.errorMessage) return ''
  if (props.item.status === 'paused' || props.item.status === 'cancelled') return ''
  return props.item.errorMessage
})

const visibleErrorSummary = computed(() => {
  if (visibleErrorMessage.value) return ''
  return props.item.errorSummary || ''
})

const RISK_REASON_LABEL_KEYS: Array<{ prefix: string; labelKey: string }> = [
  { prefix: 'schema_precheck_violation', labelKey: 'ingest.riskSchemaViolation' },
  { prefix: 'conflict_count', labelKey: 'ingest.riskConflictCount' },
  { prefix: 'low_completeness', labelKey: 'ingest.riskLowCompleteness' },
  { prefix: 'high_update_ratio', labelKey: 'ingest.riskHighUpdateRatio' },
  { prefix: 'schema_gap_hints', labelKey: 'ingest.riskSchemaGap' },
  { prefix: 'parse_degraded', labelKey: 'ingest.riskParseDegraded' },
]

const riskLabels = computed(() => {
  const decision = props.item.autoDecision
  if (!decision) return []
  const reasons = decision.reasons
  if (!Array.isArray(reasons)) return []
  const labels: string[] = []
  reasons.forEach((reason) => {
    if (typeof reason !== 'string') return
    const matched = RISK_REASON_LABEL_KEYS.find(
      ({ prefix }) => reason === prefix || reason.startsWith(`${prefix}:`),
    )
    if (matched) {
      const label = t(matched.labelKey)
      if (!labels.includes(label)) labels.push(label)
    }
  })
  return labels
})

const autoApproved = computed(() => props.item.autoDecision?.autoApprove === true)

const qualityBadge = computed(() => {
  const critical = props.item.qualityCritical ?? 0
  const warnings = props.item.qualityWarnings ?? 0
  if (critical > 0) return { label: t('ingest.qualityCriticalBadge', [critical]), tone: 'error' }
  if (warnings > 0) return { label: t('ingest.qualityWarningsBadge', [warnings]), tone: 'warning' }
  return null
})

const displayName = computed(
  () => props.item.sourceName || t('ingest.materialFallback', [props.item.executionId]),
)

const parsedAnalysis = computed(() => {
  if (!isAwaiting.value || !props.item.analyzeOutput) return null
  const parsed = parseAnalyzeOutput(props.item.analyzeOutput)
  return parsed.aiAnalysis || parsed.metadata ? parsed : null
})

function submitConfirm() {
  emit('confirm', props.item.executionId, guidance.value || undefined)
}

function submitReanalyze() {
  emit('reanalyze', props.item.executionId, guidance.value || undefined)
}

function submitRetry() {
  emit('retry', props.item.executionId)
}

function submitView() {
  emit('view', props.item.executionId)
}
</script>

<template>
  <article class="inbox-item" :class="[`inbox-item--${statusMeta.tone}`]">
    <header class="inbox-item__header">
      <input
        v-if="selectable"
        class="inbox-item__select"
        type="checkbox"
        :checked="selected"
        :disabled="busy"
        :aria-label="t('ingest.inboxSelectItem')"
        @change="emit('toggle-select', item.executionId)"
      />
      <FileText :size="16" class="inbox-item__icon" />
      <div class="inbox-item__title">
        <span class="inbox-item__name" :title="displayName">{{ displayName }}</span>
        <span v-if="item.sourceFormat" class="inbox-item__format">{{ item.sourceFormat }}</span>
      </div>
      <span v-if="autoApproved" class="inbox-item__flag inbox-item__flag--auto">
        {{ t('ingest.autoApprovedBadge') }}
      </span>
      <span
        v-if="qualityBadge"
        class="inbox-item__flag"
        :class="`inbox-item__flag--${qualityBadge.tone}`"
      >
        {{ qualityBadge.label }}
      </span>
      <span class="inbox-item__badge">{{ statusMeta.label }}</span>
      <span v-if="item.totalTokens" class="inbox-item__tokens">
        {{ t('ingest.itemTokens', [item.totalTokens]) }}
      </span>
      <button
        class="inbox-item__view"
        type="button"
        :title="t('ingest.inboxViewTask')"
        :aria-label="t('ingest.inboxViewTask')"
        @click="submitView"
      >
        <ArrowRight :size="14" />
      </button>
    </header>

    <div v-if="riskLabels.length" class="inbox-item__risks">
      <span v-for="label in riskLabels" :key="label" class="inbox-item__risk">{{ label }}</span>
    </div>

    <p v-if="visibleErrorMessage" class="inbox-item__error">{{ visibleErrorMessage }}</p>
    <p v-else-if="visibleErrorSummary" class="inbox-item__error-summary">{{ visibleErrorSummary }}</p>

    <div v-if="isAwaiting" class="inbox-item__actions">
      <button
        v-if="parsedAnalysis"
        class="inbox-item__btn inbox-item__btn--ghost"
        type="button"
        @click="expanded = !expanded"
      >
        <ChevronDown
          :size="14"
          class="inbox-item__chevron"
          :class="{ 'inbox-item__chevron--open': expanded }"
        />
        {{ expanded ? t('ingest.itemHideAnalysis') : t('ingest.itemViewAnalysis') }}
      </button>
      <input
        v-model="guidance"
        class="inbox-item__guidance"
        type="text"
        :placeholder="t('ingest.reviewGuidancePlaceholder')"
      />
      <button
        class="inbox-item__btn inbox-item__btn--primary"
        type="button"
        :disabled="busy"
        @click="submitConfirm"
      >
        {{ t('ingest.itemConfirmWrite') }}
      </button>
      <button
        class="inbox-item__btn inbox-item__btn--secondary"
        type="button"
        :disabled="busy"
        @click="submitReanalyze"
      >
        {{ t('ingest.reanalyze') }}
      </button>
    </div>

    <div v-else-if="isFailed" class="inbox-item__actions">
      <button
        class="inbox-item__btn inbox-item__btn--secondary"
        type="button"
        :disabled="busy"
        @click="submitRetry"
      >
        {{ t('common.retry') }}
      </button>
    </div>

    <div v-if="expanded && parsedAnalysis" class="inbox-item__analysis">
      <AnalysisSummaryPanel :ai-analysis="parsedAnalysis.aiAnalysis" :metadata="parsedAnalysis.metadata" />
    </div>
  </article>
</template>

<style scoped>
.inbox-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  padding: var(--space-3) var(--space-4);
}

.inbox-item--accent {
  border-color: var(--accent-light);
}

.inbox-item--error {
  border-color: var(--error-light);
}

.inbox-item__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.inbox-item__icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.inbox-item__title {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  min-width: 0;
  flex: 1 1 auto;
}

.inbox-item__name {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inbox-item__format {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  text-transform: uppercase;
  flex-shrink: 0;
}

.inbox-item__badge {
  font-size: var(--font-caption);
  padding: 2px var(--space-2);
  border-radius: var(--radius-pill);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  flex-shrink: 0;
}

.inbox-item--accent .inbox-item__badge {
  background: var(--accent-light);
  color: var(--accent-strong);
}

.inbox-item--info .inbox-item__badge {
  background: var(--info-light);
  color: var(--info-strong);
}

.inbox-item--success .inbox-item__badge {
  background: var(--success-light);
  color: var(--success-strong);
}

.inbox-item--error .inbox-item__badge {
  background: var(--error-light);
  color: var(--error-strong);
}

.inbox-item__tokens {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.inbox-item__view {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--text-tertiary);
  cursor: pointer;
  flex-shrink: 0;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.inbox-item__view:hover {
  color: var(--accent-primary);
  background: var(--accent-light);
}

.inbox-item__error {
  margin: 0;
  font-size: var(--font-body-sm);
  color: var(--error-strong);
}

.inbox-item__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.inbox-item__guidance {
  flex: 1 1 180px;
  min-width: 0;
  height: var(--btn-height-sm);
  padding: 0 var(--space-3);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-sm);
  background: var(--input-bg);
  color: var(--text-primary);
  font-size: var(--font-body-sm);
}

.inbox-item__guidance:focus {
  outline: none;
  border-color: var(--input-focus-border);
  box-shadow: 0 0 0 2px var(--input-focus-ring);
}

.inbox-item__btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: var(--btn-height-sm);
  padding: 0 var(--space-3);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.inbox-item__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.inbox-item__btn--primary {
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
}

.inbox-item__btn--secondary {
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  border-color: var(--btn-secondary-border);
}

.inbox-item__btn--ghost {
  background: transparent;
  color: var(--btn-ghost-text);
  padding-left: 0;
}

.inbox-item__chevron {
  transition: transform var(--transition-fast);
}

.inbox-item__chevron--open {
  transform: rotate(180deg);
}

.inbox-item__analysis {
  border-top: 1px solid var(--border-subtle);
  padding-top: var(--space-2);
}

.inbox-item__select {
  flex-shrink: 0;
  accent-color: var(--accent-primary);
  cursor: pointer;
}

.inbox-item__flag {
  font-size: var(--font-caption);
  padding: 2px var(--space-2);
  border-radius: var(--radius-pill);
  flex-shrink: 0;
}

.inbox-item__flag--auto {
  background: var(--success-light);
  color: var(--success-strong);
}

.inbox-item__flag--error {
  background: var(--error-light);
  color: var(--error-strong);
}

.inbox-item__flag--warning {
  background: var(--warning-light);
  color: var(--warning-strong);
}

.inbox-item__risks {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.inbox-item__risk {
  font-size: var(--font-caption);
  padding: 1px var(--space-2);
  border-radius: var(--radius-pill);
  border: 1px solid var(--warning-light);
  color: var(--warning-strong);
}

.inbox-item__error-summary {
  margin: 0;
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}
</style>
