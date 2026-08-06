<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { X, Bot, CheckCircle, AlertTriangle, XCircle, Clock, Loader2, Zap } from 'lucide-vue-next'
import { useExecutionStore } from '@/stores/execution'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

const executionStore = useExecutionStore()

const { t } = useI18n()

const currentExecution = computed(() => executionStore.currentExecution)

const stepIconMap: Record<string, typeof CheckCircle> = {
  completed: CheckCircle,
  running: Loader2,
  pending: Clock,
  waiting: AlertTriangle,
  failed: XCircle,
}

const tokenBudget = 200000

const overallBudgetUsed = computed(() => {
  return executionStore.executions.reduce((sum, e) => sum + e.totalTokens, 0)
})

const overallBudgetPercentage = computed(() => {
  return Math.min((overallBudgetUsed.value / tokenBudget) * 100, 100)
})

const executionTypeLabel = (type: string) => {
  if (type === 'ingest') return t('nav.ingest')
  if (type === 'query') return t('nav.query')
  return t('nav.lint')
}
</script>

<template>
  <Transition name="ai-panel">
    <aside v-if="props.open" class="ai-panel">
      <div class="ai-panel__header">
        <div class="ai-panel__header-title">
          <Bot :size="18" class="ai-panel__header-icon" />
          <span>{{ t('common.aiAssistant') }}</span>
          <span v-if="currentExecution" class="ai-panel__header-status">
            {{ executionTypeLabel(currentExecution.type) }}
          </span>
        </div>
        <button class="ai-panel__close" @click="emit('close')">
          <X :size="16" />
        </button>
      </div>

      <div v-if="currentExecution" class="ai-panel__section">
        <h3 class="ai-panel__section-title">{{ t('common.executionTrace') }}</h3>
        <div class="ai-panel__steps">
          <div
            v-for="step in currentExecution.steps"
            :key="step.id"
            class="ai-panel__step"
            :class="`ai-panel__step--${step.status}`"
          >
            <component
              :is="stepIconMap[step.status]"
              :size="14"
              :class="{ 'ai-panel__step-icon--spin': step.status === 'running' }"
            />
            <span class="ai-panel__step-label">{{ step.label }}</span>
            <span v-if="step.tokensUsed" class="ai-panel__step-tokens tabular-nums">
              {{ step.tokensUsed.toLocaleString() }}
            </span>
          </div>
        </div>
      </div>

      <div v-if="!currentExecution" class="ai-panel__empty">
        <Bot :size="32" class="ai-panel__empty-icon" />
        <p>{{ t('common.aiStandby') }}</p>
        <p class="ai-panel__empty-hint">{{ t('common.aiStandbyHint') }}</p>
      </div>

      <div class="ai-panel__section">
        <h3 class="ai-panel__section-title">
          <Zap :size="14" class="ai-panel__section-icon" />
          {{ t('common.usageConsumption') }}
        </h3>
        <div class="ai-panel__token-bar">
          <div class="ai-panel__token-progress">
            <div
              class="ai-panel__token-fill"
              :style="{ width: `${overallBudgetPercentage}%` }"
            ></div>
          </div>
          <div class="ai-panel__token-info">
            <span class="tabular-nums">{{ overallBudgetUsed.toLocaleString() }}</span>
            <span>/ {{ tokenBudget.toLocaleString() }}</span>
          </div>
        </div>
        <div v-if="currentExecution" class="ai-panel__token-detail">
          {{ t('common.currentExecutionLabel') }}<span class="tabular-nums">{{ currentExecution.totalTokens.toLocaleString() }}</span>
        </div>
      </div>

      <div v-if="currentExecution?.steps.some(s => s.status === 'waiting')" class="ai-panel__section">
        <h3 class="ai-panel__section-title">{{ t('common.approvalRequest') }}</h3>
        <div class="ai-panel__approval">
          <div class="ai-panel__approval-item">
            <AlertTriangle :size="14" class="ai-panel__approval-icon" />
            <span>{{ currentExecution.steps.find(s => s.status === 'waiting')?.label }}</span>
          </div>
          <div class="ai-panel__approval-actions">
            <button class="ai-panel__approval-btn ai-panel__approval-btn--approve">{{ t('common.confirm') }}</button>
            <button class="ai-panel__approval-btn ai-panel__approval-btn--reject">{{ t('common.reject') }}</button>
          </div>
        </div>
      </div>

      <div v-if="executionStore.getRunningExecutions().length > 1" class="ai-panel__section">
        <h3 class="ai-panel__section-title">{{ t('common.otherExecutions') }}</h3>
        <div class="ai-panel__other-list">
          <div
            v-for="exec in executionStore.getRunningExecutions().filter(e => e.id !== currentExecution?.id)"
            :key="exec.id"
            class="ai-panel__other-item"
          >
            <Loader2 :size="14" class="ai-panel__other-icon" />
            <span>{{ executionTypeLabel(exec.type) }}</span>
          </div>
        </div>
      </div>
    </aside>
  </Transition>
