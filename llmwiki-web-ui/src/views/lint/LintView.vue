<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ShieldCheck, XCircle, Zap, RotateCcw, ThumbsUp, ThumbsDown, Link2, EyeOff, RefreshCw, AlertTriangle, X, ListChecks, Sparkles, Archive, FileWarning, Swords, Unlink } from 'lucide-vue-next'
import { useLintStore, isAutoResolvable, type MainTabKey } from '@/stores/lint'
import { type LintFindingInfo } from '@/api/lint'
import HealthDashboard from './components/HealthDashboard.vue'
import LintTrigger from './components/LintTrigger.vue'
import ActionCards from './components/ActionCards.vue'
import LintCompletionSummary from './components/LintCompletionSummary.vue'

const { t } = useI18n()
const router = useRouter()
const store = useLintStore()

const batchPreviewOpen = ref(false)
const batchPreviewAction = ref('')
const batchPreviewItems = ref<LintFindingInfo[]>([])
const batchPreviewSkippedCount = ref(0)

const mainTabs: { key: MainTabKey; icon: any; labelKey: string }[] = [
  { key: 'manual', icon: ListChecks, labelKey: 'lint.tabManual' },
  { key: 'ai_processed', icon: Sparkles, labelKey: 'lint.tabAiProcessed' },
  { key: 'archived', icon: Archive, labelKey: 'lint.tabArchived' }
]

onMounted(async () => {
  store.startVisibilityWatcher()
  const hasActive = await store.checkActiveLint()
  if (!hasActive) {
    store.loadOverview()
    store.loadTabCounts()
    store.loadFindings()
  }
})

onUnmounted(() => {
  store.cleanup()
})

function navigateToPage(assetId: number | null) {
  if (assetId) router.push(`/wiki/${assetId}`)
}

function showBatchPreviewDialog(action: string) {
  batchPreviewAction.value = action

  // 根据操作类型筛选要处理的项目
  const filtered = store.selectedItems.filter(f => {
    switch (action) {
      case 'autoResolve':
        return isAutoResolvable(f)
      case 'retryFailed':
        return f.status === 'failed'
      case 'triggerRepair':
        return f.status === 'open' && f.findingType === 'stale'
      case 'approve':
        return f.status === 'awaiting_approval'
      case 'reject':
        return f.status === 'awaiting_approval'
      case 'rollback':
        return f.status === 'auto_resolved'
      case 'approveLinks':
      case 'rejectLinks':
        return f.findingType === 'missing_crossref' && f.status === 'open'
      case 'dismiss':
        return true
      default:
        return false
    }
  })

  batchPreviewItems.value = filtered
  batchPreviewSkippedCount.value = store.selectedItems.length - filtered.length
  batchPreviewOpen.value = true
}

function confirmBatchAction() {
  batchPreviewOpen.value = false
  
  // 根据操作类型执行对应的批量操作
  switch (batchPreviewAction.value) {
    case 'autoResolve':
      store.batchAutoResolveSelected()
      break
    case 'triggerRepair':
      store.batchTriggerRepairSelected()
      break
    case 'approve':
      store.batchApprove()
      break
    case 'reject':
      store.batchReject()
      break
    case 'rollback':
      store.batchRollback()
      break
    case 'approveLinks':
      store.batchApproveLinksAction()
      break
    case 'rejectLinks':
      store.batchRejectLinksAction()
      break
    case 'dismiss':
      store.batchDismissSelected()
      break
    case 'retryFailed':
      store.batchRetryFailedSelected()
      break
  }
}

const actionLabelKeyMap: Record<string, string> = {
  autoResolve: 'lint.actionAutoResolve',
  triggerRepair: 'lint.actionTriggerRepair',
  approve: 'lint.actionApprove',
  reject: 'lint.actionReject',
  rollback: 'lint.actionRollback',
  approveLinks: 'lint.actionApproveLinks',
  rejectLinks: 'lint.actionRejectLinks',
  dismiss: 'lint.actionDismiss',
  retryFailed: 'lint.actionRetryFailed'
}

function getActionLabel(action: string): string {
  return t(actionLabelKeyMap[action] || 'lint.actionDefault')
}

