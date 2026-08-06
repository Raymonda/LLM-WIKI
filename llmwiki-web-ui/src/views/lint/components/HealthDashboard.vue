<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ShieldCheck, CheckCircle2, AlertTriangle, XCircle, Clock,
  Unlink, RefreshCw, Link2, Swords, Lightbulb, Globe, Zap, Loader2
} from 'lucide-vue-next'

const props = defineProps<{
  running: boolean
  healthScore: number | '--'
  healthScoreDisplay: string
  healthyCount: number
  needsUpdateCount: number
  hasProblemsCount: number
  totalCount: number
  totalActiveFindings: number
  findingCountsByType: Record<string, number>
  lastLintTime: string | null
  overviewLoading: boolean
  filterType: string | undefined
}>()

const emit = defineEmits<{
  filterByType: [type: string | undefined]
}>()

const { t } = useI18n()

const lastLintDisplay = computed(() => {
  if (!props.lastLintTime) return t('lint.neverLintedShort')
  return new Date(props.lastLintTime).toLocaleString()
})

const scoreColor = computed(() => {
  if (props.healthScore === '--') return 'var(--text-tertiary)'
  if (props.healthScore >= 80) return 'var(--success)'
  if (props.healthScore >= 50) return 'var(--warning)'
  return 'var(--error)'
})

const scoreBg = computed(() => {
  if (props.healthScore === '--') return 'var(--bg-tertiary)'
  if (props.healthScore >= 80) return 'var(--success-light)'
  if (props.healthScore >= 50) return 'var(--warning-light)'
  return 'var(--error-light)'
})

const healthyPercent = computed(() => {
  if (props.totalCount === 0) return 100
  return (props.healthyCount / props.totalCount * 100).toFixed(1)
})

const needsUpdatePercent = computed(() => {
  if (props.totalCount === 0) return 0
  return (props.needsUpdateCount / props.totalCount * 100).toFixed(1)
})

const hasProblemsPercent = computed(() => {
  if (props.totalCount === 0) return 0
  return (props.hasProblemsCount / props.totalCount * 100).toFixed(1)
})

function handleTypeClick(type: string) {
  if (props.filterType === type) {
    emit('filterByType', undefined)
  } else {
    emit('filterByType', type)
  }
}

const typeItems = computed(() => {
  const map: Record<string, { icon: typeof Unlink; labelKey: string; color: string }> = {
    orphan: { icon: Unlink, labelKey: 'lint.typeOrphan', color: 'var(--warning)' },
    stale: { icon: RefreshCw, labelKey: 'lint.typeStale', color: 'var(--warning)' },
    missing_crossref: { icon: Link2, labelKey: 'lint.typeMissingCrossref', color: 'var(--accent-primary)' },
    conflict: { icon: Swords, labelKey: 'lint.typeConflict', color: 'var(--error)' },
    gap: { icon: Lightbulb, labelKey: 'lint.typeGap', color: 'var(--accent-primary)' },
    web_gap: { icon: Globe, labelKey: 'lint.typeWebGap', color: 'var(--error)' },
    action: { icon: Zap, labelKey: 'lint.typeAction', color: 'var(--success)' },
  }
  const items: { type: string; icon: typeof Unlink; label: string; color: string; count: number }[] = []
  for (const [type, count] of Object.entries(props.findingCountsByType)) {
    const meta = map[type]
    if (count > 0) items.push({ type, icon: meta?.icon || Unlink, label: meta ? t(meta.labelKey) : type, color: meta?.color || 'var(--text-secondary)', count })
  }
  return items.sort((a, b) => b.count - a.count)
})
</script>

