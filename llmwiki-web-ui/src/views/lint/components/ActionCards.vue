<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ShieldCheck, Loader2, AlertTriangle,
  Zap, RotateCcw, ThumbsUp, ThumbsDown,
  ChevronLeft, ChevronRight, CheckSquare, Square,
  Unlink, RefreshCw, Link2, Swords, Lightbulb, Globe, ChevronsLeft, ChevronsRight, FileWarning,
  Copy, FileText, X, Archive, Sparkles
} from 'lucide-vue-next'
import RulingBriefCard from '@/components/lint/RulingBriefCard.vue'
import CrossrefSuggestionCard from '@/components/lint/CrossrefSuggestionCard.vue'
import InlineMarkdown from '@/components/common/InlineMarkdown.vue'
import { type LintFindingInfo, type PageResult } from '@/api/lint'
import type { MainTabKey } from '@/stores/lint'

const { t } = useI18n()

const props = defineProps<{
  running: boolean
  findingsPaged: PageResult<LintFindingInfo> | null
  findingsLoading: boolean
  mainTab: MainTabKey
  manualTypeFilter: string | undefined
  processingIds: Set<number>
  selectedIds: Set<number>
  selectedCount: number
  allCurrentSelected: boolean
  batchProcessing: boolean
  mainTabCounts: Record<MainTabKey, number>
  pageSize: number
  pageSizeOptions: number[]
  hasSelectedAwaiting: boolean
  hasSelectedAutoResolved: boolean
  hasSelectedStale: boolean
  selectedAwaitingCount: number
  selectedStaleCount: number
  selectedAutoResolvedCount: number
  hasSelectedCrossrefOpen: boolean
  selectedCrossrefOpenCount: number
  hasSelectedFailed: boolean
  selectedAutoResolvableCount: number
  selectedFailedCount: number
}>()

const emit = defineEmits<{
  switchMainTab: [tab: MainTabKey]
  goToPage: [page: number]
  setPageSize: [size: number]
  toggleSelect: [id: number]
  toggleSelectAll: []
  clearSelection: []
  dismiss: [id: number]
  autoResolve: [id: number]
  triggerRepair: [id: number]
  approve: [id: number]
  reject: [id: number]
  rollback: [id: number]
  batchAutoResolve: []
  batchDismiss: []
  batchTriggerRepair: []
  batchApprove: []
  batchReject: []
  batchRollback: []
  navigateToPage: [assetId: number | null]
  retryOrphan: [id: number]
  enrichPage: [id: number, userSupplement: string]
  approveLink: [id: number, suggestion: any]
  rejectLink: [id: number, suggestion: any]
  ignoreLink: [id: number]
  batchApproveLinks: []
  batchRejectLinks: []
  reassess: [id: number]
  resolveCompliance: [id: number]
  conflictAction: [id: number, action: string]
  showBatchPreview: [action: string]
  retryFailed: [id: number]
}>()

const typeLabelKeyMap: Record<string, string> = {
  orphan: 'lint.typeLabelOrphan', stale: 'lint.typeLabelStale', missing_crossref: 'lint.typeLabelMissingCrossref',
  conflict: 'lint.typeLabelConflict', gap: 'lint.typeLabelGap', web_gap: 'lint.typeLabelWebGap', action: 'lint.typeLabelAction',
  schema_compliance: 'lint.typeLabelSchemaCompliance', schema_violation: 'lint.typeLabelSchemaViolation',
  duplicate_orphan: 'lint.typeLabelDuplicateOrphan', content_thin: 'lint.typeLabelContentThin'
}

const typeLabel = (type: string) => {
  return t(typeLabelKeyMap[type] || type)
}

const typeIcon = (t: string) => {
  const map: Record<string, typeof AlertTriangle> = {
    orphan: Unlink, stale: RefreshCw, missing_crossref: Link2,
    conflict: Swords, gap: Lightbulb, web_gap: Globe, action: Zap,
    schema_compliance: FileWarning, schema_violation: FileWarning,
    duplicate_orphan: Copy, content_thin: FileText
  }
  return map[t] || AlertTriangle
}

const typeColor = (t: string) => {
  const map: Record<string, string> = {
    conflict: 'var(--error)', stale: 'var(--warning)', orphan: 'var(--warning)',
    missing_crossref: 'var(--accent-primary)', gap: 'var(--accent-primary)',
    web_gap: 'var(--error)', action: 'var(--success)',
    duplicate_orphan: 'var(--warning)', content_thin: 'var(--info, var(--accent-primary))'
  }
  return map[t] || 'var(--text-secondary)'
}

const typeBgColor = (t: string) => {
  const map: Record<string, string> = {
    conflict: 'rgba(239, 68, 68, 0.12)', stale: 'rgba(245, 158, 11, 0.12)', orphan: 'rgba(245, 158, 11, 0.12)',
    missing_crossref: 'rgba(59, 130, 246, 0.12)', gap: 'rgba(59, 130, 246, 0.12)',
    web_gap: 'rgba(239, 68, 68, 0.12)', action: 'rgba(34, 197, 94, 0.12)',
    duplicate_orphan: 'rgba(245, 158, 11, 0.12)', content_thin: 'rgba(59, 130, 246, 0.12)'
  }
  return map[t] || 'rgba(100, 100, 100, 0.12)'
}

