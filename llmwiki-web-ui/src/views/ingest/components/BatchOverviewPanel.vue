<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CheckCheck, Layers, Pause, Play, XCircle } from 'lucide-vue-next'
import type { IngestBatchInfo } from '@/api/ingest'

const props = defineProps<{
  batch: IngestBatchInfo | null
  busy?: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm-all'): void
  (e: 'pause'): void
  (e: 'resume'): void
  (e: 'cancel'): void
}>()

const { t } = useI18n()

const statusLabel = computed(() => {
  switch (props.batch?.status) {
    case 'paused':
      return t('ingest.batchStatusPaused')
    case 'completed':
      return t('ingest.batchStatusCompleted')
    case 'cancelled':
      return t('ingest.batchStatusCancelled')
    default:
      return t('ingest.batchStatusActive')
  }
})

const progressPercent = computed(() => {
  const batch = props.batch
  if (!batch || batch.totalCount <= 0) return 0
  return Math.round((batch.completedCount / batch.totalCount) * 100)
})

const chips = computed(() => {
  const batch = props.batch
  if (!batch) return []
  return [
    { key: 'awaiting', count: batch.awaitingCount, label: t('ingest.batchChipAwaiting', [batch.awaitingCount]) },
    { key: 'queued', count: batch.pendingCount, label: t('ingest.batchChipQueued', [batch.pendingCount]) },
    { key: 'analyzing', count: batch.runningCount, label: t('ingest.batchChipAnalyzing', [batch.runningCount]) },
    { key: 'writing', count: batch.confirmedCount, label: t('ingest.batchChipWriting', [batch.confirmedCount]) },
    { key: 'completed', count: batch.completedCount, label: t('ingest.batchChipCompleted', [batch.completedCount]) },
    { key: 'failed', count: batch.failedCount, label: t('ingest.batchChipFailed', [batch.failedCount]) },
    { key: 'cancelled', count: batch.cancelledCount, label: t('ingest.batchChipCancelled', [batch.cancelledCount]) },
  ].filter((chip) => chip.count > 0)
})

const canConfirmAll = computed(() => (props.batch?.awaitingCount ?? 0) > 0)
const canPause = computed(() => props.batch?.status === 'active')
const canResume = computed(() => props.batch?.status === 'paused')
const canCancel = computed(() => props.batch?.status === 'active' || props.batch?.status === 'paused')

function requestConfirmAll() {
  emit('confirm-all')
}

function requestPause() {
  emit('pause')
}

function requestResume() {
  emit('resume')
}

function requestCancel() {
  emit('cancel')
}
</script>

<template>
  <section v-if="batch" class="batch-overview">
    <header class="batch-overview__header">
      <Layers :size="16" class="batch-overview__icon" />
      <h3 class="batch-overview__title">{{ t('ingest.batchOverviewTitle') }}</h3>
      <span class="batch-overview__status" :class="`batch-overview__status--${batch.status}`">
        {{ statusLabel }}
      </span>
      <span class="batch-overview__progress-text">
        {{ t('ingest.batchProgress', [batch.completedCount, batch.totalCount]) }}
      </span>
    </header>

    <div
      class="batch-overview__progress"
      role="progressbar"
      :aria-valuenow="progressPercent"
      aria-valuemin="0"
      aria-valuemax="100"
    >
      <div class="batch-overview__progress-fill" :style="{ width: `${progressPercent}%` }" />
    </div>

    <div v-if="chips.length" class="batch-overview__chips">
      <span
        v-for="chip in chips"
        :key="chip.key"
        class="batch-overview__chip"
        :class="`batch-overview__chip--${chip.key}`"
      >
        {{ chip.label }}
      </span>
    </div>

    <p v-if="batch.guidance" class="batch-overview__guidance">
      <span class="batch-overview__guidance-label">{{ t('ingest.batchGuidanceLabel') }}</span>
      {{ batch.guidance }}
    </p>

    <div class="batch-overview__actions">
      <button
        class="batch-overview__btn batch-overview__btn--primary"
        type="button"
        :disabled="busy || !canConfirmAll"
        @click="requestConfirmAll"
      >
        <CheckCheck :size="14" />
        {{ t('ingest.batchConfirmAll') }}
      </button>
      <button
        v-if="canPause"
        class="batch-overview__btn batch-overview__btn--secondary"
        type="button"
        :disabled="busy"
        @click="requestPause"
      >
        <Pause :size="14" />
        {{ t('ingest.batchPause') }}
      </button>
      <button
        v-if="canResume"
        class="batch-overview__btn batch-overview__btn--secondary"
        type="button"
        :disabled="busy"
        @click="requestResume"
      >
        <Play :size="14" />
        {{ t('ingest.batchResume') }}
      </button>
      <button
        v-if="canCancel"
        class="batch-overview__btn batch-overview__btn--danger"
        type="button"
        :disabled="busy"
        @click="requestCancel"
      >
        <XCircle :size="14" />
        {{ t('ingest.batchCancelBatch') }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.batch-overview {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  background: var(--surface-card);
  padding: var(--space-4);
}

.batch-overview__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.batch-overview__icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.batch-overview__title {
  margin: 0;
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.batch-overview__status {
  font-size: var(--font-caption);
  padding: 2px var(--space-2);
  border-radius: var(--radius-pill);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}

.batch-overview__status--active {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.batch-overview__status--completed {
  background: var(--success-light);
  color: var(--success);
}

.batch-overview__status--cancelled {
  background: var(--error-light);
  color: var(--error);
}

.batch-overview__progress-text {
  margin-left: auto;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
}

.batch-overview__progress {
  height: 6px;
  border-radius: var(--radius-full);
  background: var(--bg-tertiary);
  overflow: hidden;
}

.batch-overview__progress-fill {
  height: 100%;
  border-radius: var(--radius-full);
  background: var(--accent-primary);
  transition: width var(--transition-normal);
}

.batch-overview__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.batch-overview__chip {
  font-size: var(--font-caption);
  padding: 2px var(--space-2);
  border-radius: var(--radius-pill);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}

.batch-overview__chip--awaiting {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.batch-overview__chip--analyzing,
.batch-overview__chip--writing {
  background: var(--info-light);
  color: var(--info);
}

.batch-overview__chip--completed {
  background: var(--success-light);
  color: var(--success);
}

.batch-overview__chip--failed {
  background: var(--error-light);
  color: var(--error);
}

.batch-overview__guidance {
  margin: 0;
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.batch-overview__guidance-label {
  color: var(--text-tertiary);
}

.batch-overview__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.batch-overview__btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: var(--btn-height);
  padding: 0 var(--space-4);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.batch-overview__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.batch-overview__btn--primary {
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
}

.batch-overview__btn--secondary {
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  border-color: var(--btn-secondary-border);
}

.batch-overview__btn--danger {
  background: transparent;
  color: var(--error);
  border-color: var(--error-light);
}
</style>
