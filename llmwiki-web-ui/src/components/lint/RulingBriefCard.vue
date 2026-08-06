<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ShieldCheck, AlertTriangle, ChevronRight, ThumbsUp, ThumbsDown, RotateCcw, Lightbulb, Info, PenLine, FileWarning, Ban, GitMerge, GitBranch, CircleDot } from 'lucide-vue-next'
import InlineMarkdown from '@/components/common/InlineMarkdown.vue'

interface RulingBriefEvidence {
  field: string
  before: string
  after: string
}

interface RulingBriefAlternative {
  label: string
  description: string
  riskLevel: string
}

interface RulingBriefData {
  evidence: RulingBriefEvidence[]
  recommendation: string
  alternatives: RulingBriefAlternative[]
  riskTags: string[]
  confidence: number
}

const props = defineProps<{
  rulingBriefJson: string | null
  findingId: number
  findingType: string
  findingTitle: string
  status: string
  fromPageTitle?: string
  toPageTitle?: string
  readonly?: boolean
}>()

const emit = defineEmits<{
  approve: [id: number]
  modify: [id: number]
  reject: [id: number]
  rollback: [id: number]
  conflictAction: [id: number, action: string]
}>()

const { t } = useI18n()
const parseError = ref<string | null>(null)

const brief = computed<RulingBriefData | null>(() => {
  parseError.value = null
  if (!props.rulingBriefJson) return null
  try {
    const parsed = JSON.parse(props.rulingBriefJson)
    if (!parsed || typeof parsed !== 'object') {
      parseError.value = t('lint.rulingParseError')
      return null
    }

    let evidence: RulingBriefEvidence[] = []
    if (Array.isArray(parsed.evidence)) {
      evidence = parsed.evidence.map((e: any) => {
        if (typeof e === 'object' && e !== null && e.field) {
          return { field: e.field, before: e.before || '', after: e.after || '' }
        }
        if (typeof e === 'string') {
          return { field: t('lint.rulingConflictPoint'), before: e, after: '' }
        }
        return null
      }).filter(Boolean) as RulingBriefEvidence[]
    }

    const recommendation = typeof parsed.recommendation === 'string' ? parsed.recommendation
      : typeof parsed.summary === 'string' ? parsed.summary : ''

    const alternatives = Array.isArray(parsed.alternatives) ? parsed.alternatives : []

    let riskTags: string[] = []
    if (Array.isArray(parsed.riskTags)) {
      riskTags = parsed.riskTags
    } else if (typeof parsed.risk === 'string') {
      riskTags = [parsed.risk]
    }

    let confidence = 0.5
    if (typeof parsed.confidence === 'number') {
      confidence = parsed.confidence
    } else if (typeof parsed.risk === 'string') {
      confidence = parsed.risk === 'high' ? 0.5 : parsed.risk === 'medium' ? 0.7 : 0.9
    }

    return { evidence, recommendation, alternatives, riskTags, confidence }
  } catch (e: any) {
    parseError.value = t('lint.rulingParseFailed', [e?.message || t('lint.rulingUnknownError')])
    return null
  }
})

const confidencePercent = computed(() => {
  if (!brief.value) return 0
  return Math.round(brief.value.confidence * 100)
})

const confidenceColor = computed(() => {
  const p = confidencePercent.value
  if (p >= 80) return 'var(--success)'
  if (p >= 50) return 'var(--warning)'
  return 'var(--error)'
})

const riskTagColor = (tag: string) => {
  if (tag.includes('冲突') || tag.includes('数据丢失') || tag.includes('删除')) return 'var(--error-light)'
  if (tag.includes('不确定') || tag.includes('部分')) return 'var(--warning-light)'
  return 'var(--info-light)'
}

const riskTagTextColor = (tag: string) => {
  if (tag.includes('冲突') || tag.includes('数据丢失') || tag.includes('删除')) return 'var(--error)'
  if (tag.includes('不确定') || tag.includes('部分')) return 'var(--warning)'
  return 'var(--info)'
}

