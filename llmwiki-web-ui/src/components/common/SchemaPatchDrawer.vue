<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  X, Check, Ban, EyeOff, RefreshCw, GitBranch, ChevronDown,
  FileText, Info, ChevronRight, TriangleAlert,
  PlusCircle, Pencil, Trash2, Eye,
  CheckCheck, Square, CheckSquare,
} from 'lucide-vue-next'
import {
  acceptPatch,
  ignorePatch,
  listObservingPatches,
  listPendingPatches,
  countPendingPatches,
  loadPatchDiff,
  rejectPatch,
  promotePatch,
  listConflictRulings,
  executeRuling,
  cancelRuling,
  batchAcceptPatches,
  batchRejectPatches,
  batchIgnorePatches,
  type SchemaPatchInfo,
  type ConflictReviewInfo,
} from '@/api/harness'
import { useToastStore } from '@/stores/toast'
import { useI18n } from 'vue-i18n'
import WikiPageRenderer from '@/components/wiki/WikiPageRenderer.vue'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'applied'): void }>()

const toast = useToastStore()
const { t } = useI18n()
const loading = ref(false)
const pendingPatches = ref<SchemaPatchInfo[]>([])
const observingPatches = ref<SchemaPatchInfo[]>([])
const busyId = ref<number | null>(null)
const currentVersion = ref(0)
const expandedEvidence = ref<Set<number>>(new Set())
const expandedDiff = ref<Set<number>>(new Set())
const showObserving = ref(false)
const conflictRulings = ref<ConflictReviewInfo[]>([])
const busyRulingId = ref<number | null>(null)
const selectedIds = ref<Set<number>>(new Set())
const batchBusy = ref(false)

type ViewMode = 'report' | 'detail'
const viewMode = ref<ViewMode>('report')

const nextVersionLabel = computed(() => `v${currentVersion.value + 1}`)

const stats = computed(() => {
  const pending = pendingPatches.value
  const aiApproved = pending.filter(p => p.gatekeeperDecision === 'APPROVE').length
  const avgConf = pending.length
    ? pending.reduce((s, p) => s + (p.confidence ?? 0), 0) / pending.length
    : 0
  return {
    totalPending: pending.length,
    aiApproved,
    observingCount: observingPatches.value.length,
    avgConfidence: avgConf,
    additions: pending.filter(p => p.operation === 'ADD').length,
    modifications: pending.filter(p => p.operation === 'MODIFY').length,
    deletions: pending.filter(p => p.operation === 'DELETE').length,
  }
})

const groupedPending = computed(() => {
  const map = new Map<string, SchemaPatchInfo[]>()
  for (const p of pendingPatches.value) {
    const k = p.sectionTitle
    if (!map.has(k)) map.set(k, [])
    map.get(k)!.push(p)
  }
  return Array.from(map.entries()).map(([section, items]) => ({ section, items }))
})

const findings = computed(() => {
  return pendingPatches.value.map(p => ({
    patch: p,
    risk: getRiskLevel(p),
    section: cleanSection(p.sectionTitle),
  }))
})

const highRiskFindings = computed(() => findings.value.filter(f => f.risk.level === 'high'))
const mediumRiskFindings = computed(() => findings.value.filter(f => f.risk.level === 'medium'))
const lowRiskFindings = computed(() => findings.value.filter(f => f.risk.level === 'low'))

const lowRiskIds = computed(() =>
  pendingPatches.value
    .filter(p => getRiskLevel(p).level === 'low' && p.gatekeeperDecision === 'APPROVE')
    .map(p => p.id)
)

const allSelected = computed(() =>
  pendingPatches.value.length > 0 && selectedIds.value.size === pendingPatches.value.length
)

const hasSelection = computed(() => selectedIds.value.size > 0)

function toggleSelect(id: number) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  selectedIds.value = next
}

function toggleSelectAll() {
  if (allSelected.value) {
    selectedIds.value = new Set()
  } else {
    selectedIds.value = new Set(pendingPatches.value.map(p => p.id))
  }
}

function selectLowRisk() {
  selectedIds.value = new Set(lowRiskIds.value)
}

async function onBatchAccept() {
  const ids = Array.from(selectedIds.value)
  if (!ids.length) return
  batchBusy.value = true
  try {
    const r = await batchAcceptPatches(ids)
    toast.success(t('schemaPatch.toastBatchAccept', [r.processed, r.failed ? t('schemaPatch.failedSuffix', [r.failed]) : '']))
    const acceptedSet = new Set(ids)
    pendingPatches.value = pendingPatches.value.filter(p => !acceptedSet.has(p.id))
    selectedIds.value = new Set()
    currentVersion.value += r.processed
    emit('applied')
  } catch (e: any) {
    toast.error(e?.message || t('schemaPatch.toastBatchAcceptFail'))
  } finally {
    batchBusy.value = false
  }
}

async function onBatchReject() {
  const ids = Array.from(selectedIds.value)
  if (!ids.length) return
  batchBusy.value = true
  try {
    const r = await batchRejectPatches(ids)
    toast.info(t('schemaPatch.toastBatchReject', [r.processed, r.failed ? t('schemaPatch.failedSuffix', [r.failed]) : '']))
    const rejectedSet = new Set(ids)
    pendingPatches.value = pendingPatches.value.filter(p => !rejectedSet.has(p.id))
    selectedIds.value = new Set()
  } catch (e: any) {
    toast.error(e?.message || t('schemaPatch.toastBatchRejectFail'))
  } finally {
    batchBusy.value = false
  }
}

async function onBatchIgnore() {
  const ids = Array.from(selectedIds.value)
  if (!ids.length) return
  batchBusy.value = true
  try {
    const r = await batchIgnorePatches(ids)
    toast.info(t('schemaPatch.toastBatchIgnore', [r.processed, r.failed ? t('schemaPatch.failedSuffix', [r.failed]) : '']))
    const ignoredSet = new Set(ids)
    pendingPatches.value = pendingPatches.value.filter(p => !ignoredSet.has(p.id))
    selectedIds.value = new Set()
  } catch (e: any) {
    toast.error(e?.message || t('schemaPatch.toastBatchIgnoreFail'))
  } finally {
    batchBusy.value = false
  }
}

async function onAcceptAllLowRisk() {
  const ids = lowRiskIds.value
  if (!ids.length) return
  batchBusy.value = true
  try {
    const r = await batchAcceptPatches(ids)
    toast.success(t('schemaPatch.toastAcceptAllLow', [r.processed, r.failed ? t('schemaPatch.failedSuffix', [r.failed]) : '']))
    const acceptedSet = new Set(ids)
    pendingPatches.value = pendingPatches.value.filter(p => !acceptedSet.has(p.id))
    selectedIds.value = new Set()
    currentVersion.value += r.processed
    emit('applied')
  } catch (e: any) {
    toast.error(e?.message || t('schemaPatch.toastBatchAcceptFail'))
  } finally {
    batchBusy.value = false
  }
}

const diffLoadingIds = ref<Set<number>>(new Set())