</template>

<style scoped>
.ai-panel {
  width: 320px;
  background: var(--bg-secondary);
  border-left: 1px solid var(--border-default);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.ai-panel-enter-active,
.ai-panel-leave-active {
  transition: all var(--transition-normal);
}

.ai-panel-enter-from,
.ai-panel-leave-to {
  width: 0;
  opacity: 0;
}

.ai-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
}

.ai-panel__header-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.ai-panel__header-icon {
  color: var(--accent-primary);
}

.ai-panel__header-status {
  font-size: var(--font-caption);
  color: var(--accent-primary);
  background: var(--accent-light);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
}

.ai-panel__close {
  background: none;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: var(--space-1);
  border-radius: var(--radius-sm);
}

.ai-panel__close:hover {
  color: var(--text-primary);
}

.ai-panel__section {
  padding: var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
}

.ai-panel__section-title {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  margin-bottom: var(--space-3);
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.ai-panel__section-icon {
  color: var(--accent-primary);
}

.ai-panel__steps {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.ai-panel__step {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
}

.ai-panel__step--completed {
  color: var(--success);
  background: var(--success-light);
}

.ai-panel__step--running {
  color: var(--accent-primary);
  background: var(--accent-light);
}

.ai-panel__step--pending {
  color: var(--text-tertiary);
}

.ai-panel__step--waiting {
  color: var(--warning);
  background: var(--warning-light);
}

.ai-panel__step--failed {
  color: var(--error);
  background: var(--error-light);
}

.ai-panel__step-icon--spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.ai-panel__step-label {
  flex: 1;
}

.ai-panel__step-tokens {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.ai-panel__empty {
  padding: var(--space-8) var(--space-4);
  text-align: center;
}

.ai-panel__empty-icon {
  color: var(--text-tertiary);
  margin-bottom: var(--space-3);
}

.ai-panel__empty p {
  color: var(--text-secondary);
  font-size: var(--font-body);
}

.ai-panel__empty-hint {
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
  margin-top: var(--space-2);
}

.ai-panel__token-bar {
  margin-bottom: var(--space-2);
}

.ai-panel__token-progress {
  height: 8px;
  background: var(--bg-tertiary);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.ai-panel__token-fill {
  height: 100%;
  background: var(--accent-primary);
  border-radius: var(--radius-full);
  transition: width var(--transition-normal);
}

.ai-panel__token-info {
  display: flex;
  justify-content: space-between;
  font-size: var(--font-caption);
  color: var(--text-secondary);
  margin-top: var(--space-1);
}

.ai-panel__token-detail {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.ai-panel__approval {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.ai-panel__approval-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
  color: var(--text-primary);
}

.ai-panel__approval-icon {
  color: var(--warning);
}

.ai-panel__approval-actions {
  display: flex;
  gap: var(--space-2);
}

.ai-panel__approval-btn {
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  cursor: pointer;
  border: none;
}

.ai-panel__approval-btn--approve {
  background: var(--success);
  color: var(--text-on-accent);
}

.ai-panel__approval-btn--reject {
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  border: 1px solid var(--btn-secondary-border);
}

.ai-panel__other-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.ai-panel__other-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.ai-panel__other-icon {
  color: var(--accent-primary);
  animation: spin 1s linear infinite;
}
</style>