const handlingLabelKeyMap: Record<string, string> = {
  auto_repair: 'lint.handlingAutoRepair', auto_refresh: 'lint.handlingAutoRefresh', manual_ingest: 'lint.handlingManualIngest',
  manual_merge: 'lint.handlingManualMerge', ruling_brief: 'lint.handlingRulingBrief', dismiss: 'lint.handlingDismiss',
  manual_edit: 'lint.handlingManualEdit', manual_edit_confirmed: 'lint.handlingManualEditConfirmed'
}

const handlingLabel = (m: string | null) => {
  return m ? (handlingLabelKeyMap[m] ? t(handlingLabelKeyMap[m]) : m) : null
}

const diagnosisLabelKeyMap: Record<string, string> = {
  integrate: 'lint.diagnosisIntegrate', duplicate: 'lint.diagnosisDuplicate', standalone: 'lint.diagnosisStandalone', thin: 'lint.diagnosisThin'
}

const diagnosisLabel = (d: string | null) => {
  if (!d) return null
  return diagnosisLabelKeyMap[d] ? t(diagnosisLabelKeyMap[d]) : d
}

const diagnosisColor = (d: string | null) => {
  if (!d) return 'var(--text-tertiary)'
  const map: Record<string, string> = {
    integrate: 'var(--success)', duplicate: 'var(--warning)',
    standalone: 'var(--text-secondary)', thin: 'var(--info, var(--accent-primary))'
  }
  return map[d] || 'var(--text-tertiary)'
}

const parseExtra = (extra: string | null): Record<string, any> | null => {
  if (!extra) return null
  try { return JSON.parse(extra) } catch { return null }
}

const violationTypeLabelKeyMap: Record<string, string> = {
  CATEGORY: 'lint.violationCategory', NAMING: 'lint.violationNaming', PAGE_STRUCTURE: 'lint.violationPageStructure'
}

const violationTypeLabel = (vtype: string) => {
  return t(violationTypeLabelKeyMap[vtype] || vtype)
}

const enrichDialogOpen = ref(false)
const enrichFindingId = ref<number | null>(null)
const enrichText = ref('')

function openEnrichDialog(findingId: number) {
  enrichFindingId.value = findingId
  enrichText.value = ''
  enrichDialogOpen.value = true
}

function closeEnrichDialog() {
  enrichDialogOpen.value = false
  enrichFindingId.value = null
  enrichText.value = ''
}

function submitEnrich() {
  if (enrichFindingId.value != null && enrichText.value.trim()) {
    emit('enrichPage', enrichFindingId.value, enrichText.value.trim())
    closeEnrichDialog()
  }
}

function handleModifyFinding(findingId: number) {
  // 暂时实现为直接审批,但显示提示用户可以后续手动调整
  // 完整实现需要弹出编辑器让用户修改rulingBriefJson
  emit('approve', findingId)
}

function handleDuplicateOrphan(findingId: number) {
  alert(t('lint.duplicateOrphanAlert'))
  emit('dismiss', findingId)
}

function extractConflictPageTitles(finding: LintFindingInfo): { fromTitle: string; toTitle: string } {
  const extra = parseExtra(finding.extra)
  const titleMatch = finding.title.match(/\u300c([^\u300d]+)\u300d.*\u300c([^\u300d]+)\u300d/)
  const fromTitle = titleMatch ? titleMatch[1] : (finding.title || t('lint.pageFallbackA'))
  const toTitle = titleMatch
    ? titleMatch[2]
    : (extra?.relatedPageTitle || extra?.relatedPagePath || t('lint.pageFallbackB'))
  return { fromTitle, toTitle }
}

function handleConflictAction(findingId: number, action: string) {
  if (action === 'dismiss') {
    emit('dismiss', findingId)
  } else {
    emit('conflictAction', findingId, action)
  }
}

const priorityLabelKeyMap: Record<string, string> = {
  high: 'lint.severityHigh', medium: 'lint.severityMedium', low: 'lint.severityLow'
}

const priorityLabel = (p: string) => {
  return t(priorityLabelKeyMap[p] || p)
}

const priorityBg = (p: string) => {
  if (p === 'high') return 'var(--error)'
  if (p === 'medium') return 'var(--warning)'
  return 'var(--bg-tertiary)'
}

const statusBadgeColor = (s: string) => {
  if (s === 'open') return 'var(--warning)'
  if (s === 'repairing') return 'var(--accent-primary)'
  if (s === 'auto_resolved') return 'var(--success)'
  if (s === 'awaiting_approval') return 'var(--info, var(--accent-primary))'
  if (s === 'deferred') return 'var(--text-tertiary)'
  if (s === 'resolved') return 'var(--success)'
  if (s === 'dismissed') return 'var(--text-tertiary)'
  if (s === 'rolled_back') return 'var(--error)'
  if (s === 'failed') return 'var(--error)'
  return 'var(--text-tertiary)'
}

const statusLabelKeyMap: Record<string, string> = {
  open: 'lint.statusOpen', repairing: 'lint.statusRepairing', auto_resolved: 'lint.statusAutoResolved',
  awaiting_approval: 'lint.statusAwaitingApproval', deferred: 'lint.statusDeferred',
  resolved: 'lint.statusResolved', rolled_back: 'lint.statusRolledBack', dismissed: 'lint.statusDismissed', failed: 'lint.statusFailed'
}