const hasPatches = computed(() => pendingPatches.value.length > 0)
const hasConflictRulings = computed(() => conflictRulings.value.length > 0)
const hasAnyItems = computed(() => hasPatches.value || hasConflictRulings.value || observingPatches.value.length > 0)

const actionSummary = computed(() => {
  const s = stats.value
  if (!s.totalPending) return t('schemaPatch.summaryNoPending')
  const parts: string[] = []
  if (s.aiApproved > 0) parts.push(t('schemaPatch.summaryAiApproved', [s.aiApproved]))
  if (s.deletions > 0) parts.push(t('schemaPatch.summaryDeletions', [s.deletions]))
  if (s.observingCount > 0) parts.push(t('schemaPatch.summaryObserving', [s.observingCount]))
  return parts.length ? parts.join(t('common.listSeparator')) + '。' : t('schemaPatch.summaryDefault')
})

async function load() {
  loading.value = true
  try {
    const [pendingList, observingList, countRes, rulingsList] = await Promise.all([
      listPendingPatches(),
      listObservingPatches().catch(() => [] as SchemaPatchInfo[]),
      countPendingPatches().catch(() => ({ pending: 0, observing: 0, conflictRulings: 0, currentVersion: 0 })),
      listConflictRulings().catch(() => [] as ConflictReviewInfo[]),
    ])
    pendingPatches.value = pendingList
    observingPatches.value = observingList
    conflictRulings.value = rulingsList
    currentVersion.value = countRes.currentVersion ?? 0
  } catch (e: any) {
    toast.error(e?.message || t('schemaPatch.toastLoadFail'))
  } finally {
    loading.value = false
  }
}

watch(() => props.open, (o) => {
  if (o) {
    viewMode.value = 'report'
    load()
  }
})

function removeFromBoth(id: number) {
  pendingPatches.value = pendingPatches.value.filter(x => x.id !== id)
  observingPatches.value = observingPatches.value.filter(x => x.id !== id)
}

async function onAccept(p: SchemaPatchInfo) {
  busyId.value = p.id
  try {
    await acceptPatch(p.id)
    toast.success(t('schemaPatch.toastAccept', [cleanSection(p.sectionTitle), opLabel(p.operation), nextVersionLabel.value]))
    currentVersion.value += 1
    removeFromBoth(p.id)
    emit('applied')
  } catch (e: any) {
    toast.error(e?.message || t('schemaPatch.toastApplyFail'))
  } finally {
    busyId.value = null
  }
}

async function onReject(p: SchemaPatchInfo) {
  busyId.value = p.id
  try {
    await rejectPatch(p.id)
    removeFromBoth(p.id)
    toast.info(t('schemaPatch.toastReject'))
  } catch (e: any) {
    toast.error(e?.message || t('schemaPatch.toastOpFail'))
  } finally {
    busyId.value = null
  }
}

async function onIgnore(p: SchemaPatchInfo) {
  busyId.value = p.id
  try {
    await ignorePatch(p.id)
    removeFromBoth(p.id)
    toast.info(t('schemaPatch.toastIgnore'))
  } catch (e: any) {
    toast.error(e?.message || t('schemaPatch.toastOpFail'))
  } finally {
    busyId.value = null
  }
}

async function onPromote(p: SchemaPatchInfo) {
  busyId.value = p.id
  try {
    await promotePatch(p.id)
    observingPatches.value = observingPatches.value.filter(x => x.id !== p.id)
    pendingPatches.value = [...pendingPatches.value, { ...p, status: 'PENDING' }]
    toast.success(t('schemaPatch.toastPromote'))
  } catch (e: any) {
    toast.error(e?.message || t('schemaPatch.toastPromoteFail'))
  } finally {
    busyId.value = null
  }
}

async function onExecuteRuling(ruling: ConflictReviewInfo, action: string) {
  busyRulingId.value = ruling.id
  try {
    const result = await executeRuling(ruling.id, action)
    if (result.status === 'failed') {
      throw new Error(result.executionError || t('schemaPatch.toastRulingExecFail'))
    }
    const actionLabels: Record<string, string> = {
      merge: t('schemaPatch.rulingMerge'),
      coexist: t('schemaPatch.rulingCoexist'),
      choose_a: t('schemaPatch.rulingKeepA', [ruling.fromPageTitle]),
      choose_b: t('schemaPatch.rulingKeepB', [ruling.toPageTitle]),
    }
    toast.success(t('schemaPatch.toastRulingExec', [actionLabels[action] || action]))
    conflictRulings.value = conflictRulings.value.filter(r => r.id !== ruling.id)
  } catch (e: any) {
    toast.error(e?.message || t('schemaPatch.toastRulingExecFail'))
  } finally {
    busyRulingId.value = null
  }
}

async function onCancelRuling(ruling: ConflictReviewInfo) {
  busyRulingId.value = ruling.id
  try {
    await cancelRuling(ruling.id)
    toast.info(t('schemaPatch.toastRulingCancel'))
    conflictRulings.value = conflictRulings.value.filter(r => r.id !== ruling.id)
  } catch (e: any) {
    toast.error(e?.message || t('schemaPatch.toastOpFail'))
  } finally {
    busyRulingId.value = null
  }
}

