<script setup lang="ts">
import { ref, watch, computed, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{
  saving?: boolean
  aiAvailable?: boolean
}>()

const emit = defineEmits<{
  (e: 'save', mode: 'draft' | 'save'): void
  (e: 'pre-validate'): void
}>()

const { t } = useI18n()

const saveElapsed = ref(0)
let saveTimer: ReturnType<typeof setInterval> | null = null

watch(() => props.saving, (val) => {
  if (val) {
    saveElapsed.value = 0
    saveTimer = setInterval(() => { saveElapsed.value++ }, 1000)
  } else {
    if (saveTimer) { clearInterval(saveTimer); saveTimer = null }
    saveElapsed.value = 0
  }
})

onUnmounted(() => {
  if (saveTimer) clearInterval(saveTimer)
})

const saveLabel = computed(() => {
  if (!props.saving) return t('editor.save')
  if (saveElapsed.value < 3) return t('editor.saving')
  else if (saveElapsed.value < 8) return t('editor.aiEnhancing')
  else return t('editor.aiProcessing')
})

function handleSave(mode: 'draft' | 'save') {
  emit('save', mode)
}
</script>

<template>
  <div class="save-bar">
    <button
      class="save-bar__btn save-bar__btn--draft"
      :disabled="saving"
      @click="handleSave('draft')"
    >
      {{ t('editor.saveDraftBtn') }}
    </button>
    <button
      class="save-bar__btn save-bar__btn--primary"
      :disabled="saving || !aiAvailable"
      :title="aiAvailable ? t('editor.aiEnhanceTooltip') : t('editor.aiUnavailableTooltip')"
      @click="handleSave('save')"
    >
      <svg v-if="saving" class="save-bar__spinner" viewBox="0 0 16 16" fill="none" width="14" height="14">
        <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-opacity="0.3" stroke-width="2"/>
        <path d="M14 8a6 6 0 0 0-6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
      </svg>
      {{ saving ? saveLabel : t('editor.save') }}
    </button>
  </div>
</template>

<style scoped>
.save-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  padding: var(--space-3) 0;
  flex-shrink: 0;
  max-width: var(--wiki-content-max);
  width: 100%;
  margin: 0 auto;
}

.save-bar__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  height: var(--btn-height-sm);
  padding: 0 var(--space-4);
  border-radius: var(--radius-sm);
  font-family: var(--font-body);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
  border: none;
  white-space: nowrap;
}

.save-bar__btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.save-bar__btn--draft {
  background: transparent;
  color: var(--text-tertiary);
}

.save-bar__btn--draft:hover:not(:disabled) {
  background: var(--bg-primary);
  color: var(--text-secondary);
}

.save-bar__btn--primary {
  background: var(--accent-primary);
  color: var(--text-on-accent);
  box-shadow: var(--shadow-sm);
}

.save-bar__btn--primary:hover:not(:disabled) {
  background: var(--accent-hover);
  box-shadow: var(--shadow-md);
}

.save-bar__spinner {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