function getActionIcon(action: string) {
  const icons: Record<string, any> = {
    autoResolve: Zap,
    triggerRepair: RefreshCw,
    approve: ThumbsUp,
    reject: ThumbsDown,
    rollback: RotateCcw,
    approveLinks: Link2,
    rejectLinks: X,
    dismiss: EyeOff,
    retryFailed: RefreshCw
  }
  return icons[action] || AlertTriangle
}

const TYPE_LABEL_KEY_MAP: Record<string, string> = {
  orphan: 'lint.typeLabelOrphan',
  stale: 'lint.typeLabelStale',
  missing_crossref: 'lint.typeLabelMissingCrossref',
  conflict: 'lint.typeLabelConflict',
  gap: 'lint.typeLabelGap',
  web_gap: 'lint.typeLabelWebGap',
  action: 'lint.typeLabelAction',
  schema_compliance: 'lint.typeLabelSchemaCompliance',
  schema_violation: 'lint.typeLabelSchemaViolation',
  duplicate_orphan: 'lint.typeLabelDuplicateOrphan',
  content_thin: 'lint.typeLabelContentThin'
}

function getTypeLabel(type: string): string {
  return t(TYPE_LABEL_KEY_MAP[type] || type)
}

const TYPE_ICON_MAP: Record<string, any> = {
  orphan: Unlink,
  stale: RefreshCw,
  missing_crossref: Link2,
  conflict: Swords,
  gap: AlertTriangle,
  web_gap: AlertTriangle,
  action: Zap,
  schema_compliance: FileWarning,
  schema_violation: FileWarning,
  duplicate_orphan: AlertTriangle,
  content_thin: FileWarning
}

function getTypeIcon(type: string) {
  return TYPE_ICON_MAP[type] || AlertTriangle
}

const PRIORITY_LABEL_KEY_MAP: Record<string, { labelKey: string; color: string }> = {
  high: { labelKey: 'lint.severityHigh', color: 'var(--error)' },
  medium: { labelKey: 'lint.severityMedium', color: 'var(--warning)' },
  low: { labelKey: 'lint.severityLow', color: 'var(--text-tertiary)' }
}

function getPriorityLabel(priority: string) {
  const entry = PRIORITY_LABEL_KEY_MAP[priority]
  return entry ? { label: t(entry.labelKey), color: entry.color } : { label: priority, color: 'var(--text-tertiary)' }
}

function getImpactDescription(action: string, f: LintFindingInfo): string {
  const linkCount = f.crossrefSuggestions?.length || 0
  switch (action) {
    case 'autoResolve':
      if (f.findingType === 'conflict') return t('lint.impactAutoResolveConflict')
      if (f.findingType === 'duplicate_orphan') return t('lint.impactAutoResolveDuplicate')
      return t('lint.impactAutoResolveDefault')
    case 'triggerRepair':
      return t('lint.impactTriggerRepair')
    case 'approve':
      return t('lint.impactApprove')
    case 'reject':
      return t('lint.impactReject')
    case 'rollback':
      if (f.findingType === 'conflict') return t('lint.impactRollbackConflict')
      if (f.findingType === 'stale') return t('lint.impactRollbackStale')
      return t('lint.impactRollbackDefault')
    case 'approveLinks':
      return linkCount > 0
        ? t('lint.impactApproveLinks', [linkCount])
        : t('lint.impactApproveLinksNoCount')
    case 'rejectLinks':
      return linkCount > 0
        ? t('lint.impactRejectLinks', [linkCount])
        : t('lint.impactRejectLinksNoCount')
    case 'dismiss':
      return t('lint.impactDismiss')
    case 'retryFailed':
      return t('lint.impactRetryFailed')
    default:
      return ''
  }
}

const batchPreviewSummary = computed(() => {
  const items = batchPreviewItems.value
  const action = batchPreviewAction.value
  const uniquePagePaths = new Set(
    items.map(i => i.pagePath || i.assetId?.toString() || '').filter(Boolean)
  )
  const typeBreakdown: Record<string, number> = {}
  const priorityBreakdown: Record<string, number> = {}
  let linkCount = 0
  items.forEach(i => {
    typeBreakdown[i.findingType] = (typeBreakdown[i.findingType] || 0) + 1
    priorityBreakdown[i.priority] = (priorityBreakdown[i.priority] || 0) + 1
    if (i.findingType === 'missing_crossref' && (action === 'approveLinks' || action === 'rejectLinks')) {
      linkCount += i.crossrefSuggestions?.length || 0
    }
  })
  const duplicatePageItems = items.length - uniquePagePaths.size
  return {
    total: items.length,
    pageCount: uniquePagePaths.size,
    duplicatePageItems,
    typeBreakdown,
    priorityBreakdown,
    linkCount
  }
})

