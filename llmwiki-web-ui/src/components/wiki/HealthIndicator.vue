<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CheckCircle, AlertTriangle, XCircle, AlertCircle, Clock, type LucideIcon } from 'lucide-vue-next'

const { t } = useI18n()

const props = defineProps<{
  health: 'healthy' | 'needs-update' | 'has-problems' | 'conflict-warning' | 'deprecated'
  lastChecked?: string
}>()

type HealthKey = 'healthy' | 'needs-update' | 'has-problems' | 'conflict-warning' | 'deprecated'

const iconMap: Record<HealthKey, LucideIcon> = {
  healthy: CheckCircle,
  'needs-update': AlertTriangle,
  'has-problems': XCircle,
  'conflict-warning': AlertCircle,
  deprecated: Clock,
}

const colorMap: Record<HealthKey, string> = {
  healthy: 'var(--success)',
  'needs-update': 'var(--warning)',
  'has-problems': 'var(--error)',
  'conflict-warning': 'var(--warning)',
  deprecated: 'var(--text-tertiary)',
}

const bgMap: Record<HealthKey, string> = {
  healthy: 'var(--success-light)',
  'needs-update': 'var(--warning-light)',
  'has-problems': 'var(--error-light)',
  'conflict-warning': 'var(--warning-light)',
  deprecated: 'var(--bg-tertiary)',
}

const labelKeyMap: Record<HealthKey, string> = {
  healthy: 'wiki.healthy',
  'needs-update': 'wiki.needsUpdate',
  'has-problems': 'wiki.hasProblems',
  'conflict-warning': 'wiki.conflictWarning',
  deprecated: 'wiki.deprecatedLabel',
}

const label = computed(() => t(labelKeyMap[props.health]))
</script>

<template>
  <div class="health-indicator" :style="{ background: bgMap[props.health], color: colorMap[props.health] }">
    <component :is="iconMap[props.health]" :size="14" />
    <span class="health-indicator__label">{{ label }}</span>
    <span v-if="props.lastChecked" class="health-indicator__time">{{ props.lastChecked }}</span>
  </div>
</template>

<style scoped>
.health-indicator {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.health-indicator__label {
  line-height: 1;
}

.health-indicator__time {
  font-size: var(--font-caption);
  opacity: 0.7;
}
</style>