function cleanSection(title: string): string {
  return title.replace(/^##\s*\d+\.\s*/, '').trim()
}

function opLabel(op: string): string {
  if (op === 'ADD') return t('schemaPatch.opAdd')
  if (op === 'MODIFY') return t('schemaPatch.opModify')
  if (op === 'DELETE') return t('schemaPatch.opDelete')
  return op
}

function sourceLabel(p: SchemaPatchInfo): string {
  if (p.sourceType === 'INGEST') return t('schemaPatch.srcIngest')
  if (p.sourceType === 'QUERY') return t('schemaPatch.srcQuery')
  if (p.sourceType === 'LINT') return t('schemaPatch.srcLint')
  if (p.sourceType === 'MANUAL') return t('schemaPatch.srcManual')
  return t('schemaPatch.srcUnknown')
}

function stripGatekeeperTag(text: string): string {
  return text.replace(/^\[Gatekeeper\/\w+\]\s*[^\n]*\n?/, '').trim()
}

function cleanRationale(text: string | null | undefined): string {
  if (!text) return t('schemaPatch.noRationale')
  return stripGatekeeperTag(text) || text
}

function patchNarrative(p: SchemaPatchInfo): string {
  if (p.rationale) return cleanRationale(p.rationale)
  const section = cleanSection(p.sectionTitle)
  if (p.operation === 'ADD') return t('schemaPatch.narrativeAdd', [section])
  if (p.operation === 'MODIFY') return t('schemaPatch.narrativeModify', [section])
  if (p.operation === 'DELETE') return t('schemaPatch.narrativeDelete', [section])
  return t('schemaPatch.narrativeDefault', [section])
}

function parseEvidence(json: string | null): string[] {
  if (!json) return []
  try {
    const arr = JSON.parse(json)
    if (!Array.isArray(arr)) return []
    return arr.map((v: any) => typeof v === 'string' ? v : JSON.stringify(v)).slice(0, 5)
  } catch {
    return []
  }
}

function toggleEvidence(id: number) {
  const s = new Set(expandedEvidence.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  expandedEvidence.value = s
}

async function toggleDiff(id: number) {
  const s = new Set(expandedDiff.value)
  if (s.has(id)) {
    s.delete(id)
    expandedDiff.value = s
    return
  }
  const patch = [...pendingPatches.value, ...observingPatches.value].find(p => p.id === id)
  if (patch && patch.diffBefore == null && patch.diffAfter == null) {
    const loading = new Set(diffLoadingIds.value)
    loading.add(id)
    diffLoadingIds.value = loading
    try {
      const full = await loadPatchDiff(id)
      patch.diffBefore = full.diffBefore
      patch.diffAfter = full.diffAfter
    } catch (e: any) {
      toast.error(e?.message || t('schemaPatch.toastDiffLoadFail'))
    } finally {
      const done = new Set(diffLoadingIds.value)
      done.delete(id)
      diffLoadingIds.value = done
    }
  }
  s.add(id)
  expandedDiff.value = s
}

function getRiskLevel(p: SchemaPatchInfo) {
  if (p.operation === 'DELETE') {
    return { level: 'high' as const, label: t('schemaPatch.riskHigh'), reason: t('schemaPatch.reasonDelete') }
  }
  if (p.operation === 'MODIFY') {
    if ((p.confidence ?? 0) < 0.6) return { level: 'medium' as const, label: t('schemaPatch.riskMedium'), reason: t('schemaPatch.reasonModifyLow') }
    return { level: 'low' as const, label: t('schemaPatch.riskLow'), reason: t('schemaPatch.reasonModifyHigh') }
  }
  if ((p.confidence ?? 0) < 0.5) return { level: 'medium' as const, label: t('schemaPatch.riskMedium'), reason: t('schemaPatch.reasonAddLow') }
  return { level: 'low' as const, label: t('schemaPatch.riskLow'), reason: t('schemaPatch.reasonAddHigh') }
}

function riskClass(level: string) {
  return `rpt-risk rpt-risk--${level}`
}

function confidenceText(c: number | null): string {
  if (c === null || c === undefined) return t('schemaPatch.confidenceUnrated')
  const pct = Math.round(c * 100)
  if (pct >= 70) return t('schemaPatch.confidenceHigh', [pct])
  if (pct >= 40) return t('schemaPatch.confidenceMedium', [pct])
  return t('schemaPatch.confidenceLow', [pct])
}

function gatekeeperText(d: string | null): string {
  if (d === 'APPROVE') return t('schemaPatch.gkApprove')
  if (d === 'OBSERVE') return t('schemaPatch.gkObserve')
  if (d === 'REJECT') return t('schemaPatch.gkReject')
  return ''
}
</script>

<template>
  <Teleport to="body">
    <transition name="patch-drawer">
      <div v-if="open" class="patch-mask" @click.self="emit('close')">
        <aside class="patch-drawer" role="dialog" aria-modal="true">

          <header class="patch-header">
            <div>
              <h2 class="patch-header__title">{{ t('schemaPatch.title') }}</h2>
              <p class="patch-header__sub">{{ t('schemaPatch.subtitle') }}</p>
            </div>
            <div class="patch-header-actions">
              <button class="patch-icon-btn" @click="load" :disabled="loading" :title="t('schemaPatch.refresh')">
                <RefreshCw :size="16" :class="{ 'patch-spin': loading }" />
              </button>
              <button class="patch-icon-btn" @click="emit('close')" :title="t('common.close')">
                <X :size="18" />
              </button>
            </div>
          </header>

          <div v-if="hasAnyItems" class="patch-view-tabs">
            <button
              class="patch-view-tab"
              :class="{ 'patch-view-tab--active': viewMode === 'report' }"
              @click="viewMode = 'report'"
            >{{ t('schemaPatch.tabReport') }}</button>
            <button
              class="patch-view-tab"
              :class="{ 'patch-view-tab--active': viewMode === 'detail' }"
              @click="viewMode = 'detail'"
            >{{ t('schemaPatch.tabDetail') }}</button>
          </div>

          <section class="patch-body">
            <div v-if="loading && !pendingPatches.length && !observingPatches.length && !conflictRulings.length" class="patch-empty">
              <div class="patch-empty__text">{{ t('schemaPatch.loading') }}</div>
            </div>

            <div v-else-if="!hasAnyItems" class="patch-empty">
              <div class="patch-empty__icon">
                <Check :size="28" />
              </div>
              <div class="patch-empty__text">{{ t('schemaPatch.noItems') }}</div>
              <div class="patch-empty__hint">
                {{ t('schemaPatch.noItemsDesc') }}
              </div>
            </div>

            <template v-else>

              <!-- ========== REPORT VIEW ========== -->
              <div v-if="viewMode === 'report'" class="rpt">

                <div v-if="hasPatches" class="rpt-version">
                  <GitBranch :size="14" />
                  <span>{{ t('schemaPatch.currentVersion') }} <strong>v{{ currentVersion }}</strong>{{ t('common.commaSeparator') }}{{ t('schemaPatch.nextVersion') }} <strong>{{ nextVersionLabel }}</strong></span>
                </div>

                <!-- Stats -->
                <div v-if="hasPatches" class="rpt-stats">
                  <div class="rpt-stat">
                    <span class="rpt-stat__value">{{ stats.totalPending }}</span>
                    <span class="rpt-stat__label">{{ t('schemaPatch.statPending') }}</span>
                  </div>
                  <div class="rpt-stat">
                    <span class="rpt-stat__value rpt-stat__value--success">{{ stats.aiApproved }}</span>
                    <span class="rpt-stat__label">{{ t('schemaPatch.statAiApproved') }}</span>
                  </div>
                  <div class="rpt-stat">
                    <span class="rpt-stat__value">{{ Math.round(stats.avgConfidence * 100) }}%</span>
                    <span class="rpt-stat__label">{{ t('schemaPatch.statAvgConfidence') }}</span>
                  </div>
                  <div class="rpt-stat">
                    <span class="rpt-stat__value rpt-stat__value--muted">{{ stats.observingCount }}</span>
                    <span class="rpt-stat__label">{{ t('schemaPatch.statObserving') }}</span>
                  </div>
                </div>

                <!-- Section: 发现了什么 -->
                <div v-if="hasPatches" class="rpt-section">
                  <h3 class="rpt-section__title">
                    <FileText :size="16" />
                    {{ t('schemaPatch.secFindings') }}
                  </h3>
                  <p class="rpt-section__body">
                    {{ t('schemaPatch.findingsDesc', [stats.totalPending]) }}
                    <span v-if="stats.additions">{{ t('schemaPatch.findingsAdd', [stats.additions]) }}</span>
                    <span v-if="stats.additions && stats.modifications">{{ t('common.listSeparator') }}</span>
                    <span v-if="stats.modifications">{{ t('schemaPatch.findingsModify', [stats.modifications]) }}</span>
                    <span v-if="(stats.additions || stats.modifications) && stats.deletions">{{ t('common.listSeparator') }}</span>
                    <span v-if="stats.deletions">{{ t('schemaPatch.findingsDelete', [stats.deletions]) }}</span>
                    {{ t('schemaPatch.findingsSections') }}
                  </p>
                  <ul class="rpt-section-list">
                    <li v-for="g in groupedPending" :key="g.section" class="rpt-section-list__item">
                      <span class="rpt-section-list__dot"></span>
                      <strong>{{ cleanSection(g.section) }}</strong>
                      <span class="rpt-section-list__count">{{ t('schemaPatch.suggestionCount', [g.items.length]) }}</span>
                    </li>
                  </ul>
                </div>

                <!-- Section: 风险评估 -->
                <div v-if="hasPatches" class="rpt-section">
                  <h3 class="rpt-section__title">
                    <TriangleAlert :size="16" />
                    {{ t('schemaPatch.secRisk') }}
                  </h3>
                  <div class="rpt-risk-summary">
                    <div v-if="highRiskFindings.length" class="rpt-risk-row">
                      <span class="rpt-risk rpt-risk--high">{{ t('schemaPatch.riskHigh') }}</span>
                      <span>{{ t('schemaPatch.riskHighDesc', [highRiskFindings.length]) }}</span>
                    </div>
                    <div v-if="mediumRiskFindings.length" class="rpt-risk-row">
                      <span class="rpt-risk rpt-risk--medium">{{ t('schemaPatch.riskMedium') }}</span>
                      <span>{{ t('schemaPatch.riskMediumDesc', [mediumRiskFindings.length]) }}</span>
                    </div>
                    <div v-if="lowRiskFindings.length" class="rpt-risk-row">
                      <span class="rpt-risk rpt-risk--low">{{ t('schemaPatch.riskLow') }}</span>
                      <span>{{ t('schemaPatch.riskLowDesc', [lowRiskFindings.length]) }}</span>
                    </div>
                  </div>
                </div>

                <!-- Section: 建议内容 -->
                <div v-if="hasPatches" class="rpt-section">
                  <h3 class="rpt-section__title">
                    <Info :size="16" />
                    {{ t('schemaPatch.secContent') }}
                  </h3>
                  <div class="rpt-findings">
                    <div v-for="f in findings" :key="f.patch.id" class="rpt-finding">
                      <div class="rpt-finding__head">
                        <span :class="riskClass(f.risk.level)">{{ f.risk.label }}</span>
                        <span class="rpt-finding__op">
                          <component :is="f.patch.operation === 'ADD' ? PlusCircle : f.patch.operation === 'MODIFY' ? Pencil : Trash2" :size="13" />
                          {{ opLabel(f.patch.operation) }}「{{ f.section }}」
                        </span>
                      </div>
                      <p class="rpt-finding__desc">{{ patchNarrative(f.patch) }}</p>
                      <p class="rpt-finding__meta">
                        {{ sourceLabel(f.patch) }}
                        <span v-if="f.patch.gatekeeperDecision"> · {{ gatekeeperText(f.patch.gatekeeperDecision) }}</span>
                        <span v-if="f.patch.gatekeeperReason"> · {{ f.patch.gatekeeperReason }}</span>
                      </p>
                    </div>
                  </div>
                </div>

                <!-- Section: 冲突裁决 -->
                <div v-if="hasConflictRulings" class="rpt-section rpt-section--conflict">
                  <h3 class="rpt-section__title">
                    <TriangleAlert :size="16" />
                    {{ t('schemaPatch.conflictTitle', [conflictRulings.length]) }}
                  </h3>
                  <p class="rpt-section__body">
                    {{ t('schemaPatch.conflictDesc') }}
                  </p>
                  <div class="rpt-findings">
                    <div v-for="r in conflictRulings" :key="r.id" class="rpt-finding rpt-finding--conflict">
                      <div class="rpt-finding__head">
                        <span class="rpt-risk rpt-risk--medium">{{ t('schemaPatch.conflictBadge') }}</span>
                        <span class="rpt-finding__op">
                          「{{ r.fromPageTitle }}」↔「{{ r.toPageTitle }}」
                        </span>
                      </div>
                      <p class="rpt-finding__desc">
                        {{ t('schemaPatch.conflictTypeLabel') }}{{ r.conflictType || t('schemaPatch.conflictTypeUnknown') }}
                        <span v-if="r.strategyLabel"> · {{ t('schemaPatch.conflictStrategyLabel') }}{{ r.strategyLabel }}</span>
                      </p>
                      <p class="rpt-finding__meta">{{ r.routeReason }}</p>
                    </div>
                  </div>
                </div>

                <!-- Section: 你需要做什么 -->
                <div v-if="hasPatches || hasConflictRulings" class="rpt-section rpt-section--action">
                  <h3 class="rpt-section__title">
                    <ChevronRight :size="16" />
                    {{ t('schemaPatch.secAction') }}
                  </h3>
                  <p class="rpt-section__body">
                    <span v-if="hasPatches">{{ actionSummary }}</span>
                    <span v-if="hasPatches && hasConflictRulings"><br></span>
                    <span v-if="hasConflictRulings">{{ t('schemaPatch.actionConflict', [conflictRulings.length]) }}</span>
                  </p>
                  <div class="rpt-action-bar">
                    <button v-if="hasPatches && lowRiskIds.length" class="rpt-action-btn rpt-action-btn--safe" :disabled="batchBusy" @click="onAcceptAllLowRisk">
                      <CheckCheck :size="14" />
                      {{ t('schemaPatch.btnAcceptAllLow', [lowRiskIds.length]) }}
                    </button>
                    <button v-if="hasPatches" class="rpt-action-btn rpt-action-btn--primary" @click="viewMode = 'detail'">
                      <Eye :size="14" />
                      {{ t('schemaPatch.btnReviewDetail') }}
                    </button>
                    <button v-if="hasConflictRulings && !hasPatches" class="rpt-action-btn rpt-action-btn--primary" @click="viewMode = 'detail'">
                      <Eye :size="14" />
                      {{ t('schemaPatch.btnReviewConflict') }}
                    </button>
                  </div>
                </div>

                <!-- Observing patches summary -->
                <div v-if="observingPatches.length" class="rpt-observing">
                  <button class="rpt-observing__toggle" @click="showObserving = !showObserving">
                    <ChevronDown :size="14" :class="{ 'rpt-observing__chevron--open': showObserving }" />
                    {{ t('schemaPatch.observingCount', [observingPatches.length]) }}
                  </button>
                  <div v-if="showObserving" class="rpt-observing__list">
                    <div v-for="p in observingPatches" :key="p.id" class="rpt-observing__item">
                      <span class="rpt-observing__op">{{ opLabel(p.operation) }}</span>
                      <span class="rpt-observing__section">{{ cleanSection(p.sectionTitle) }}</span>
                      <span class="rpt-observing__rationale">{{ cleanRationale(p.rationale) }}</span>
                      <span class="rpt-observing__conf">{{ t('schemaPatch.confidenceLabel') }} {{ confidenceText(p.confidence) }}</span>
                    </div>
                  </div>
                </div>
              </div>

              <!-- ========== DETAIL VIEW ========== -->
              <div v-if="viewMode === 'detail'">

                <div v-if="hasPatches || observingPatches.length" class="patch-version-banner">
                  <GitBranch :size="14" />
                  <span>{{ t('schemaPatch.currentVersion') }} <strong>v{{ currentVersion }}</strong> · {{ t('schemaPatch.nextVersion') }} <strong>{{ nextVersionLabel }}</strong></span>
                </div>

                <!-- 批量操作栏 -->
                <div v-if="hasPatches" class="patch-batch-bar">
                  <button class="patch-batch-bar__select-all" @click="toggleSelectAll">
                    <component :is="allSelected ? CheckSquare : Square" :size="15" />
                    {{ allSelected ? t('schemaPatch.deselectAll') : t('schemaPatch.selectAll') }}
                  </button>
                  <span v-if="hasSelection" class="patch-batch-bar__count">{{ t('schemaPatch.selected', [selectedIds.size]) }}</span>
                  <template v-if="hasSelection">
                    <button class="patch-btn patch-btn--ghost" :disabled="batchBusy" @click="onBatchIgnore">
                      <EyeOff :size="14" /> {{ t('schemaPatch.batchIgnore') }}
                    </button>
                    <button class="patch-btn patch-btn--ghost" :disabled="batchBusy" @click="onBatchReject">
                      <Ban :size="14" /> {{ t('schemaPatch.batchReject') }}
                    </button>
                    <button class="patch-btn patch-btn--primary" :disabled="batchBusy" @click="onBatchAccept">
                      <Check :size="14" /> {{ t('schemaPatch.batchAccept') }}
                    </button>
                  </template>
                  <template v-else-if="lowRiskIds.length">
                    <button class="patch-btn patch-btn--ghost" @click="selectLowRisk">
                      <CheckCheck :size="14" /> {{ t('schemaPatch.selectLowRisk', [lowRiskIds.length]) }}
                    </button>
                  </template>
                </div>

                <div v-for="g in groupedPending" :key="g.section" class="patch-group">
                  <h3 class="patch-group__title">{{ cleanSection(g.section) }}</h3>
                  <article
                    v-for="p in g.items"
                    :key="p.id"
                    class="patch-card"
                    :class="{ 'patch-card--busy': busyId === p.id, 'patch-card--selected': selectedIds.has(p.id) }"
                  >
                    <div class="patch-card__select" @click="toggleSelect(p.id)">
                      <component :is="selectedIds.has(p.id) ? CheckSquare : Square" :size="16" />
                    </div>
                    <div class="patch-card__finding-title">
                      {{ patchNarrative(p) }}
                    </div>

                    <div class="patch-card__head">
                      <span :class="riskClass(getRiskLevel(p).level)" :title="getRiskLevel(p).reason">
                        {{ getRiskLevel(p).label }}
                      </span>
                      <span class="patch-card__op-tag" :class="`patch-card__op--${p.operation.toLowerCase()}`">
                        {{ opLabel(p.operation) }}
                      </span>
                      <span class="patch-card__conf" :title="t('schemaPatch.confidenceTitle')">
                        {{ t('schemaPatch.confidenceLabel') }} {{ confidenceText(p.confidence) }}
                      </span>
                      <span v-if="p.gatekeeperDecision" class="patch-card__gk" :title="p.gatekeeperReason || ''">
                        {{ gatekeeperText(p.gatekeeperDecision) }}
                      </span>
                    </div>

                    <div v-if="p.gatekeeperReason" class="patch-card__gk-reason">
                      {{ t('schemaPatch.gkReason') }}{{ p.gatekeeperReason }}
                    </div>

                    <div class="patch-card__source-info">
                      {{ sourceLabel(p) }}
                    </div>

                    <div v-if="parseEvidence(p.evidenceJson).length" class="patch-evidence">
                      <button class="patch-evidence__toggle" @click="toggleEvidence(p.id)">
                        <ChevronDown :size="13" :class="{ 'patch-evidence__chevron--open': expandedEvidence.has(p.id) }" />
                        {{ t('schemaPatch.viewEvidence', [parseEvidence(p.evidenceJson).length]) }}
                      </button>
                      <ul v-show="expandedEvidence.has(p.id)" class="patch-evidence__list">
                        <li v-for="(ev, i) in parseEvidence(p.evidenceJson)" :key="i">{{ ev }}</li>
                      </ul>
                    </div>

                    <div v-if="p.operation !== 'ADD' || p.diffBefore || p.diffAfter" class="patch-diff-toggle">
                      <button class="patch-evidence__toggle" :disabled="diffLoadingIds.has(p.id)" @click="toggleDiff(p.id)">
                        <RefreshCw v-if="diffLoadingIds.has(p.id)" :size="13" class="spin-icon" />
                        <ChevronDown v-else :size="13" :class="{ 'patch-evidence__chevron--open': expandedDiff.has(p.id) }" />
                        {{ diffLoadingIds.has(p.id) ? t('schemaPatch.diffLoading') : t('schemaPatch.viewDiff') }}
                      </button>
                    </div>
                    <template v-if="expandedDiff.has(p.id)">
                      <div v-if="p.operation !== 'ADD' && p.diffBefore" class="patch-diff patch-diff--before">
                        <div class="patch-diff__label">{{ t('schemaPatch.diffBefore') }}</div>
                        <div class="patch-diff__md">
                          <WikiPageRenderer :content="p.diffBefore" />
                        </div>
                      </div>
                      <div v-if="p.operation !== 'DELETE' && p.diffAfter" class="patch-diff patch-diff--after">
                        <div class="patch-diff__label">{{ t('schemaPatch.diffAfter') }}</div>
                        <div class="patch-diff__md">
                          <WikiPageRenderer :content="p.diffAfter" />
                        </div>
                      </div>
                    </template>

                    <div class="patch-card__actions">
                      <button class="patch-btn patch-btn--ghost" :disabled="busyId === p.id" @click="onIgnore(p)">
                        <EyeOff :size="14" /> {{ t('schemaPatch.btnIgnore') }}
                      </button>
                      <button class="patch-btn patch-btn--ghost" :disabled="busyId === p.id" @click="onReject(p)">
                        <Ban :size="14" /> {{ t('schemaPatch.btnReject') }}
                      </button>
                      <button class="patch-btn patch-btn--primary" :disabled="busyId === p.id" @click="onAccept(p)">
                        <Check :size="14" /> {{ t('schemaPatch.btnAccept') }}
                      </button>
                    </div>
                  </article>
                </div>

                <!-- Observing patches cards (detail view) -->
                <div v-if="observingPatches.length" class="patch-group patch-group--observing">
                  <h3 class="patch-group__title">{{ t('schemaPatch.observingTitle') }}</h3>
                  <article
                    v-for="p in observingPatches"
                    :key="p.id"
                    class="patch-card patch-card--observing"
                    :class="{ 'patch-card--busy': busyId === p.id }"
                  >
                    <div class="patch-card__finding-title">
                      {{ patchNarrative(p) }}
                    </div>

                    <div class="patch-card__head">
                      <span class="rpt-risk rpt-risk--low">{{ t('schemaPatch.observingBadge') }}</span>
                      <span class="patch-card__op-tag" :class="`patch-card__op--${p.operation.toLowerCase()}`">
                        {{ opLabel(p.operation) }}
                      </span>
                      <span class="patch-card__conf" :title="t('schemaPatch.confidenceTitle')">
                        {{ t('schemaPatch.confidenceLabel') }} {{ confidenceText(p.confidence) }}
                      </span>
                      <span v-if="p.gatekeeperDecision" class="patch-card__gk" :title="p.gatekeeperReason || ''">
                        {{ gatekeeperText(p.gatekeeperDecision) }}
                      </span>
                    </div>

                    <div v-if="p.gatekeeperReason" class="patch-card__gk-reason">
                      {{ t('schemaPatch.observingReason') }}{{ p.gatekeeperReason }}
                    </div>

                    <div class="patch-card__source-info">
                      {{ sourceLabel(p) }}
                    </div>

                    <div v-if="parseEvidence(p.evidenceJson).length" class="patch-evidence">
                      <button class="patch-evidence__toggle" @click="toggleEvidence(p.id)">
                        <ChevronDown :size="13" :class="{ 'patch-evidence__chevron--open': expandedEvidence.has(p.id) }" />
                        {{ t('schemaPatch.viewEvidence', [parseEvidence(p.evidenceJson).length]) }}
                      </button>
                      <ul v-show="expandedEvidence.has(p.id)" class="patch-evidence__list">
                        <li v-for="(ev, i) in parseEvidence(p.evidenceJson)" :key="i">{{ ev }}</li>
                      </ul>
                    </div>

                    <div v-if="p.operation !== 'ADD' || p.diffBefore || p.diffAfter" class="patch-diff-toggle">
                      <button class="patch-evidence__toggle" :disabled="diffLoadingIds.has(p.id)" @click="toggleDiff(p.id)">
                        <RefreshCw v-if="diffLoadingIds.has(p.id)" :size="13" class="spin-icon" />
                        <ChevronDown v-else :size="13" :class="{ 'patch-evidence__chevron--open': expandedDiff.has(p.id) }" />
                        {{ diffLoadingIds.has(p.id) ? t('schemaPatch.diffLoading') : t('schemaPatch.viewDiff') }}
                      </button>
                    </div>
                    <template v-if="expandedDiff.has(p.id)">
                      <div v-if="p.operation !== 'ADD' && p.diffBefore" class="patch-diff patch-diff--before">
                        <div class="patch-diff__label">{{ t('schemaPatch.diffBefore') }}</div>
                        <div class="patch-diff__md">
                          <WikiPageRenderer :content="p.diffBefore" />
                        </div>
                      </div>
                      <div v-if="p.operation !== 'DELETE' && p.diffAfter" class="patch-diff patch-diff--after">
                        <div class="patch-diff__label">{{ t('schemaPatch.diffAfter') }}</div>
                        <div class="patch-diff__md">
                          <WikiPageRenderer :content="p.diffAfter" />
                        </div>
                      </div>
                    </template>

                    <div class="patch-card__actions">
                      <button class="patch-btn patch-btn--ghost" :disabled="busyId === p.id" @click="onIgnore(p)">
                        <EyeOff :size="14" /> {{ t('schemaPatch.btnIgnore') }}
                      </button>
                      <button class="patch-btn patch-btn--ghost" :disabled="busyId === p.id" @click="onReject(p)">
                        <Ban :size="14" /> {{ t('schemaPatch.btnReject') }}
                      </button>
                      <button class="patch-btn" :disabled="busyId === p.id" @click="onPromote(p)">
                        <GitBranch :size="14" /> {{ t('schemaPatch.btnPromote') }}
                      </button>
                      <button class="patch-btn patch-btn--primary" :disabled="busyId === p.id" @click="onAccept(p)">
                        <Check :size="14" /> {{ t('schemaPatch.btnAcceptDirect') }}
                      </button>
                    </div>
                  </article>
                </div>

                <!-- Conflict rulings cards -->
                <div v-if="hasConflictRulings" class="patch-group patch-group--conflict">
                  <h3 class="patch-group__title">{{ t('schemaPatch.secConflict') }}</h3>
                  <article
                    v-for="r in conflictRulings"
                    :key="r.id"
                    class="patch-card patch-card--conflict"
                    :class="{ 'patch-card--busy': busyRulingId === r.id }"
                  >
                    <div class="patch-card__finding-title">
                      {{ t('schemaPatch.conflictBetween', [r.fromPageTitle, r.toPageTitle]) }}
                    </div>

                    <div class="patch-card__head">
                      <span class="rpt-risk rpt-risk--medium">{{ t('schemaPatch.conflictBadge') }}</span>
                      <span class="patch-card__op-tag patch-card__op--conflict">
                        {{ r.conflictType || t('schemaPatch.conflictTypeUnknown') }}
                      </span>
                      <span v-if="r.strategyLabel" class="patch-card__conf">
                        {{ t('schemaPatch.conflictStrategyLabel') }}{{ r.strategyLabel }}
                      </span>
                    </div>

                    <div v-if="r.routeReason" class="patch-card__gk-reason">
                      {{ t('schemaPatch.conflictRouteLabel') }}{{ r.routeReason }}
                    </div>

                    <div class="patch-card__source-info">
                      {{ r.sourceType === 'INGEST' ? t('schemaPatch.srcIngest') : t('schemaPatch.conflictSourceOther') + r.sourceType }}
                    </div>

                    <div class="patch-card__actions patch-card__actions--conflict">
                      <button class="patch-btn patch-btn--ghost" :disabled="busyRulingId === r.id" @click="onCancelRuling(r)">
                        <Ban :size="14" /> {{ t('schemaPatch.btnDismiss') }}
                      </button>
                      <button class="patch-btn" :disabled="busyRulingId === r.id" @click="onExecuteRuling(r, 'choose_a')">
                        {{ t('schemaPatch.btnKeepA') }}
                      </button>
                      <button class="patch-btn" :disabled="busyRulingId === r.id" @click="onExecuteRuling(r, 'coexist')">
                        {{ t('schemaPatch.btnCoexist') }}
                      </button>
                      <button class="patch-btn" :disabled="busyRulingId === r.id" @click="onExecuteRuling(r, 'choose_b')">
                        {{ t('schemaPatch.btnKeepB') }}
                      </button>
                      <button class="patch-btn patch-btn--primary" :disabled="busyRulingId === r.id" @click="onExecuteRuling(r, 'merge')">
                        <Check :size="14" /> {{ t('schemaPatch.btnMerge') }}
                      </button>
                    </div>
                  </article>
                </div>
              </div>
            </template>
          </section>
        </aside>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.patch-mask {
  position: fixed;
  inset: 0;
  background: rgba(11, 14, 22, 0.55);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  z-index: 8500;
  display: flex;
  justify-content: flex-end;
}
.patch-drawer {
  width: min(560px, 100%);
  background: var(--surface-elevated);
  border-left: 1px solid var(--border-default);
  border-radius: var(--radius-lg) 0 0 var(--radius-lg);
  box-shadow: var(--shadow-xl), -4px 0 16px rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

.patch-header {
  padding: var(--space-5) var(--space-5) var(--space-4);
  border-bottom: 1px solid var(--border-default);
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-3);
}
.patch-header__title {
  margin: 0 0 4px;
  font-size: var(--font-h3);
  color: var(--text-primary);
  font-family: var(--font-heading);
}
.patch-header__sub {
  margin: 0;
  font-size: var(--font-caption);
  color: var(--text-secondary);
  line-height: 1.6;
}
.patch-header-actions { display: flex; gap: var(--space-1); }
.patch-icon-btn {
  background: transparent;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  padding: 7px;
  min-width: 32px;
  min-height: 32px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background var(--transition-fast), color var(--transition-fast), border-color var(--transition-fast);
}
.patch-icon-btn:hover:not(:disabled) {
  color: var(--text-primary);
  border-color: var(--border-default);
  background: var(--bg-tertiary);
}
.patch-icon-btn:disabled { opacity: 0.38; cursor: not-allowed; }
.patch-spin { animation: patch-spin 1s linear infinite; }
@keyframes patch-spin { to { transform: rotate(360deg); } }

.patch-view-tabs {
  display: flex;
  gap: var(--space-1);
  padding: 0 var(--space-5);
  border-bottom: 1px solid var(--border-default);
}
.patch-view-tab {
  padding: var(--space-2) var(--space-3);
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: color var(--transition-fast), border-color var(--transition-fast);
}
.patch-view-tab:hover { color: var(--text-primary); }
.patch-view-tab--active {
  color: var(--accent-primary);
  border-bottom-color: var(--accent-primary);
  font-weight: 600;
}

.patch-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-4) var(--space-5);
}

