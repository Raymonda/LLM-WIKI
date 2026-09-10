<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { CheckCircle2, AlertTriangle, Loader2, X, XCircle, ArrowRight, PauseCircle, ClipboardCheck } from 'lucide-vue-next'
import { useIngestProgressStore } from '@/stores/ingestProgress'
import { useIngestBatchStore } from '@/stores/ingestBatch'
import { formatRemaining } from '@/views/ingest/progressModel'

const store = useIngestProgressStore()
const batchStore = useIngestBatchStore()
const router = useRouter()
const route = useRoute()
const { t } = useI18n()

const isEditorRoute = computed(() => {
  return typeof route.path === 'string' && route.path.includes('/editor')
})

const AUTO_DISMISS_MS = 30_000
const autoDismissTimer = ref<number | null>(null)

const mostUrgentTask = computed(() => {
  const all = store.allTaskSummaries.filter(t => !t.floatingDismissed)
  if (all.length === 0) return null
  const failed = all.find(t => t.status === 'failed' || t.status === 'budget_exhausted')
  if (failed) return failed
  const cancelled = all.find(t => t.status === 'cancelled')
  if (cancelled) return cancelled
  const running = all.find(t => t.isPhaseRunning)
  if (running) return running
  const reviewing = all.find(t => t.currentStep === 'review')
  if (reviewing) return reviewing
  const paused = all.find(t => t.status === 'paused')
  if (paused) return paused
  const done = all.find(t => t.currentStep === 'done')
  if (done) return done
  return null
})

const extraTaskCount = computed(() => {
  return store.runningTaskCount - 1
})

const show = computed(() => {
  return mostUrgentTask.value !== null
})

const awaitingBatch = computed(() => {
  return batchStore.inbox.find(b => b.awaitingCount > 0) ?? null
})

const showBatchFloating = computed(() => {
  return !show.value && awaitingBatch.value !== null
})

function openBatchReview() {
  const batch = awaitingBatch.value
  if (!batch) return
  router.push({ path: '/ingest', query: { batch: String(batch.batchId) } })
}

const ringSize = 28
const ringRadius = 12
const ringCircumference = computed(() => 2 * Math.PI * ringRadius)
const ringDashOffset = computed(() => {
  const ratio = Math.max(0, Math.min(1, displayProgress.value))
  return ringCircumference.value * (1 - ratio)
})

const displayPhase = computed(() => {
  const t = mostUrgentTask.value
  if (!t) return 'analyzing'
  if (t.status === 'failed' || t.status === 'budget_exhausted') return 'failed'
  if (t.status === 'cancelled') return 'cancelled'
  if (t.status === 'paused') return 'paused'
  if (t.currentStep === 'done') return 'done'
  if (t.currentStep === 'review') return 'review'
  if (t.isPhaseRunning) return 'executing'
  return 'analyzing'
})

const displayProgress = computed(() => mostUrgentTask.value?.progress ?? 0)

const displaySourceName = computed(() => mostUrgentTask.value?.sourceName || '')

const phaseLabel = computed(() => {
  switch (displayPhase.value) {
    case 'analyzing': return t('ingest.floatingPhaseAnalyzing')
    case 'executing': return t('ingest.floatingPhaseExecuting')
    case 'done': return t('ingest.floatingPhaseDone')
    case 'failed': return t('ingest.floatingPhaseFailed')
    case 'cancelled': return t('ingest.floatingPhaseCancelled')
    case 'paused': return t('ingest.floatingPhasePaused')
    case 'review': return t('ingest.floatingPhaseReview')
    default: return t('ingest.floatingPhaseDefault')
  }
})

const secondaryLine = computed(() => {
  const task = mostUrgentTask.value
  if (!task) return ''
  if (task.status === 'paused') return t('ingest.floatingProgressSaved')
  if (task.status === 'cancelled') return t('ingest.floatingPhaseCancelled')
  if (task.status === 'failed' || task.status === 'budget_exhausted') return task.pipelineError || t('ingest.floatingFailedRetry')
  if (task.currentStep === 'done') return t('ingest.floatingClickResult')
  if (task.currentStep === 'review') return t('ingest.floatingReviewHint')
  if (store.currentTip) return store.currentTip
  return store.remainingMs > 0 ? t('ingest.floatingRemaining', [formatRemaining(store.remainingMs)]) : ''
})

