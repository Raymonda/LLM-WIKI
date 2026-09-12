<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Info, Loader2, Sparkles } from 'lucide-vue-next'
import { type LintFindingInfo } from '@/api/lint'
import { normalizeConflictExtra } from '@/utils/conflictPresentation'

const props = defineProps<{
  finding: LintFindingInfo
  processing?: boolean
}>()

const emit = defineEmits<{
  analyze: [id: number]
}>()

const { t } = useI18n()
const router = useRouter()

const conflict = computed(() => normalizeConflictExtra(props.finding))

const typeLabelKeyMap: Record<string, string> = {
  fact_conflict: 'lint.conflict.typeFact',
  value_conflict: 'lint.conflict.typeValue',
  definition_conflict: 'lint.conflict.typeDefinition',
  temporal_conflict: 'lint.conflict.typeTemporal',
  content_duplication: 'lint.conflict.typeContentDuplication'
}

const typeLabel = computed(() => {
  const key = typeLabelKeyMap[conflict.value.conflictType]
  return key ? t(key) : t('lint.conflict.typeUnknown')
})

const showAnalyze = computed(() =>
  !props.finding.rulingBriefJson
  && (props.finding.status === 'open' || props.finding.status === 'deferred')
  && conflict.value.canOperate
)

function openPage(pageId: number | null) {
  if (pageId !== null) router.push(`/wiki/${pageId}`)
}
</script>

<template>
  <div class="conflict-body">
    <div class="conflict-body__head">
      <span class="conflict-body__type">{{ typeLabel }}</span>
      <Info
        v-if="conflict.isLegacy"
        :size="12"
        class="conflict-body__legacy"
        :title="t('lint.conflict.legacyTooltip')"
      />
    </div>

    <div class="conflict-body__grid">
      <div class="conflict-body__side">
        <span class="conflict-body__side-label">{{ t('lint.conflict.columnFrom') }}</span>
        <button
          v-if="conflict.fromPageId !== null"
          class="conflict-body__page"
          @click="openPage(conflict.fromPageId)"
        >{{ conflict.fromTitle }}</button>
        <span v-else class="conflict-body__page conflict-body__page--static">{{ conflict.fromTitle }}</span>
        <p v-if="conflict.claimA" class="conflict-body__claim">{{ conflict.claimA }}</p>
      </div>
      <div class="conflict-body__side">
        <span class="conflict-body__side-label">{{ t('lint.conflict.columnRelated') }}</span>
        <button
          v-if="conflict.toPageId !== null"
          class="conflict-body__page"
          @click="openPage(conflict.toPageId)"
        >{{ conflict.toTitle }}</button>
        <span v-else class="conflict-body__page conflict-body__page--static">{{ conflict.toTitle }}</span>
        <p v-if="conflict.claimB" class="conflict-body__claim">{{ conflict.claimB }}</p>
      </div>
    </div>

    <p v-if="!conflict.hasClaims && finding.detail" class="conflict-body__fallback">{{ finding.detail }}</p>

    <button
      v-if="showAnalyze"
      class="conflict-body__analyze"
      :disabled="processing"
      @click="emit('analyze', finding.id)"
    >
      <Loader2 v-if="processing" :size="12" class="conflict-body__spin" />
      <Sparkles v-else :size="12" />
      {{ t('lint.conflict.analyzeBtn') }}
    </button>
  </div>
</template>

<style scoped>
.conflict-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.conflict-body__head {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.conflict-body__type {
  font-size: 10px;
  font-weight: var(--weight-bold);
  color: var(--error);
  background: rgba(239, 68, 68, 0.12);
  padding: 1px 6px;
  border-radius: var(--radius-full);
}

.conflict-body__legacy {
  color: var(--text-tertiary);
  cursor: help;
}

.conflict-body__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-2);
}

.conflict-body__side {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
  padding: var(--space-2);
  min-width: 0;
}

.conflict-body__side-label {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.conflict-body__page {
  align-self: flex-start;
  border: none;
  background: none;
  padding: 0;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--accent-primary);
  cursor: pointer;
  text-align: left;
}

.conflict-body__page--static {
  color: var(--text-primary);
  cursor: default;
}

.conflict-body__claim {
  margin: 0;
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  white-space: pre-line;
  word-break: break-word;
}

.conflict-body__fallback {
  margin: 0;
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  white-space: pre-line;
}

.conflict-body__analyze {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  border: none;
  border-radius: var(--radius-md);
  padding: 4px 10px;
  font-size: var(--font-body-sm);
  background: var(--accent-primary);
  color: white;
  cursor: pointer;
}

.conflict-body__analyze:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.conflict-body__spin {
  animation: conflict-body-spin 1s linear infinite;
}

@keyframes conflict-body-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
