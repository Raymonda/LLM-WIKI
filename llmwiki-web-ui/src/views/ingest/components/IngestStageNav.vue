<script setup lang="ts">
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { Check, AlertTriangle } from 'lucide-vue-next'

export type StageStatus = 'pending' | 'active' | 'completed' | 'failed'

export interface StageItem {
  key: string
  label: string
  icon?: Component
  hint?: string
}

const props = defineProps<{
  stages: StageItem[]
  currentKey: string
  completedKeys?: string[]
  errorKey?: string
}>()

const { t } = useI18n()

function statusOf(stage: StageItem, idx: number): StageStatus {
  if (props.errorKey && stage.key === props.errorKey) return 'failed'
  if (props.completedKeys && props.completedKeys.includes(stage.key)) return 'completed'
  if (stage.key === props.currentKey) return 'active'
  const currentIdx = props.stages.findIndex(s => s.key === props.currentKey)
  if (currentIdx >= 0 && idx < currentIdx) return 'completed'
  return 'pending'
}

const items = computed(() =>
  props.stages.map((stage, idx) => ({
    stage,
    idx,
    status: statusOf(stage, idx),
  })),
)
</script>

<template>
  <nav class="ingest-stage-nav" :aria-label="t('ingest.stageNavLabel')">
    <ol class="ingest-stage-nav__list">
      <li
        v-for="item in items"
        :key="item.stage.key"
        class="ingest-stage-nav__item"
        :class="[`ingest-stage-nav__item--${item.status}`]"
      >
        <div class="ingest-stage-nav__node">
          <div class="ingest-stage-nav__dot" :aria-current="item.status === 'active' ? 'step' : undefined">
            <Check v-if="item.status === 'completed'" :size="14" />
            <AlertTriangle v-else-if="item.status === 'failed'" :size="14" />
            <component v-else-if="item.stage.icon" :is="item.stage.icon" :size="14" />
            <span v-else class="ingest-stage-nav__index">{{ item.idx + 1 }}</span>
          </div>
          <div class="ingest-stage-nav__labels">
            <span class="ingest-stage-nav__label">{{ item.stage.label }}</span>
            <span v-if="item.stage.hint" class="ingest-stage-nav__hint">{{ item.stage.hint }}</span>
          </div>
        </div>
        <div v-if="item.idx < items.length - 1" class="ingest-stage-nav__connector" aria-hidden="true" />
      </li>
    </ol>
  </nav>
</template>

<style scoped>
.ingest-stage-nav {
  width: 100%;
}

.ingest-stage-nav__list {
  display: flex;
  align-items: stretch;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.ingest-stage-nav__item {
  display: flex;
  align-items: center;
  flex: 1 1 0;
  min-width: 0;
}

.ingest-stage-nav__node {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.ingest-stage-nav__dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
  border: 1.5px solid transparent;
  flex-shrink: 0;
  transition: background 200ms ease-out, color 200ms ease-out, border-color 200ms ease-out;
}

.ingest-stage-nav__index {
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.ingest-stage-nav__labels {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.ingest-stage-nav__label {
  font-size: var(--font-body-sm);
  font-weight: 500;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ingest-stage-nav__hint {
  font-size: 11px;
  color: var(--text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ingest-stage-nav__connector {
  flex: 1 1 auto;
  height: 2px;
  background: var(--border-subtle);
  margin: 0 var(--space-3);
  border-radius: 2px;
  min-width: 24px;
}

/* states */
.ingest-stage-nav__item--completed .ingest-stage-nav__dot {
  background: var(--success);
  color: var(--text-on-accent);
}
.ingest-stage-nav__item--completed .ingest-stage-nav__label {
  color: var(--text-primary);
}
.ingest-stage-nav__item--completed + .ingest-stage-nav__item .ingest-stage-nav__connector,
.ingest-stage-nav__item--completed .ingest-stage-nav__connector {
  background: var(--success);
}

.ingest-stage-nav__item--active .ingest-stage-nav__dot {
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border-color: var(--accent-light);
  box-shadow: 0 0 0 4px var(--accent-light);
  animation: ingest-stage-pulse 1.8s ease-in-out infinite;
}
.ingest-stage-nav__item--active .ingest-stage-nav__label {
  color: var(--accent-primary);
  font-weight: 600;
}

.ingest-stage-nav__item--failed .ingest-stage-nav__dot {
  background: var(--error);
  color: var(--text-on-accent);
  box-shadow: 0 0 0 4px var(--error-light);
}
.ingest-stage-nav__item--failed .ingest-stage-nav__label {
  color: var(--error);
  font-weight: 600;
}

.ingest-stage-nav__item--pending .ingest-stage-nav__dot {
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
}

@keyframes ingest-stage-pulse {
  0%, 100% {
    box-shadow: 0 0 0 4px var(--accent-light);
  }
  50% {
    box-shadow: 0 0 0 8px var(--accent-light);
  }
}

@media (prefers-reduced-motion: reduce) {
  .ingest-stage-nav__item--active .ingest-stage-nav__dot {
    animation: none;
  }
  .ingest-stage-nav__dot {
    transition: none;
  }
}

@media (max-width: 720px) {
  .ingest-stage-nav__hint {
    display: none;
  }
  .ingest-stage-nav__label {
    font-size: 12px;
  }
  .ingest-stage-nav__connector {
    margin: 0 var(--space-2);
    min-width: 12px;
  }
}
</style>