const statusLabelMap = (s: string) => {
  return t(statusLabelKeyMap[s] || s)
}

const currentItems = computed(() => props.findingsPaged?.items ?? [])

const tabEmptyText = computed(() => {
  if (props.mainTab === 'manual') {
    if (props.manualTypeFilter) return t('lint.tabEmptyManualFiltered', [typeLabel(props.manualTypeFilter)])
    return t('lint.tabEmptyManual')
  }
  if (props.mainTab === 'ai_processed') return t('lint.tabEmptyAiProcessed')
  return t('lint.tabEmptyArchived')
})

const totalPages = computed(() => props.findingsPaged?.totalPages ?? 1)
const currentPage = computed(() => props.findingsPaged?.page ?? 1)
const totalItems = computed(() => props.findingsPaged?.total ?? 0)

const visiblePages = computed(() => {
  const pages: number[] = []
  const tp = totalPages.value
  const cp = currentPage.value
  if (tp <= 7) {
    for (let i = 1; i <= tp; pages.push(++i));
  } else {
    pages.push(1)
    if (cp > 3) pages.push(-1)
    const start = Math.max(2, cp - 1)
    const end = Math.min(tp - 1, cp + 1)
    for (let i = start; i <= end; i++) pages.push(i)
    if (cp < tp - 2) pages.push(-1)
    pages.push(tp)
  }
  return pages
})

</script>

