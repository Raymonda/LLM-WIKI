<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Inbox, RotateCcw } from 'lucide-vue-next'
import type { IngestBatchItemInfo } from '@/api/ingest'
import { groupInboxItems, type InboxGroupKey } from '@/stores/ingestBatch'
import InboxItemCard from './InboxItemCard.vue'

const props = defineProps<{
  items: IngestBatchItemInfo[]
  busy?: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm', executionId: number, guidance?: string): void
  (e: 'reanalyze', executionId: number, guidance?: string): void
  (e: 'retry', executionId: number): void
  (e: 'retry-all'): void
}>()

const { t } = useI18n()

const GROUP_LABEL_KEYS: Record<InboxGroupKey, string> = {
  awaiting: 'ingest.inboxGroupAwaiting',
  queued: 'ingest.inboxGroupQueued',
  analyzing: 'ingest.inboxGroupAnalyzing',
  writing: 'ingest.inboxGroupWriting',
  completed: 'ingest.inboxGroupCompleted',
  failed: 'ingest.inboxGroupFailed',
}

const groups = computed(() =>
  groupInboxItems(props.items)
    .filter((group) => group.items.length > 0)
    .map((group) => ({
      key: group.key,
      label: t(GROUP_LABEL_KEYS[group.key]),
      items: group.items,
    })),
)

function onConfirm(executionId: number, guidance?: string) {
  emit('confirm', executionId, guidance)
}

function onReanalyze(executionId: number, guidance?: string) {
  emit('reanalyze', executionId, guidance)
}

function onRetry(executionId: number) {
  emit('retry', executionId)
}
</script>

<template>
  <section class="review-inbox" :aria-label="t('ingest.inboxTitle')">
    <header class="review-inbox__header">
      <Inbox :size="16" />
      <h2 class="review-inbox__title">{{ t('ingest.inboxTitle') }}</h2>
    </header>

    <p v-if="groups.length === 0" class="review-inbox__empty">{{ t('ingest.inboxEmpty') }}</p>

    <div v-for="group in groups" :key="group.key" class="review-inbox__group">
      <div class="review-inbox__group-header">
        <span class="review-inbox__group-label">{{ group.label }}</span>
        <span class="review-inbox__group-count">{{ group.items.length }}</span>
        <button
          v-if="group.key === 'failed' && group.items.length >= 2"
          class="review-inbox__group-action"
          type="button"
          :disabled="busy"
          @click="emit('retry-all')"
        >
          <RotateCcw :size="12" />
          {{ t('ingest.inboxRetryAll') }}
        </button>
      </div>
      <div class="review-inbox__group-items">
        <InboxItemCard
          v-for="item in group.items"
          :key="item.executionId"
          :item="item"
          :busy="busy"
          @confirm="onConfirm"
          @reanalyze="onReanalyze"
          @retry="onRetry"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.review-inbox {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.review-inbox__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-secondary);
}

.review-inbox__title {
  margin: 0;
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.review-inbox__empty {
  margin: 0;
  padding: var(--space-6) 0;
  text-align: center;
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}

.review-inbox__group {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.review-inbox__group-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.review-inbox__group-label {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
}

.review-inbox__group-count {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  background: var(--bg-tertiary);
  border-radius: var(--radius-pill);
  padding: 1px var(--space-2);
  font-variant-numeric: tabular-nums;
}

.review-inbox__group-action {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 2px var(--space-2);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--btn-ghost-text);
  font-size: var(--font-caption);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.review-inbox__group-action:hover {
  background: var(--sidebar-item-hover);
}

.review-inbox__group-action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.review-inbox__group-items {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
</style>
