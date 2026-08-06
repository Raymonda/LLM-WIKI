<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Loader2, Check, AlertTriangle, Clock, Cpu, Sparkles, Cog, User, Layers, PauseCircle } from 'lucide-vue-next'
import {
  INGEST_STEPS,
  PHASE2_PARALLEL_GROUPS,
  formatDuration,
  type IngestPhase,
  type StepState,
  type StepType,
} from '../progressModel'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    stepStates: StepState[]
    phase: IngestPhase
    includeParse?: boolean
    nowMs?: number
    collapsed?: boolean
    paused?: boolean
  }>(),
  {
    includeParse: true,
    nowMs: () => Date.now(),
    collapsed: false,
    paused: false,
  },
)

const visibleSteps = computed(() =>
  INGEST_STEPS.filter(s => s.phase === props.phase && (props.includeParse || s.name !== 'UPLOAD')),
)

const parallelGroupForPhase = computed(() =>
  props.phase === 'phase2' ? PHASE2_PARALLEL_GROUPS : [],
)

const parallelStepNames = computed(() => {
  const names = new Set<string>()
  for (const group of parallelGroupForPhase.value) {
    for (const name of group.stepNames) names.add(name)
  }
  return names
})

function isParallelStep(name: string): boolean {
  return parallelStepNames.value.has(name)
}

function parallelGroupBeforeStep(name: string): { label: string; stepNames: string[] } | null {
  for (const group of parallelGroupForPhase.value) {
    if (group.stepNames.includes(name)) return group
  }
  return null
}

const runningParallelCount = computed(() => {
  let count = 0
  for (const name of parallelStepNames.value) {
    const state = props.stepStates.find(s => s.name === name)
    if (state?.status === 'running') count++
  }
  return count
})

function stateOf(name: string): StepState | undefined {
  return props.stepStates.find(s => s.name === name)
}

function iconFor(type: StepType) {
  if (type === 'ai') return Sparkles
  if (type === 'python') return Cpu
  if (type === 'user') return User
  return Cog
}

function statusClass(status: string | undefined) {
  if (!status) return 'ingest-step-timeline__row--pending'
  return `ingest-step-timeline__row--${status}`
}

function elapsedText(state: StepState | undefined): string {
  if (!state) return ''
  if (state.status === 'completed' && state.startedAtMs && state.completedAtMs) {
    return formatDuration(state.completedAtMs - state.startedAtMs)
  }
  if (state.status === 'running' && state.startedAtMs) {
    return formatDuration(Math.max(0, props.nowMs - state.startedAtMs))
  }
  return ''
}

function estimateHint(state: StepState, meta: (typeof INGEST_STEPS)[number]): string {
  if (meta.baselineMs < 3000 || !state.startedAtMs) return ''
  const elapsed = Math.max(0, props.nowMs - state.startedAtMs)
  const remaining = Math.max(0, meta.baselineMs - elapsed)
  if (elapsed > meta.baselineMs * 1.5) return t('ingest.slowerThanExpected')
  if (remaining < 1000) return t('ingest.aboutToComplete')
  if (remaining < 60_000) return t('ingest.estimatedSeconds', [Math.ceil(remaining / 1000)])
  return t('ingest.estimatedMinutes', [Math.ceil(remaining / 60_000)])
}
</script>

<template>
  <ul class="ingest-step-timeline" :class="{ 'ingest-step-timeline--collapsed': collapsed }">
    <template v-for="meta in visibleSteps" :key="meta.name">
      <!-- 并行组 header -->
      <li
        v-if="isParallelStep(meta.name) && parallelGroupBeforeStep(meta.name)?.stepNames[0] === meta.name && runningParallelCount > 0"
        class="ingest-step-timeline__parallel-header"
      >
        <Layers :size="14" class="ingest-step-timeline__parallel-icon" />
        <span class="ingest-step-timeline__parallel-label">{{ t('ingest.parallelGroupLabel', [parallelGroupBeforeStep(meta.name)?.label || '', runningParallelCount]) }}</span>
      </li>
      <li
        class="ingest-step-timeline__row"
        :class="[
          statusClass(stateOf(meta.name)?.status),
          `ingest-step-timeline__row--type-${meta.type}`,
          { 'ingest-step-timeline__row--parallel': isParallelStep(meta.name) },
        ]"
      >
        <div class="ingest-step-timeline__rail" aria-hidden="true">
          <div class="ingest-step-timeline__dot">
            <Loader2 v-if="stateOf(meta.name)?.status === 'running' && !props.paused" :size="12" class="ingest-step-timeline__spin" />
            <PauseCircle v-else-if="stateOf(meta.name)?.status === 'running' && props.paused" :size="12" style="color: var(--warning)" />
            <Check v-else-if="stateOf(meta.name)?.status === 'completed'" :size="12" />
            <AlertTriangle v-else-if="stateOf(meta.name)?.status === 'failed'" :size="12" />
            <Clock v-else :size="10" />
          </div>
        </div>

        <div class="ingest-step-timeline__body">
          <div class="ingest-step-timeline__head">
            <component :is="iconFor(meta.type)" :size="14" class="ingest-step-timeline__type-icon" />
            <span class="ingest-step-timeline__label">{{ meta.label }}</span>
            <span v-if="isParallelStep(meta.name)" class="ingest-step-timeline__badge ingest-step-timeline__badge--parallel">
              {{ t('ingest.parallel') }}
            </span>
            <span v-else class="ingest-step-timeline__badge" :class="`ingest-step-timeline__badge--${meta.type}`">
              {{ meta.type === 'ai' ? 'AI' : meta.type === 'python' ? 'Python' : meta.type === 'user' ? t('ingest.you') : t('ingest.system') }}
            </span>
            <span v-if="elapsedText(stateOf(meta.name))" class="ingest-step-timeline__elapsed">
              {{ elapsedText(stateOf(meta.name)) }}
            </span>
          </div>
          <div
            v-if="!collapsed && stateOf(meta.name)?.status === 'running'"
            class="ingest-step-timeline__tip"
          >
            <template v-if="estimateHint(stateOf(meta.name)!, meta)">
              <span class="ingest-step-timeline__tip-estimate">{{ estimateHint(stateOf(meta.name)!, meta) }}</span>
              <span class="ingest-step-timeline__tip-sep">·</span>
            </template>
            {{ meta.tip }}
          </div>
          <div
            v-if="!collapsed && stateOf(meta.name)?.status === 'running' && (stateOf(meta.name)?.total ?? 0) > 0"
            class="ingest-step-timeline__subprogress"
          >
            <span class="ingest-step-timeline__subprogress-count">
              {{ stateOf(meta.name)?.current ?? 0 }} / {{ stateOf(meta.name)?.total }}
            </span>
            <template v-if="(stateOf(meta.name)?.avgMsPerUnit ?? 0) > 0">
              <span class="ingest-step-timeline__subprogress-sep">·</span>
              <span class="ingest-step-timeline__subprogress-avg">
                {{ t('ingest.avgPerChunk', [formatDuration(stateOf(meta.name)!.avgMsPerUnit!)]) }}
              </span>
            </template>
          </div>
        </div>
      </li>
    </template>
  </ul>
