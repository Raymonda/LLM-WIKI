<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Sparkles, Layers } from 'lucide-vue-next'
import type { ChunkPreviewItem } from '@/stores/ingestProgress'

const props = defineProps<{
  previews: ChunkPreviewItem[]
}>()

const expanded = ref(false)

const visiblePreviews = computed(() => {
  if (expanded.value || props.previews.length <= 3) return props.previews
  return props.previews.slice(0, 3)
})

const hiddenCount = computed(() => Math.max(0, props.previews.length - 3))

const { t } = useI18n()
</script>

<template>
  <div v-if="previews.length > 0" class="entity-discovery-wall">
    <div class="entity-discovery-wall__header">
      <Sparkles :size="13" class="entity-discovery-wall__header-icon" />
      <span class="entity-discovery-wall__header-label">{{ t('ingest.liveEntities') }}</span>
      <span class="entity-discovery-wall__header-live">LIVE</span>
    </div>

    <TransitionGroup
      name="entity-tag"
      tag="div"
      class="entity-discovery-wall__tags"
    >
      <div
        v-for="(pv, pIdx) in visiblePreviews"
        :key="pv.chunkIndex"
        class="entity-discovery-wall__chunk-group"
      >
        <span
          v-for="(entity, eIdx) in pv.entities"
          :key="pv.chunkIndex + '-' + eIdx"
          class="entity-discovery-wall__tag"
          :style="{ transitionDelay: (pIdx * 0.04 + eIdx * 0.03) + 's' }"
        >
          {{ entity }}
        </span>
      </div>
    </TransitionGroup>

    <button
      v-if="hiddenCount > 0"
      class="entity-discovery-wall__expand"
      @click="expanded = !expanded"
    >
      <Layers :size="12" />
      {{ expanded ? t('common.collapse') : t('ingest.moreEntities', [hiddenCount]) }}
    </button>
  </div>
</template>

<style scoped>
.entity-discovery-wall {
  margin-top: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--accent-light);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
}

.entity-discovery-wall__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.entity-discovery-wall__header-icon {
  color: var(--accent-primary);
  flex-shrink: 0;
}

.entity-discovery-wall__header-label {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.entity-discovery-wall__header-live {
  font-size: 9px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: var(--radius-sm);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  letter-spacing: 0.06em;
  animation: entity-live-pulse 1.8s ease-in-out infinite;
}

.entity-discovery-wall__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  min-height: 24px;
}

.entity-discovery-wall__chunk-group {
  display: contents;
}

.entity-discovery-wall__tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: var(--accent-primary);
  background: var(--surface-card);
  border: 1px solid var(--accent-light);
  border-radius: var(--radius-full);
  white-space: nowrap;
}

.entity-discovery-wall__expand {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  margin-top: var(--space-2);
  padding: 0;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  background: none;
  border: none;
  cursor: pointer;
  transition: color 200ms ease;
}

.entity-discovery-wall__expand:hover {
  color: var(--accent-primary);
}

.entity-tag-enter-active {
  transition: opacity 280ms cubic-bezier(0.34, 1.56, 0.64, 1), transform 280ms cubic-bezier(0.34, 1.56, 0.64, 1);
}

.entity-tag-enter-from {
  opacity: 0;
  transform: translateY(-6px) scale(0.92);
}

@keyframes entity-live-pulse {
  0%, 100% { opacity: 0.85; }
  50% { opacity: 1; }
}

@media (prefers-reduced-motion: reduce) {
  .entity-discovery-wall__header-live {
    animation: none;
  }
  .entity-tag-enter-active {
    transition: none;
  }
}
</style>
