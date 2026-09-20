<script setup lang="ts">
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { Layers, Plus, X } from 'lucide-vue-next'
import type { IngestBatchInfo } from '@/api/ingest'
import {
  batchQueueState,
  isOpenBatchStatus,
  isTerminalTaskStatus,
  taskQueueState,
  type QueueTaskLike,
  type QueueTone,
} from '../queueModel'

interface QueueRow {
  key: string
  kind: 'task' | 'batch'
  id: number
  name: string
  sub: string
  tone: QueueTone
  icon: Component
  active: boolean
  badge: number
  progressRatio: number | null
  failedRatio: number
  payload: QueueTaskLike | null
}

interface QueueSubgroup {
  key: 'batches' | 'tasks'
  labelKey: string
  rows: QueueRow[]
}

interface QueueSection {
  key: string
  titleKey: string
  hasMixedKinds: boolean
  subgroups: QueueSubgroup[]
}

const props = defineProps<{
  tasks: QueueTaskLike[]
  activeTaskId: number | null
  batches: IngestBatchInfo[]
  selectedBatchId: number | null
  newTaskActive: boolean
}>()

const emit = defineEmits<{
  newTask: []
  selectTask: [executionId: number]
  closeTask: [task: QueueTaskLike]
  selectBatch: [batchId: number]
}>()

const { t } = useI18n()

function buildTaskRow(task: QueueTaskLike): QueueRow {
  const display = taskQueueState(task)
  const name = task.sourceName || t('ingest.materialFallback', [task.executionId])
  let sub = t(display.labelKey)
  if (task.isPhaseRunning) {
    sub = `${sub} · ${Math.round(task.progress * 100)}%`
  }
  return {
    key: `task-${task.executionId}`,
    kind: 'task',
    id: task.executionId,
    name,
    sub,
    tone: display.tone,
    icon: display.icon,
    active: task.executionId === props.activeTaskId,
    badge: 0,
    progressRatio: null,
    failedRatio: 0,
    payload: task,
  }
}

function buildBatchRow(batch: IngestBatchInfo): QueueRow {
  const display = batchQueueState(batch)
  let sub = `${t('ingest.batchProgress', [batch.completedCount, batch.totalCount])} · ${t(display.labelKey)}`
  if (batch.failedCount > 0) {
    sub = `${sub} · ${t('ingest.batchChipFailed', [batch.failedCount])}`
  }
  return {
    key: `batch-${batch.batchId}`,
    kind: 'batch',
    id: batch.batchId,
    name: `${t('ingest.batchSelectLabel')} #${batch.batchId}`,
    sub,
    tone: display.tone,
    icon: Layers,
    active: batch.batchId === props.selectedBatchId,
    badge: batch.awaitingCount,
    progressRatio: batch.totalCount > 0 ? batch.completedCount / batch.totalCount : 0,
    failedRatio: batch.totalCount > 0 ? batch.failedCount / batch.totalCount : 0,
    payload: null,
  }
}

const sections = computed<QueueSection[]>(() => {
  const openBatches = props.batches.filter(b => isOpenBatchStatus(b.status))
  const historyBatches = props.batches.filter(b => !isOpenBatchStatus(b.status))
  const activeTasks = props.tasks.filter(task => !isTerminalTaskStatus(task.status))
  const historyTasks = props.tasks.filter(task => isTerminalTaskStatus(task.status))
  const buildSection = (key: string, titleKey: string, batchRows: QueueRow[], taskRows: QueueRow[]): QueueSection => {
    const subgroups: QueueSubgroup[] = [
      { key: 'batches', labelKey: 'ingest.queueGroupBatches', rows: batchRows },
      { key: 'tasks', labelKey: 'ingest.queueGroupTasks', rows: taskRows },
    ]
    return {
      key,
      titleKey,
      hasMixedKinds: batchRows.length > 0 && taskRows.length > 0,
      subgroups: subgroups.filter(subgroup => subgroup.rows.length > 0),
    }
  }
  return [
    buildSection('active', 'ingest.queueSectionActive', openBatches.map(buildBatchRow), activeTasks.map(buildTaskRow)),
    buildSection('history', 'ingest.queueSectionHistory', historyBatches.map(buildBatchRow), historyTasks.map(buildTaskRow)),
  ].filter(section => section.subgroups.length > 0)
})

const isEmpty = computed(() => props.tasks.length === 0 && props.batches.length === 0)

function onRowClick(row: QueueRow) {
  if (row.kind === 'batch') {
    emit('selectBatch', row.id)
  } else {
    emit('selectTask', row.id)
  }
}

function onRowClose(row: QueueRow) {
  if (row.kind === 'task' && row.payload) {
    emit('closeTask', row.payload)
  }
}
</script>