<template>
  <div class="action-cards">
    <div class="action-cards__toolbar">
      <div class="action-cards__toolbar-left">
        <button
          v-if="mainTab !== 'archived' && mainTab !== 'ai_processed'"
          class="action-cards__select-btn"
          @click="emit('toggleSelectAll')"
        >
          <component :is="allCurrentSelected ? CheckSquare : Square" :size="14" />
          {{ allCurrentSelected ? t('lint.cancelSelectAll') : t('lint.selectAll') }}
        </button>
        <span v-else-if="mainTab === 'archived'" class="action-cards__archive-hint">
          <Archive :size="13" />
          {{ t('lint.archiveHint') }}
        </span>
        <span v-else-if="mainTab === 'ai_processed'" class="action-cards__archive-hint">
          <Sparkles :size="13" />
          {{ t('lint.aiProcessedHint') }}
        </span>
      </div>
      <span class="action-cards__toolbar-total">{{ t('lint.totalItemsSuffix', [totalItems]) }}</span>
    </div>

    <div class="action-cards__content">
      <div v-if="running" class="action-cards__running">
        <Loader2 :size="20" class="action-cards__spin" />
        <span>{{ t('lint.lintInProgressHint') }}</span>
      </div>

      <div v-else-if="findingsLoading" class="action-cards__loading">
        <Loader2 :size="20" class="action-cards__spin" />
        {{ t('common.loading') }}
      </div>

      <template v-else>
        <div v-if="currentItems.length === 0" class="action-cards__empty">
          <ShieldCheck :size="24" />
          <span>{{ tabEmptyText }}</span>
        </div>

        <template v-if="currentItems.length > 0">
          <div v-if="mainTab !== 'archived' && mainTab !== 'ai_processed' && selectedCount > 0" class="action-cards__batch-bar">
            <span class="action-cards__batch-count">{{ t('lint.selectedCountLabel', [selectedCount]) }}</span>
            <div class="action-cards__batch-actions">
              <button
                v-if="selectedAutoResolvableCount > 0"
                class="action-cards__batch-btn action-cards__batch-btn--resolve"
                :disabled="batchProcessing"
                @click="emit('showBatchPreview', 'autoResolve')"
              >
                <Loader2 v-if="batchProcessing" :size="12" class="action-cards__spin" />
                <Zap v-else :size="12" />{{ t('lint.batchFixCount', [selectedAutoResolvableCount]) }}
              </button>
              <button
                v-if="hasSelectedStale"
                class="action-cards__batch-btn action-cards__batch-btn--refresh"
                :disabled="batchProcessing"
                @click="emit('showBatchPreview', 'triggerRepair')"
              >
                <Loader2 v-if="batchProcessing" :size="12" class="action-cards__spin" />
                <RefreshCw v-else :size="12" />{{ t('lint.batchRefreshCount', [selectedStaleCount]) }}
              </button>
              <button
                v-if="hasSelectedFailed"
                class="action-cards__batch-btn action-cards__batch-btn--refresh"
                :disabled="batchProcessing"
                @click="emit('showBatchPreview', 'retryFailed')"
              >
                <Loader2 v-if="batchProcessing" :size="12" class="action-cards__spin" />
                <RefreshCw v-else :size="12" />{{ t('lint.batchRetryFailedCount', [selectedFailedCount]) }}
              </button>
              <button
                v-if="hasSelectedAwaiting"
                class="action-cards__batch-btn action-cards__batch-btn--approve"
                :disabled="batchProcessing"
                @click="emit('showBatchPreview', 'approve')"
              >
                <Loader2 v-if="batchProcessing" :size="12" class="action-cards__spin" />
                <ThumbsUp v-else :size="12" />{{ t('lint.batchApproveCount', [selectedAwaitingCount]) }}
              </button>
              <button
                v-if="hasSelectedAwaiting"
                class="action-cards__batch-btn action-cards__batch-btn--reject"
                :disabled="batchProcessing"
                @click="emit('showBatchPreview', 'reject')"
              >
                <Loader2 v-if="batchProcessing" :size="12" class="action-cards__spin" />
                <ThumbsDown v-else :size="12" />{{ t('lint.batchRejectCount', [selectedAwaitingCount]) }}
              </button>
              <button
                v-if="hasSelectedAutoResolved"
                class="action-cards__batch-btn action-cards__batch-btn--rollback"
                :disabled="batchProcessing"
                @click="emit('showBatchPreview', 'rollback')"
              >
                <Loader2 v-if="batchProcessing" :size="12" class="action-cards__spin" />
                <RotateCcw v-else :size="12" />{{ t('lint.batchRollbackCount', [selectedAutoResolvedCount]) }}
              </button>
              <button
                v-if="hasSelectedCrossrefOpen"
                class="action-cards__batch-btn action-cards__batch-btn--link"
                :disabled="batchProcessing"
                @click="emit('showBatchPreview', 'approveLinks')"
              >
                <Loader2 v-if="batchProcessing" :size="12" class="action-cards__spin" />
                <Link2 v-else :size="12" />{{ t('lint.batchApproveLinksCount', [selectedCrossrefOpenCount]) }}
              </button>
              <button
                v-if="hasSelectedCrossrefOpen"
                class="action-cards__batch-btn action-cards__batch-btn--reject-link"
                :disabled="batchProcessing"
                @click="emit('showBatchPreview', 'rejectLinks')"
              >
                <Loader2 v-if="batchProcessing" :size="12" class="action-cards__spin" />
                <X v-else :size="12" />{{ t('lint.batchRejectLinksCount', [selectedCrossrefOpenCount]) }}
              </button>
              <button
                class="action-cards__batch-btn action-cards__batch-btn--dismiss"
                :disabled="batchProcessing"
                @click="emit('showBatchPreview', 'dismiss')"
              >
                {{ t('lint.batchDismissCount', [selectedCount]) }}
              </button>
            </div>
            <button class="action-cards__batch-btn action-cards__batch-btn--ghost" @click="emit('clearSelection')">{{ t('lint.cancelSelection') }}</button>
          </div>

          <div class="action-cards__list">
            <div
              v-for="finding in currentItems"
              :key="finding.id"
              class="action-card"
              :class="{
                'action-card--auto': finding.status === 'auto_resolved',
                'action-card--archived': mainTab === 'archived',
                'action-card--dismissed': finding.status === 'dismissed'
              }"
            >
              <div class="action-card__header">
                <button
                  v-if="mainTab !== 'archived' && mainTab !== 'ai_processed'"
                  class="action-card__check"
                  @click="emit('toggleSelect', finding.id)"
                >
                  <component :is="selectedIds.has(finding.id) ? CheckSquare : Square" :size="14" />
                </button>
                <component :is="typeIcon(finding.findingType)" :size="14" :style="{ color: typeColor(finding.findingType) }" />
                <span class="action-card__type" :style="{ color: typeColor(finding.findingType), background: typeBgColor(finding.findingType) }">
                  {{ typeLabel(finding.findingType) }}
                </span>
                <span class="action-card__title" @click="emit('navigateToPage', finding.assetId)">{{ finding.title }}</span>
                <span class="action-card__priority" :style="{ background: priorityBg(finding.priority), color: 'white' }">
                  {{ priorityLabel(finding.priority) }}
                </span>
                <span v-if="handlingLabel(finding.handlingMethod)" class="action-card__handling">
                  {{ handlingLabel(finding.handlingMethod) }}
                </span>
                <span
                  v-if="finding.findingType === 'orphan' && diagnosisLabel(finding.orphanDiagnosis)"
                  class="action-card__diagnosis"
                  :style="{ color: diagnosisColor(finding.orphanDiagnosis), borderColor: diagnosisColor(finding.orphanDiagnosis) }"
                >
                  {{ diagnosisLabel(finding.orphanDiagnosis) }}
                </span>
                <span class="action-card__status" :style="{ background: statusBadgeColor(finding.status) }">
                  {{ statusLabelMap(finding.status) }}
                </span>
              </div>

              <div v-if="finding.detail" class="action-card__detail">
                <InlineMarkdown :content="finding.detail" />
              </div>

              <div
                v-if="mainTab === 'archived' && finding.status === 'dismissed'"
                class="action-card__archive-actions"
              >
                <span class="action-card__archive-label">{{ t('lint.ignored') }}</span>
              </div>

              <template v-if="finding.findingType === 'schema_compliance' && parseExtra(finding.extra)">
                <div class="action-card__compliance-info">
                  <div class="compliance-row">
                    <span class="compliance-label">{{ t('lint.violationType') }}</span>
                    <span class="compliance-value">{{ violationTypeLabel(parseExtra(finding.extra)!.violationType) }}</span>
                  </div>
                  <div v-if="parseExtra(finding.extra)!.expectedStructure" class="compliance-row">
                    <span class="compliance-label">{{ t('lint.expectedStructure') }}</span>
                    <span class="compliance-value">{{ parseExtra(finding.extra)!.expectedStructure }}</span>
                  </div>
                  <div v-if="parseExtra(finding.extra)!.actualStructure" class="compliance-row">
                    <span class="compliance-label">{{ t('lint.actualStructure') }}</span>
                    <span class="compliance-value">{{ parseExtra(finding.extra)!.actualStructure }}</span>
                  </div>
                </div>
              </template>

              <RulingBriefCard
                v-if="finding.rulingBriefJson && (finding.status === 'awaiting_approval' || finding.status === 'auto_resolved' || finding.status === 'resolved')"
                :ruling-brief-json="finding.rulingBriefJson"
                :finding-id="finding.id"
                :finding-type="finding.findingType"
                :finding-title="finding.title"
                :status="finding.status"
                :from-page-title="finding.findingType === 'conflict' ? extractConflictPageTitles(finding).fromTitle : undefined"
                :to-page-title="finding.findingType === 'conflict' ? extractConflictPageTitles(finding).toTitle : undefined"
                :readonly="mainTab === 'archived' || mainTab === 'ai_processed'"
                @approve="emit('approve', finding.id)"
                @modify="handleModifyFinding(finding.id)"
                @reject="emit('reject', finding.id)"
                @rollback="emit('rollback', finding.id)"
                @conflict-action="handleConflictAction"
              />

              <div
                v-else-if="!finding.rulingBriefJson && (finding.status === 'auto_resolved' || finding.status === 'resolved')"
                class="action-card__auto-resolved-brief"
              >
                <Zap :size="14" style="color: var(--success)" />
                <span>{{ t('lint.aiAutoResolved') }}</span>
                <span v-if="finding.handlingMethod" class="action-card__auto-method">{{ handlingLabel(finding.handlingMethod) || finding.handlingMethod }}</span>
                <button
                  v-if="mainTab !== 'archived' && mainTab !== 'ai_processed'"
                  class="action-card__btn action-card__btn--ghost"
                  :disabled="processingIds.has(finding.id)"
                  @click="emit('rollback', finding.id)"
                >
                  <RotateCcw :size="12" />{{ t('lint.rollbackBtn') }}
                </button>
              </div>

              <!-- Phase 2 新增：交叉引用建议卡片 -->
              <template v-if="mainTab !== 'archived' && mainTab !== 'ai_processed' && finding.findingType === 'missing_crossref' && finding.crossrefSuggestions && finding.crossrefSuggestions.length > 0">
                <CrossrefSuggestionCard
                  v-for="(suggestion, idx) in finding.crossrefSuggestions"
                  :key="idx"
                  :suggestion="suggestion"
                  :finding-id="finding.id"
                  :processing="processingIds.has(finding.id)"
                  @approve="emit('approveLink', finding.id, suggestion)"
                  @reject="emit('rejectLink', finding.id, suggestion)"
                  @ignore="emit('ignoreLink', finding.id)"
                />
              </template>

              <div v-if="mainTab !== 'archived' && mainTab !== 'ai_processed' && (!finding.rulingBriefJson || finding.status === 'open' || finding.status === 'failed') && !(finding.status === 'auto_resolved' && !finding.rulingBriefJson) && !(finding.status === 'resolved' && !finding.rulingBriefJson) && !(finding.findingType === 'missing_crossref' && finding.crossrefSuggestions && finding.crossrefSuggestions.length > 0)" class="action-card__actions">
                <button
                  v-if="finding.status === 'open' && finding.findingType === 'orphan'"
                  class="action-card__btn action-card__btn--primary"
                  :disabled="processingIds.has(finding.id)"
                  @click="emit('retryOrphan', finding.id)"
                >
                  <Loader2 v-if="processingIds.has(finding.id)" :size="12" class="action-cards__spin" />
                  <Zap v-else :size="12" />{{ t('lint.aiDiagnoseFix') }}
                </button>
                <button
                  v-if="finding.status === 'open' && finding.findingType !== 'orphan' && finding.findingType !== 'content_thin' && finding.findingType !== 'schema_compliance' && finding.findingType !== 'stale'"
                  class="action-card__btn action-card__btn--primary"
                  :disabled="processingIds.has(finding.id)"
                  @click="emit('autoResolve', finding.id)"
                >
                  <Zap :size="12" />{{ t('lint.fixBtn') }}
                </button>
                <button
                  v-if="finding.status === 'open' && finding.findingType === 'schema_compliance'"
                  class="action-card__btn action-card__btn--confirm"
                  :disabled="processingIds.has(finding.id)"
                  @click="emit('resolveCompliance', finding.id)"
                >
                  <CheckSquare :size="12" />{{ t('lint.markFixed') }}
                </button>
                <button
                  v-if="finding.status === 'open' && finding.findingType === 'content_thin'"
                  class="action-card__btn action-card__btn--ingest"
                  :disabled="processingIds.has(finding.id)"
                  @click="openEnrichDialog(finding.id)"
                >
                  <Loader2 v-if="processingIds.has(finding.id)" :size="12" class="action-cards__spin" />
                  <FileText v-else :size="12" />{{ t('lint.enrichContent') }}
                </button>
                <button
                  v-if="finding.status === 'open' && finding.findingType === 'duplicate_orphan'"
                  class="action-card__btn action-card__btn--warning"
                  :disabled="processingIds.has(finding.id)"
                  @click="handleDuplicateOrphan(finding.id)"
                >
                  <Copy :size="12" />{{ t('lint.startMerge') }}
                </button>
                <button
                  v-if="finding.status === 'open' && finding.findingType === 'stale'"
                  class="action-card__btn action-card__btn--refresh"
                  :disabled="processingIds.has(finding.id)"
                  @click="emit('triggerRepair', finding.id)"
                >
                  <RefreshCw :size="12" />{{ t('common.refresh') }}
                </button>
                <button
                  v-if="finding.status === 'awaiting_approval' && finding.findingType !== 'conflict'"
                  class="action-card__btn action-card__btn--approve"
                  :disabled="processingIds.has(finding.id)"
                  @click="emit('approve', finding.id)"
                >
                  <ThumbsUp :size="12" />{{ t('lint.approveBtn') }}
                </button>
                <button
                  v-if="finding.status === 'awaiting_approval' && finding.findingType !== 'conflict'"
                  class="action-card__btn action-card__btn--reject"
                  :disabled="processingIds.has(finding.id)"
                  @click="emit('reject', finding.id)"
                >
                  <ThumbsDown :size="12" />{{ t('lint.rejectBtn') }}
                </button>
                <button
                  v-if="finding.status === 'repairing'"
                  class="action-card__btn action-card__btn--disabled"
                  disabled
                >
                  <Loader2 :size="12" class="action-cards__spin" />{{ t('lint.statusRepairing') }}
                </button>
                <button
                  v-if="finding.status === 'deferred'"
                  class="action-card__btn action-card__btn--primary"
                  :disabled="processingIds.has(finding.id)"
                  @click="emit('reassess', finding.id)"
                >
                  <RefreshCw :size="12" />{{ t('lint.reassessBtn') }}
                </button>
                <button
                  v-if="finding.status === 'failed'"
                  class="action-card__btn action-card__btn--refresh"
                  :disabled="processingIds.has(finding.id)"
                  @click="emit('retryFailed', finding.id)"
                >
                  <Loader2 v-if="processingIds.has(finding.id)" :size="12" class="action-cards__spin" />
                  <RefreshCw v-else :size="12" />{{ t('lint.retryFailedBtn') }}
                </button>
                <button
                  v-if="finding.status === 'auto_resolved' && !finding.rulingBriefJson"
                  class="action-card__btn action-card__btn--ghost"
                  :disabled="processingIds.has(finding.id)"
                  @click="emit('rollback', finding.id)"
                >
                  <RotateCcw :size="12" />{{ t('lint.rollbackBtn') }}
                </button>
                <button
                  v-if="finding.status === 'open' || finding.status === 'awaiting_approval'"
                  class="action-card__btn action-card__btn--dismiss"
                  :disabled="processingIds.has(finding.id)"
                  @click="emit('dismiss', finding.id)"
                >
                  {{ t('lint.ignore') }}
                </button>
              </div>
            </div>
          </div>

          <div v-if="totalPages > 1 || pageSizeOptions.length > 0" class="action-cards__pagination">
            <div class="action-cards__pagination-left">
              <span class="action-cards__pagination-info">{{ t('lint.paginationInfo', [currentPage, totalPages, totalItems]) }}</span>
              <select
                class="action-cards__page-size-select"
                :value="pageSize"
                @change="emit('setPageSize', Number(($event.target as HTMLSelectElement).value))"
              >
                <option v-for="opt in pageSizeOptions" :key="opt" :value="opt">{{ opt }} {{ t('lint.pageSizeSuffix') }}</option>
              </select>
            </div>
            <div class="action-cards__pagination-right">
              <button class="action-cards__page-btn" :disabled="currentPage <= 1" @click="emit('goToPage', 1)">
                <ChevronsLeft :size="14" />
              </button>
              <button class="action-cards__page-btn" :disabled="currentPage <= 1" @click="emit('goToPage', currentPage - 1)">
                <ChevronLeft :size="14" />
              </button>
              <template v-for="p in visiblePages" :key="p">
                <span v-if="p === -1" class="action-cards__page-ellipsis">...</span>
                <button
                  v-else
                  class="action-cards__page-num"
                  :class="{ 'action-cards__page-num--active': p === currentPage }"
                  @click="emit('goToPage', p)"
                >
                  {{ p }}
                </button>
              </template>
              <button class="action-cards__page-btn" :disabled="currentPage >= totalPages" @click="emit('goToPage', currentPage + 1)">
                <ChevronRight :size="14" />
              </button>
              <button class="action-cards__page-btn" :disabled="currentPage >= totalPages" @click="emit('goToPage', totalPages)">
                <ChevronsRight :size="14" />
              </button>
            </div>
          </div>
        </template>
      </template>
    </div>

    <Teleport to="body">
      <div v-if="enrichDialogOpen" class="enrich-overlay" @click.self="closeEnrichDialog">
        <div class="enrich-dialog">
          <div class="enrich-dialog__header">
            <h3 class="enrich-dialog__title">
              <FileText :size="16" />
              {{ t('lint.enrichDialogTitle') }}
            </h3>
            <button class="enrich-dialog__close" @click="closeEnrichDialog">
              <X :size="16" />
            </button>
          </div>
          <p class="enrich-dialog__desc">{{ t('lint.enrichDialogDesc') }}</p>
          <textarea
            v-model="enrichText"
            class="enrich-dialog__textarea"
            :placeholder="t('lint.enrichPlaceholder')"
            rows="8"
            autofocus
          ></textarea>
          <div class="enrich-dialog__actions">
            <button class="enrich-dialog__btn enrich-dialog__btn--cancel" @click="closeEnrichDialog">{{ t('common.cancel') }}</button>
            <button
              class="enrich-dialog__btn enrich-dialog__btn--submit"
              :disabled="!enrichText.trim() || (enrichFindingId != null && processingIds.has(enrichFindingId))"
              @click="submitEnrich"
            >
              <Loader2 v-if="enrichFindingId != null && processingIds.has(enrichFindingId)" :size="14" class="action-cards__spin" />
              <Zap v-else :size="14" />
              {{ t('lint.aiEnrichBtn') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.action-cards {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
}

.action-cards__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2) var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
}