function getRiskLevel(action: string): 'high' | 'medium' | 'low' {
  if (action === 'rollback') return 'high'
  if (action === 'reject' || action === 'rejectLinks' || action === 'approve' || action === 'approveLinks') return 'medium'
  if (action === 'dismiss') return 'medium'
  return 'low'
}

function getRiskHint(action: string): { icon: any; tone: 'error' | 'warning' | 'info'; text: string } | null {
  if (action === 'rollback') {
    return {
      icon: AlertTriangle,
      tone: 'error',
      text: t('lint.riskRollback')
    }
  }
  if (action === 'approve') {
    return {
      icon: AlertTriangle,
      tone: 'warning',
      text: t('lint.riskApprove')
    }
  }
  if (action === 'reject') {
    return {
      icon: AlertTriangle,
      tone: 'warning',
      text: t('lint.riskReject')
    }
  }
  if (action === 'approveLinks' || action === 'rejectLinks') {
    return {
      icon: Link2,
      tone: 'info',
      text: t('lint.riskLinks')
    }
  }
  if (action === 'autoResolve' || action === 'triggerRepair') {
    return {
      icon: Zap,
      tone: 'info',
      text: t('lint.riskAutoResolve')
    }
  }
  if (action === 'dismiss') {
    return {
      icon: EyeOff,
      tone: 'warning',
      text: t('lint.riskDismiss')
    }
  }
  return null
}
</script>

