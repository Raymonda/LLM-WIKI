<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CheckCircle2, AlertTriangle, Zap, Eye, X,
  Unlink, RefreshCw, Link2, Swords, Lightbulb, Globe, FileText, Copy
} from 'lucide-vue-next'
import type { CompletionSummary } from '@/stores/lint'

const props = defineProps<{
  summary: CompletionSummary
}>()

const emit = defineEmits<{
  dismiss: []
}>()

const { t } = useI18n()

const typeMeta: Record<string, { icon: typeof Unlink; labelKey: string; color: string }> = {
  orphan: { icon: Unlink, labelKey: 'lint.typeOrphan', color: 'var(--warning)' },
  stale: { icon: RefreshCw, labelKey: 'lint.typeStaleContent', color: 'var(--warning)' },
  missing_crossref: { icon: Link2, labelKey: 'lint.typeMissingCrossref', color: 'var(--accent-primary)' },
  conflict: { icon: Swords, labelKey: 'lint.typeConflict', color: 'var(--error)' },
  gap: { icon: Lightbulb, labelKey: 'lint.typeGap', color: 'var(--accent-primary)' },
  web_gap: { icon: Globe, labelKey: 'lint.typeWebGap', color: 'var(--error)' },
  action: { icon: Zap, labelKey: 'lint.typeAction', color: 'var(--success)' },
  duplicate_orphan: { icon: Copy, labelKey: 'lint.typeDuplicateOrphan', color: 'var(--warning)' },
  content_thin: { icon: FileText, labelKey: 'lint.typeContentThin', color: 'var(--info, var(--accent-primary))' }
}

const typeEntries = computed(() => {
  return Object.entries(props.summary.byType)
    .map(([type, stats]) => {
      const meta = typeMeta[type]
      return {
        type,
        icon: meta?.icon || AlertTriangle,
        label: meta ? t(meta.labelKey) : type,
        color: meta?.color || 'var(--text-secondary)',
        ...stats
      }
    })
    .sort((a, b) => b.total - a.total)
})

const hasActions = computed(() => props.summary.autoResolved > 0 || props.summary.awaitingApproval > 0 || props.summary.openPending > 0)

const allClear = computed(() => props.summary.totalFound === 0)
</script>

