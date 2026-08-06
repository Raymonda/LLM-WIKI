<script setup lang="ts">
import { useI18n } from 'vue-i18n'

defineProps<{
  visible: boolean
  streaming: boolean
  patchCount: number
  diffMode: 'preview' | 'diff'
  diffAdded: number
  diffRemoved: number
  retryRound?: number
  retryFailedCount?: number
}>()

const emit = defineEmits<{
  (e: 'accept'): void
  (e: 'reject'): void
  (e: 'update:diffMode', mode: 'preview' | 'diff'): void
}>()

const { t } = useI18n()
</script>

<template>
  <Transition name="overlay-slide">
    <div v-if="visible" class="diff-overlay">
      <div class="diff-overlay__inner">
        <!-- Mode tabs (only when done, not streaming) -->
        <div v-if="!streaming" class="diff-overlay__tabs">
          <button
            class="diff-overlay__tab"
            :class="{ 'diff-overlay__tab--active': diffMode === 'diff' }"
            @click="emit('update:diffMode', 'diff')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.376 3.622a1 1 0 0 1 3.002 3.002L7.368 18.635a2 2 0 0 1-.855.506l-2.872.838a.5.5 0 0 1-.62-.62l.838-2.872a2 2 0 0 1 .506-.854z"/></svg>
            {{ t('editor.diffCompare') }}
            <span v-if="diffAdded + diffRemoved > 0" class="diff-overlay__badge">
              <span class="diff-overlay__badge-add">+{{ diffAdded }}</span>
              <span class="diff-overlay__badge-del">-{{ diffRemoved }}</span>
            </span>
          </button>
          <button
            class="diff-overlay__tab"
            :class="{ 'diff-overlay__tab--active': diffMode === 'preview' }"
            @click="emit('update:diffMode', 'preview')"
          >
            {{ t('editor.preview') }}
          </button>
        </div>

        <div class="diff-overlay__info">
          <span v-if="streaming" class="diff-overlay__streaming">
            <span class="diff-overlay__pulse"></span>
            {{ t('editor.aiEditing') }}（{{ t('editor.patchesApplied', [patchCount]) }}{{ retryRound ? ' · ' + t('editor.aiRetrying', [retryFailedCount ?? 0, retryRound]) : '' }}）
          </span>
          <span v-else class="diff-overlay__done">
            {{ diffMode === 'diff' ? t('editor.viewDiffDetail') : t('editor.aiModified', [patchCount]) }}
          </span>
        </div>
        <div class="diff-overlay__actions">
          <button
            class="diff-overlay__btn diff-overlay__btn--reject"
            :disabled="streaming"
            @click="emit('reject')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            {{ t('editor.discardBtn') }}
          </button>
          <button
            class="diff-overlay__btn diff-overlay__btn--accept"
            :disabled="streaming"
            @click="emit('accept')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
            {{ t('editor.acceptChange') }}
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.diff-overlay {
  position: sticky;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 10;
  padding: var(--space-3) var(--space-4);
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
  border-top: 1px solid var(--border-subtle);
}

.diff-overlay__inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  max-width: var(--wiki-content-max, 800px);
  margin: 0 auto;
}

.diff-overlay__tabs {
  display: flex;
  gap: 2px;
  background: var(--bg-tertiary, #f0f0f0);
  border-radius: var(--radius-md);
  padding: 2px;
  flex-shrink: 0;
}

.diff-overlay__tab {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: var(--btn-height-sm, 28px);
  padding: 0 var(--space-3);
  border: none;
  border-radius: calc(var(--radius-md) - 2px);
  background: transparent;
  color: var(--text-tertiary);
  font-family: var(--font-body);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
}

.diff-overlay__tab:hover {
  color: var(--text-secondary);
}

.diff-overlay__tab--active {
  background: var(--bg-primary, #fff);
  color: var(--text-primary);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}

.diff-overlay__badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  font-weight: var(--weight-semibold);
  font-family: var(--font-code);
}

.diff-overlay__badge-add {
  color: var(--success, #22c55e);
}

.diff-overlay__badge-del {
  color: var(--error, #ef4444);
}

.diff-overlay__info {
  flex: 1;
  min-width: 0;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-secondary);
}

.diff-overlay__streaming {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--accent-primary);
}

.diff-overlay__pulse {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: var(--radius-full);
  background: var(--accent-primary);
  animation: overlay-pulse 1.5s ease-in-out infinite;
  flex-shrink: 0;
}

@keyframes overlay-pulse {
  0%, 100% { opacity: 0.3; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.2); }
}

.diff-overlay__done {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--success);
}

.diff-overlay__actions {
  display: flex;
  gap: var(--space-2);
  flex-shrink: 0;
}

.diff-overlay__btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: var(--btn-height-sm, 32px);
  padding: 0 var(--space-4);
  border-radius: var(--radius-md);
  font-family: var(--font-body);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
  border: none;
  white-space: nowrap;
}

.diff-overlay__btn--reject {
  background: var(--error-light, #fef2f2);
  color: var(--error, #ef4444);
  border: 1px solid var(--error, #ef4444);
}

.diff-overlay__btn--reject:hover:not(:disabled) {
  background: var(--error, #ef4444);
  color: var(--text-on-accent, #fff);
}

.diff-overlay__btn--accept {
  background: var(--success, #22c55e);
  color: var(--text-on-accent, #fff);
}

.diff-overlay__btn--accept:hover:not(:disabled) {
  opacity: 0.9;
}

.diff-overlay__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Transition */
.overlay-slide-enter-active,
.overlay-slide-leave-active {
  transition: all 250ms ease;
}

.overlay-slide-enter-from,
.overlay-slide-leave-to {
  opacity: 0;
  transform: translateY(12px);
}
</style>