<template>
  <div class="lint-view">
    <div class="lint-view__header">
      <div class="lint-view__header-left">
        <h1 class="lint-view__title">{{ t('lint.title') }}</h1>
        <div v-if="store.actionError" class="lint-view__error-toast">
          <XCircle :size="14" />
          {{ store.actionError }}
        </div>
      </div>
      <LintTrigger
        :running="store.running"
        :error="store.error"
        :execution="store.execution"
        :start-time="store.lintStartTime"
        @trigger="store.triggerLint()"
      />
    </div>

    <LintCompletionSummary
      v-if="store.completionSummary"
      :summary="store.completionSummary"
      @dismiss="store.dismissCompletionSummary()"
    />

    <HealthDashboard
      :running="store.running"
      :health-score="store.healthScore"
      :health-score-display="store.healthScoreDisplay"
      :healthy-count="store.healthyCount"
      :needs-update-count="store.needsUpdateCount"
      :has-problems-count="store.hasProblemsCount"
      :total-count="store.totalCount"
      :total-active-findings="store.totalActiveFindings"
      :finding-counts-by-type="store.findingCountsByType"
      :last-lint-time="store.lastLintTime"
      :overview-loading="store.overviewLoading"
      :filter-type="store.manualTypeFilter"
      @filter-by-type="(t) => { store.setManualTypeFilter(t); store.switchMainTab('manual') }"
    />

    <div class="lint-view__main-tabs" :class="{ 'lint-view__main-tabs--disabled': store.running }">
      <button
        v-for="tab in mainTabs"
        :key="tab.key"
        class="lint-view__main-tab"
        :class="{ 'lint-view__main-tab--active': store.mainTab === tab.key }"
        :disabled="store.running"
        @click="store.switchMainTab(tab.key)"
      >
        <component :is="tab.icon" :size="14" />
        {{ t(tab.labelKey) }}
        <span v-if="(store.mainTabCounts as Record<string, number>)[tab.key] > 0" class="lint-view__main-tab-badge">{{ (store.mainTabCounts as Record<string, number>)[tab.key] }}</span>
      </button>
    </div>

    <ActionCards
      :running="store.running"
      :findings-paged="store.findingsPaged"
      :findings-loading="store.findingsLoading"
      :main-tab="store.mainTab"
      :manual-type-filter="store.manualTypeFilter"
      :processing-ids="store.processingIds"
      :selected-ids="store.selectedIds"
      :selected-count="store.selectedCount"
      :all-current-selected="store.allCurrentSelected"
      :batch-processing="store.batchProcessing"
      :main-tab-counts="store.mainTabCounts"
      :page-size="store.pageSize"
      :page-size-options="store.pageSizeOptions"
      :has-selected-awaiting="store.hasSelectedAwaiting"
      :has-selected-auto-resolved="store.hasSelectedAutoResolved"
      :has-selected-stale="store.hasSelectedStale"
      :has-selected-crossref-open="store.hasSelectedCrossrefOpen"
      :has-selected-failed="store.hasSelectedFailed"
      :selected-auto-resolvable-count="store.selectedAutoResolvableCount"
      :selected-failed-count="store.selectedFailedCount"
      :selected-awaiting-count="store.selectedAwaitingCount"
      :selected-stale-count="store.selectedStaleCount"
      :selected-auto-resolved-count="store.selectedAutoResolvedCount"
      :selected-crossref-open-count="store.selectedCrossrefOpenCount"
      @switch-main-tab="store.switchMainTab"
      @go-to-page="store.goToPage"
      @set-page-size="store.setPageSize"
      @toggle-select="store.toggleSelect"
      @toggle-select-all="store.toggleSelectAll"
      @clear-selection="store.clearSelection"
      @dismiss="store.dismissFinding"
      @reassess="store.reassessFinding"
      @auto-resolve="store.autoResolveAction"
      @resolve-compliance="store.resolveComplianceAction"
      @trigger-repair="store.triggerRepairAction"
      @approve="store.approveFindingAction"
      @reject="store.rejectFindingAction"
      @rollback="store.rollbackFindingAction"
      @retry-orphan="store.retryOrphanFixAction"
      @retry-failed="store.retryFailedFindingAction"
      @enrich-page="store.enrichPageAction"
      @approve-link="store.approveLinkAction"
      @reject-link="store.rejectLinkAction"
      @ignore-link="store.ignoreLinkAction"
      @conflict-action="store.executeConflictRulingAction"
      @batch-approve-links="store.batchApproveLinksAction"
      @batch-reject-links="store.batchRejectLinksAction"
      @batch-auto-resolve="store.batchAutoResolveSelected"
      @batch-dismiss="store.batchDismissSelected"
      @batch-trigger-repair="store.batchTriggerRepairSelected"
      @batch-approve="store.batchApprove"
      @batch-reject="store.batchReject"
      @batch-rollback="store.batchRollback"
      @show-batch-preview="showBatchPreviewDialog"
      @navigate-to-page="navigateToPage"
    />

    <div
      v-if="!store.overviewLoading && !store.findingsLoading && store.totalActiveFindings === 0 && !store.running"
      class="lint-view__all-clear"
    >
      <ShieldCheck :size="32" />
      <div class="lint-view__all-clear-text">
        <span class="lint-view__all-clear-title">{{ t('lint.allClearTitle') }}</span>
        <span class="lint-view__all-clear-desc">{{ t('lint.allClearDesc') }}</span>
      </div>
    </div>

    <!-- 批量操作预览对话框 -->
    <Teleport to="body">
      <div v-if="batchPreviewOpen" class="batch-preview-overlay" @click.self="batchPreviewOpen = false">
        <div class="batch-preview-dialog" :class="`batch-preview-dialog--risk-${getRiskLevel(batchPreviewAction)}`">
          <header class="batch-preview-dialog__header">
            <div class="batch-preview-dialog__title-block">
              <span class="batch-preview-dialog__icon-sq" :class="`batch-preview-dialog__icon-sq--${getRiskLevel(batchPreviewAction)}`">
                <component :is="getActionIcon(batchPreviewAction)" :size="18" />
              </span>
              <div class="batch-preview-dialog__title-text">
                <h3 class="batch-preview-dialog__title">{{ getActionLabel(batchPreviewAction) }}</h3>
                <p class="batch-preview-dialog__subtitle">{{ t('lint.batchPreviewSubtitle') }}</p>
              </div>
            </div>
            <button class="batch-preview-dialog__close" @click="batchPreviewOpen = false" :aria-label="t('common.close')">
              <XCircle :size="18" />
            </button>
          </header>

          <!-- 全宽色块汇总区：color blocking -->
          <div class="batch-preview-summary">
            <div class="batch-preview-summary__hero">
              <div class="batch-preview-summary__num">{{ batchPreviewSummary.total }}</div>
              <div class="batch-preview-summary__label">{{ t('lint.batchItemsWillProcess') }}</div>
            </div>
            <div class="batch-preview-summary__divider"></div>
            <div class="batch-preview-summary__stats">
              <div class="batch-preview-summary__stat">
                <div class="batch-preview-summary__stat-num">{{ batchPreviewSummary.pageCount }}</div>
                <div class="batch-preview-summary__stat-label">{{ t('lint.affectedPages') }}</div>
              </div>
              <div v-if="batchPreviewSummary.linkCount > 0" class="batch-preview-summary__stat">
                <div class="batch-preview-summary__stat-num">{{ batchPreviewSummary.linkCount }}</div>
                <div class="batch-preview-summary__stat-label">{{ t('lint.batchLinkItems') }}</div>
              </div>
              <div class="batch-preview-summary__stat" :class="{ 'batch-preview-summary__stat--warn': (batchPreviewSummary.priorityBreakdown.high || 0) > 0 }">
                <div class="batch-preview-summary__stat-num">{{ batchPreviewSummary.priorityBreakdown.high || 0 }}</div>
                <div class="batch-preview-summary__stat-label">{{ t('lint.batchHighPriority') }}</div>
              </div>
            </div>
          </div>

          <!-- 类型分布：inline 条状，无 chip 边框 -->
          <div v-if="batchPreviewItems.length > 0" class="batch-preview-typeline">
            <span class="batch-preview-typeline__label">{{ t('lint.batchTypeDistribution') }}</span>
            <span class="batch-preview-typeline__sep">·</span>
            <template v-for="(count, type, idx) in batchPreviewSummary.typeBreakdown" :key="type">
              <span class="batch-preview-typeline__item">
                <component :is="getTypeIcon(type)" :size="13" />
                {{ getTypeLabel(type) }} × {{ count }}
              </span>
              <span v-if="idx < Object.keys(batchPreviewSummary.typeBreakdown).length - 1" class="batch-preview-typeline__dot">·</span>
            </template>
          </div>

          <!-- 跳过项提示（仅在有跳过时） -->
          <div v-if="batchPreviewSkippedCount > 0" class="batch-preview-skipped">
            <AlertTriangle :size="14" />
            <span>{{ t('lint.batchSkippedHint', [batchPreviewSkippedCount]) }}</span>
          </div>

          <!-- 风险提示：左侧 accent stripe + 浅底色 -->
          <div v-if="getRiskHint(batchPreviewAction)" class="batch-preview-hint" :class="`batch-preview-hint--${getRiskHint(batchPreviewAction)!.tone}`">
            <component :is="getRiskHint(batchPreviewAction)!.icon" :size="16" />
            <span>{{ getRiskHint(batchPreviewAction)!.text }}</span>
          </div>

          <!-- 详细列表：compact list, divider 分隔，无每条 border -->
          <div class="batch-preview-body">
            <div v-if="batchPreviewItems.length > 0" class="batch-preview-list">
              <article
                v-for="(item, idx) in batchPreviewItems"
                :key="item.id"
                class="batch-preview-item"
                :class="{
                  'batch-preview-item--high': item.priority === 'high',
                  'batch-preview-item--medium': item.priority === 'medium',
                  'batch-preview-item--last': idx === batchPreviewItems.length - 1
                }"
              >
                <div class="batch-preview-item__top">
                  <span class="batch-preview-item__icon-sq" :class="`batch-preview-item__icon-sq--${item.findingType}`">
                    <component :is="getTypeIcon(item.findingType)" :size="13" />
                  </span>
                  <span class="batch-preview-item__type">{{ getTypeLabel(item.findingType) }}</span>
                  <span class="batch-preview-item__title">{{ item.title }}</span>
                  <span
                    class="batch-preview-item__priority"
                    :class="`batch-preview-item__priority--${item.priority}`"
                  >{{ getPriorityLabel(item.priority).label }}</span>
                </div>
                <div class="batch-preview-item__bottom">
                  <div v-if="item.pagePath || item.assetId" class="batch-preview-item__path">
                    <span class="batch-preview-item__meta-key">{{ t('lint.batchPageLabel') }}</span>
                    <span class="batch-preview-item__meta-val">{{ item.pagePath || `#${item.assetId}` }}</span>
                  </div>
                  <div class="batch-preview-item__impact">
                    <span class="batch-preview-item__meta-key">{{ t('lint.batchImpactLabel') }}</span>
                    <span class="batch-preview-item__meta-val">{{ getImpactDescription(batchPreviewAction, item) }}</span>
                  </div>
                </div>
              </article>
            </div>

            <div v-else class="batch-preview-empty">
              <span>{{ t('lint.batchEmpty') }}</span>
            </div>
          </div>

          <footer class="batch-preview-dialog__footer">
            <button class="batch-preview-dialog__btn batch-preview-dialog__btn--cancel" @click="batchPreviewOpen = false">
              {{ t('common.cancel') }}
            </button>
            <button
              class="batch-preview-dialog__btn batch-preview-dialog__btn--confirm"
              :class="{ 'batch-preview-dialog__btn--danger': getRiskLevel(batchPreviewAction) === 'high' }"
              :disabled="batchPreviewItems.length === 0"
              @click="confirmBatchAction"
            >
              <component :is="getActionIcon(batchPreviewAction)" :size="14" />
              <span>{{ t('lint.batchConfirmAction', [getActionLabel(batchPreviewAction), batchPreviewItems.length]) }}</span>
            </button>
          </footer>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.lint-view {
  max-width: 960px;
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.lint-view__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.lint-view__header-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.lint-view__title {
  font-size: var(--font-h1);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
  margin: 0;
}

.lint-view__main-tabs {
  display: flex;
  gap: var(--space-1);
  padding: var(--space-1);
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
}

.lint-view__main-tabs--disabled { opacity: 0.5; }

.lint-view__main-tab {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex: 1;
  justify-content: center;
  padding: var(--space-2) var(--space-3);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
}

.lint-view__main-tab:hover:not(:disabled) {
  background: var(--bg-tertiary);
  color: var(--text-primary);
}

.lint-view__main-tab--active {
  background: var(--surface-card);
  color: var(--accent-primary);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.lint-view__main-tab:disabled {
  cursor: not-allowed;
}

.lint-view__main-tab-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: var(--radius-full);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  font-weight: var(--weight-semibold);
  min-width: 18px;
  text-align: center;
}

.lint-view__main-tab--active .lint-view__main-tab-badge {
  background: var(--accent-primary);
  color: var(--text-on-accent);
}

.lint-view__error-toast {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--error-light);
  color: var(--error);
  border: 1px solid var(--error);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

.lint-view__all-clear {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-8);
  color: var(--success);
}

