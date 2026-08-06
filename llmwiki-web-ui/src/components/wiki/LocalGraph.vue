<script setup lang="ts">
import { ref, onMounted, watch, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { getLocalGraph, type GraphData, type SourceInfo } from '@/api/wiki'
import { Network, ExternalLink, ArrowRight, ArrowLeft, FileText, FileSearch, Download } from 'lucide-vue-next'

const props = defineProps<{
  pageId: number
  sources?: SourceInfo[]
}>()

const emit = defineEmits<{
  'preview-source': [sourceId: number, format: string]
  'download-source': [sourceId: number, sourceName: string]
}>()

const router = useRouter()
const { t } = useI18n()
const loading = ref(true)
const graphData = ref<GraphData | null>(null)

interface Neighbor {
  id: number
  title: string
  category: string
  direction: 'out' | 'in' | 'both'
  degree: number
  healthStatus: string
  lifecycleStatus: string | null
  isConflict: boolean
  isNeedsUpdate: boolean
  isHasProblems: boolean
  isDeprecated: boolean
  isMerged: boolean
}

const neighbors = computed<Neighbor[]>(() => {
  if (!graphData.value) return []
  const { nodes, edges } = graphData.value
  const currentId = props.pageId

  const outIds = new Set<number>()
  const inIds = new Set<number>()
  for (const e of edges) {
    if (e.fromId === currentId) outIds.add(e.toId)
    if (e.toId === currentId) inIds.add(e.fromId)
  }

  const neighborNodes = nodes.filter(n => n.id !== currentId && (outIds.has(n.id) || inIds.has(n.id)))

  return neighborNodes.map(n => {
    const isOut = outIds.has(n.id)
    const isIn = inIds.has(n.id)
    let direction: 'out' | 'in' | 'both' = 'out'
    if (isOut && isIn) direction = 'both'
    else if (isIn) direction = 'in'

    return {
      id: n.id,
      title: n.title,
      category: n.category || t('wiki.uncategorized'),
      direction,
      degree: n.inDegree + n.outDegree,
      healthStatus: n.healthStatus || 'healthy',
      lifecycleStatus: n.lifecycleStatus || null,
      isConflict: n.healthStatus === 'conflict-warning' || n.healthStatus === 'has-problems',
      isNeedsUpdate: n.healthStatus === 'needs-update',
      isHasProblems: n.healthStatus === 'has-problems',
      isDeprecated: n.lifecycleStatus === 'DEPRECATED',
      isMerged: n.lifecycleStatus === 'MERGED'
    }
  }).sort((a, b) => b.degree - a.degree)
})

const outgoing = computed(() => neighbors.value.filter(n => n.direction === 'out' || n.direction === 'both'))
const incoming = computed(() => neighbors.value.filter(n => n.direction === 'in' || n.direction === 'both'))
const hasData = computed(() => neighbors.value.length > 0)
const hasSources = computed(() => (props.sources?.length ?? 0) > 0)
const hasAnyData = computed(() => hasData.value || hasSources.value)

async function loadData() {
  loading.value = true
  try {
    graphData.value = await getLocalGraph(props.pageId)
  } catch {
    graphData.value = null
  } finally {
    loading.value = false
  }
}

function goToPage(id: number) {
  router.push(`/wiki/${id}`)
}

function goToFullGraph() {
  router.push(`/graph?focusPageId=${props.pageId}`)
}

watch(() => props.pageId, async () => {
  graphData.value = null
  await loadData()
})

onMounted(loadData)
</script>

<template>
  <div class="local-graph">
    <div class="local-graph__header">
      <Network :size="13" />
      <span class="local-graph__title">{{ t('wiki.localGraphTitle') }}</span>
      <button
        v-if="hasData"
        class="local-graph__full-link"
        :title="t('wiki.localGraphViewFull')"
        @click="goToFullGraph"
      >
        <ExternalLink :size="12" />
      </button>
    </div>

    <div v-if="loading" class="local-graph__loading">
      <div class="local-graph__spinner"></div>
    </div>
    <div v-else-if="!hasAnyData" class="local-graph__empty">
      <span>{{ t('wiki.localGraphEmpty') }}</span>
    </div>
    <div v-else class="local-graph__body">
      <div v-if="outgoing.length > 0" class="local-graph__group">
        <div class="local-graph__group-label">
          <ArrowRight :size="11" />
          <span>{{ t('wiki.localGraphOutgoing', [outgoing.length]) }}</span>
        </div>
        <button
          v-for="n in outgoing"
          :key="'out-' + n.id"
          class="local-graph__item"
          :class="{
            'local-graph__item--conflict': n.isConflict,
            'local-graph__item--has-problems': n.isHasProblems,
            'local-graph__item--needs-update': n.isNeedsUpdate,
            'local-graph__item--deprecated': n.isDeprecated,
            'local-graph__item--merged': n.isMerged
          }"
          @click="goToPage(n.id)"
        >
          <span class="local-graph__item-title">{{ n.title }}</span>
          <span v-if="n.isDeprecated" class="local-graph__item-tag local-graph__item-tag--deprecated">{{ t('wiki.deprecated') }}</span>
          <span v-else-if="n.isMerged" class="local-graph__item-tag local-graph__item-tag--merged">{{ t('wiki.merged') }}</span>
          <span class="local-graph__item-cat">{{ n.category }}</span>
        </button>
      </div>

      <div v-if="incoming.length > 0" class="local-graph__group">
        <div class="local-graph__group-label">
          <ArrowLeft :size="11" />
          <span>{{ t('wiki.localGraphIncoming', [incoming.length]) }}</span>
        </div>
        <button
          v-for="n in incoming"
          :key="'in-' + n.id"
          class="local-graph__item"
          :class="{
            'local-graph__item--conflict': n.isConflict,
            'local-graph__item--has-problems': n.isHasProblems,
            'local-graph__item--needs-update': n.isNeedsUpdate,
            'local-graph__item--deprecated': n.isDeprecated,
            'local-graph__item--merged': n.isMerged
          }"
          @click="goToPage(n.id)"
        >
          <span class="local-graph__item-title">{{ n.title }}</span>
          <span v-if="n.isDeprecated" class="local-graph__item-tag local-graph__item-tag--deprecated">{{ t('wiki.deprecated') }}</span>
          <span v-else-if="n.isMerged" class="local-graph__item-tag local-graph__item-tag--merged">{{ t('wiki.merged') }}</span>
          <span class="local-graph__item-cat">{{ n.category }}</span>
        </button>
      </div>

      <div v-if="hasSources" class="local-graph__group">
        <div class="local-graph__group-label">
          <FileText :size="11" />
          <span>{{ t('wiki.localGraphSources', [sources!.length]) }}</span>
        </div>
        <div
          v-for="s in sources"
          :key="'src-' + s.id"
          class="local-graph__source"
        >
          <div class="local-graph__source-info">
            <span class="local-graph__source-name" :title="s.name">{{ s.name }}</span>
            <span class="local-graph__source-meta">{{ s.format }} · {{ s.createdAt }}</span>
          </div>
          <div class="local-graph__source-actions">
            <button class="local-graph__source-btn" :title="t('wiki.preview')" @click="emit('preview-source', s.id, s.format)">
              <FileSearch :size="13" />
            </button>
            <button class="local-graph__source-btn" :title="t('wiki.download')" @click="emit('download-source', s.id, s.name)">
              <Download :size="13" />
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.local-graph {
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  overflow: hidden;
}