function handleOpen() {
  if (mostUrgentTask.value) {
    store.setActiveTask(mostUrgentTask.value.executionId)
  }
  if (displayPhase.value === 'done' || displayPhase.value === 'failed') {
    store.dismissFloating()
  }
  router.push('/ingest')
}

function handleClose(e: Event) {
  e.stopPropagation()
  store.dismissAllFloating()
}

const canClose = computed(() => {
  const t = mostUrgentTask.value
  if (!t) return false
  return t.currentStep === 'done' || t.status === 'failed' || t.status === 'budget_exhausted' || t.status === 'cancelled' || t.status === 'paused'
})

watch(show, (visible) => {
  if (autoDismissTimer.value) {
    clearTimeout(autoDismissTimer.value)
    autoDismissTimer.value = null
  }
  if (!visible) return
  const t = mostUrgentTask.value
  if (!t) return
  if (t.currentStep === 'done' || t.status === 'failed' || t.status === 'budget_exhausted' || t.status === 'cancelled') {
    autoDismissTimer.value = window.setTimeout(() => {
      autoDismissTimer.value = null
      store.dismissFloatingById(t.executionId)
    }, AUTO_DISMISS_MS)
  }
})

onMounted(() => {
  batchStore.startPolling()
})

onUnmounted(() => {
  batchStore.stopPolling()
})
</script>

<template>
  <Transition name="ingest-floating-fade">
    <div
      v-if="show"
      class="ingest-floating"
      :class="[`ingest-floating--${displayPhase}`, { 'ingest-floating--editor': isEditorRoute }]"
      role="button"
      tabindex="0"
      @click="handleOpen"
      @keydown.enter="handleOpen"
      @keydown.space.prevent="handleOpen"
    >
      <div class="ingest-floating__indicator" :class="{ 'ingest-floating__indicator--cancelled': displayPhase === 'cancelled', 'ingest-floating__indicator--paused': displayPhase === 'paused' }">
        <template v-if="displayPhase === 'done'">
          <CheckCircle2 :size="20" />
        </template>
        <template v-else-if="displayPhase === 'cancelled'">
          <XCircle :size="20" />
        </template>
        <template v-else-if="displayPhase === 'paused'">
          <PauseCircle :size="20" />
        </template>
        <template v-else-if="displayPhase === 'review'">
          <ClipboardCheck :size="20" />
        </template>
        <template v-else-if="displayPhase === 'failed'">
          <AlertTriangle :size="20" />
        </template>
        <template v-else>
          <svg class="ingest-floating__ring" :width="ringSize" :height="ringSize" :viewBox="`0 0 ${ringSize} ${ringSize}`">
            <circle
              class="ingest-floating__ring-track"
              :cx="ringSize / 2"
              :cy="ringSize / 2"
              :r="ringRadius"
              fill="none"
              stroke-width="2.5"
            />
            <circle
              class="ingest-floating__ring-progress"
              :cx="ringSize / 2"
              :cy="ringSize / 2"
              :r="ringRadius"
              fill="none"
              stroke-width="2.5"
              :stroke-dasharray="ringCircumference"
              :stroke-dashoffset="ringDashOffset"
              stroke-linecap="round"
              :transform="`rotate(-90 ${ringSize / 2} ${ringSize / 2})`"
            />
          </svg>
          <Loader2 :size="10" class="ingest-floating__spin" />
        </template>
      </div>

      <div class="ingest-floating__body">
        <div class="ingest-floating__head">
          <span class="ingest-floating__phase">{{ phaseLabel }}</span>
          <span v-if="displaySourceName" class="ingest-floating__source">· {{ displaySourceName }}</span>
        </div>
        <div v-if="secondaryLine" class="ingest-floating__sub">{{ secondaryLine }}</div>
      </div>

      <div class="ingest-floating__tail">
        <span v-if="extraTaskCount > 0" class="ingest-floating__badge">{{ extraTaskCount }}</span>
        <span
          v-if="batchStore.awaitingTotal > 0"
          class="ingest-floating__badge ingest-floating__badge--awaiting"
          :title="t('ingest.inboxTitle')"
        >{{ batchStore.awaitingTotal }}</span>
        <button v-if="canClose" class="ingest-floating__close" :aria-label="t('common.close')" @click.stop="handleClose">
          <X :size="14" />
        </button>
        <ArrowRight v-else :size="14" class="ingest-floating__arrow" />
      </div>
    </div>
  </Transition>

  <Transition name="ingest-floating-fade">
    <div
      v-if="showBatchFloating && awaitingBatch"
      class="ingest-floating ingest-floating--review"
      :class="{ 'ingest-floating--editor': isEditorRoute }"
      role="button"
      tabindex="0"
      @click="openBatchReview"
      @keydown.enter="openBatchReview"
      @keydown.space.prevent="openBatchReview"
    >
      <div class="ingest-floating__indicator">
        <ClipboardCheck :size="20" />
      </div>

      <div class="ingest-floating__body">
        <div class="ingest-floating__head">
          <span class="ingest-floating__phase">{{ t('ingest.inboxTitle') }}</span>
        </div>
        <div class="ingest-floating__sub">{{ t('ingest.batchFloatingAwaiting', [awaitingBatch.awaitingCount]) }}</div>
      </div>

      <div class="ingest-floating__tail">
        <ArrowRight :size="14" class="ingest-floating__arrow" />
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.ingest-floating {
  position: fixed;
  right: var(--space-5);
  bottom: var(--space-5);
  z-index: 2000;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  min-width: 260px;
  max-width: 360px;
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg, 0 8px 24px rgba(0, 0, 0, 0.12));
  cursor: pointer;
  transition: transform 180ms ease, box-shadow 180ms ease, border-color 180ms ease;
}