.patch-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-10) var(--space-4);
  color: var(--text-secondary);
  text-align: center;
  gap: var(--space-2);
}
.patch-empty__icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--success-light);
  color: var(--success);
  margin-bottom: var(--space-2);
}
.patch-empty__text { font-size: var(--font-body); color: var(--text-primary); }
.patch-empty__hint { font-size: var(--font-caption); line-height: 1.6; max-width: 380px; }

/* ===== REPORT STYLES ===== */
.rpt { display: flex; flex-direction: column; gap: var(--space-4); }

.rpt-version {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-lg);
  background: var(--accent-light);
  color: var(--text-primary);
  font-size: var(--font-caption);
  line-height: 1.5;
  border: 1px solid var(--border-subtle);
}
.rpt-version strong { color: var(--accent-primary); font-weight: 600; }

.rpt-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-2);
}
.rpt-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: var(--space-3) var(--space-2);
  border-radius: var(--radius-lg);
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
}
.rpt-stat__value {
  font-size: var(--font-h3);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  line-height: 1;
}
.rpt-stat__value--success { color: var(--success); }
.rpt-stat__value--muted { color: var(--text-tertiary); }
.rpt-stat__label {
  font-size: 11px;
  color: var(--text-secondary);
  text-align: center;
}

.rpt-section {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-4);
}
.rpt-section--action {
  border-color: var(--accent-primary);
  background: linear-gradient(135deg, var(--accent-light), var(--surface-card));
}
.rpt-section__title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0 0 var(--space-3);
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}
.rpt-section__body {
  margin: 0 0 var(--space-3);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.7;
}
.rpt-section__body strong { color: var(--text-primary); }