.local-graph__header {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  color: var(--text-tertiary);
}

.local-graph__title {
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  flex: 1;
}

.local-graph__full-link {
  display: flex;
  align-items: center;
  padding: 2px;
  border: none;
  background: none;
  color: var(--text-tertiary);
  cursor: pointer;
  transition: color var(--transition-fast);
  border-radius: var(--radius-sm);
}

.local-graph__full-link:hover {
  color: var(--accent-primary);
}

.local-graph__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4);
}

.local-graph__spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--border-default);
  border-top-color: var(--accent-primary);
  border-radius: var(--radius-full);
  animation: spin 0.8s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.local-graph__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4);
  color: var(--text-tertiary);
  font-size: var(--font-caption);
}

.local-graph__body {
  display: flex;
  flex-direction: column;
  max-height: 320px;
  overflow-y: auto;
  border-top: 1px solid var(--border-subtle);
}

.local-graph__group {
  display: flex;
  flex-direction: column;
}

.local-graph__group + .local-graph__group {
  border-top: 1px solid var(--border-subtle);
}

.local-graph__source {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-3);
}

.local-graph__source-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.local-graph__source-name {
  font-size: var(--font-caption);
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.local-graph__source-meta {
  font-size: 11px;
  color: var(--text-tertiary);
}

.local-graph__source-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity var(--transition-fast);
}

.local-graph__source:hover .local-graph__source-actions {
  opacity: 1;
}

.local-graph__source-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  background: none;
  color: var(--text-tertiary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: color var(--transition-fast), background var(--transition-fast);
}

.local-graph__source-btn:hover {
  color: var(--accent-primary);
  background: var(--bg-tertiary);
}

.local-graph__group-label {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  font-size: 11px;
  font-weight: var(--weight-medium);
  color: var(--text-tertiary);
  background: var(--bg-secondary);
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.local-graph__item {
  display: flex;
  align-items: baseline;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  border: none;
  background: none;
  width: 100%;
  text-align: left;
  cursor: pointer;
  transition: background var(--transition-fast);
  font-size: var(--font-caption);
  line-height: 1.5;
}

.local-graph__item:hover {
  background: var(--bg-secondary);
}

.local-graph__item--conflict {
  background: var(--error-light);
}

.local-graph__item--conflict:hover {
  background: var(--error-light);
  opacity: 0.85;
}

.local-graph__item--has-problems {
  background: var(--error-light);
  border-left: 2px solid var(--error);
}

.local-graph__item--has-problems:hover {
  background: var(--error-light);
  opacity: 0.85;
}

.local-graph__item--needs-update {
  background: var(--warning-light);
  opacity: 0.8;
  border-left: 2px dashed var(--warning);
}

.local-graph__item--needs-update:hover {
  background: var(--warning-light);
  opacity: 0.9;
}

.local-graph__item--deprecated {
  opacity: 0.5;
  border-left: 2px dotted var(--text-tertiary);
}

.local-graph__item--deprecated:hover {
  opacity: 0.7;
  background: var(--bg-secondary);
}

.local-graph__item--merged {
  opacity: 0.5;
  border-left: 2px dashed var(--text-tertiary);
  text-decoration: line-through;
}

.local-graph__item--merged:hover {
  opacity: 0.7;
  background: var(--bg-secondary);
}

.local-graph__item-tag {
  display: inline-block;
  padding: 0 4px;
  border-radius: var(--radius-sm);
  font-size: 10px;
  font-weight: var(--weight-medium);
  flex-shrink: 0;
}

.local-graph__item-tag--deprecated {
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
}

.local-graph__item-tag--merged {
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
}

.local-graph__item-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
}

.local-graph__item-cat {
  font-size: 11px;
  color: var(--text-tertiary);
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 60px;
}
</style>