.lint-view__all-clear-title {
  font-size: var(--font-h3);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.lint-view__all-clear-desc {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.lint-view__all-clear-text {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

/* 批量操作预览对话框 */
.batch-preview-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  animation: fadeIn 0.2s ease;
}

.batch-preview-dialog {
  background: var(--surface-card);
  border-radius: var(--radius-xl);
  width: 90%;
  max-width: 600px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  animation: slideUp 0.3s ease;
}

@keyframes slideUp {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

.batch-preview-dialog__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border-subtle);
  flex-shrink: 0;
  background: var(--surface-card);
}

.batch-preview-dialog__title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-primary);
}

.batch-preview-dialog__title h3 {
  font-size: var(--font-h3);
  font-weight: var(--weight-semibold);
  margin: 0;
}

.batch-preview-dialog__close {
  background: none;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: var(--space-1);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.batch-preview-dialog__close:hover {
  background: var(--bg-secondary);
  color: var(--text-primary);
}

.batch-preview-dialog__body {
  padding: var(--space-4) var(--space-5);
  overflow-y: auto;
  flex: 1 1 auto;
  min-height: 0;
  scrollbar-gutter: stable;
}

.batch-preview-dialog__desc {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin: 0 0 var(--space-3) 0;
}

.batch-preview-dialog__list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.batch-preview-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
}

