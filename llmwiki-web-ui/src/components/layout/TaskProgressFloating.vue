<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  CheckCircle2, AlertTriangle, Loader2, X, XCircle,
  ArrowRight, Merge, ShieldAlert
} from 'lucide-vue-next'
import { useTaskProgressStore, type BackgroundTask } from '@/stores/taskProgress'
import { useIngestProgressStore } from '@/stores/ingestProgress'

const props = defineProps<{
  ingestFloatingActive?: boolean
}>()

const store = useTaskProgressStore()
const ingestStore = useIngestProgressStore()
const router = useRouter()
const { t } = useI18n()

const taskIcon = (type: string) => {
  switch (type) {
    case 'merge': return Merge
    case 'conflict_resolve': return ShieldAlert
    default: return Loader2
  }
}

const phaseLabel = (task: BackgroundTask) => {
  switch (task.status) {
    case 'completed': return t('harness.taskPhaseCompleted')
    case 'failed': return t('harness.taskPhaseFailed')
    case 'cancelled': return t('harness.taskPhaseCancelled')
    default: return task.currentStepName || t('harness.taskPhaseDefault')
  }
}

const bottomOffset = computed(() => {
  const ingestActive = props.ingestFloatingActive ?? ingestStore.active
  if (ingestActive) return 'calc(var(--space-5) + 76px)'
  return 'var(--space-5)'
})

const MAX_VISIBLE = 3
const displayTasks = computed(() => store.visibleTasks.slice(0, MAX_VISIBLE))
const extraCount = computed(() => Math.max(0, store.visibleTasks.length - MAX_VISIBLE))

function handleClick(task: BackgroundTask) {
  if (task.status === 'completed' || task.status === 'failed') {
    store.dismissFloating(task.executionId)
  }
  router.push(`/harness/${task.executionId}`)
}

function handleClose(e: Event, task: BackgroundTask) {
  e.stopPropagation()
  store.dismissFloating(task.executionId)
}

const canClose = (task: BackgroundTask) => {
  return task.status === 'completed' || task.status === 'failed' || task.status === 'cancelled'
}
</script>

<template>
  <div
    v-if="store.hasVisibleTasks"
    class="task-floating-container"
    :style="{ bottom: bottomOffset }"
  >
    <TransitionGroup name="task-floating-fade">
      <div
        v-for="task in displayTasks"
        :key="task.executionId"
        class="task-floating"
        :class="`task-floating--${task.status}`"
        role="button"
        tabindex="0"
        @click="handleClick(task)"
        @keydown.enter="handleClick(task)"
        @keydown.space.prevent="handleClick(task)"
      >
        <div class="task-floating__indicator">
          <template v-if="task.status === 'completed'">
            <CheckCircle2 :size="20" />
          </template>
          <template v-else-if="task.status === 'failed'">
            <AlertTriangle :size="20" />
          </template>
          <template v-else-if="task.status === 'cancelled'">
            <XCircle :size="20" />
          </template>
          <template v-else>
            <div class="task-floating__ring-wrap">
              <svg class="task-floating__ring" width="28" height="28" viewBox="0 0 28 28">
                <circle class="task-floating__ring-track" cx="14" cy="14" r="12" fill="none" stroke-width="2.5" />
                <circle
                  class="task-floating__ring-progress"
                  cx="14" cy="14" r="12"
                  fill="none" stroke-width="2.5"
                  :stroke-dasharray="2 * Math.PI * 12"
                  :stroke-dashoffset="2 * Math.PI * 12 * (1 - task.progress)"
                  stroke-linecap="round"
                  transform="rotate(-90 14 14)"
                />
              </svg>
              <component :is="taskIcon(task.type)" :size="12" class="task-floating__spin" />
            </div>
          </template>
        </div>

        <div class="task-floating__body">
          <div class="task-floating__head">
            <span class="task-floating__title">{{ task.title }}</span>
          </div>
          <div class="task-floating__sub">
            <template v-if="task.status === 'completed'">{{ t('harness.taskClickResult') }}</template>
            <template v-else-if="task.status === 'failed'">{{ task.errorMessage || t('harness.taskPhaseFailed') }}</template>
            <template v-else>
              {{ phaseLabel(task) }}
              <template v-if="task.totalSteps > 0"> · {{ task.completedSteps }}/{{ task.totalSteps }}</template>
            </template>
          </div>
        </div>

        <div class="task-floating__tail">
          <button
            v-if="canClose(task)"
            class="task-floating__close"
            :aria-label="t('common.close')"
            @click.stop="handleClose($event, task)"
          >
            <X :size="14" />
          </button>
          <ArrowRight v-else :size="14" class="task-floating__arrow" />
        </div>
      </div>
    </TransitionGroup>

    <div v-if="extraCount > 0" class="task-floating__more">
      {{ t('harness.taskCountMore', [extraCount]) }}
    </div>
  </div>
</template>

<style scoped>
.task-floating-container {
  position: fixed;
  right: var(--space-5);
  z-index: 2000;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  transition: bottom 200ms ease;
}

.task-floating {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  min-width: 260px;
  max-width: 340px;
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg, 0 8px 24px rgba(0, 0, 0, 0.12));
  cursor: pointer;
  transition: transform 180ms ease, box-shadow 180ms ease, border-color 180ms ease;
}

.task-floating:hover {
  transform: translateY(-1px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.16);
  border-color: var(--accent-primary);
}

.task-floating__indicator {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--accent-light);
  color: var(--accent-primary);
}

.task-floating--completed .task-floating__indicator {
  background: var(--success-light);
  color: var(--success);
}

.task-floating--failed .task-floating__indicator {
  background: var(--error-light);
  color: var(--error);
}

.task-floating--failed {
  border-color: var(--error);
}

.task-floating--cancelled .task-floating__indicator {
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
}

.task-floating--cancelled {
  border-color: var(--border-default);
  opacity: 0.85;
}

.task-floating__ring-wrap {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}

.task-floating__ring {
  position: absolute;
}

.task-floating__ring-track {
  stroke: var(--border-primary, rgba(0, 0, 0, 0.08));
}

.task-floating__ring-progress {
  stroke: var(--accent-primary);
  transition: stroke-dashoffset 300ms ease;
}

.task-floating__spin {
  position: relative;
  animation: task-spin 1s linear infinite;
  color: var(--accent-primary);
}

.task-floating__body {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.task-floating__head {
  display: flex;
  align-items: baseline;
  gap: var(--space-1);
}

.task-floating__title {
  font-size: var(--font-body-sm);
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-floating__sub {
  font-size: 11px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-floating__tail {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex-shrink: 0;
  color: var(--text-tertiary);
}

.task-floating__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--text-tertiary);
  cursor: pointer;
  transition: background-color 150ms ease, color 150ms ease;
}

.task-floating__close:hover {
  color: var(--text-primary);
  background: var(--hover-bg, rgba(0, 0, 0, 0.06));
}

.task-floating__arrow {
  color: var(--text-tertiary);
}

.task-floating__more {
  text-align: center;
  font-size: 11px;
  color: var(--text-tertiary);
  padding: 2px 0;
}

@keyframes task-spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .task-floating__spin { animation: none; }
  .task-floating, .task-floating__ring-progress { transition: none; }
}

.task-floating-fade-enter-active,
.task-floating-fade-leave-active {
  transition: opacity 180ms ease, transform 180ms ease;
}
.task-floating-fade-enter-from,
.task-floating-fade-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