<template>
  <div class="completion-summary" :class="{ 'completion-summary--all-clear': allClear }">
    <div class="completion-summary__header">
      <div class="completion-summary__header-left">
        <CheckCircle2 v-if="allClear" :size="20" class="completion-summary__icon completion-summary__icon--success" />
        <Eye v-else :size="20" class="completion-summary__icon completion-summary__icon--info" />
        <div class="completion-summary__title-group">
          <span class="completion-summary__title">
            {{ allClear ? t('lint.allClear') : t('lint.lintComplete') }}
          </span>
          <span v-if="!allClear" class="completion-summary__subtitle">
            {{ t('lint.issuesFound', [summary.totalFound]) }}
          </span>
        </div>
      </div>
      <button class="completion-summary__dismiss" @click="emit('dismiss')">
        <X :size="16" />
      </button>
    </div>

    <template v-if="!allClear && hasActions">
      <div class="completion-summary__stats">
        <div v-if="summary.autoResolved > 0" class="completion-summary__stat completion-summary__stat--success">
          <Zap :size="16" />
          <span class="completion-summary__stat-count">{{ summary.autoResolved }}</span>
          <span class="completion-summary__stat-label">{{ t('lint.autoFixed') }}</span>
        </div>
        <div v-if="summary.awaitingApproval > 0" class="completion-summary__stat completion-summary__stat--warning">
          <AlertTriangle :size="16" />
          <span class="completion-summary__stat-count">{{ summary.awaitingApproval }}</span>
          <span class="completion-summary__stat-label">{{ t('lint.pendingRuling') }}</span>
        </div>
        <div v-if="summary.openPending > 0" class="completion-summary__stat completion-summary__stat--pending">
          <Eye :size="16" />
          <span class="completion-summary__stat-count">{{ summary.openPending }}</span>
          <span class="completion-summary__stat-label">{{ t('lint.pendingProcess') }}</span>
        </div>
      </div>

      <div class="completion-summary__breakdown">
        <div class="completion-summary__breakdown-title">{{ t('lint.issueBreakdown') }}</div>
        <div class="completion-summary__breakdown-list">
          <div
            v-for="entry in typeEntries"
            :key="entry.type"
            class="completion-summary__breakdown-item"
          >
            <component :is="entry.icon" :size="14" :style="{ color: entry.color }" />
            <span class="completion-summary__breakdown-label">{{ entry.label }}</span>
            <span class="completion-summary__breakdown-total">{{ entry.total }}</span>
            <template v-if="entry.autoResolved > 0">
              <span class="completion-summary__breakdown-sep">·</span>
              <span class="completion-summary__breakdown-fixed">
                <Zap :size="11" />{{ entry.autoResolved }} {{ t('lint.fixedLabel') }}
              </span>
            </template>
            <template v-if="entry.awaiting > 0">
              <span class="completion-summary__breakdown-sep">·</span>
              <span class="completion-summary__breakdown-awaiting">{{ entry.awaiting }} {{ t('lint.pendingRulingShort') }}</span>
            </template>
            <template v-if="entry.open > 0">
              <span class="completion-summary__breakdown-sep">·</span>
              <span class="completion-summary__breakdown-open">{{ entry.open }} {{ t('lint.pendingProcess') }}</span>
            </template>
          </div>
        </div>
      </div>
    </template>

    <div v-if="!allClear" class="completion-summary__hint">
      {{ t('lint.completionHint') }}
    </div>
  </div>
</template>

<style scoped>
.completion-summary {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  animation: slideDown 0.35s ease;
}

.completion-summary--all-clear {
  border-color: var(--success);
  background: var(--success-light);
}

@keyframes slideDown {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}

.completion-summary__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.completion-summary__header-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.completion-summary__icon--success { color: var(--success); }
.completion-summary__icon--info { color: var(--accent-primary); }

.completion-summary__title-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.completion-summary__title {
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.completion-summary__subtitle {
  font-size: var(--font-caption);
  color: var(--text-secondary);
}

.completion-summary__dismiss {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--text-tertiary);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.completion-summary__dismiss:hover {
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}

.completion-summary__stats {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-4);
  flex-wrap: wrap;
}

.completion-summary__stat {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  flex: 1;
  min-width: 120px;
}

.completion-summary__stat--success {
  background: var(--success-light);
  color: var(--success);
}

.completion-summary__stat--warning {
  background: var(--warning-light);
  color: var(--warning);
}

.completion-summary__stat--pending {
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}

.completion-summary__stat-count {
  font-size: var(--font-h2);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
  line-height: 1;
}

.completion-summary__stat-label {
  font-size: var(--font-caption);
  white-space: nowrap;
}

.completion-summary__breakdown {
  margin-top: var(--space-4);
}

.completion-summary__breakdown-title {
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  margin-bottom: var(--space-2);
}

.completion-summary__breakdown-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.completion-summary__breakdown-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--bg-secondary);
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
}

.completion-summary__breakdown-label {
  color: var(--text-primary);
  flex: 1;
}

.completion-summary__breakdown-total {
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  min-width: 20px;
  text-align: right;
}

.completion-summary__breakdown-sep {
  color: var(--text-tertiary);
}

.completion-summary__breakdown-fixed {
  display: flex;
  align-items: center;
  gap: 2px;
  color: var(--success);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.completion-summary__breakdown-awaiting {
  color: var(--warning);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.completion-summary__breakdown-open {
  color: var(--text-secondary);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.completion-summary__hint {
  margin-top: var(--space-3);
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  text-align: center;
}
</style>