const typeIconMap: Record<string, typeof AlertTriangle> = {
  conflict: AlertTriangle,
  stale: Info,
  missing_crossref: ChevronRight,
  orphan: Info,
  gap: Lightbulb,
  web_gap: ChevronRight,
  action: Lightbulb,
  schema_compliance: FileWarning,
  schema_violation: FileWarning
}

const typeLabelKeyMap: Record<string, string> = {
  conflict: 'lint.rulingTypeConflict',
  stale: 'lint.rulingTypeStale',
  missing_crossref: 'lint.rulingTypeMissingCrossref',
  orphan: 'lint.rulingTypeOrphan',
  gap: 'lint.rulingTypeGap',
  web_gap: 'lint.rulingTypeWebGap',
  action: 'lint.rulingTypeAction',
  schema_compliance: 'lint.rulingTypeSchemaCompliance',
  schema_violation: 'lint.rulingTypeSchemaViolation',
}

const currentTypeIcon = computed(() => typeIconMap[props.findingType] || Info)
const currentTypeLabel = computed(() => typeLabelKeyMap[props.findingType] ? t(typeLabelKeyMap[props.findingType]) : props.findingType)

const isAwaitingApproval = computed(() => props.status === 'awaiting_approval')
const isResolvedOrAuto = computed(() => props.status === 'resolved' || props.status === 'auto_resolved')
const isConflict = computed(() => props.findingType === 'conflict')
const conflictFromLabel = computed(() => props.fromPageTitle || t('lint.pageFallbackA'))
const conflictToLabel = computed(() => props.toPageTitle || t('lint.pageFallbackB'))
</script>

