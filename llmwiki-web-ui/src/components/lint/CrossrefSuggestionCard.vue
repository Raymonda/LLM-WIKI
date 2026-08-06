<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Link2, MessageCircle, ArrowRight, ChevronDown, X, Loader2 } from 'lucide-vue-next'
import InlineMarkdown from '@/components/common/InlineMarkdown.vue'

export interface CrossrefSuggestion {
  sourceTitle: string
  targetTitle: string
  sourcePath: string
  targetPath: string
  sourceAssetId: number
  targetAssetId: number
  linkContext: string
  confidence: number
  reason: string
  linkType: string
}

const props = defineProps<{
  suggestion: CrossrefSuggestion
  findingId: number
  processing: boolean
}>()

const emit = defineEmits<{
  approve: [findingId: number, suggestion: CrossrefSuggestion]
  reject: [findingId: number, suggestion: CrossrefSuggestion, reason?: string]
  ignore: [findingId: number]
}>()

const { t } = useI18n()
const showReason = ref(false)

const confidencePercent = computed(() => {
  return Math.round(props.suggestion.confidence * 100)
})

const confidenceColor = computed(() => {
  const p = confidencePercent.value
  if (p >= 80) return 'var(--success)'
  if (p >= 50) return 'var(--warning)'
  return 'var(--error)'
})

const confidenceBgColor = computed(() => {
  const p = confidencePercent.value
  if (p >= 80) return 'rgba(34, 197, 94, 0.12)'
  if (p >= 50) return 'rgba(245, 158, 11, 0.12)'
  return 'rgba(239, 68, 68, 0.12)'
})

const linkTypeLabelMap: Record<string, string> = {
  explanation: 'lint.crossrefTypeExplanation',
  extension: 'lint.crossrefTypeExtension',
  comparison: 'lint.crossrefTypeComparison',
  causality: 'lint.crossrefTypeCausality',
  hierarchy: 'lint.crossrefTypeHierarchy',
  sibling: 'lint.crossrefTypeSibling',
  related: 'lint.crossrefTypeRelated',
}

const linkTypeLabel = (type: string) => {
  return t(linkTypeLabelMap[type] || 'lint.crossrefTypeRelated')
}

const linkTypeColor = (type: string) => {
  const map: Record<string, string> = {
    explanation: 'var(--accent-primary)',
    extension: 'var(--success)',
    comparison: 'var(--warning)',
    causality: 'var(--error)',
    hierarchy: 'var(--info, var(--accent-primary))',
    sibling: 'var(--text-secondary)',
    related: 'var(--text-secondary)'
  }
  return map[type] || 'var(--text-secondary)'
}

function navigateToPage(assetId: number) {
  window.open(`/wiki/${assetId}`, '_blank')
}
</script>

