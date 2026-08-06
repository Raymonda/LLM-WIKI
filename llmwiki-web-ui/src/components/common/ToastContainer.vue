<script setup lang="ts">
import { useToastStore } from '@/stores/toast'
import { CheckCircle, AlertTriangle, XCircle, Info, Loader2, X } from 'lucide-vue-next'

const toastStore = useToastStore()

const iconMap = {
  success: CheckCircle,
  warning: AlertTriangle,
  error: XCircle,
  info: Info,
  progress: Loader2,
}

const colorMap = {
  success: 'var(--success)',
  warning: 'var(--warning)',
  error: 'var(--error)',
  info: 'var(--info)',
  progress: 'var(--accent-primary)',
}
</script>

<template>
  <div class="toast-container">
    <TransitionGroup name="toast">
      <div
        v-for="toast in toastStore.toasts"
        :key="toast.id"
        class="toast"
        :class="[`toast--${toast.type}`]"
      >
        <component
          :is="iconMap[toast.type]"
          :size="18"
          :class="{ 'toast__icon--spin': toast.type === 'progress' }"
          :style="{ color: colorMap[toast.type] }"
        />
        <div class="toast__content">
          <span class="toast__title">{{ toast.title }}</span>
          <span v-if="toast.message" class="toast__message">{{ toast.message }}</span>
        </div>
        <button class="toast__close" @click="toastStore.removeToast(toast.id)">
          <X :size="14" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-container {
  position: fixed;
  top: var(--space-4);
  right: var(--space-4);
  z-index: 9999;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-width: 400px;
}

.toast {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--surface-elevated);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  min-width: 300px;
}

.toast__icon--spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.toast__content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.toast__title {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}

.toast__message {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.toast__close {
  background: none;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  padding: var(--space-1);
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
}

.toast__close:hover {
  color: var(--text-primary);
  background: var(--bg-tertiary);
}

.toast-enter-active {
  transition: all var(--transition-normal);
}

.toast-leave-active {
  transition: all var(--transition-fast);
}

.toast-enter-from {
  opacity: 0;
  transform: translateX(100%);
}

.toast-leave-to {
  opacity: 0;
  transform: translateX(100%);
}

.toast-move {
  transition: transform var(--transition-normal);
}
</style>