.rpt-section-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.rpt-section-list__item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) 0;
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}
.rpt-section-list__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-primary);
  flex-shrink: 0;
}
.rpt-section-list__count {
  margin-left: auto;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.rpt-risk-summary {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.rpt-risk-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.6;
}
.rpt-risk {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: var(--radius-sm);
  font-weight: 500;
  white-space: nowrap;
  flex-shrink: 0;
  margin-top: 2px;
}
.rpt-risk--high { background: rgba(239, 68, 68, 0.12); color: var(--error); }
.rpt-risk--medium { background: rgba(245, 158, 11, 0.12); color: #b37c09; }
.rpt-risk--low { background: rgba(16, 185, 129, 0.12); color: var(--success); }

.rpt-findings { display: flex; flex-direction: column; gap: var(--space-3); }
.rpt-finding {
  padding: var(--space-3);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
}
.rpt-finding__head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}
.rpt-finding__op {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--font-caption);
  color: var(--text-secondary);
}
.rpt-finding__desc {
  margin: 0 0 var(--space-1);
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  line-height: 1.6;
}
.rpt-finding__meta {
  margin: 0;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  line-height: 1.5;
}

.rpt-action-bar {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-2);
}
.rpt-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  border: none;
  transition: all var(--transition-fast);
}
.rpt-action-btn--primary {
  background: var(--accent-primary);
  color: var(--text-on-accent);
}
.rpt-action-btn--primary:hover {
  background: var(--accent-hover);
}