.action-cards__toolbar-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.action-cards__filter-type {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-secondary);
}

.action-cards__filter-select {
  font-size: var(--font-body-sm);
  padding: 2px 8px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  color: var(--text-primary);
  cursor: pointer;
}

.action-cards__select-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-caption);
  color: var(--text-secondary);
  background: none;
  border: none;
  cursor: pointer;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-md);
}

.action-cards__select-btn:hover { color: var(--accent-primary); background: var(--bg-secondary); }

.action-cards__toolbar-total {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.action-cards__content { padding: var(--space-4); }

.action-cards__loading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  padding: var(--space-6);
  justify-content: center;
}

.action-cards__running {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--accent-primary);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  padding: var(--space-8);
  justify-content: center;
}

.action-cards__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-8);
  color: var(--text-tertiary);
  text-align: center;
}

.action-cards__batch-bar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--accent-light);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-3);
}

.action-cards__batch-count {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  white-space: nowrap;
}

.action-cards__batch-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  flex: 1;
}

.action-cards__batch-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--font-caption);
  padding: 4px 12px;
  border-radius: var(--radius-md);
  border: none;
  cursor: pointer;
  font-weight: var(--weight-medium);
  transition: opacity var(--transition-fast);
}

.action-cards__batch-btn:hover { opacity: 0.85; }
.action-cards__batch-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-cards__batch-btn--resolve { background: var(--accent-primary); color: var(--text-on-accent); }
.action-cards__batch-btn--refresh { background: var(--accent-primary); color: var(--text-on-accent); }
.action-cards__batch-btn--approve { background: var(--success); color: white; }
.action-cards__batch-btn--reject { background: var(--error); color: white; }
.action-cards__batch-btn--link { background: var(--success); color: white; }
.action-cards__batch-btn--reject-link { background: var(--error); color: white; }
.action-cards__batch-btn--rollback { background: transparent; border: 1px solid var(--border-default); color: var(--text-secondary); }
.action-cards__batch-btn--dismiss { background: transparent; color: var(--text-tertiary); border: 1px solid var(--border-subtle); }
.action-cards__batch-btn--ghost { background: transparent; color: var(--text-secondary); border: 1px solid var(--border-default); }