<template>
  <div class="ruling-brief-card">
    <div class="ruling-brief-card__header">
      <component :is="currentTypeIcon" :size="16" class="ruling-brief-card__type-icon" />
      <span class="ruling-brief-card__type-label">{{ currentTypeLabel }}</span>
      <span class="ruling-brief-card__title">{{ findingTitle }}</span>
      <div v-if="brief" class="ruling-brief-card__confidence">
        <div class="ruling-brief-card__confidence-bar" :style="{ background: confidenceColor, width: confidencePercent + '%' }"></div>
        <span class="ruling-brief-card__confidence-text" :style="{ color: confidenceColor }">{{ confidencePercent }}%</span>
      </div>
    </div>

    <template v-if="brief">
      <div v-if="brief.evidence.length > 0" class="ruling-brief-card__section">
        <div class="ruling-brief-card__section-title">
          <Info :size="14" />
          {{ t('lint.rulingEvidenceCompare') }}
        </div>
        <div class="ruling-brief-card__evidence-list">
          <div v-for="(ev, idx) in brief.evidence" :key="idx" class="ruling-brief-card__evidence-item">
            <span class="ruling-brief-card__evidence-field">{{ ev.field }}</span>
            <div class="ruling-brief-card__evidence-diff">
              <div class="ruling-brief-card__evidence-before">
                <span class="ruling-brief-card__evidence-label">{{ t('lint.rulingCurrent') }}</span>
                <span class="ruling-brief-card__evidence-text"><InlineMarkdown :content="ev.before" inline /></span>
              </div>
              <div class="ruling-brief-card__evidence-after">
                <span class="ruling-brief-card__evidence-label">{{ t('lint.rulingSuggested') }}</span>
                <span class="ruling-brief-card__evidence-text"><InlineMarkdown :content="ev.after" inline /></span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="brief.recommendation" class="ruling-brief-card__section">
        <div class="ruling-brief-card__section-title">
          <Lightbulb :size="14" />
          {{ t('lint.rulingRecommendation') }}
        </div>
        <div class="inline-markdown-container ruling-brief-card__recommendation"><InlineMarkdown :content="brief.recommendation" /></div>
      </div>

      <div v-if="brief.alternatives.length > 0" class="ruling-brief-card__section">
        <div class="ruling-brief-card__section-title">
          <ChevronRight :size="14" />
          {{ t('lint.rulingAlternatives') }}
        </div>
        <div class="ruling-brief-card__alternatives">
          <div v-for="(alt, idx) in brief.alternatives" :key="idx" class="ruling-brief-card__alternative-item">
            <span class="ruling-brief-card__alternative-label">{{ alt.label }}</span>
            <span class="ruling-brief-card__alternative-desc"><InlineMarkdown :content="alt.description" inline /></span>
            <span class="ruling-brief-card__alternative-risk">{{ alt.riskLevel }}</span>
          </div>
        </div>
      </div>

      <div v-if="brief.riskTags.length > 0" class="ruling-brief-card__section">
        <div class="ruling-brief-card__section-title">
          <AlertTriangle :size="14" />
          {{ t('lint.rulingRiskTags') }}
        </div>
        <div class="ruling-brief-card__risk-tags">
          <span
            v-for="tag in brief.riskTags"
            :key="tag"
            class="ruling-brief-card__risk-tag"
            :style="{ background: riskTagColor(tag), color: riskTagTextColor(tag) }"
          >
            {{ tag }}
          </span>
        </div>
      </div>
    </template>

    <div v-else-if="parseError" class="ruling-brief-card__empty">
      <AlertTriangle :size="16" style="color: var(--error)" />
      <span>{{ parseError }}</span>
    </div>
    <div v-else class="ruling-brief-card__empty">
      <ShieldCheck :size="16" />
      <span>{{ t('lint.rulingNotGenerated') }}</span>
    </div>

    <!-- 矛盾裁决专属按钮（参考变更审批中心） -->
    <div v-if="!readonly && isAwaitingApproval && isConflict" class="ruling-brief-card__actions ruling-brief-card__actions--conflict">
      <button class="ruling-brief-card__btn ruling-brief-card__btn--dismiss" @click="emit('conflictAction', findingId, 'dismiss')">
        <Ban :size="14" />
        {{ t('lint.rulingDismiss') }}
      </button>
      <button class="ruling-brief-card__btn ruling-brief-card__btn--ghost" @click="emit('conflictAction', findingId, 'choose_a')">
        <CircleDot :size="14" />
        {{ t('lint.rulingKeepPage', [conflictFromLabel]) }}
      </button>
      <button class="ruling-brief-card__btn ruling-brief-card__btn--ghost" @click="emit('conflictAction', findingId, 'coexist')">
        <GitBranch :size="14" />
        {{ t('lint.rulingCoexist') }}
      </button>
      <button class="ruling-brief-card__btn ruling-brief-card__btn--ghost" @click="emit('conflictAction', findingId, 'choose_b')">
        <CircleDot :size="14" />
        {{ t('lint.rulingKeepPage', [conflictToLabel]) }}
      </button>
      <button class="ruling-brief-card__btn ruling-brief-card__btn--approve" @click="emit('conflictAction', findingId, 'merge')">
        <GitMerge :size="14" />
        {{ t('lint.rulingMerge') }}
      </button>
    </div>

    <!-- 其他类型 finding 的审批按钮 -->
    <div v-else-if="!readonly && isAwaitingApproval" class="ruling-brief-card__actions">
      <button class="ruling-brief-card__btn ruling-brief-card__btn--approve" @click="emit('approve', findingId)">
        <ThumbsUp :size="14" />
        {{ t('lint.rulingApproveAll') }}
      </button>
      <button class="ruling-brief-card__btn ruling-brief-card__btn--modify" @click="emit('modify', findingId)">
        <PenLine :size="14" />
        {{ t('lint.rulingModify') }}
      </button>
      <button class="ruling-brief-card__btn ruling-brief-card__btn--reject" @click="emit('reject', findingId)">
        <ThumbsDown :size="14" />
        {{ t('lint.rejectBtn') }}
      </button>
    </div>

    <div v-if="isResolvedOrAuto" class="ruling-brief-card__resolved-badge">
      <ShieldCheck :size="14" />
      {{ t('lint.rulingApproved') }}
      <button v-if="!readonly" class="ruling-brief-card__btn ruling-brief-card__btn--rollback" @click="emit('rollback', findingId)">
        <RotateCcw :size="12" />
        {{ t('lint.rulingRollback') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.ruling-brief-card {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-4);
}

.ruling-brief-card__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.ruling-brief-card__type-icon {
  color: var(--accent-primary);
}

.ruling-brief-card__type-label {
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--accent-primary);
  background: var(--accent-light);
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

.ruling-brief-card__title {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  flex: 1;
}

.ruling-brief-card__confidence {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 80px;
}

.ruling-brief-card__confidence-bar {
  height: 6px;
  border-radius: 3px;
  transition: width var(--transition-normal);
}

.ruling-brief-card__confidence-text {
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  min-width: 32px;
}

.ruling-brief-card__section {
  margin-bottom: var(--space-3);
}

.ruling-brief-card__section-title {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  margin-bottom: var(--space-2);
}

.ruling-brief-card__evidence-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.ruling-brief-card__evidence-item {
  padding: var(--space-2);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
}

.ruling-brief-card__evidence-field {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: var(--text-secondary);
  margin-bottom: var(--space-1);
  display: block;
}

.ruling-brief-card__evidence-diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-2);
}