</template>

<style scoped>
.ingest-step-timeline {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.ingest-step-timeline__row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  position: relative;
  transition: background 200ms ease-out;
}

.ingest-step-timeline__rail {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  padding-top: 2px;
}

.ingest-step-timeline__dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
  border: 1.5px solid transparent;
}

.ingest-step-timeline__spin {
  animation: ingest-step-spin 1s linear infinite;
}

.ingest-step-timeline__body {
  flex: 1 1 auto;
  min-width: 0;
}

.ingest-step-timeline__head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.ingest-step-timeline__label {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  font-weight: 500;
}

.ingest-step-timeline__type-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.ingest-step-timeline__badge {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  letter-spacing: 0.02em;
  background: var(--accent-light);
  color: var(--accent-primary);
}
.ingest-step-timeline__badge--ai {
  background: var(--accent-light);
  color: var(--accent-primary);
}
.ingest-step-timeline__badge--python {
  background: var(--info-light);
  color: var(--info);
}
.ingest-step-timeline__badge--system {
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
}
.ingest-step-timeline__badge--user {
  background: var(--warning-light);
  color: var(--warning);
}

.ingest-step-timeline__badge--parallel {
  background: var(--info-light);
  color: var(--info);
}

.ingest-step-timeline__parallel-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--info-light);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--info);
}

.ingest-step-timeline__parallel-icon {
  flex-shrink: 0;
}

.ingest-step-timeline__parallel-label {
  font-weight: 500;
}

.ingest-step-timeline__row--parallel {
  padding-left: var(--space-5);
}

.ingest-step-timeline__row--parallel .ingest-step-timeline__label {
  font-style: italic;
}

.ingest-step-timeline__elapsed {
  font-size: 11px;
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
  margin-left: auto;
}

.ingest-step-timeline__tip {
  margin-top: var(--space-1);
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}

.ingest-step-timeline__tip-estimate {
  font-weight: 600;
  color: var(--accent-primary);
}

.ingest-step-timeline__tip-sep {
  color: var(--text-tertiary);
  margin: 0 2px;
}

.ingest-step-timeline__subprogress {
  margin-top: 2px;
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: 11px;
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
}
.ingest-step-timeline__subprogress-count {
  color: var(--accent-primary);
  font-weight: 600;
}
.ingest-step-timeline__subprogress-sep {
  color: var(--border-primary);
}

/* --- 状态样式 --- */
.ingest-step-timeline__row--running {
  background: var(--accent-light);
}
.ingest-step-timeline__row--running .ingest-step-timeline__dot {
  background: var(--accent-primary);
  color: var(--text-on-accent);
  box-shadow: 0 0 0 3px var(--accent-light);
}
.ingest-step-timeline__row--running .ingest-step-timeline__label {
  color: var(--accent-primary);
  font-weight: 600;
}

.ingest-step-timeline__row--completed .ingest-step-timeline__dot {
  background: var(--success);
  color: var(--text-on-accent);
}
.ingest-step-timeline__row--completed .ingest-step-timeline__label {
  color: var(--text-secondary);
}

.ingest-step-timeline__row--failed .ingest-step-timeline__dot {
  background: var(--error);
  color: var(--text-on-accent);
}
.ingest-step-timeline__row--failed .ingest-step-timeline__label {
  color: var(--error);
  font-weight: 600;
}

.ingest-step-timeline__row--paused .ingest-step-timeline__dot {
  background: var(--warning);
  color: var(--text-on-accent);
}

.ingest-step-timeline__row--paused .ingest-step-timeline__label {
  color: var(--warning);
  font-weight: 500;
}

.ingest-step-timeline__row--pending .ingest-step-timeline__label {
  color: var(--text-tertiary);
}

.ingest-step-timeline--collapsed .ingest-step-timeline__row {
  padding: var(--space-1) var(--space-2);
}

@keyframes ingest-step-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .ingest-step-timeline__spin {
    animation: none;
  }
  .ingest-step-timeline__row {
    transition: none;
  }
}
</style>
