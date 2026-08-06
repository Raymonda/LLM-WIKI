<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Bot, Building2, Lightbulb, FileText, Calendar, Tag, Hash,
  Link2, Quote
} from 'lucide-vue-next'
import type { AnalysisMetadata, EntityWithType } from '@/stores/ingestProgress'

const props = defineProps<{
  aiAnalysis: string
  metadata: AnalysisMetadata | null
}>()

const { t } = useI18n()

interface ParsedEntity {
  name: string
  type: string
  description: string
}

interface ParsedFact {
  text: string
}

interface ParsedAssociation {
  text: string
}

const ENTITY_TYPE_CONFIG: Record<string, { icon: any; labelKey: string; colorClass: string }> = {
  organization: { icon: Building2, labelKey: 'ingest.entityOrg', colorClass: 'analysis-entity--org' },
  concept: { icon: Lightbulb, labelKey: 'ingest.entityConcept', colorClass: 'analysis-entity--concept' },
  document: { icon: FileText, labelKey: 'ingest.entityDocument', colorClass: 'analysis-entity--doc' },
  event: { icon: Calendar, labelKey: 'ingest.entityEvent', colorClass: 'analysis-entity--event' },
  person: { icon: Bot, labelKey: 'ingest.entityPerson', colorClass: 'analysis-entity--person' },
  location: { icon: Tag, labelKey: 'ingest.entityLocation', colorClass: 'analysis-entity--loc' },
}

const DEFAULT_ENTITY_CONFIG = { icon: Lightbulb, labelKey: 'ingest.entityDefault', colorClass: 'analysis-entity--concept' }

function getTypeConfig(type: string) {
  return ENTITY_TYPE_CONFIG[type] || DEFAULT_ENTITY_CONFIG
}

const parsedSections = computed(() => {
  if (!props.aiAnalysis) return { entities: [] as ParsedEntity[], facts: [] as ParsedFact[], associations: [] as ParsedAssociation[] }

  const sections = props.aiAnalysis.split(/^###\s+/m).filter(Boolean)
  let entities: ParsedEntity[] = []
  let facts: ParsedFact[] = []
  let associations: ParsedAssociation[] = []

  for (const section of sections) {
    const lines = section.split('\n').filter(l => l.trim())
    const header = lines[0]?.trim() || ''
    const bullets = lines.slice(1).filter(l => l.trim().startsWith('-'))

    if (/主要实体|实体列表|Core Entities|Entity List/i.test(header)) {
      for (const b of bullets) {
        const text = b.replace(/^-\s*/, '').trim()
        const parts = text.split('|').map(s => s.trim())
        if (parts.length >= 2) {
          entities.push({
            name: parts[0],
            type: parts[1] || 'concept',
            description: parts.slice(2).join(' | ') || '',
          })
        } else if (parts.length === 1 && text) {
          entities.push({ name: text, type: 'concept', description: '' })
        }
      }
    } else if (/关键事实|Key Facts/i.test(header)) {
      for (const b of bullets) {
        const text = b.replace(/^-\s*/, '').trim()
        if (text) facts.push({ text })
      }
    } else if (/关联点|潜在关联|Associations|Potential Links/i.test(header)) {
      for (const b of bullets) {
        const text = b.replace(/^-\s*/, '').trim()
        if (text) associations.push({ text })
      }
    }
  }

  return { entities, facts, associations }
})

const enrichedEntities = computed(() => {
  const parsed = parsedSections.value.entities
  if (!props.metadata?.entities?.length) return parsed

  const metaMap = new Map<string, EntityWithType>()
  for (const e of props.metadata.entities) {
    metaMap.set(e.name, e)
  }

  const seen = new Set<string>()
  const result: ParsedEntity[] = []

  for (const pe of parsed) {
    seen.add(pe.name)
    const meta = metaMap.get(pe.name)
    result.push({
      name: pe.name,
      type: meta?.type || pe.type || 'concept',
      description: pe.description || meta?.description || '',
    })
  }

  for (const me of props.metadata.entities) {
    if (!seen.has(me.name)) {
      result.push({
        name: me.name,
        type: me.type || 'concept',
        description: me.description || '',
      })
    }
  }

  return result
})
</script>

<template>
  <div class="analysis-panel">
    <div class="analysis-panel__header">
      <Bot :size="16" class="analysis-panel__header-icon" />
      <span class="analysis-panel__header-title">{{ t('ingest.aiAnalysisResult') }}</span>
    </div>

    <div class="analysis-panel__body">
      <div v-if="metadata" class="analysis-panel__overview">
        <div class="analysis-panel__overview-top">
          <h3 v-if="metadata.title" class="analysis-panel__doc-title">{{ metadata.title }}</h3>
          <span v-if="metadata.category" class="analysis-panel__category-badge">{{ metadata.category }}</span>
        </div>
        <p v-if="metadata.summary" class="analysis-panel__summary">{{ metadata.summary }}</p>
        <div v-if="metadata.tags.length > 0 || metadata.keywords.length > 0" class="analysis-panel__tag-row">
          <span v-for="tag in metadata.tags" :key="tag" class="analysis-panel__tag-pill">
            <Tag :size="10" />{{ tag }}
          </span>
          <span v-for="kw in metadata.keywords" :key="kw" class="analysis-panel__kw-pill">
            <Hash :size="10" />{{ kw }}
          </span>
        </div>
      </div>

      <div v-if="enrichedEntities.length > 0" class="analysis-panel__section">
        <h4 class="analysis-panel__section-title">
          <Lightbulb :size="14" />
          {{ t('ingest.entitiesFoundCount', [enrichedEntities.length]) }}
        </h4>
        <div class="analysis-panel__entity-grid">
          <div v-for="entity in enrichedEntities" :key="entity.name" :class="['analysis-panel__entity-card', getTypeConfig(entity.type).colorClass]">
            <div class="analysis-panel__entity-top">
              <span class="analysis-panel__entity-name">{{ entity.name }}</span>
              <span class="analysis-panel__entity-type-badge">
                <component :is="getTypeConfig(entity.type).icon" :size="10" />
                {{ t(getTypeConfig(entity.type).labelKey) }}
              </span>
            </div>
            <p v-if="entity.description" class="analysis-panel__entity-desc">{{ entity.description }}</p>
          </div>
        </div>
      </div>

      <div v-if="parsedSections.facts.length > 0" class="analysis-panel__section">
        <h4 class="analysis-panel__section-title">
          <Quote :size="14" />
          {{ t('ingest.keyFactsCount', [parsedSections.facts.length]) }}
        </h4>
        <div class="analysis-panel__facts-list">
          <div v-for="(fact, idx) in parsedSections.facts" :key="idx" class="analysis-panel__fact-item">
            <span class="analysis-panel__fact-dot"></span>
            <span class="analysis-panel__fact-text">{{ fact.text }}</span>
          </div>
        </div>
      </div>

      <div v-if="parsedSections.associations.length > 0" class="analysis-panel__section">
        <h4 class="analysis-panel__section-title">
          <Link2 :size="14" />
          {{ t('ingest.potentialAssociationsCount', [parsedSections.associations.length]) }}
        </h4>
        <div class="analysis-panel__assoc-list">
          <div v-for="(assoc, idx) in parsedSections.associations" :key="idx" class="analysis-panel__assoc-item">
            <Link2 :size="12" class="analysis-panel__assoc-icon" />
            <span class="analysis-panel__assoc-text">{{ assoc.text }}</span>
          </div>
        </div>
      </div>

      <div v-if="!metadata && enrichedEntities.length === 0 && parsedSections.facts.length === 0 && parsedSections.associations.length === 0 && aiAnalysis" class="analysis-panel__fallback">
        {{ aiAnalysis }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.analysis-panel {
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.analysis-panel__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--accent-light);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--accent-primary);
  border-bottom: 1px solid var(--border-default);
}

