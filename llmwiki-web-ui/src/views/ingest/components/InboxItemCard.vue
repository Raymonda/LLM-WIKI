<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ChevronDown, FileText } from 'lucide-vue-next'
import type { IngestBatchItemInfo } from '@/api/ingest'
import { parseAnalyzeOutput } from '@/utils/parseAnalyzeOutput'
import AnalysisSummaryPanel from './AnalysisSummaryPanel.vue'

const props = defineProps<{
  item: IngestBatchItemInfo
  busy?: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm', executionId: number, guidance?: string): void
  (e: 'reanalyze', executionId: number, guidance?: string): void
  (e: 'retry', executionId: number): void
}>()

const { t } = useI18n()
const expanded = ref(false)
const guidance = ref('')

const isAwaiting = computed(
  () => props.item.status === 'awaiting_confirmation' || props.item.status === 'awaiting_review',
)
const isFailed = computed(
  () => props.item.status === 'failed' || props.item.status === 'budget_exhausted',
)

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
    case 'budget_exhausted':
      return { label: t('ingest.inboxGroupFailed'), tone: 'error' }
    case 'running':
      return props.item.phase1Completed
        ? { label: t('ingest.statusWriting'), tone: 'info' }
        : { label: t('ingest.statusAnalyzing'), tone: 'info' }
    default:
      return { label: props.item.status, tone: 'muted' }
  }
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
</script>

<template>
  <article class="inbox-item" :class="[`inbox-item--${statusMeta.tone}`]">
    <header class="inbox-item__header">
      <FileText :size="16" class="inbox-item__icon" />
      <div class="inbox-item__title">
        <span class="inbox-item__name" :title="displayName">{{ displayName }}</span>
        <span v-if="item.sourceFormat" class="inbox-item__format">{{ item.sourceFormat }}</span>
      </div>
      <span class="inbox-item__badge">{{ statusMeta.label }}</span>
      <span v-if="item.totalTokens" class="inbox-item__tokens">
        {{ t('ingest.itemTokens', [item.totalTokens]) }}
      </span>
    </header>

    <p v-if="item.errorMessage" class="inbox-item__error">{{ item.errorMessage }}</p>

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
  color: var(--accent-primary);
}

.inbox-item--info .inbox-item__badge {
  background: var(--info-light);
  color: var(--info);
}

.inbox-item--success .inbox-item__badge {
  background: var(--success-light);
  color: var(--success);
}

.inbox-item--error .inbox-item__badge {
  background: var(--error-light);
  color: var(--error);
}

.inbox-item__tokens {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.inbox-item__error {
  margin: 0;
  font-size: var(--font-body-sm);
  color: var(--error);
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
</style>
