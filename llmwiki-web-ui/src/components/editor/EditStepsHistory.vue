<script setup lang="ts">
import { ref } from 'vue'
import type { EditStepInfo } from '@/api/wiki'
import { useI18n } from 'vue-i18n'

defineProps<{
  steps: EditStepInfo[]
  loading?: boolean
}>()

const emit = defineEmits<{
  (e: 'undo', stepId: number): void
  (e: 'undo-all'): void
}>()

const collapsed = ref(false)
const { t } = useI18n()
</script>

<template>
  <div class="edit-steps">
    <div class="edit-steps__header" @click="collapsed = !collapsed">
      <span class="edit-steps__title">{{ t('editor.editHistory') }}</span>
      <div class="edit-steps__header-actions">
        <button
          v-if="steps.length > 0"
          class="edit-steps__undo-all"
          :disabled="loading"
          @click.stop="emit('undo-all')"
        >
          {{ t('editor.undoAll') }}
        </button>
        <svg class="edit-steps__chevron" :class="{ 'edit-steps__chevron--collapsed': collapsed }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
      </div>
    </div>

    <div v-show="!collapsed">

    <div v-if="steps.length === 0" class="edit-steps__empty">
      {{ t('editor.noSteps') }}
    </div>

    <div v-else class="edit-steps__list">
      <div
        v-for="step in steps"
        :key="step.id"
        class="edit-step-item"
      >
        <div class="edit-step-item__header">
          <span class="edit-step-item__number">#{{ step.stepNumber }}</span>
          <span v-if="step.selectedLines" class="edit-step-item__lines">{{ step.selectedLines }}</span>
          <button
            class="edit-step-item__undo"
            :disabled="loading"
            @click="emit('undo', step.id)"
          >
            {{ t('editor.undo') }}
          </button>
        </div>
        <div class="edit-step-item__instruction">
          {{ step.instruction }}
        </div>
      </div>
    </div>
    </div>
  </div>
</template>

<style scoped>
.edit-steps {
  border-top: 1px solid var(--border-subtle);
  padding-top: var(--space-2);
}

.edit-steps__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 var(--space-1) var(--space-2);
  cursor: pointer;
  user-select: none;
}

.edit-steps__header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.edit-steps__chevron {
  color: var(--text-tertiary);
  transition: transform var(--transition-fast);
  flex-shrink: 0;
}

.edit-steps__chevron--collapsed {
  transform: rotate(-90deg);
}

.edit-steps__title {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.edit-steps__undo-all {
  display: inline-flex;
  align-items: center;
  height: var(--btn-height-sm);
  padding: 0 var(--space-2);
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--error);
  font-family: var(--font-body);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.edit-steps__undo-all:hover:not(:disabled) {
  background: var(--error-light);
}

.edit-steps__undo-all:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.edit-steps__empty {
  text-align: center;
  color: var(--text-tertiary);
  font-size: var(--font-caption);
  padding: var(--space-3);
}

.edit-steps__list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  max-height: 200px;
  overflow-y: auto;
}

.edit-step-item {
  padding: var(--space-2) var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--bg-secondary);
  font-size: var(--font-caption);
}

.edit-step-item__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.edit-step-item__number {
  font-weight: var(--weight-bold);
  color: var(--accent-primary);
  font-size: var(--font-caption);
  min-width: 24px;
}

.edit-step-item__lines {
  color: var(--text-tertiary);
  font-family: var(--font-code);
  font-size: var(--font-caption);
}

.edit-step-item__undo {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  height: var(--btn-height-sm);
  padding: 0 var(--space-2);
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--warning);
  font-family: var(--font-body);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
  opacity: 0;
}

.edit-step-item:hover .edit-step-item__undo {
  opacity: 1;
}

.edit-step-item__undo:hover:not(:disabled) {
  background: var(--warning-light);
}

.edit-step-item__undo:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.edit-step-item__instruction {
  margin-top: var(--space-1);
  color: var(--text-secondary);
  line-height: var(--leading-caption);
  font-size: var(--font-caption);
}
</style>