.ruling-brief-card__evidence-before,
.ruling-brief-card__evidence-after {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.ruling-brief-card__evidence-label {
  font-size: 10px;
  font-weight: var(--weight-semibold);
  color: var(--text-tertiary);
}

.ruling-brief-card__evidence-before .ruling-brief-card__evidence-label {
  color: var(--warning);
}

.ruling-brief-card__evidence-after .ruling-brief-card__evidence-label {
  color: var(--success);
}

.ruling-brief-card__evidence-text {
  font-size: var(--font-caption);
  color: var(--text-primary);
  line-height: 1.4;
  word-break: break-word;
}

.ruling-brief-card__recommendation {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  background: var(--success-light);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
}
.ruling-brief-card__recommendation :deep(.inline-markdown p) {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  margin: 0;
}

.ruling-brief-card__alternatives {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.ruling-brief-card__alternative-item {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  font-size: var(--font-caption);
  padding: var(--space-1) var(--space-2);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
}

.ruling-brief-card__alternative-label {
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}

.ruling-brief-card__alternative-desc {
  color: var(--text-secondary);
  flex: 1;
}

.ruling-brief-card__alternative-risk {
  font-weight: var(--weight-medium);
  color: var(--warning);
}

.ruling-brief-card__risk-tags {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.ruling-brief-card__risk-tag {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

.ruling-brief-card__empty {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}

.ruling-brief-card__actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}

.ruling-brief-card__btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  border: none;
  transition: opacity var(--transition-fast);
}

.ruling-brief-card__btn:hover {
  opacity: 0.85;
}

.ruling-brief-card__btn--approve {
  background: var(--success);
  color: var(--text-on-accent);
}

.ruling-brief-card__btn--reject {
  background: var(--error);
  color: var(--text-on-accent);
}

.ruling-brief-card__btn--modify {
  background: var(--accent-primary);
  color: var(--text-on-accent);
}

.ruling-brief-card__btn--dismiss {
  background: transparent;
  border: 1px solid var(--border-default);
  color: var(--text-secondary);
}

.ruling-brief-card__btn--ghost {
  background: transparent;
  border: 1px solid var(--border-default);
  color: var(--text-primary);
}

.ruling-brief-card__btn--ghost:hover {
  background: var(--bg-secondary);
  border-color: var(--accent-primary);
  color: var(--accent-primary);
}

.ruling-brief-card__actions--conflict {
  flex-wrap: wrap;
  gap: var(--space-2);
}

.ruling-brief-card__btn--rollback {
  background: transparent;
  border: 1px solid var(--border-default);
  color: var(--text-secondary);
  font-size: var(--font-caption);
  padding: var(--space-1) var(--space-2);
}

.ruling-brief-card__resolved-badge {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
  color: var(--success);
  font-weight: var(--weight-medium);
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}
</style>