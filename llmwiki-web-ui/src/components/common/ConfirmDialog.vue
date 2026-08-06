<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { AlertTriangle, XCircle, Info, X } from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  type?: 'warning' | 'danger' | 'info'
  confirmVariant?: 'danger' | 'primary'
}>(), {
  confirmText: undefined,
  cancelText: undefined,
  type: 'warning',
  confirmVariant: 'primary',
})

const emit = defineEmits<{
  (e: 'confirm'): void
  (e: 'cancel'): void
}>()

const { t } = useI18n()
const actualConfirmText = computed(() => props.confirmText || t('common.confirm'))
const actualCancelText = computed(() => props.cancelText || t('common.cancel'))

const iconComponent = computed(() => {
  switch (props.type) {
    case 'danger': return XCircle
    case 'info': return Info
    default: return AlertTriangle
  }
})

const iconColor = computed(() => {
  switch (props.type) {
    case 'danger': return 'var(--error)'
    case 'info': return 'var(--info)'
    default: return 'var(--warning)'
  }
})
</script>

<template>
  <Teleport to="body">
    <Transition name="cf">
      <div v-if="open" class="cf-overlay" @click.self="emit('cancel')">
        <div class="cf-dialog">
          <div class="cf-dialog__header">
            <div class="cf-dialog__icon" :style="{ color: iconColor }">
              <component :is="iconComponent" :size="20" />
            </div>
            <h3 class="cf-dialog__title">{{ title }}</h3>
            <button class="cf-dialog__close" @click="emit('cancel')" :aria-label="t('common.close')">
              <X :size="18" />
            </button>
          </div>
          <div class="cf-dialog__body">
            <p class="cf-dialog__message">{{ message }}</p>
          </div>
          <div class="cf-dialog__footer">
            <button class="cf-dialog__btn cf-dialog__btn--cancel" @click="emit('cancel')">
              {{ actualCancelText }}
            </button>
            <button class="cf-dialog__btn cf-dialog__btn--confirm" :class="'cf-dialog__btn--' + confirmVariant" @click="emit('confirm')">
              {{ actualConfirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.cf-overlay {
  position: fixed;
  inset: 0;
  background: rgba(11, 14, 22, 0.55);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9000;
}

.cf-dialog {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-xl);
  width: 400px;
  max-width: 90vw;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.cf-dialog__header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border-default);
}

.cf-dialog__icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--bg-tertiary);
}

.cf-dialog__title {
  flex: 1;
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin: 0;
}

.cf-dialog__close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.cf-dialog__close:hover {
  background: var(--bg-tertiary);
  border-color: var(--border-default);
  color: var(--text-primary);
}

.cf-dialog__body {
  padding: var(--space-5);
}

.cf-dialog__message {
  font-size: var(--font-body);
  color: var(--text-secondary);
  line-height: 1.6;
  margin: 0;
}

.cf-dialog__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-top: 1px solid var(--border-default);
}

.cf-dialog__btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  border: 1px solid transparent;
  transition: all var(--transition-fast);
  height: var(--btn-height-sm);
}

.cf-dialog__btn--cancel {
  color: var(--text-primary);
  background: var(--bg-secondary);
  border-color: var(--border-default);
}

.cf-dialog__btn--cancel:hover {
  background: var(--bg-tertiary);
}

.cf-dialog__btn--primary {
  color: var(--text-on-accent);
  background: var(--accent-primary);
}

.cf-dialog__btn--primary:hover {
  background: var(--accent-hover);
}

.cf-dialog__btn--danger {
  color: var(--text-on-accent);
  background: var(--error);
}

.cf-dialog__btn--danger:hover {
  filter: brightness(1.1);
}

.cf-enter-active {
  transition: opacity 200ms ease-out;
}
.cf-leave-active {
  transition: opacity 150ms ease-in;
}
.cf-enter-from {
  opacity: 0;
}
.cf-enter-from .cf-dialog {
  transform: translateY(16px) scale(0.97);
  transition: transform 250ms ease-out;
}
.cf-enter-to .cf-dialog {
  transform: translateY(0) scale(1);
}
.cf-leave-from {
  opacity: 1;
}
.cf-leave-to {
  opacity: 0;
}
</style>