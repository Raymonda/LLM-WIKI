<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Inbox, RotateCcw, XCircle } from 'lucide-vue-next'
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
  (e: 'view', executionId: number): void
  (e: 'cancel-items', executionIds: number[]): void
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
      retryableCount: group.items.filter((item) => item.status === 'failed').length,
    })),
)

const selectedIds = ref<Set<number>>(new Set())

const selectedCount = computed(() => selectedIds.value.size)

watch(
  () => props.items,
  (items) => {
    const awaitingIds = new Set(
      items
        .filter((item) => item.status === 'awaiting_confirmation' || item.status === 'awaiting_review')
        .map((item) => item.executionId),
    )
    const next = new Set<number>()
    selectedIds.value.forEach((id) => {
      if (awaitingIds.has(id)) next.add(id)
    })
    selectedIds.value = next
  },
)

function isGroupAllSelected(groupItems: IngestBatchItemInfo[]): boolean {
  return groupItems.length > 0 && groupItems.every((item) => selectedIds.value.has(item.executionId))
}

function toggleGroupSelection(groupItems: IngestBatchItemInfo[]) {
  const next = new Set(selectedIds.value)
  if (isGroupAllSelected(groupItems)) {
    groupItems.forEach((item) => next.delete(item.executionId))
  } else {
    groupItems.forEach((item) => next.add(item.executionId))
  }
  selectedIds.value = next
}

function onToggleSelect(executionId: number) {
  const next = new Set(selectedIds.value)
  if (next.has(executionId)) {
    next.delete(executionId)
  } else {
    next.add(executionId)
  }
  selectedIds.value = next
}

function rejectSelected() {
  if (selectedIds.value.size === 0) return
  emit('cancel-items', Array.from(selectedIds.value))
  selectedIds.value = new Set()
}

function onConfirm(executionId: number, guidance?: string) {
  emit('confirm', executionId, guidance)
}

function onReanalyze(executionId: number, guidance?: string) {
  emit('reanalyze', executionId, guidance)
}

function onRetry(executionId: number) {
  emit('retry', executionId)
}

function onView(executionId: number) {
  emit('view', executionId)
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
          v-if="group.key === 'failed' && group.retryableCount >= 2"
          class="review-inbox__group-action"
          type="button"
          :disabled="busy"
          @click="emit('retry-all')"
        >
          <RotateCcw :size="12" />
          {{ t('ingest.inboxRetryAll') }}
        </button>
        <label v-if="group.key === 'awaiting'" class="review-inbox__select-all">
          <input
            type="checkbox"
            :checked="isGroupAllSelected(group.items)"
            :disabled="busy"
            @change="toggleGroupSelection(group.items)"
          />
          {{ t('ingest.inboxSelectAll') }}
        </label>
        <button
          v-if="group.key === 'awaiting' && selectedCount > 0"
          class="review-inbox__group-action review-inbox__group-action--danger"
          type="button"
          :disabled="busy"
          @click="rejectSelected"
        >
          <XCircle :size="12" />
          {{ t('ingest.inboxRejectSelected', [selectedCount]) }}
        </button>
      </div>
      <div class="review-inbox__group-items">
        <InboxItemCard
          v-for="item in group.items"
          :key="item.executionId"
          :item="item"
          :busy="busy"
          :selectable="group.key === 'awaiting'"
          :selected="selectedIds.has(item.executionId)"
          @confirm="onConfirm"
          @reanalyze="onReanalyze"
          @retry="onRetry"
          @view="onView"
          @toggle-select="onToggleSelect"
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

.review-inbox__group-action--danger {
  color: var(--error-strong);
}

.review-inbox__select-all {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  cursor: pointer;
  user-select: none;
}

.review-inbox__select-all input {
  cursor: pointer;
}

.review-inbox__group-items {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
</style>
