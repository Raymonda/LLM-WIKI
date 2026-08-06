<script setup lang="ts">
import { computed, ref, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Play, Loader2, CheckCircle2, XCircle } from 'lucide-vue-next'
import { stepLabelPlain, statusLabel as statusLabelUtil } from '@/utils/executionLabels'

const props = defineProps<{
  running: boolean
  error: string | null
  execution: {
    status: string
    completedSteps: number
    totalSteps: number
    currentStepName: string | null
    totalTokens: number
  } | null
  startTime: number | null
}>()

const emit = defineEmits<{
  trigger: []
}>()

const now = ref(Date.now())
let elapsedTimer: ReturnType<typeof setInterval> | null = null

const { t } = useI18n()

if (props.running) {
  elapsedTimer = setInterval(() => { now.value = Date.now() }, 1000)
}

watch(() => props.running, (val) => {
  if (val) {
    elapsedTimer = setInterval(() => { now.value = Date.now() }, 1000)
  } else {
    if (elapsedTimer) {
      clearInterval(elapsedTimer)
      elapsedTimer = null
    }
  }
})

onUnmounted(() => {
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
})

const elapsedSeconds = computed(() => {
  if (!props.startTime) return 0
  return Math.floor((now.value - props.startTime) / 1000)
})

const elapsedDisplay = computed(() => {
  const s = elapsedSeconds.value
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rs = s % 60
  return `${m}m${rs}s`
})

const stepLabel = (name: string) => stepLabelPlain(name)

const statusIcon = computed(() => {
  if (!props.execution) return CheckCircle2
  const s = props.execution.status
  if (s === 'completed') return CheckCircle2
  if (s === 'failed') return XCircle
  return Loader2
})

const statusColor = computed(() => {
  if (!props.execution) return 'var(--text-tertiary)'
  const s = props.execution.status
  if (s === 'completed') return 'var(--success)'
  if (s === 'failed') return 'var(--error)'
  return 'var(--accent-primary)'
})

const statusLabel = computed(() => {
  if (!props.execution) return ''
  return statusLabelUtil(props.execution.status) || props.execution.status
})

const progressPercent = computed(() => {
  if (!props.execution || !props.execution.totalSteps) return 0
  return Math.round((props.execution.completedSteps || 0) / props.execution.totalSteps * 100)
})

const isIndeterminate = computed(() => {
  return props.execution?.status === 'running' && (!props.execution.totalSteps || props.execution.totalSteps === 0)
})

const currentStepDisplay = computed(() => {
  if (!props.execution) return ''
  if (props.execution.status === 'running' && !props.execution.currentStepName) return t('lint.preparing')
  if (!props.execution.currentStepName) return ''
  return stepLabel(props.execution.currentStepName)
})

const progressCountDisplay = computed(() => {
  if (!props.execution) return ''
  if (!props.execution.totalSteps) return ''
  return `${props.execution.completedSteps}/${props.execution.totalSteps}`
})
</script>

<template>
  <div class="lint-trigger">
    <button class="lint-trigger__btn" :disabled="running" @click="emit('trigger')">
      <Loader2 v-if="running" :size="16" class="lint-trigger__spin" />
      <Play v-else :size="16" />
      {{ running ? t('lint.linting') : t('lint.startLint') }}
    </button>

    <div v-if="error" class="lint-trigger__error">{{ error }}</div>

    <div v-if="execution && execution.status === 'running'" class="lint-trigger__progress">
      <div class="lint-trigger__progress-header">
        <Loader2 :size="14" class="lint-trigger__spin" style="color: var(--accent-primary)" />
        <span class="lint-trigger__progress-step">{{ currentStepDisplay }}</span>
        <span v-if="elapsedSeconds > 0" class="lint-trigger__elapsed">{{ elapsedDisplay }}</span>
        <span v-if="progressCountDisplay" class="lint-trigger__progress-count">{{ progressCountDisplay }}</span>
      </div>
      <div class="lint-trigger__progress-bar">
        <div
          class="lint-trigger__progress-fill"
          :class="{ 'lint-trigger__progress-fill--indeterminate': isIndeterminate }"
          :style="isIndeterminate ? {} : { width: progressPercent + '%' }"
        ></div>
      </div>
    </div>

    <div v-if="execution && execution.status !== 'running'" class="lint-trigger__result">
      <component :is="statusIcon" :size="16" :style="{ color: statusColor }" />
      <span :style="{ color: statusColor }" class="lint-trigger__result-label">{{ statusLabel }}</span>
      <span v-if="execution.totalTokens" class="lint-trigger__tokens">Token {{ execution.totalTokens }}</span>
    </div>
  </div>
</template>

<style scoped>
.lint-trigger {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.lint-trigger__btn {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: opacity var(--transition-fast), transform var(--transition-fast);
  white-space: nowrap;
}

.lint-trigger__btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.lint-trigger__btn:not(:disabled):hover {
  opacity: 0.9;
  transform: translateY(-1px);
}

.lint-trigger__btn:not(:disabled):active {
  transform: translateY(0);
}

.lint-trigger__error {
  color: var(--error);
  font-size: var(--font-body-sm);
}

.lint-trigger__progress {
  flex: 1;
  min-width: 200px;
}

.lint-trigger__progress-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.lint-trigger__progress-step {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: var(--accent-primary);
}

.lint-trigger__progress-count {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  margin-left: auto;
}

.lint-trigger__elapsed {
  font-size: var(--font-caption);
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.lint-trigger__progress-bar {
  height: 4px;
  background: var(--border-default);
  border-radius: 2px;
  overflow: hidden;
  margin-top: var(--space-2);
}

.lint-trigger__progress-fill {
  height: 100%;
  background: var(--accent-primary);
  border-radius: 2px;
  transition: width 0.4s ease;
}

.lint-trigger__progress-fill--indeterminate {
  width: 40%;
  animation: lintProgressIndeterminate 1.6s ease-in-out infinite;
}

@keyframes lintProgressIndeterminate {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(350%); }
}

.lint-trigger__result {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.lint-trigger__result-label {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
}

.lint-trigger__tokens {
  font-size: var(--font-caption);
  color: var(--text-secondary);
}

.lint-trigger__spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>