.batch-preview-item__title {
  flex: 1;
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.batch-preview-item__type {
  padding: 2px 8px;
  background: var(--accent-light);
  color: var(--accent-primary);
  border-radius: var(--radius-full);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.batch-preview-item__status {
  padding: 2px 8px;
  background: var(--warning-light);
  color: var(--warning);
  border-radius: var(--radius-full);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.batch-preview-dialog__warning {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3);
  background: var(--warning-light);
  border: 1px solid var(--warning);
  border-radius: var(--radius-md);
  margin-top: var(--space-3);
  font-size: var(--font-body-sm);
  color: var(--warning);
}

.batch-preview-dialog__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-5);
  border-top: 1px solid var(--border-subtle);
  flex-shrink: 0;
  background: var(--surface-card);
}

.batch-preview-dialog__btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  border: none;
  transition: opacity var(--transition-fast);
}

.batch-preview-dialog__btn:hover {
  opacity: 0.85;
}

.batch-preview-dialog__btn--cancel {
  background: transparent;
  border: 1px solid var(--border-default);
  color: var(--text-secondary);
}

/* ============================================================
   批量操作预览对话框 — Flat Design + Color Blocking
   设计原则：零冗余边框、色块分区、统一字阶 12/14/16/18/24
   ============================================================ */