<template>
  <aside class="ingest-queue" :aria-label="t('ingest.queueTitle')">
    <button
      type="button"
      :class="['ingest-queue__new', { 'ingest-queue__new--active': newTaskActive }]"
      @click="emit('newTask')"
    >
      <Plus :size="16" />
      <span>{{ t('ingest.queueNewTask') }}</span>
    </button>

    <div v-if="isEmpty" class="ingest-queue__empty">
      <p class="ingest-queue__empty-title">{{ t('ingest.queueEmpty') }}</p>
      <p class="ingest-queue__empty-hint">{{ t('ingest.queueEmptyHint') }}</p>
    </div>

    <section v-for="section in sections" :key="section.key" class="ingest-queue__section">
      <h2 class="ingest-queue__section-title">{{ t(section.titleKey) }}</h2>
      <div v-for="subgroup in section.subgroups" :key="subgroup.key" class="ingest-queue__subgroup">
        <h3 v-if="section.hasMixedKinds" class="ingest-queue__subgroup-title">{{ t(subgroup.labelKey) }}</h3>
        <ul class="ingest-queue__list">
          <li v-for="row in subgroup.rows" :key="row.key">
            <div
              :class="['ingest-queue__item', { 'ingest-queue__item--active': row.active }]"
              role="button"
              tabindex="0"
              @click="onRowClick(row)"
              @keydown.enter="onRowClick(row)"
              @keydown.space.prevent="onRowClick(row)"
            >
              <component
                :is="row.icon"
                :size="14"
                :class="['ingest-queue__item-icon', `ingest-queue__item-icon--${row.tone}`, { 'ingest-queue__item-icon--spin': row.tone === 'running' }]"
              />
              <div class="ingest-queue__item-body">
                <span class="ingest-queue__item-name" :title="row.name">{{ row.name }}</span>
                <span class="ingest-queue__item-sub">{{ row.sub }}</span>
                <span
                  v-if="row.progressRatio !== null"
                  class="ingest-queue__item-progress"
                  role="progressbar"
                  :aria-valuenow="Math.round(row.progressRatio * 100)"
                  aria-valuemin="0"
                  aria-valuemax="100"
                >
                  <span class="ingest-queue__item-progress-fill" :style="{ width: `${row.progressRatio * 100}%` }"></span>
                  <span
                    v-if="row.failedRatio > 0"
                    class="ingest-queue__item-progress-fill ingest-queue__item-progress-fill--failed"
                    :style="{ width: `${row.failedRatio * 100}%` }"
                  ></span>
                </span>
              </div>
              <span v-if="row.badge > 0" class="ingest-queue__badge">{{ row.badge }}</span>
              <button
                v-if="row.kind === 'task'"
                type="button"
                class="ingest-queue__item-close"
                :title="t('ingest.closeTask')"
                :aria-label="t('ingest.closeTask')"
                @click.stop="onRowClose(row)"
              >
                <X :size="12" />
              </button>
            </div>
          </li>
        </ul>
      </div>
    </section>
  </aside>
</template>

<style scoped>
.ingest-queue {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-3);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  overflow-y: auto;
  scrollbar-width: thin;
}

.ingest-queue__new {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  width: 100%;
  height: var(--btn-height-sm);
  border: none;
  border-radius: var(--radius-sm);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  cursor: pointer;
  transition: background var(--transition-fast), box-shadow var(--transition-fast);
  flex-shrink: 0;
}

.ingest-queue__new:hover {
  background: var(--accent-hover);
}

.ingest-queue__new--active {
  box-shadow: 0 0 0 3px var(--accent-light);
}

.ingest-queue__empty {
  padding: var(--space-6) var(--space-3);
  text-align: center;
}

.ingest-queue__empty-title {
  margin: 0;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
}

.ingest-queue__empty-hint {
  margin: var(--space-1) 0 0;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.ingest-queue__section + .ingest-queue__section {
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}

.ingest-queue__section-title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-tertiary);
  letter-spacing: 0.02em;
}

.ingest-queue__subgroup + .ingest-queue__subgroup {
  margin-top: var(--space-2);
}

.ingest-queue__subgroup-title {
  margin: 0 0 var(--space-1);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: var(--text-tertiary);
}

.ingest-queue__list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}

.ingest-queue__item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-2);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.ingest-queue__item:hover {
  background: var(--bg-secondary);
}

.ingest-queue__item:focus-visible {
  outline: 2px solid var(--accent-primary);
  outline-offset: -2px;
}

.ingest-queue__item--active {
  background: var(--accent-light);
  border-color: var(--accent-primary);
}

.ingest-queue__item-icon {
  flex-shrink: 0;
  color: var(--text-tertiary);
}

.ingest-queue__item-icon--running,
.ingest-queue__item-icon--attention {
  color: var(--accent-primary);
}

.ingest-queue__item-icon--paused {
  color: var(--warning);
}

.ingest-queue__item-icon--failed {
  color: var(--error);
}

.ingest-queue__item-icon--done {
  color: var(--success);
}

.ingest-queue__item-icon--spin {
  animation: ingest-queue-spin 1s linear infinite;
}

@keyframes ingest-queue-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.ingest-queue__item-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ingest-queue__item-name {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ingest-queue__item-sub {
  font-size: var(--font-caption);
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ingest-queue__item-progress {
  display: flex;
  height: 3px;
  margin-top: 2px;
  border-radius: var(--radius-full);
  background: var(--bg-tertiary);
  overflow: hidden;
}

.ingest-queue__item-progress-fill {
  height: 100%;
  background: var(--accent-primary);
  transition: width var(--transition-normal);
}

.ingest-queue__item-progress-fill--failed {
  background: var(--error);
}

.ingest-queue__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 var(--space-1);
  border-radius: var(--radius-full);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  flex-shrink: 0;
}

.ingest-queue__item-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
  flex-shrink: 0;
}

.ingest-queue__item:hover .ingest-queue__item-close,
.ingest-queue__item:focus-within .ingest-queue__item-close,
.ingest-queue__item--active .ingest-queue__item-close {
  opacity: 1;
}

.ingest-queue__item-close:hover {
  color: var(--error);
  background: var(--error-light);
}

@media (max-width: 767px) {
  .ingest-queue__item-close {
    opacity: 1;
  }
}
</style>