.action-cards__list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.action-card {
  padding: var(--space-3);
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  transition: border-color var(--transition-fast);
}

.action-card:hover { border-color: var(--border-default); }

.action-card--auto { background: var(--success-light); border-color: transparent; }

.action-card--archived {
  padding: var(--space-2) var(--space-3);
  background: transparent;
  border: none;
  border-bottom: 1px solid var(--border-subtle);
  border-radius: 0;
}

.action-card--archived:hover { background: var(--bg-secondary); }

.action-card--dismissed {
  background: color-mix(in srgb, var(--warning) 4%, transparent);
}

.action-card__archive-actions {
  margin-top: var(--space-2);
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}

.action-card__archive-label {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  font-weight: var(--weight-medium);
}

.action-cards__archive-hint {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: 12px;
  color: var(--text-tertiary);
  letter-spacing: 0.01em;
}

.action-card__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.action-card__check {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-1);
  background: none;
  border: none;
  cursor: pointer;
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.action-card__check:hover { color: var(--accent-primary); }

.action-card__type {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  padding: 1px 6px;
  border-radius: var(--radius-full);
  white-space: nowrap;
}

.action-card__title {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  cursor: pointer;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.action-card__title:hover { color: var(--accent-primary); }

.action-card__priority {
  font-size: 10px;
  font-weight: var(--weight-bold);
  padding: 1px 5px;
  border-radius: var(--radius-full);
  white-space: nowrap;
}

.action-card__handling {
  font-size: 10px;
  font-weight: var(--weight-medium);
  color: var(--success);
  white-space: nowrap;
}

.action-card__status {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: var(--radius-full);
  color: white;
  white-space: nowrap;
}

.action-card__detail {
  font-size: var(--font-caption);
  color: var(--text-secondary);
  margin: var(--space-1) 0 var(--space-2);
  padding-left: var(--space-6);
}

.action-card__auto-resolved-brief {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  margin: var(--space-2) 0 0 var(--space-6);
  background: var(--success-light);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  color: var(--success);
}

.action-card__auto-method {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  background: var(--bg-secondary);
  padding: 1px 6px;
  border-radius: var(--radius-full);
}

.action-card__actions {
  display: flex;
  gap: var(--space-2);
  padding-left: var(--space-6);
  flex-wrap: wrap;
}

.action-card__btn {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--font-caption);
  padding: 4px 12px;
  border-radius: var(--radius-md);
  border: none;
  cursor: pointer;
  font-weight: var(--weight-medium);
  transition: opacity var(--transition-fast);
}