.rpt-observing {
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  overflow: hidden;
}
.rpt-observing__toggle {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-3) var(--space-4);
  background: var(--bg-secondary);
  border: none;
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  text-align: left;
  transition: background var(--transition-fast);
}
.rpt-observing__toggle:hover { background: var(--bg-tertiary); }
.rpt-observing__chevron--open { transform: rotate(180deg); transition: transform var(--transition-normal); }
.rpt-observing__list {
  padding: var(--space-2) var(--space-4) var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.rpt-observing__item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  font-size: var(--font-caption);
  color: var(--text-secondary);
  line-height: 1.5;
  padding: var(--space-2);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  flex-wrap: wrap;
}
.rpt-observing__op {
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  font-size: 11px;
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  background: var(--bg-tertiary);
}
.rpt-observing__section {
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}
.rpt-observing__rationale {
  flex-basis: 100%;
  color: var(--text-secondary);
}
.rpt-observing__conf {
  font-size: 11px;
  color: var(--text-tertiary);
}

/* ===== DETAIL VIEW STYLES ===== */
.patch-version-banner {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  margin-bottom: var(--space-4);
  border-radius: var(--radius-lg);
  background: var(--accent-light);
  color: var(--text-primary);
  font-size: var(--font-caption);
  line-height: 1.5;
  border: 1px solid var(--border-subtle);
}
.patch-version-banner strong { color: var(--accent-primary); font-weight: 600; }