<template>
  <div class="crossref-suggestion-card">
    <!-- Header: 置信度 + 链接类型 -->
    <div class="crossref-suggestion-card__header">
      <Link2 :size="16" class="crossref-suggestion-card__icon" />
      <span class="crossref-suggestion-card__type-label">{{ t('lint.crossrefAiSuggestion') }}</span>
      
      <!-- 链接类型标签 -->
      <span 
        class="crossref-suggestion-card__link-type"
        :style="{ color: linkTypeColor(suggestion.linkType), borderColor: linkTypeColor(suggestion.linkType) }"
      >
        {{ linkTypeLabel(suggestion.linkType) }}
      </span>
      
      <!-- 置信度进度条（复用 RulingBriefCard 模式） -->
      <div class="crossref-suggestion-card__confidence">
        <div 
          class="crossref-suggestion-card__confidence-bar" 
          :style="{ background: confidenceBgColor, width: '100%' }"
        >
          <div 
            class="crossref-suggestion-card__confidence-fill"
            :style="{ background: confidenceColor, width: confidencePercent + '%' }"
          ></div>
        </div>
        <span class="crossref-suggestion-card__confidence-text" :style="{ color: confidenceColor }">
          {{ confidencePercent }}%
        </span>
      </div>
    </div>

    <!-- 链接上下文说明 -->
    <div class="crossref-suggestion-card__section">
      <div class="crossref-suggestion-card__section-title">
        <MessageCircle :size="14" />
        {{ t('lint.crossrefLinkContext') }}
      </div>
      <div class="crossref-suggestion-card__context">
        <InlineMarkdown :content="suggestion.linkContext" />
      </div>
    </div>

    <!-- 页面预览（源页面 → 目标页面） -->
    <div class="crossref-suggestion-card__section">
      <div class="crossref-suggestion-card__section-title">
        <ArrowRight :size="14" />
        {{ t('lint.crossrefSuggestedLink') }}
      </div>
      <div class="crossref-suggestion-card__pages">
        <div class="crossref-suggestion-card__page-item">
          <span class="crossref-suggestion-card__page-label">{{ t('lint.crossrefSourcePage') }}</span>
          <a 
            class="crossref-suggestion-card__page-link" 
            @click.prevent="navigateToPage(suggestion.sourceAssetId)"
          >
            {{ suggestion.sourceTitle }}
          </a>
          <span class="crossref-suggestion-card__page-path">{{ suggestion.sourcePath }}</span>
        </div>
        <div class="crossref-suggestion-card__arrow">→</div>
        <div class="crossref-suggestion-card__page-item">
          <span class="crossref-suggestion-card__page-label">{{ t('lint.crossrefTargetPage') }}</span>
          <a 
            class="crossref-suggestion-card__page-link" 
            @click.prevent="navigateToPage(suggestion.targetAssetId)"
          >
            {{ suggestion.targetTitle }}
          </a>
          <span class="crossref-suggestion-card__page-path">{{ suggestion.targetPath }}</span>
        </div>
      </div>
    </div>

    <!-- AI 判断理由（可折叠） -->
    <div class="crossref-suggestion-card__section">
      <button class="crossref-suggestion-card__collapse-btn" @click="showReason = !showReason">
        <ChevronDown :size="14" :class="{ 'crossref-suggestion-card__chevron--rotated': showReason }" />
        {{ t('lint.crossrefAiReason') }}
      </button>
      <div v-if="showReason" class="crossref-suggestion-card__reason">
        <InlineMarkdown :content="suggestion.reason" />
      </div>
    </div>

    <!-- 审批按钮（与 RulingBriefCard 一致） -->
    <div class="crossref-suggestion-card__actions">
      <button 
        class="crossref-suggestion-card__btn crossref-suggestion-card__btn--approve" 
        :disabled="processing"
        @click="emit('approve', findingId, suggestion)"
      >
        <Link2 v-if="!processing" :size="14" />
        <Loader2 v-else :size="14" class="crossref-suggestion-card__spin" />
        {{ t('lint.crossrefCreateLink', [suggestion.sourceTitle, suggestion.targetTitle]) }}
      </button>
      <button 
        class="crossref-suggestion-card__btn crossref-suggestion-card__btn--reject" 
        :disabled="processing"
        @click="emit('reject', findingId, suggestion)"
      >
        <X :size="14" />
        {{ t('lint.crossrefRejectLink') }}
      </button>
      <button 
        class="crossref-suggestion-card__btn crossref-suggestion-card__btn--ignore" 
        :disabled="processing"
        @click="emit('ignore', findingId)"
      >
        {{ t('lint.ignore') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.crossref-suggestion-card {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-4);
  margin-top: var(--space-3);
}

/* Header - 复用 RulingBriefCard 模式 */
.crossref-suggestion-card__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.crossref-suggestion-card__icon {
  color: var(--accent-primary);
}

.crossref-suggestion-card__type-label {
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--accent-primary);
  background: var(--accent-light);
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

.crossref-suggestion-card__link-type {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  padding: 2px 8px;
  border-radius: var(--radius-full);
  border: 1px solid;
}

/* 置信度 - 完全复用 RulingBriefCard 模式 */
.crossref-suggestion-card__confidence {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 80px;
  margin-left: auto;
}

.crossref-suggestion-card__confidence-bar {
  height: 6px;
  border-radius: 3px;
  background: var(--bg-secondary);
  overflow: hidden;
  width: 50px;
}

.crossref-suggestion-card__confidence-fill {
  height: 100%;
  border-radius: 3px;
  transition: width var(--transition-normal);
}

.crossref-suggestion-card__confidence-text {
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  min-width: 32px;
}

/* Section 标题 */
.crossref-suggestion-card__section {
  margin-bottom: var(--space-3);
}

.crossref-suggestion-card__section-title {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  margin-bottom: var(--space-2);
}

/* 链接上下文 - 引用样式 */
.crossref-suggestion-card__context {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  background: var(--bg-secondary);
  border-left: 3px solid var(--accent-primary);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  line-height: 1.6;
}

/* 页面预览 */
.crossref-suggestion-card__pages {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.crossref-suggestion-card__page-item {
  flex: 1;
  padding: var(--space-2);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.crossref-suggestion-card__page-label {
  font-size: 10px;
  font-weight: var(--weight-semibold);
  color: var(--text-tertiary);
}

.crossref-suggestion-card__page-link {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: var(--accent-primary);
  cursor: pointer;
  text-decoration: none;
}

.crossref-suggestion-card__page-link:hover {
  text-decoration: underline;
}

.crossref-suggestion-card__page-path {
  font-size: 10px;
  color: var(--text-tertiary);
  font-family: monospace;
}

.crossref-suggestion-card__arrow {
  font-size: var(--font-h3);
  color: var(--accent-primary);
  font-weight: var(--weight-bold);
}

/* 可折叠理由 */
.crossref-suggestion-card__collapse-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  background: none;
  border: none;
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0;
}

.crossref-suggestion-card__chevron--rotated {
  transform: rotate(180deg);
  transition: transform var(--transition-fast);
}

.crossref-suggestion-card__reason {
  margin-top: var(--space-2);
  padding: var(--space-2);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
  font-size: var(--font-caption);
  color: var(--text-secondary);
  line-height: 1.5;
}

/* 审批按钮 - 复用 RulingBriefCard 模式 */
.crossref-suggestion-card__actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}

.crossref-suggestion-card__btn {
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

.crossref-suggestion-card__btn:hover:not(:disabled) {
  opacity: 0.85;
}

.crossref-suggestion-card__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.crossref-suggestion-card__btn--approve {
  background: var(--success);
  color: var(--text-on-accent);
}

.crossref-suggestion-card__btn--reject {
  background: var(--error);
  color: var(--text-on-accent);
}

.crossref-suggestion-card__btn--ignore {
  background: transparent;
  border: 1px solid var(--border-default);
  color: var(--text-secondary);
}

.crossref-suggestion-card__spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