.action-card__btn:hover { opacity: 0.85; }
.action-card__btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-card__btn--primary { background: var(--accent-primary); color: var(--text-on-accent); }
.action-card__btn--refresh { background: var(--accent-primary); color: var(--text-on-accent); }
.action-card__btn--approve { background: var(--success); color: white; }
.action-card__btn--reject { background: var(--error); color: white; }
.action-card__btn--dismiss { background: transparent; color: var(--text-tertiary); border: 1px solid var(--border-subtle); }
.action-card__btn--ghost { background: transparent; color: var(--text-secondary); border: 1px solid var(--border-default); }
.action-card__btn--ingest { background: var(--info, var(--accent-primary)); color: white; }
.action-card__btn--confirm { background: var(--accent-primary); color: var(--text-on-accent); }
.action-card__btn--warning { background: var(--warning); color: white; }
.action-card__btn--disabled { opacity: 0.6; cursor: not-allowed; color: var(--accent-primary); background: var(--bg-secondary); }

.action-card__diagnosis {
  font-size: 10px;
  font-weight: var(--weight-medium);
  padding: 1px 6px;
  border-radius: var(--radius-full);
  border: 1px solid;
  white-space: nowrap;
}

.action-cards__pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) 0;
  margin-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
  flex-wrap: wrap;
  gap: var(--space-3);
}