.patch-group { margin-bottom: var(--space-5); }
.patch-group__title {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin: 0 0 var(--space-3);
  padding-bottom: 4px;
  border-bottom: 1px dashed var(--border-subtle);
  font-weight: var(--weight-semibold);
}

.patch-card {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-3) var(--space-4);
  margin-bottom: var(--space-3);
  box-shadow: var(--shadow-sm);
  transition: opacity var(--transition-fast);
}
.patch-card--busy { opacity: 0.38; }

.patch-card__finding-title {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  font-weight: var(--weight-medium);
  line-height: 1.6;
  margin-bottom: var(--space-2);
}

.patch-card__head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
  flex-wrap: wrap;
}
.patch-card__op-tag {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  font-weight: 500;
}
.patch-card__op--add { background: rgba(16, 185, 129, 0.12); color: var(--success); }
.patch-card__op--modify { background: rgba(245, 158, 11, 0.12); color: #b37c09; }
.patch-card__op--delete { background: rgba(239, 68, 68, 0.12); color: var(--error); }
.patch-card__conf {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}
.patch-card__gk {
  font-size: var(--font-caption);
  color: var(--info);
  font-weight: var(--weight-medium);
}

.patch-card__gk-reason {
  padding: var(--space-1) var(--space-2);
  border-left: 2px solid var(--accent-primary);
  background: var(--bg-tertiary);
  font-size: var(--font-caption);
  color: var(--text-secondary);
  margin-bottom: var(--space-2);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  line-height: 1.5;
}

.patch-card__source-info {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  margin-bottom: var(--space-2);
}

.patch-evidence {
  margin: var(--space-2) 0 0;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-md);
  background: var(--bg-tertiary);
  border: 1px solid var(--border-subtle);
}
.patch-evidence__toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: none;
  color: var(--text-secondary);
  font-size: var(--font-caption);
  cursor: pointer;
  padding: 2px 0;
  transition: color var(--transition-fast);
}
.patch-evidence__toggle:hover { color: var(--accent-primary); }
.patch-evidence__chevron--open { transform: rotate(180deg); transition: transform var(--transition-normal); }
.patch-evidence__list {
  margin: var(--space-1) 0 0;
  padding-left: var(--space-4);
  font-size: var(--font-caption);
  color: var(--text-secondary);
  line-height: 1.6;
}
.patch-evidence__list li { margin-bottom: 2px; word-break: break-word; }