.ingest-floating:hover {
  transform: translateY(-1px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.16);
  border-color: var(--accent-primary);
}

.ingest-floating__indicator {
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

.ingest-floating__ring {
  position: absolute;
  inset: 0;
  margin: auto;
}

.ingest-floating__ring-track {
  stroke: var(--border-primary, rgba(0, 0, 0, 0.08));
}

.ingest-floating__ring-progress {
  stroke: var(--accent-primary);
  transition: stroke-dashoffset 300ms ease;
}

.ingest-floating__spin {
  position: relative;
  animation: ingest-floating-spin 1s linear infinite;
  color: var(--accent-primary);
}

.ingest-floating__body {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ingest-floating__head {
  display: flex;
  align-items: baseline;
  gap: var(--space-1);
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ingest-floating__source {
  font-weight: 400;
  color: var(--text-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ingest-floating__sub {
  font-size: 11px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ingest-floating__tail {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex-shrink: 0;
  color: var(--text-tertiary);
}

.ingest-floating__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border-radius: var(--radius-full);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
}

.ingest-floating__badge--awaiting {
  background: var(--warning);
}

.ingest-floating__close {
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

.ingest-floating__close:hover {
  color: var(--text-primary);
  background: var(--hover-bg, rgba(0, 0, 0, 0.06));
}

.ingest-floating__arrow {
  color: var(--text-tertiary);
}

.ingest-floating--done .ingest-floating__indicator {
  background: var(--success-light);
  color: var(--success);
}

.ingest-floating--failed .ingest-floating__indicator {
  background: var(--error-light);
  color: var(--error);
}

.ingest-floating--failed {
  border-color: var(--error);
}

.ingest-floating__indicator--cancelled {
  background: var(--bg-tertiary) !important;
  color: var(--text-tertiary) !important;
}

.ingest-floating--cancelled .ingest-floating__indicator {
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
}

.ingest-floating--cancelled {
  border-color: var(--border-default);
  opacity: 0.85;
}

.ingest-floating--paused .ingest-floating__indicator {
  background: var(--warning-light);
  color: var(--warning);
}

.ingest-floating--paused {
  border-color: var(--warning);
}

.ingest-floating--review .ingest-floating__indicator {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.ingest-floating--review {
  border-color: var(--accent-primary);
}

@keyframes ingest-floating-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .ingest-floating__spin {
    animation: none;
  }
  .ingest-floating,
  .ingest-floating__ring-progress {
    transition: none;
  }
}

.ingest-floating--editor {
  right: auto;
  left: var(--space-5);
}

.ingest-floating-fade-enter-active,
.ingest-floating-fade-leave-active {
  transition: opacity 180ms ease, transform 180ms ease;
}
.ingest-floating-fade-enter-from,
.ingest-floating-fade-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