.analysis-panel__header-icon {
  flex-shrink: 0;
}

.analysis-panel__header-title {
  font-weight: var(--weight-semibold);
}

.analysis-panel__body {
  padding: var(--space-4);
}

.analysis-panel__overview {
  margin-bottom: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
}

.analysis-panel__overview-top {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.analysis-panel__doc-title {
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin: 0;
}

.analysis-panel__category-badge {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  padding: 1px var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--accent-light);
  color: var(--accent-primary);
  white-space: nowrap;
}

.analysis-panel__summary {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.6;
  margin: 0 0 var(--space-2) 0;
}

.analysis-panel__tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.analysis-panel__tag-pill {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: var(--weight-medium);
  background: var(--accent-light);
  color: var(--accent-primary);
  border-radius: var(--radius-full);
}

.analysis-panel__kw-pill {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: var(--weight-medium);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  border-radius: var(--radius-full);
}

.analysis-panel__section {
  margin-bottom: var(--space-4);
}

.analysis-panel__section:last-child {
  margin-bottom: 0;
}

.analysis-panel__section-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  margin: 0 0 var(--space-3) 0;
}

.analysis-panel__entity-grid {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.analysis-panel__entity-card {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  border: 1px solid var(--border-default);
  background: var(--surface-card);
}

.analysis-panel__entity-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.analysis-panel__entity-name {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}

.analysis-panel__entity-type-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  padding: 1px var(--space-2);
  border-radius: var(--radius-sm);
  white-space: nowrap;
}

.analysis-entity--org .analysis-panel__entity-type-badge {
  background: var(--info-light);
  color: var(--info);
}

.analysis-entity--concept .analysis-panel__entity-type-badge {
  background: var(--success-light);
  color: var(--success);
}

.analysis-entity--doc .analysis-panel__entity-type-badge {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.analysis-entity--event .analysis-panel__entity-type-badge {
  background: var(--warning-light);
  color: var(--warning);
}

.analysis-entity--person .analysis-panel__entity-type-badge {
  background: #fce7f3;
  color: #db2777;
}

.analysis-entity--loc .analysis-panel__entity-type-badge {
  background: #e0e7ff;
  color: #4f46e5;
}

.analysis-panel__entity-desc {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  margin: var(--space-1) 0 0 0;
  line-height: 1.4;
}

.analysis-panel__facts-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.analysis-panel__fact-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
}

.analysis-panel__fact-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-primary);
  flex-shrink: 0;
  margin-top: 6px;
}

.analysis-panel__fact-text {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  line-height: 1.6;
}

.analysis-panel__assoc-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.analysis-panel__assoc-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--accent-light);
  border-radius: var(--radius-md);
}

.analysis-panel__assoc-icon {
  color: var(--accent-primary);
  flex-shrink: 0;
  margin-top: 2px;
}

.analysis-panel__assoc-text {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  line-height: 1.6;
}

.analysis-panel__fallback {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.7;
  white-space: pre-wrap;
}
</style>