<template>
  <div class="health-dashboard">
    <template v-if="running">
      <div class="health-dashboard__running">
        <Loader2 :size="24" class="health-dashboard__spin" />
        <div class="health-dashboard__running-text">
          <span class="health-dashboard__running-title">{{ t('lint.lintInProgress') }}</span>
          <span class="health-dashboard__running-desc">{{ t('lint.checkingHealth') }}</span>
        </div>
      </div>
    </template>

    <template v-else-if="overviewLoading">
      <div class="health-dashboard__skeleton">
        <div class="health-dashboard__skeleton-score"></div>
        <div class="health-dashboard__skeleton-stats">
          <div class="health-dashboard__skeleton-stat"></div>
          <div class="health-dashboard__skeleton-stat"></div>
          <div class="health-dashboard__skeleton-stat"></div>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="health-dashboard__score-section">
        <div class="health-dashboard__score-card" :style="{ background: scoreBg }">
          <ShieldCheck :size="28" :style="{ color: scoreColor }" />
          <div class="health-dashboard__score-body">
            <span class="health-dashboard__score-value" :style="{ color: scoreColor }">{{ healthScoreDisplay }}</span>
            <span class="health-dashboard__score-label">{{ t('lint.healthScoreLabel') }}</span>
          </div>
        </div>
        <div class="health-dashboard__stats">
          <div class="health-dashboard__stat health-dashboard__stat--healthy">
            <CheckCircle2 :size="16" />
            <span class="health-dashboard__stat-count">{{ healthyCount }}</span>
            <span class="health-dashboard__stat-label">{{ t('lint.healthy') }} {{ healthyPercent }}%</span>
          </div>
          <div class="health-dashboard__stat health-dashboard__stat--warning">
            <AlertTriangle :size="16" />
            <span class="health-dashboard__stat-count">{{ needsUpdateCount }}</span>
            <span class="health-dashboard__stat-label">{{ t('lint.needsUpdate') }} {{ needsUpdatePercent }}%</span>
          </div>
          <div class="health-dashboard__stat health-dashboard__stat--error">
            <XCircle :size="16" />
            <span class="health-dashboard__stat-count">{{ hasProblemsCount }}</span>
            <span class="health-dashboard__stat-label">{{ t('lint.hasProblems') }} {{ hasProblemsPercent }}%</span>
          </div>
        </div>
      </div>

      <div v-if="totalCount > 0" class="health-dashboard__bar-section">
        <div class="health-dashboard__bar-track">
          <div class="health-dashboard__bar-fill health-dashboard__bar-fill--healthy" :style="{ width: healthyPercent + '%' }"></div>
          <div class="health-dashboard__bar-fill health-dashboard__bar-fill--warning" :style="{ width: needsUpdatePercent + '%' }"></div>
          <div class="health-dashboard__bar-fill health-dashboard__bar-fill--error" :style="{ width: hasProblemsPercent + '%' }"></div>
        </div>
      </div>

      <div v-if="typeItems.length > 0" class="health-dashboard__type-section">
        <div class="health-dashboard__type-title">{{ t('lint.issueDistribution') }}</div>
        <div class="health-dashboard__type-grid">
          <div
            v-for="item in typeItems"
            :key="item.type"
            class="health-dashboard__type-item"
            :class="{ 'health-dashboard__type-item--active': filterType === item.type }"
            @click="handleTypeClick(item.type)"
          >
            <component :is="item.icon" :size="14" :style="{ color: item.color }" />
            <span class="health-dashboard__type-label">{{ item.label }}</span>
            <span class="health-dashboard__type-count" :style="{ color: item.color }">{{ item.count }}</span>
          </div>
        </div>
      </div>

      <div class="health-dashboard__meta">
        <Clock :size="12" />
        <span>{{ t('lint.lastLintLabel') }}: {{ lastLintDisplay }}</span>
        <span v-if="totalActiveFindings > 0" class="health-dashboard__meta-badge">{{ t('lint.pendingCount', [totalActiveFindings]) }}</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.health-dashboard {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
}

.health-dashboard__running {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-6) var(--space-4);
  justify-content: center;
}

.health-dashboard__spin {
  animation: spin 1s linear infinite;
  color: var(--accent-primary);
}

.health-dashboard__running-text {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.health-dashboard__running-title {
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.health-dashboard__running-desc {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.health-dashboard__skeleton {
  display: flex;
  gap: var(--space-5);
  align-items: stretch;
}

.health-dashboard__skeleton-score {
  width: 180px;
  height: 72px;
  border-radius: var(--radius-lg);
  background: linear-gradient(90deg, var(--bg-tertiary) 25%, var(--bg-secondary) 50%, var(--bg-tertiary) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
}

.health-dashboard__skeleton-stats {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  flex: 1;
}

.health-dashboard__skeleton-stat {
  height: 36px;
  border-radius: var(--radius-md);
  background: linear-gradient(90deg, var(--bg-tertiary) 25%, var(--bg-secondary) 50%, var(--bg-tertiary) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
}

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.health-dashboard__score-section {
  display: flex;
  gap: var(--space-5);
  align-items: stretch;
}

.health-dashboard__score-card {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-radius: var(--radius-lg);
  min-width: 180px;
}

.health-dashboard__score-body {
  display: flex;
  flex-direction: column;
}

.health-dashboard__score-value {
  font-size: var(--font-h1);
  font-weight: var(--weight-bold);
  line-height: 1;
}

.health-dashboard__score-label {
  font-size: var(--font-caption);
  color: var(--text-secondary);
}

.health-dashboard__stats {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  flex: 1;
}

.health-dashboard__stat {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
}

.health-dashboard__stat--healthy { background: var(--success-light); color: var(--success); }
.health-dashboard__stat--warning { background: var(--warning-light); color: var(--warning); }
.health-dashboard__stat--error { background: var(--error-light); color: var(--error); }

.health-dashboard__stat-count {
  font-size: var(--font-h3);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
}

.health-dashboard__stat-label {
  font-size: var(--font-caption);
  color: var(--text-secondary);
  margin-left: auto;
}

.health-dashboard__bar-section {
  margin-top: var(--space-4);
}

.health-dashboard__bar-track {
  display: flex;
  height: 8px;
  border-radius: var(--radius-full);
  overflow: hidden;
  background: var(--border-default);
}

.health-dashboard__bar-fill--healthy { background: var(--success); }
.health-dashboard__bar-fill--warning { background: var(--warning); }
.health-dashboard__bar-fill--error { background: var(--error); }

.health-dashboard__type-section {
  margin-top: var(--space-4);
}

.health-dashboard__type-title {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  margin-bottom: var(--space-2);
}

.health-dashboard__type-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: var(--space-2);
}

.health-dashboard__type-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
  transition: background var(--transition-fast);
  cursor: pointer;
  border: 1px solid transparent;
}

.health-dashboard__type-item:hover {
  background: var(--bg-tertiary);
}

.health-dashboard__type-item--active {
  background: var(--accent-light);
  border-color: var(--accent-primary);
}

.health-dashboard__type-label {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  flex: 1;
}

.health-dashboard__type-count {
  font-size: var(--font-h3);
  font-weight: var(--weight-bold);
}

.health-dashboard__meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.health-dashboard__meta-badge {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: var(--warning);
  background: var(--warning-light);
  padding: 2px 8px;
  border-radius: var(--radius-full);
}
</style>