.action-cards__pagination-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.action-cards__pagination-info {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.action-cards__page-size-select {
  font-size: var(--font-caption);
  padding: 2px 6px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  color: var(--text-primary);
  cursor: pointer;
}

.action-cards__pagination-right {
  display: flex;
  align-items: center;
  gap: 2px;
}

.action-cards__page-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  color: var(--text-secondary);
  cursor: pointer;
}

.action-cards__page-btn:disabled { opacity: 0.35; cursor: not-allowed; }
.action-cards__page-btn:not(:disabled):hover { border-color: var(--accent-primary); color: var(--accent-primary); }

.action-cards__page-num {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 28px;
  padding: 0 4px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  color: var(--text-secondary);
  font-size: var(--font-caption);
  cursor: pointer;
}

.action-cards__page-num:hover { border-color: var(--accent-primary); color: var(--accent-primary); }
.action-cards__page-num--active { background: var(--accent-primary); color: var(--text-on-accent); border-color: var(--accent-primary); }

.action-cards__page-ellipsis {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  color: var(--text-tertiary);
  font-size: var(--font-caption);
}

.action-cards__spin { animation: spin 1s linear infinite; }

@keyframes spin { to { transform: rotate(360deg); } }

.enrich-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(2px);
}

.enrich-dialog {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  width: 560px;
  max-width: 90vw;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
}

.enrich-dialog__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-3);
}

.enrich-dialog__title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-h3);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin: 0;
}

.enrich-dialog__close {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-1);
  background: none;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  border-radius: var(--radius-sm);
}

.enrich-dialog__close:hover { color: var(--text-primary); background: var(--bg-secondary); }

.enrich-dialog__desc {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin-bottom: var(--space-3);
  line-height: 1.5;
}

.enrich-dialog__textarea {
  flex: 1;
  min-height: 160px;
  padding: var(--space-3);
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: var(--font-body);
  resize: vertical;
  outline: none;
  line-height: 1.6;
  font-family: inherit;
}

.enrich-dialog__textarea:focus {
  border-color: var(--input-focus-border);
  box-shadow: 0 0 0 2px var(--accent-light);
}

.enrich-dialog__textarea::placeholder { color: var(--text-tertiary); }

.enrich-dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

.enrich-dialog__btn {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  border: none;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: opacity var(--transition-fast);
}

.enrich-dialog__btn:hover { opacity: 0.85; }
.enrich-dialog__btn:disabled { opacity: 0.5; cursor: not-allowed; }
.enrich-dialog__btn--cancel { background: transparent; color: var(--text-secondary); border: 1px solid var(--border-default); }
.enrich-dialog__btn--submit { background: var(--accent-primary); color: var(--text-on-accent); }

.action-card__compliance-info {
  padding: var(--space-2) var(--space-3);
  margin: var(--space-1) 0 var(--space-2) var(--space-6);
  background: var(--bg-tertiary);
  border-radius: var(--radius-md);
  font-size: var(--font-caption);
}
.compliance-row { display: flex; gap: var(--space-2); padding: 2px 0; }
.compliance-label { color: var(--text-tertiary); min-width: 60px; flex-shrink: 0; }
.compliance-value { color: var(--text-secondary); }
</style>