.batch-preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.48);
  backdrop-filter: blur(2px);
  padding: var(--space-4);
}

.batch-preview-dialog {
  background: var(--surface-card);
  border-radius: 12px;
  width: min(720px, 92vw);
  max-height: min(85vh, 760px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
  font-size: 14px;
  color: var(--text-primary);
  line-height: 1.5;
}

/* 高风险对话框顶部色带 */
.batch-preview-dialog--risk-high::before {
  content: '';
  display: block;
  height: 4px;
  background: var(--error);
}

/* ---------- Header ---------- */
.batch-preview-dialog__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 20px 24px 16px;
}

.batch-preview-dialog__title-block {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.batch-preview-dialog__icon-sq {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  flex-shrink: 0;
  color: white;
}

.batch-preview-dialog__icon-sq--low {
  background: var(--accent-primary);
}
.batch-preview-dialog__icon-sq--medium {
  background: var(--warning);
}
.batch-preview-dialog__icon-sq--high {
  background: var(--error);
}

.batch-preview-dialog__title-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.batch-preview-dialog__title {
  font-size: 18px;
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin: 0;
  line-height: 1.3;
}

.batch-preview-dialog__subtitle {
  font-size: 12px;
  color: var(--text-tertiary);
  margin: 0;
}

.batch-preview-dialog__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: transparent;
  border: none;
  color: var(--text-tertiary);
  border-radius: 6px;
  cursor: pointer;
  transition: background 150ms ease, color 150ms ease;
  flex-shrink: 0;
}

.batch-preview-dialog__close:hover {
  background: var(--bg-secondary);
  color: var(--text-primary);
}

/* ---------- Summary（color block） ---------- */
.batch-preview-summary {
  display: flex;
  align-items: stretch;
  margin: 0 24px 0;
  padding: 18px 0;
  background: var(--bg-secondary);
  border-radius: 8px;
}

.batch-preview-summary__hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 0 24px;
  min-width: 120px;
}

.batch-preview-summary__num {
  font-size: 32px;
  font-weight: var(--weight-bold);
  color: var(--accent-primary);
  line-height: 1.1;
  letter-spacing: -0.5px;
}

.batch-preview-summary__label {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 4px;
}

.batch-preview-summary__divider {
  width: 1px;
  background: var(--border-default);
  align-self: stretch;
}

.batch-preview-summary__stats {
  display: flex;
  flex: 1;
  padding: 0 24px;
  gap: 24px;
  align-items: center;
  flex-wrap: wrap;
}

.batch-preview-summary__stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 60px;
}

.batch-preview-summary__stat-num {
  font-size: 18px;
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  line-height: 1.2;
}

.batch-preview-summary__stat--warn .batch-preview-summary__stat-num {
  color: var(--error);
}

.batch-preview-summary__stat-label {
  font-size: 12px;
  color: var(--text-tertiary);
}

/* ---------- 类型分布 inline 条 ---------- */
.batch-preview-typeline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 10px;
  padding: 12px 24px 4px;
  font-size: 12px;
  color: var(--text-secondary);
}

.batch-preview-typeline__label {
  color: var(--text-tertiary);
  font-weight: var(--weight-medium);
}

.batch-preview-typeline__sep,
.batch-preview-typeline__dot {
  color: var(--text-tertiary);
  user-select: none;
}

.batch-preview-typeline__item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--text-secondary);
}

/* ---------- 跳过项 ---------- */
.batch-preview-skipped {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 24px 0;
  padding: 8px 12px;
  background: var(--bg-tertiary);
  border-radius: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}

/* ---------- 风险提示（左侧 accent stripe） ---------- */
.batch-preview-hint {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 8px 24px 0;
  padding: 10px 14px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.55;
  border-left: 3px solid;
}

.batch-preview-hint--error {
  background: rgba(239, 68, 68, 0.06);
  border-left-color: var(--error);
  color: var(--error);
}