.patch-diff-toggle {
  margin-top: var(--space-2);
}
.spin-icon { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.patch-diff {
  margin: var(--space-2) 0 0;
  border-radius: var(--radius-md);
  overflow: hidden;
  font-size: var(--font-caption);
}
.patch-diff__label { padding: var(--space-1) var(--space-2); font-size: 11px; }
.patch-diff__md {
  padding: var(--space-2) var(--space-3);
  line-height: 1.55;
}
.patch-diff__md :deep(h1),
.patch-diff__md :deep(h2),
.patch-diff__md :deep(h3),
.patch-diff__md :deep(h4) {
  font-size: var(--font-body);
  margin: var(--space-2) 0 var(--space-1);
}
.patch-diff__md :deep(p) { margin: var(--space-1) 0; }
.patch-diff__md :deep(pre) {
  margin: var(--space-1) 0;
  padding: var(--space-2);
  font-size: var(--font-caption);
}
.patch-diff__md :deep(code) { font-size: var(--font-caption); }
.patch-diff--before { background: rgba(239, 68, 68, 0.06); }
.patch-diff--before .patch-diff__label { color: var(--error); }
.patch-diff--after { background: rgba(16, 185, 129, 0.06); }
.patch-diff--after .patch-diff__label { color: var(--success); }

.patch-card__actions {
  margin-top: var(--space-3);
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}
.patch-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  border-radius: var(--radius-md);
  font-size: var(--font-caption);
  cursor: pointer;
  border: 1px solid transparent;
  transition: background var(--transition-fast), color var(--transition-fast), border-color var(--transition-fast);
}
.patch-btn:disabled { opacity: 0.38; cursor: not-allowed; }
.patch-btn--ghost {
  background: transparent;
  border-color: var(--border-default);
  color: var(--text-secondary);
}
.patch-btn--ghost:hover:not(:disabled) {
  color: var(--text-primary);
  border-color: var(--text-tertiary);
  background: var(--bg-tertiary);
}
.patch-btn--primary {
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border-color: var(--accent-primary);
}
.patch-btn--primary:hover:not(:disabled) {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
}

.patch-group--conflict .patch-group__title {
  color: var(--warning, #e67700);
}
.patch-card--conflict {
  border-left: 3px solid var(--warning, #e67700);
}
.patch-card__op--conflict {
  background: rgba(230, 119, 0, 0.12);
  color: var(--warning, #e67700);
}
.patch-card__actions--conflict {
  flex-wrap: wrap;
  gap: var(--space-2);
}

.patch-group--observing .patch-group__title {
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
}
.patch-card--observing {
  border-left: 3px solid var(--border-default);
  opacity: 0.92;
}
.rpt-section--conflict {
  border-top: 1px solid var(--border-default);
  margin-top: var(--space-3);
  padding-top: var(--space-3);
}
.rpt-finding--conflict {
  border-left: 3px solid var(--warning, #e67700);
  padding-left: var(--space-3);
}

.patch-drawer-enter-active,
.patch-drawer-leave-active {
  transition: background-color var(--transition-normal), backdrop-filter var(--transition-normal);
}

/* ===== 批量操作栏 ===== */
.patch-batch-bar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  background: var(--bg-tertiary);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-3);
  border: 1px solid var(--border-default);
  flex-wrap: wrap;
}
.patch-batch-bar__select-all {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  background: transparent;
  border: none;
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  padding: 2px 4px;
  border-radius: var(--radius-sm);
  transition: color var(--transition-fast);
}
.patch-batch-bar__select-all:hover { color: var(--text-primary); }
.patch-batch-bar__count {
  font-size: var(--font-body-sm);
  color: var(--accent-primary);
  font-weight: var(--weight-medium);
  margin-right: auto;
}

/* 卡片选中状态 */
.patch-card--selected {
  border-color: var(--accent-primary);
  background: color-mix(in srgb, var(--accent-primary) 4%, var(--bg-primary));
}
.patch-card__select {
  position: absolute;
  top: var(--space-3);
  right: var(--space-3);
  color: var(--text-tertiary);
  cursor: pointer;
  transition: color var(--transition-fast);
  line-height: 0;
}
.patch-card__select:hover { color: var(--accent-primary); }
.patch-card--selected .patch-card__select { color: var(--accent-primary); }
.patch-card { position: relative; padding-right: calc(var(--space-3) + 20px); }

/* 一键采纳安全按钮（绿色） */
.rpt-action-btn--safe {
  background: var(--success);
  color: white;
}
.rpt-action-btn--safe:hover:not(:disabled) {
  background: color-mix(in srgb, var(--success) 88%, black);
}
.rpt-action-btn--safe:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.patch-drawer-enter-from,
.patch-drawer-leave-to {
  background-color: rgba(0, 0, 0, 0);
  backdrop-filter: blur(0px);
  -webkit-backdrop-filter: blur(0px);
}
.patch-drawer-enter-active .patch-drawer,
.patch-drawer-leave-active .patch-drawer {
  transition: transform var(--transition-normal) cubic-bezier(.22,.36,.36,1), opacity var(--transition-normal) ease;
}
.patch-drawer-enter-from .patch-drawer,
.patch-drawer-leave-to   .patch-drawer {
  transform: translateX(100%);
  opacity: 0;
}
</style>
