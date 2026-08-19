<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { FactBlockView } from '@/composables/queryStreamLogic'

defineProps<{ blocks: FactBlockView[] }>()

const { t } = useI18n()

function confidenceLabel(confidence: string): string {
  return confidence === 'high'
    ? t('search.confidenceHigh')
    : confidence === 'low'
      ? t('search.confidenceLow')
      : t('search.confidenceMedium')
}

function factRefPath(raw: string): string {
  let p = raw
  if (p.startsWith('wiki/')) p = p.slice(5)
  if (!p.startsWith('pages/')) return p
  if (!p.endsWith('.md')) p += '.md'
  return p
}
</script>

<template>
  <div class="fact-evidence-list">
    <article v-for="block in blocks" :key="block.id" class="fact-evidence-list__card">
      <div v-if="block.kind !== 'text'" class="fact-evidence-list__head">
        <span class="fact-evidence-list__confidence" :class="'fact-evidence-list__confidence--' + block.confidence">
          {{ confidenceLabel(block.confidence) }}
        </span>
      </div>
      <p class="fact-evidence-list__conclusion">{{ block.conclusion }}</p>
      <p v-if="block.evidence" class="fact-evidence-list__evidence">{{ block.evidence }}</p>
      <div v-if="block.refs.length > 0" class="fact-evidence-list__refs">
        <template v-for="ref in block.refs" :key="block.id + '-' + (ref.path || ref.title)">
          <router-link
            v-if="ref.path && factRefPath(ref.path).startsWith('pages/')"
            :to="`/wiki/p/${factRefPath(ref.path)}`"
            class="fact-evidence-list__ref"
          >
            {{ ref.title }}
          </router-link>
        </template>
      </div>
    </article>
  </div>
</template>

<style scoped>
.fact-evidence-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.fact-evidence-list__card {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4);
}
.fact-evidence-list__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}
.fact-evidence-list__confidence {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-pill);
  background: var(--accent-light);
  color: var(--accent-primary);
}
.fact-evidence-list__confidence--high {
  background: var(--success-light);
  color: var(--success);
}
.fact-evidence-list__confidence--low {
  background: var(--warning-light);
  color: var(--warning);
}
.fact-evidence-list__conclusion {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  margin: 0;
}
.fact-evidence-list__evidence {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin: var(--space-2) 0 0;
}
.fact-evidence-list__refs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-2);
}
.fact-evidence-list__ref {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  font-size: var(--font-body-sm);
  color: var(--accent-primary);
  background: var(--accent-light);
  border-radius: var(--radius-sm);
  text-decoration: none;
  transition: color var(--transition-fast), background var(--transition-fast);
}
.fact-evidence-list__ref:hover {
  color: var(--text-on-accent);
  background: var(--accent-primary);
}
</style>