.batch-preview-hint--warning {
  background: rgba(245, 158, 11, 0.06);
  border-left-color: var(--warning);
  color: var(--warning);
}

.batch-preview-hint--info {
  background: var(--bg-tertiary);
  border-left-color: var(--accent-primary);
  color: var(--text-secondary);
}

.batch-preview-hint > svg {
  flex-shrink: 0;
  margin-top: 1px;
}

/* ---------- 详细列表 ---------- */
.batch-preview-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px 8px;
  margin-top: 8px;
}

.batch-preview-list {
  display: flex;
  flex-direction: column;
}

.batch-preview-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border-subtle);
  position: relative;
}

.batch-preview-item--high {
  padding-left: 10px;
  border-left: 2px solid var(--error);
  margin-left: -12px;
  padding-left: 10px;
}

.batch-preview-item--medium {
  padding-left: 10px;
  border-left: 2px solid var(--warning);
  margin-left: -12px;
}

.batch-preview-item--last {
  border-bottom: none;
}

.batch-preview-item__top {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.batch-preview-item__icon-sq {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 5px;
  color: white;
  flex-shrink: 0;
}

.batch-preview-item__icon-sq--orphan,
.batch-preview-item__icon-sq--stale,
.batch-preview-item__icon-sq--duplicate_orphan {
  background: var(--warning);
}

.batch-preview-item__icon-sq--missing_crossref,
.batch-preview-item__icon-sq--gap,
.batch-preview-item__icon-sq--content_thin {
  background: var(--accent-primary);
}

.batch-preview-item__icon-sq--conflict,
.batch-preview-item__icon-sq--web_gap {
  background: var(--error);
}

.batch-preview-item__icon-sq--schema_compliance,
.batch-preview-item__icon-sq--schema_violation {
  background: var(--text-secondary);
}

.batch-preview-item__icon-sq--action {
  background: var(--success);
}

.batch-preview-item__type {
  font-size: 12px;
  color: var(--text-secondary);
  font-weight: var(--weight-medium);
  flex-shrink: 0;
}

.batch-preview-item__title {
  font-size: 14px;
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.batch-preview-item__priority {
  font-size: 12px;
  font-weight: var(--weight-medium);
  padding: 1px 8px;
  border-radius: 4px;
  flex-shrink: 0;
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}

.batch-preview-item__priority--high {
  background: var(--error);
  color: white;
}

.batch-preview-item__priority--medium {
  background: rgba(245, 158, 11, 0.18);
  color: var(--warning);
}

.batch-preview-item__priority--low {
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
}

.batch-preview-item__bottom {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-left: 30px;
}

.batch-preview-item__path,
.batch-preview-item__impact {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.batch-preview-item__meta-key {
  font-size: 12px;
  color: var(--text-tertiary);
  flex-shrink: 0;
  width: 28px;
  text-align: right;
}

.batch-preview-item__meta-val {
  font-size: 12px;
  color: var(--text-secondary);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.batch-preview-item__path .batch-preview-item__meta-val {
  font-family: var(--font-mono, ui-monospace, monospace);
  color: var(--text-secondary);
}

.batch-preview-item__impact .batch-preview-item__meta-val {
  color: var(--text-primary);
  white-space: normal;
  line-height: 1.5;
}

.batch-preview-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: var(--text-tertiary);
  font-size: 14px;
}

/* ---------- Footer ---------- */
.batch-preview-dialog__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px 20px;
  border-top: 1px solid var(--border-subtle);
}

.batch-preview-dialog__btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  padding: 0 16px;
  border-radius: 6px;
  font-size: 14px;
  font-weight: var(--weight-medium);
  cursor: pointer;
  border: none;
  transition: opacity 150ms ease, background 150ms ease;
}

.batch-preview-dialog__btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.batch-preview-dialog__btn--cancel {
  background: transparent;
  color: var(--text-secondary);
}

.batch-preview-dialog__btn--cancel:hover {
  background: var(--bg-secondary);
  color: var(--text-primary);
}

.batch-preview-dialog__btn--confirm {
  background: var(--accent-primary);
  color: white;
}

.batch-preview-dialog__btn--confirm:hover:not(:disabled) {
  opacity: 0.9;
}

.batch-preview-dialog__btn--danger {
  background: var(--error);
}
</style>