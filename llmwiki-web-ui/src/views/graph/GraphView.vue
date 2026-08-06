<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import * as echarts from 'echarts'
import {
  getGraph, getGraphOverview, getGraphByCategory, searchGraphNodes,
  type GraphData, type GraphNode, type GraphStats,
  type GraphOverviewData, type CategoryNode
} from '@/api/wiki'
import {
  Network, FileText, AlertTriangle, Award, TrendingUp,
  Search, X, ChevronRight, Filter, RotateCcw,
  AlertCircle, Target, Info, Link, ChevronLeft, Layers,
  EyeOff, GitMerge
} from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const authStore = useAuthStore()

const FULL_LOAD_THRESHOLD = 800
const CATEGORY_RENDER_LIMIT = 600
type ViewMode = 'loading' | 'overview' | 'category' | 'full'

const router = useRouter()
const route = useRoute()
const chartRef = ref<HTMLElement>()
const loading = ref(true)
const loadError = ref(false)
const loadingStep = ref(t('graph.loadingOverview'))
const viewMode = ref<ViewMode>('loading')
const overviewData = ref<GraphOverviewData | null>(null)
const currentCategory = ref<string | null>(null)
const graphData = ref<GraphData | null>(null)
const searchKeyword = ref('')
const selectedCategories = ref<Set<string>>(new Set())
const showOrphansOnly = ref(false)
const showConflictsOnly = ref(false)
const showNeedsUpdateOnly = ref(false)
const showHubsOnly = ref(false)
const showDeprecatedOnly = ref(false)
const showMergedOnly = ref(false)
const focusedNode = ref<GraphNode | null>(null)
const focusedNeighborIds = ref<Set<number>>(new Set())
const chartSettled = ref(false)
const chartRevealed = ref(false)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let searchTimer: ReturnType<typeof setTimeout> | null = null
let settleTimer: ReturnType<typeof setTimeout> | null = null
let revealTimer: ReturnType<typeof setTimeout> | null = null

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

const CATEGORY_COLORS = [
  '#5E6AD2', '#10B981', '#F59E0B', '#EF4444', '#3B82F6',
  '#8B5CF6', '#EC4899', '#14B8A6', '#F97316', '#6366F1',
  '#84CC16', '#06B6D4', '#D946EF', '#E11D48', '#0EA5E9'
]

const categoryColorMap = computed(() => {
  const map = new Map<string, string>()
  const cats = graphData.value?.stats?.categoryCounts || []
  cats.forEach((c, i) => {
    map.set(c.category, CATEGORY_COLORS[i % CATEGORY_COLORS.length])
  })
  return map
})

const filteredNodes = computed(() => {
  if (!graphData.value) return []
  let nodes = graphData.value.nodes

  if (selectedCategories.value.size > 0) {
    nodes = nodes.filter(n => {
      const cat = n.category || t('graph.uncategorized')
      for (const sel of selectedCategories.value) {
        if (cat === sel || cat.startsWith(sel + '/')) return true
      }
      return false
    })
  }
  if (showOrphansOnly.value) nodes = nodes.filter(n => n.inDegree + n.outDegree === 0)
  if (showConflictsOnly.value) nodes = nodes.filter(n => n.healthStatus === 'conflict-warning' || n.healthStatus === 'has-problems')
  if (showNeedsUpdateOnly.value) nodes = nodes.filter(n => n.healthStatus === 'needs-update')
  if (showHubsOnly.value) nodes = nodes.filter(n => n.inDegree + n.outDegree >= 5)
  if (showDeprecatedOnly.value) nodes = nodes.filter(n => n.lifecycleStatus === 'DEPRECATED')
  if (showMergedOnly.value) nodes = nodes.filter(n => n.lifecycleStatus === 'MERGED')
  if (searchKeyword.value.trim()) {
    const kw = searchKeyword.value.trim().toLowerCase()
    nodes = nodes.filter(n => n.title.toLowerCase().includes(kw) || (n.category || '').toLowerCase().includes(kw))
  }
  if (focusedNode.value) {
    nodes = nodes.filter(n => n.id === focusedNode.value!.id || focusedNeighborIds.value.has(n.id))
  }
  return nodes
})

const renderNodes = computed(() => {
  const nodes = filteredNodes.value
  if (nodes.length <= CATEGORY_RENDER_LIMIT) return nodes
  return [...nodes]
    .sort((a, b) => (b.inDegree + b.outDegree) - (a.inDegree + a.outDegree))
    .slice(0, CATEGORY_RENDER_LIMIT)
})

const isTruncated = computed(() => filteredNodes.value.length > CATEGORY_RENDER_LIMIT)

const filteredNodeIds = computed(() => {
  const set = new Set<number>()
  for (const n of renderNodes.value) set.add(n.id)
  return set
})

const filteredEdges = computed(() => {
  if (!graphData.value) return []
  const ids = filteredNodeIds.value
  return graphData.value.edges.filter(e => ids.has(e.fromId) && ids.has(e.toId))
})

const stats = computed<GraphStats | null>(() => {
  if (viewMode.value === 'overview') return overviewData.value?.stats || null
  return graphData.value?.stats || null
})

const pageTypeLabel = (pt: string | null) => {
  if (pt === 'summary') return t('graph.pageTypeSummary')
  if (pt === 'entity') return t('graph.pageTypeEntity')
  if (pt === 'reference') return t('graph.pageTypeReference')
  return t('graph.pageTypeGeneral')
}

function clearFilters() {
  selectedCategories.value = new Set()
  showOrphansOnly.value = false
  showConflictsOnly.value = false
  showNeedsUpdateOnly.value = false
  showHubsOnly.value = false
  showDeprecatedOnly.value = false
  showMergedOnly.value = false
  searchKeyword.value = ''
  focusedNode.value = null
  focusedNeighborIds.value = new Set()
}

function focusOnNode(node: GraphNode) {
  if (focusedNode.value?.id === node.id) {
    focusedNode.value = null
    focusedNeighborIds.value = new Set()
    return
  }
  focusedNode.value = node
  const neighborIds = new Set<number>()
  graphData.value?.edges.forEach(e => {
    if (e.fromId === node.id) neighborIds.add(e.toId)
    if (e.toId === node.id) neighborIds.add(e.fromId)
  })
  focusedNeighborIds.value = neighborIds
}

function navigateToPage(nodeId: number) {
  router.push(`/wiki/${nodeId}`)
}

function buildOverviewOption() {
  if (!overviewData.value) return {}
  const cats = overviewData.value.categories
  const catEdges = overviewData.value.edges

  const maxPages = Math.max(1, ...cats.map(c => c.pageCount))
  const seriesData = cats.map((c, i) => {
    const size = Math.max(20, Math.min(80, 20 + Math.sqrt(c.pageCount / maxPages) * 60))
    const hasConflict = c.conflictCount > 0
    const itemStyle: Record<string, unknown> = {
      color: CATEGORY_COLORS[i % CATEGORY_COLORS.length]
    }
    if (hasConflict) {
      itemStyle.borderColor = cssVar('--error')
      itemStyle.borderWidth = 3
      itemStyle.shadowBlur = 8
      itemStyle.shadowColor = cssVar('--error') + '66'
    }
    return {
      id: c.category,
      name: c.category,
      symbolSize: size,
      category: i,
      value: c.pageCount,
      itemStyle,
      label: { show: true, fontSize: 12, color: cssVar('--text-primary') },
      _cat: c
    }
  })

  const edgeData = catEdges
    .filter(e => cats.some(c => c.category === e.fromCategory) && cats.some(c => c.category === e.toCategory))
    .map(e => ({
      source: e.fromCategory,
      target: e.toCategory,
      lineStyle: {
        color: cssVar('--border-default'),
        opacity: Math.min(0.8, 0.2 + e.linkCount / 50),
        width: Math.max(1, Math.min(4, e.linkCount / 10)),
        curveness: 0.1
      }
    }))

  const categoryData = cats.map((c, i) => ({
    name: c.category,
    itemStyle: { color: CATEGORY_COLORS[i % CATEGORY_COLORS.length] }
  }))

  return {
    tooltip: {
      trigger: 'item' as const,
      backgroundColor: cssVar('--surface-card') + 'F5',
      borderColor: cssVar('--border-default'),
      borderWidth: 1,
      textStyle: { color: cssVar('--text-primary'), fontSize: 13 },
      formatter: (params: Record<string, unknown>) => {
        if (params.dataType === 'node') {
          const raw = (params.data as { _cat: CategoryNode })._cat
          if (!raw) return ''
          return `<div style="max-width:280px">
            <div style="font-weight:600;font-size:14px;margin-bottom:4px">${raw.category}</div>
            <div style="font-size:12px;color:${cssVar('--text-tertiary')};margin-bottom:6px">${t('graph.tooltipPages', [raw.pageCount])}</div>
            <div style="font-size:12px;color:${cssVar('--text-tertiary')}">
              ${t('graph.tooltipOrphanHub', [raw.orphanCount, raw.hubCount])}
            </div>
            <div style="font-size:12px;color:${cssVar('--text-tertiary')}">
              ${t('graph.tooltipProblemsUpdate', [raw.conflictCount, raw.needsUpdateCount || 0])}
            </div>
            <div style="font-size:12px;color:${cssVar('--text-tertiary')}">${t('graph.tooltipAvgDegree', [raw.avgDegree.toFixed(1)])}</div>
            <div style="font-size:11px;color:${cssVar('--text-tertiary')};margin-top:4px">${t('graph.tooltipClickExpand')}</div>
          </div>`
        }
        if (params.dataType === 'edge') {
          return `<div><b>${t('graph.tooltipCrossCategory')}</b>: ${((params.data as Record<string, unknown>).lineStyle as Record<string, unknown>)?.width || 1}</div>`
        }
        return ''
      }
    },
    legend: { show: false },
    animation: false,
    animationDuration: 0,
    animationDurationUpdate: 0,
    series: [{
      type: 'graph',
      layout: 'circular',
      data: seriesData,
      links: edgeData,
      categories: categoryData,
      roam: true,
      draggable: false,
      layoutAnimation: false,
      emphasis: {
        focus: 'adjacency' as const,
        lineStyle: { width: 3, opacity: 0.9 },
        itemStyle: { borderWidth: 3 }
      },
      label: {
        position: 'inside' as const,
        formatter: (p: { name: string }) => {
          const name = p.name || ''
          return name.length > 6 ? name.slice(0, 5) + '…' : name
        }
      },
      lineStyle: { opacity: 0.4, width: 1.5, curveness: 0.1 },
      zoom: 1
    }]
  }
}

function buildChartOption() {
  if (viewMode.value === 'overview') return buildOverviewOption()

  const nodes = renderNodes.value
  const edges = filteredEdges.value
  if (!graphData.value) return {}

  const maxDegree = Math.max(1, ...nodes.map(n => n.inDegree + n.outDegree))

  const categories = [...new Set(nodes.map(n => n.category || t('graph.uncategorized')))]
  const categoryIndex = new Map<string, number>()
  categories.forEach((cat, i) => categoryIndex.set(cat, i))
  const seriesData = nodes.map(n => {
    const degree = n.inDegree + n.outDegree
    const isOrphan = degree === 0
    const isHub = degree >= 5
    const isProblematic = n.healthStatus === 'conflict-warning' || n.healthStatus === 'has-problems'
    const isNeedsUpdate = n.healthStatus === 'needs-update'
    const isDeprecated = n.lifecycleStatus === 'DEPRECATED'
    const isMerged = n.lifecycleStatus === 'MERGED'
    const cat = n.category || t('graph.uncategorized')
    const catIdx = categoryIndex.get(cat) ?? 0
    const baseColor = categoryColorMap.value.get(cat) || cssVar('--text-tertiary')

    let symbolSize = Math.max(12, Math.min(50, 12 + (degree / maxDegree) * 38))
    if (isOrphan) symbolSize = 10
    if (isDeprecated || isMerged) symbolSize = Math.max(8, symbolSize * 0.7)

    const itemStyle: Record<string, unknown> = { color: baseColor }
    if (isDeprecated) {
      itemStyle.opacity = 0.3
      itemStyle.color = cssVar('--text-tertiary')
    }
    if (isMerged) {
      itemStyle.opacity = 0.35
      itemStyle.color = cssVar('--bg-tertiary')
    }
    if (isOrphan && !isDeprecated && !isMerged) {
      itemStyle.color = cssVar('--bg-tertiary')
      itemStyle.borderColor = cssVar('--text-tertiary')
      itemStyle.borderWidth = 1.5
      itemStyle.borderType = 'dashed'
      itemStyle.opacity = 0.6
    }
    if (isNeedsUpdate && !isOrphan && !isDeprecated && !isMerged) {
      itemStyle.opacity = 0.65
      itemStyle.borderColor = cssVar('--warning')
      itemStyle.borderWidth = 1.5
      itemStyle.borderType = 'dashed'
    }
    if (isProblematic && !isDeprecated && !isMerged) {
      itemStyle.borderColor = cssVar('--error')
      itemStyle.borderWidth = 2
      itemStyle.shadowBlur = 8
      itemStyle.shadowColor = cssVar('--error') + '60'
    }
    if (isHub && !isProblematic && !isDeprecated && !isMerged) {
      itemStyle.borderWidth = 2
      itemStyle.borderColor = cssVar('--surface-card')
    }
    if (focusedNode.value && n.id === focusedNode.value.id) {
      itemStyle.borderColor = cssVar('--warning')
      itemStyle.borderWidth = 3
      itemStyle.shadowBlur = 12
      itemStyle.shadowColor = cssVar('--warning') + '60'
    }

    const showLabel = isHub || isProblematic || isDeprecated || isMerged || (focusedNode.value && n.id === focusedNode.value.id)
    const namePrefix = isDeprecated ? '⚠ ' : isMerged ? '↪ ' : ''

    return {
      id: String(n.id),
      name: namePrefix + n.title,
      symbolSize,
      category: catIdx,
      value: degree,
      itemStyle,
      label: {
        show: showLabel,
        fontSize: isHub ? 12 : 11,
        color: (isDeprecated || isMerged) ? cssVar('--text-tertiary') : cssVar('--text-primary')
      },
      _raw: n
    }
  })

  const edgeData = edges.map(e => ({
    source: String(e.fromId),
    target: String(e.toId),
    lineStyle: {
      color: cssVar('--border-default'),
      opacity: 0.4,
      curveness: 0.05
    },
    _linkType: e.linkType,
    _linkContext: e.linkContext
  }))

  const categoryData = categories.map((cat, i) => ({
    name: cat,
    itemStyle: { color: categoryColorMap.value.get(cat) || CATEGORY_COLORS[i % CATEGORY_COLORS.length] }
  }))

  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: cssVar('--surface-card') + 'F5',
      borderColor: cssVar('--border-default'),
      borderWidth: 1,
      textStyle: { color: cssVar('--text-primary'), fontSize: 13 },
      formatter: (params: Record<string, unknown>) => {
        if (params.dataType === 'node') {
          const raw = (params.data as { _raw: GraphNode })._raw
          if (!raw) return ''
          const degree = raw.inDegree + raw.outDegree
          const badgeEntries: { key: string; label: string }[] = []
          if (raw.lifecycleStatus === 'DEPRECATED') badgeEntries.push({ key: 'deprecated', label: t('graph.badgeDeprecated') })
          if (raw.lifecycleStatus === 'MERGED') badgeEntries.push({ key: 'merged', label: t('graph.badgeMerged') })
          if (degree >= 5) badgeEntries.push({ key: 'hub', label: t('graph.badgeHub') })
          if (degree === 0) badgeEntries.push({ key: 'orphan', label: t('graph.badgeOrphan') })
          if (raw.healthStatus === 'conflict-warning' || raw.healthStatus === 'has-problems') badgeEntries.push({ key: 'problems', label: t('graph.badgeProblems') })
          if (raw.healthStatus === 'needs-update') badgeEntries.push({ key: 'needsUpdate', label: t('graph.badgeNeedsUpdate') })
          const badgeColor = (k: string) => k === 'problems' ? cssVar('--error-light') : k === 'needsUpdate' ? cssVar('--warning-light') : k === 'deprecated' ? cssVar('--bg-tertiary') : k === 'merged' ? cssVar('--bg-tertiary') : cssVar('--bg-tertiary')
          const badgeTextColor = (k: string) => k === 'problems' ? cssVar('--error') : k === 'needsUpdate' ? cssVar('--warning') : k === 'deprecated' ? cssVar('--text-tertiary') : k === 'merged' ? cssVar('--text-tertiary') : cssVar('--text-secondary')
          const badgeHtml = badgeEntries.length
            ? `<div style="margin-bottom:4px">${badgeEntries.map(e => `<span style="display:inline-block;padding:1px 6px;margin-right:4px;border-radius:3px;font-size:11px;background:${badgeColor(e.key)};color:${badgeTextColor(e.key)}">${e.label}</span>`).join('')}</div>`
            : ''
          return `<div style="max-width:260px">
            <div style="font-weight:600;font-size:14px;margin-bottom:4px">${raw.title}</div>
            <div style="font-size:12px;color:${cssVar('--text-tertiary')};margin-bottom:6px">${raw.category || t('graph.uncategorized')} · ${pageTypeLabel(raw.pageType)}</div>
            ${badgeHtml}
            <div style="font-size:12px;color:${cssVar('--text-tertiary')}">${t('graph.tooltipNodeDegrees', [raw.inDegree, raw.outDegree, raw.sourceCount])}</div>
            <div style="font-size:11px;color:${cssVar('--text-tertiary')};margin-top:4px">${t('graph.tooltipClickHint')}</div>
          </div>`
        }
        if (params.dataType === 'edge') {
          const lt = (params.data as { _linkType: string })._linkType
          const lc = (params.data as { _linkContext: string })._linkContext
          return `<div><b>${t('graph.tooltipLinkRelation')}</b>: ${lt || 'related'}${lc ? `<br/><span style="font-size:12px;color:${cssVar('--text-tertiary')}">${lc}</span>` : ''}</div>`
        }
        return ''
      }
    },
    legend: { show: false },
    animation: false,
    animationDuration: 0,
    animationDurationUpdate: 0,
    series: [{
      type: 'graph',
      layout: 'force',
      data: seriesData,
      links: edgeData,
      categories: categoryData,
      roam: true,
      draggable: false,
      layoutAnimation: false,
      force: {
        repulsion: 250,
        gravity: 0.5,
        edgeLength: [40, 120],
        friction: 1,
        initLayout: 'circular'
      },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 6],
      emphasis: {
        focus: 'adjacency',
        lineStyle: { width: 2, opacity: 0.8 },
        itemStyle: { borderWidth: 3 }
      },
      label: {
        position: 'right',
        formatter: (p: { name: string }) => {
          const name = p.name || ''
          return name.length > 8 ? name.slice(0, 7) + '…' : name
        }
      },
      lineStyle: { opacity: 0.4, width: 1.5, curveness: 0.05 },
      zoom: 1
    }]
  }
}

function ensureChart() {
  if (chart || !chartRef.value) return chart
  chart = echarts.init(chartRef.value)
  chart.on('click', handleChartClick as never)
  chart.on('dblclick', handleChartDblClick as never)
  chart.on('finished', () => revealChart())
  if (!resizeObserver) {
    resizeObserver = new ResizeObserver(() => { chart?.resize() })
    resizeObserver.observe(chartRef.value)
  }
  return chart
}

function renderChart() {
  ensureChart()
  if (!chart || !chartRef.value) return
  chartSettled.value = false
  chartRevealed.value = false
  chart.setOption(buildChartOption() as echarts.EChartsOption, false)
  scheduleReveal()
}

function handleChartClick(params: { dataType: string; data: { id: string; _raw?: GraphNode; _cat?: CategoryNode } }) {
  if (viewMode.value === 'overview' && params.dataType === 'node' && params.data._cat) {
    drillIntoCategory(params.data._cat.category)
    return
  }
  if (params.dataType === 'node' && params.data._raw) {
    focusOnNode(params.data._raw)
  }
}

function handleChartDblClick(params: { dataType: string; data: { _raw?: GraphNode } }) {
  if (params.dataType === 'node' && params.data._raw) {
    navigateToPage(params.data._raw.id)
  }
}

function handleResize() {
  chart?.resize()
}

async function loadData() {
  loading.value = true
  loadError.value = false
  loadingStep.value = t('graph.loadingOverview')
  try {
    const overview = await getGraphOverview()
    overviewData.value = overview

    if (overview.stats.totalNodes <= FULL_LOAD_THRESHOLD) {
      loadingStep.value = t('graph.loadingFull')
      const fullData = await getGraph()
      if (fullData.requiresOverview) {
        viewMode.value = 'overview'
        graphData.value = null
      } else {
        graphData.value = fullData
        viewMode.value = 'full'
        const focusId = route.query.focusPageId
        if (focusId && graphData.value) {
          const targetId = Number(focusId)
          const targetNode = graphData.value.nodes.find(n => n.id === targetId)
          if (targetNode) nextTick(() => focusOnNode(targetNode))
        }
      }
    } else {
      viewMode.value = 'overview'
      const focusId = route.query.focusPageId
      if (focusId) {
        loadingStep.value = t('graph.loadingLocate')
        const targetId = Number(focusId)
        const searchData = await searchGraphNodes(String(targetId), 1)
        if (searchData.nodes.length > 0) {
          await drillIntoCategory(searchData.nodes[0].category || t('graph.uncategorized'))
        }
      }
    }
  } catch {
    graphData.value = null
    overviewData.value = null
    viewMode.value = 'full'
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function retryLoad() {
  await loadData()
  if (!loadError.value) {
    await nextTick()
    renderChart()
  }
}

async function drillIntoCategory(category: string) {
  currentCategory.value = category
  viewMode.value = 'category'
  focusedNode.value = null
  focusedNeighborIds.value = new Set()
  selectedCategories.value = new Set()
  showOrphansOnly.value = false
  showConflictsOnly.value = false
  showNeedsUpdateOnly.value = false
  showHubsOnly.value = false
  showDeprecatedOnly.value = false
  showMergedOnly.value = false
  searchKeyword.value = ''
  loading.value = true
  loadError.value = false
  loadingStep.value = t('graph.loadingCategory', [category])
  try {
    graphData.value = await getGraphByCategory(category)
    nextTick(renderChart)
  } catch {
    graphData.value = null
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function backToOverview() {
  currentCategory.value = null
  viewMode.value = 'overview'
  graphData.value = null
  focusedNode.value = null
  focusedNeighborIds.value = new Set()
  clearFilters()
  nextTick(renderChart)
}

watch(searchKeyword, (val) => {
  if (searchTimer) clearTimeout(searchTimer)
  if (viewMode.value === 'overview' && val.trim()) {
    searchTimer = setTimeout(async () => {
      try {
        const result = await searchGraphNodes(val.trim(), 50)
        if (result.nodes.length > 0) {
          const catSet = new Set(result.nodes.map(n => n.category || t('graph.uncategorized')))
          if (catSet.size === 1) {
            drillIntoCategory([...catSet][0])
          }
        }
      } catch { }
    }, 800)
  }
})

watch([renderNodes, filteredEdges], () => {
  if (viewMode.value !== 'overview') nextTick(renderChart)
})

function revealChart() {
  if (!chartSettled.value) {
    chartSettled.value = true
    revealTimer = setTimeout(() => {
      chartRevealed.value = true
      nextTick(() => chart?.resize())
    }, 200)
  }
}

function scheduleReveal() {
  if (settleTimer) clearTimeout(settleTimer)
  const nodeCount = renderNodes.value.length
  const settleMs = nodeCount <= 50 ? 200 : nodeCount <= 200 ? 400 : 600
  settleTimer = setTimeout(revealChart, settleMs)
}

onMounted(async () => {
  await loadData()
  await nextTick()
  ensureChart()
  renderChart()
  window.addEventListener('resize', handleResize)
})

async function onGraphScopeChange(newScopeId: number) {
  authStore.switchScope(newScopeId)
  loading.value = true
  viewMode.value = 'loading'
  graphData.value = null
  overviewData.value = null
  currentCategory.value = null
  focusedNode.value = null
  await loadData()
  await nextTick()
  renderChart()
}

onUnmounted(() => {
  if (searchTimer) clearTimeout(searchTimer)
  if (settleTimer) clearTimeout(settleTimer)
  if (revealTimer) clearTimeout(revealTimer)
  resizeObserver?.disconnect()
  chart?.dispose()
  window.removeEventListener('resize', handleResize)
})
</script>

<template>
  <div class="graph-page">
    <header class="graph-header">
      <div class="graph-header__title">
        <Network :size="22" />
        <template v-if="viewMode === 'category' && currentCategory">
          <button class="breadcrumb-btn" @click="backToOverview">
            <ChevronLeft :size="16" />
            {{ t('graph.backToOverview') }}
          </button>
          <ChevronRight :size="14" class="breadcrumb-sep" />
          <h1>{{ currentCategory }}</h1>
        </template>
        <template v-else>
          <h1>{{ t('graph.title') }}</h1>
          <span v-if="viewMode === 'overview' && stats" class="graph-header__badge">
            <Layers :size="12" />
            {{ t('graph.overviewBadge') }}
          </span>
        </template>
        <select
          v-if="authStore.scopes.length > 1"
          class="graph-header__scope-select"
          :value="authStore.scopeId"
          @change="(e: Event) => onGraphScopeChange(Number((e.target as HTMLSelectElement).value))"
        >
          <option v-for="s in authStore.scopes" :key="s.scopeId" :value="s.scopeId">{{ s.scopeName }}</option>
        </select>
      </div>
      <div v-if="stats" class="graph-header__stats">
        <div class="stat-chip stat-chip--primary">
          <FileText :size="14" />
          <span>{{ t('graph.statPages', [stats.totalNodes]) }}</span>
        </div>
        <div class="stat-chip">
          <Link :size="14" />
          <span>{{ t('graph.statLinks', [stats.totalEdges]) }}</span>
        </div>
        <div class="stat-chip stat-chip--warning" v-if="stats.orphanCount > 0">
          <AlertCircle :size="14" />
          <span>{{ t('graph.statOrphan', [stats.orphanCount]) }}</span>
        </div>
        <div class="stat-chip stat-chip--success" v-if="stats.hubCount > 0">
          <Award :size="14" />
          <span>{{ t('graph.statHub', [stats.hubCount]) }}</span>
        </div>
        <div class="stat-chip stat-chip--danger" v-if="stats.conflictCount > 0">
          <AlertTriangle :size="14" />
          <span>{{ t('graph.statConflict', [stats.conflictCount]) }}</span>
        </div>
        <div class="stat-chip stat-chip--caution" v-if="stats.needsUpdateCount > 0">
          <AlertCircle :size="14" />
          <span>{{ t('graph.statNeedsUpdate', [stats.needsUpdateCount]) }}</span>
        </div>
        <div class="stat-chip stat-chip--muted">
          <EyeOff :size="14" />
          <span>{{ t('graph.statDeprecated', [stats.deprecatedCount]) }}</span>
        </div>
        <div class="stat-chip stat-chip--muted" v-if="stats.mergedCount > 0">
          <GitMerge :size="14" />
          <span>{{ t('graph.statMerged', [stats.mergedCount]) }}</span>
        </div>
        <div class="stat-chip">
          <TrendingUp :size="14" />
          <span>{{ t('graph.statDensity', [stats.linkDensity.toFixed(1)]) }}</span>
        </div>
      </div>
    </header>

    <div class="graph-body">
      <aside class="graph-sidebar">
        <div class="graph-sidebar__search">
          <Search :size="15" class="graph-sidebar__search-icon" />
          <input
            v-model="searchKeyword"
            type="text"
            :placeholder="viewMode === 'overview' ? t('graph.searchCategoryPlaceholder') : t('graph.searchPagePlaceholder')"
            class="graph-sidebar__search-input"
          />
          <button v-if="searchKeyword" class="graph-sidebar__search-clear" @click="searchKeyword = ''">
            <X :size="14" />
          </button>
        </div>

        <template v-if="viewMode === 'overview' && overviewData">
          <div class="graph-sidebar__section">
            <div class="graph-sidebar__section-title">
              <Layers :size="14" />
              {{ t('graph.categoryList') }}
            </div>
            <div class="category-list">
              <button
                v-for="(cat, i) in overviewData.categories"
                :key="cat.category"
                class="category-item"
                @click="drillIntoCategory(cat.category)"
              >
                <span class="category-dot" :style="{ background: CATEGORY_COLORS[i % CATEGORY_COLORS.length] }"></span>
                <span class="category-name">{{ cat.category }}</span>
                <span class="category-count">{{ cat.pageCount }}</span>
              </button>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="graph-sidebar__section">
            <div class="graph-sidebar__section-title">
              <Filter :size="14" />
              {{ t('graph.quickFilters') }}
            </div>
            <button
              class="filter-btn"
              :class="{ 'filter-btn--active': showOrphansOnly }"
              @click="showOrphansOnly = !showOrphansOnly"
            >
              <AlertCircle :size="14" />
              <span>{{ t('graph.filterOrphan') }}</span>
              <span v-if="graphData?.stats" class="filter-btn__count">{{ graphData.stats.orphanCount }}</span>
            </button>
            <button
              class="filter-btn"
              :class="{ 'filter-btn--active': showConflictsOnly }"
              @click="showConflictsOnly = !showConflictsOnly"
            >
              <AlertTriangle :size="14" />
              <span>{{ t('graph.filterProblems') }}</span>
              <span v-if="graphData?.stats" class="filter-btn__count">{{ graphData.stats.conflictCount }}</span>
            </button>
            <button
              class="filter-btn"
              :class="{ 'filter-btn--active': showNeedsUpdateOnly }"
              @click="showNeedsUpdateOnly = !showNeedsUpdateOnly"
            >
              <AlertCircle :size="14" />
              <span>{{ t('graph.filterNeedsUpdate') }}</span>
              <span v-if="graphData?.stats" class="filter-btn__count">{{ graphData.stats.needsUpdateCount }}</span>
            </button>
            <button
              class="filter-btn"
              :class="{ 'filter-btn--active': showHubsOnly }"
              @click="showHubsOnly = !showHubsOnly"
            >
              <Target :size="14" />
              <span>{{ t('graph.filterHub') }}</span>
              <span v-if="graphData?.stats" class="filter-btn__count">{{ graphData.stats.hubCount }}</span>
            </button>
            <button
              class="filter-btn"
              :class="{ 'filter-btn--active': showDeprecatedOnly }"
              @click="showDeprecatedOnly = !showDeprecatedOnly"
            >
              <EyeOff :size="14" />
              <span>{{ t('graph.filterDeprecated') }}</span>
              <span v-if="graphData?.stats" class="filter-btn__count">{{ graphData.stats.deprecatedCount }}</span>
            </button>
            <button
              class="filter-btn"
              :class="{ 'filter-btn--active': showMergedOnly }"
              @click="showMergedOnly = !showMergedOnly"
            >
              <GitMerge :size="14" />
              <span>{{ t('graph.filterMerged') }}</span>
              <span v-if="graphData?.stats" class="filter-btn__count">{{ graphData.stats.mergedCount }}</span>
            </button>
          </div>

          <div class="graph-sidebar__section">
            <button class="reset-btn" @click="clearFilters">
              <RotateCcw :size="14" />
              {{ t('graph.resetFilters') }}
            </button>
          </div>
        </template>

        <div v-if="focusedNode" class="graph-sidebar__section graph-sidebar__focus">
          <div class="graph-sidebar__section-title">
            <Info :size="14" />
            {{ t('graph.focusNode') }}
          </div>
          <div class="focus-card">
            <div class="focus-card__title">{{ focusedNode.title }}</div>
            <div class="focus-card__meta">
              {{ focusedNode.category || t('graph.uncategorized') }} · {{ pageTypeLabel(focusedNode.pageType) }}
            </div>
            <div class="focus-card__stats">
              {{ t('graph.focusStats', [focusedNode.inDegree, focusedNode.outDegree, focusedNeighborIds.size]) }}
            </div>
            <div class="focus-card__actions">
              <button class="focus-card__btn" @click="navigateToPage(focusedNode.id)">
                <ChevronRight :size="14" />
                {{ t('graph.navigateToPage') }}
              </button>
              <button class="focus-card__btn" @click="focusedNode = null; focusedNeighborIds = new Set()">
                <X :size="14" />
                {{ t('graph.cancelFocus') }}
              </button>
            </div>
          </div>
        </div>

        <div class="graph-sidebar__legend">
          <div class="graph-sidebar__section-title">{{ t('graph.legend') }}</div>
          <div class="legend-items">
            <div class="legend-item">
              <span class="legend-dot legend-dot--hub"></span>
              {{ t('graph.legendHub') }}
            </div>
            <div class="legend-item">
              <span class="legend-dot legend-dot--orphan"></span>
              {{ t('graph.legendOrphan') }}
            </div>
            <div class="legend-item">
              <span class="legend-dot legend-dot--has-problems"></span>
              {{ t('graph.legendProblems') }}
            </div>
            <div class="legend-item">
              <span class="legend-dot legend-dot--needs-update"></span>
              {{ t('graph.legendNeedsUpdate') }}
            </div>
            <div class="legend-item">
              <span class="legend-dot legend-dot--deprecated"></span>
              {{ t('graph.legendDeprecated') }}
            </div>
            <div class="legend-item">
              <span class="legend-dot legend-dot--merged"></span>
              {{ t('graph.legendMerged') }}
            </div>
            <div class="legend-item">
              <span class="legend-dot legend-dot--normal"></span>
              {{ t('graph.legendNormal') }}
            </div>
          </div>
          <div class="legend-hint">{{ t('graph.legendHint') }}</div>
        </div>
      </aside>

      <div class="graph-main">
        <div v-if="loading" class="graph-skeleton">
          <div class="graph-skeleton__sidebar">
            <div class="skel-bar"></div>
            <div class="skel-section-title"></div>
            <div class="skel-filter" v-for="i in 4" :key="i"></div>
            <div class="skel-section-title" style="margin-top: 16px"></div>
            <div class="skel-legend" v-for="i in 3" :key="'l'+i"></div>
          </div>
          <div class="graph-skeleton__chart">
            <div class="graph-skeleton__pulse">
              <div class="graph-loading__spinner"></div>
              <p>{{ loadingStep }}</p>
            </div>
          </div>
        </div>
        <div v-else-if="loadError" class="graph-error">
          <AlertCircle :size="48" />
          <h3>{{ t('graph.loadFailed') }}</h3>
          <p>{{ t('graph.loadFailedDesc') }}</p>
          <button class="graph-error__retry" @click="retryLoad">
            <RotateCcw :size="15" />
            {{ t('common.retry') }}
          </button>
        </div>
        <div v-else-if="viewMode !== 'overview' && (!graphData || graphData.nodes.length === 0)" class="graph-empty">
          <Network :size="48" />
          <h3>{{ t('graph.graphEmpty') }}</h3>
          <p>{{ t('graph.graphEmptyDesc') }}</p>
        </div>
        <div v-else class="graph-chart-wrap">
          <div v-if="!chartRevealed" class="graph-loading graph-loading--overlay" :class="{ 'fade-out': chartSettled }">
            <div class="graph-loading__spinner"></div>
            <p class="graph-loading__text">{{ t('graph.loadingLayout') }}</p>
          </div>
          <div ref="chartRef" class="graph-chart" :class="{ 'graph-chart--settled': chartRevealed }"></div>
        </div>

        <div v-if="!loading && viewMode === 'overview' && overviewData" class="graph-chart__info">
          {{ t('graph.infoOverview', [overviewData.categories.length, stats?.totalNodes || 0, stats?.totalEdges || 0]) }}
        </div>
        <div v-else-if="!loading && graphData && graphData.nodes.length > 0" class="graph-chart__info">
          {{ t('graph.infoCategory', [renderNodes.length, graphData.nodes.length, filteredEdges.length, graphData.edges.length]) }}
          <template v-if="isTruncated"> · {{ t('graph.truncatedHint', [CATEGORY_RENDER_LIMIT]) }}</template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.graph-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-primary);
  overflow: hidden;
}

.graph-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-5);
  border-bottom: 1px solid var(--border-default);
  background: var(--surface-card);
  flex-shrink: 0;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.graph-header__title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-primary);
}

.graph-header__title h1 {
  font-size: var(--font-h3);
  font-weight: var(--weight-semibold);
  margin: 0;
}

.breadcrumb-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 2px 8px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
  background: var(--bg-secondary);
  color: var(--accent-primary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.breadcrumb-btn:hover { background: var(--accent-light); border-color: var(--accent-primary); }

.breadcrumb-sep { color: var(--text-tertiary); flex-shrink: 0; }

.graph-header__badge {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--accent-light);
  color: var(--accent-primary);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.graph-header__scope-select {
  margin-left: auto;
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  outline: none;
  transition: border-color var(--transition-fast);
}

.graph-header__scope-select:focus {
  border-color: var(--accent-primary);
}

.graph-header__scope-select:hover {
  border-color: var(--accent-primary);
}

.graph-header__stats {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.stat-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  border: 1px solid var(--border-subtle);
  opacity: 0;
  transform: translateY(-6px);
  animation: statIn 280ms ease-out forwards;
}

@keyframes statIn {
  to { opacity: 1; transform: translateY(0); }
}

.stat-chip:nth-child(1) { animation-delay: 0ms; }
.stat-chip:nth-child(2) { animation-delay: 30ms; }
.stat-chip:nth-child(3) { animation-delay: 60ms; }
.stat-chip:nth-child(4) { animation-delay: 90ms; }
.stat-chip:nth-child(5) { animation-delay: 120ms; }
.stat-chip:nth-child(6) { animation-delay: 150ms; }
.stat-chip:nth-child(7) { animation-delay: 180ms; }
.stat-chip:nth-child(8) { animation-delay: 210ms; }
.stat-chip:nth-child(9) { animation-delay: 240ms; }

.stat-chip--primary { background: var(--accent-light); color: var(--accent-primary); border-color: transparent; }
.stat-chip--warning { background: var(--warning-light); color: var(--warning); border-color: transparent; }
.stat-chip--success { background: var(--success-light); color: var(--success); border-color: transparent; }
.stat-chip--danger { background: var(--error-light); color: var(--error); border-color: transparent; }
.stat-chip--caution { background: var(--warning-light); color: var(--warning); border-color: transparent; }
.stat-chip--muted { background: var(--bg-tertiary); color: var(--text-tertiary); border-color: transparent; }

.graph-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.graph-sidebar {
  width: 260px;
  border-right: 1px solid var(--border-default);
  background: var(--bg-secondary);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  flex-shrink: 0;
  padding: var(--space-3);
  gap: var(--space-3);
  opacity: 0;
  transform: translateX(-12px);
  animation: sidebarIn 350ms ease-out 100ms forwards;
}

@keyframes sidebarIn {
  to { opacity: 1; transform: translateX(0); }
}

.graph-sidebar__search {
  position: relative;
  display: flex;
  align-items: center;
}

.graph-sidebar__search-icon {
  position: absolute;
  left: 10px;
  color: var(--text-tertiary);
  pointer-events: none;
}

.graph-sidebar__search-input {
  width: 100%;
  padding: 7px 30px 7px 32px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--bg-primary);
  color: var(--text-primary);
  font-size: var(--font-body-sm);
  outline: none;
  transition: border-color var(--transition-fast);
}

.graph-sidebar__search-input:focus {
  border-color: var(--accent-primary);
}

.graph-sidebar__search-clear {
  position: absolute;
  right: 6px;
  background: none;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  padding: 2px;
  display: flex;
}

.graph-sidebar__section {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.graph-sidebar__section-title {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: var(--space-1);
}

.filter-btn {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 6px 10px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--bg-primary);
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
  width: 100%;
  text-align: left;
}

.filter-btn:hover { border-color: var(--accent-primary); color: var(--accent-primary); }
.filter-btn--active { background: var(--accent-light); border-color: var(--accent-primary); color: var(--accent-primary); }

.filter-btn__count {
  margin-left: auto;
  font-size: var(--font-caption);
  background: var(--bg-tertiary);
  padding: 1px 6px;
  border-radius: var(--radius-pill);
}

.filter-btn--active .filter-btn__count { background: var(--accent-primary); color: var(--text-on-accent); }

.category-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 200px;
  overflow-y: auto;
}

.category-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 5px 8px;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: background var(--transition-fast);
  width: 100%;
  text-align: left;
}

.category-item:hover { background: var(--bg-tertiary); }
.category-item--active { background: var(--accent-light); color: var(--accent-primary); font-weight: var(--weight-medium); }

.category-dot {
  width: 10px;
  height: 10px;
  border-radius: var(--radius-full);
  flex-shrink: 0;
}

.category-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.category-count { font-size: var(--font-caption); color: var(--text-tertiary); flex-shrink: 0; }

.reset-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: 6px;
  border: 1px dashed var(--border-default);
  border-radius: var(--radius-md);
  background: none;
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
  width: 100%;
}

.reset-btn:hover { border-color: var(--accent-primary); color: var(--accent-primary); }

.graph-sidebar__focus { border-top: 2px solid var(--accent-primary); padding-top: var(--space-3); }

.focus-card {
  background: var(--bg-primary);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.focus-card__title { font-size: var(--font-body); font-weight: var(--weight-semibold); color: var(--text-primary); }
.focus-card__meta { font-size: var(--font-caption); color: var(--text-tertiary); }
.focus-card__stats { font-size: var(--font-caption); color: var(--text-secondary); }

.focus-card__actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-1);
}

.focus-card__btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  padding: 5px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  color: var(--text-secondary);
  font-size: var(--font-caption);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.focus-card__btn:hover { border-color: var(--accent-primary); color: var(--accent-primary); }
.focus-card__btn:first-child { background: var(--accent-primary); color: var(--text-on-accent); border-color: var(--accent-primary); }
.focus-card__btn:first-child:hover { opacity: 0.9; }

.graph-sidebar__legend {
  margin-top: auto;
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}

.legend-items {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-caption);
  color: var(--text-secondary);
}

.legend-dot {
  width: 12px;
  height: 12px;
  border-radius: var(--radius-full);
  flex-shrink: 0;
}

.legend-dot--hub { background: var(--accent-primary); box-shadow: 0 0 6px var(--accent-primary); border: 2px solid var(--surface-card); }
.legend-dot--orphan { background: var(--bg-tertiary); border: 2px dashed var(--text-tertiary); }
.legend-dot--conflict { background: var(--accent-primary); border: 3px solid var(--error); }
.legend-dot--has-problems { background: var(--accent-primary); box-shadow: 0 0 8px var(--error); border: 2px solid var(--error); }
.legend-dot--needs-update { background: var(--accent-primary); opacity: 0.6; border: 2px dashed var(--warning); }
.legend-dot--deprecated { background: var(--text-tertiary); opacity: 0.35; border: 2px dotted var(--text-tertiary); }
.legend-dot--merged { background: var(--bg-tertiary); opacity: 0.4; border: 2px dashed var(--text-tertiary); }
.legend-dot--normal { background: var(--accent-primary); }

.legend-hint {
  margin-top: var(--space-2);
  font-size: 11px;
  color: var(--text-tertiary);
  line-height: 1.4;
}

.graph-main {
  flex: 1;
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.graph-chart-wrap {
  flex: 1;
  position: relative;
  width: 100%;
  overflow: hidden;
}

.graph-loading--overlay {
  position: absolute;
  inset: 0;
  z-index: 2;
  background: var(--bg-primary);
  flex: unset;
  transition: opacity 350ms ease-out;
}

.graph-loading--overlay.fade-out {
  opacity: 0;
  pointer-events: none;
}

.graph-chart {
  width: 100%;
  height: 100%;
  opacity: 0;
  transform: scale(0.94);
  transition: opacity 400ms ease-out, transform 500ms ease-out;
}

.graph-chart--settled {
  opacity: 1;
  transform: scale(1);
}

.graph-chart__info {
  position: absolute;
  bottom: var(--space-3);
  left: var(--space-3);
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  background: var(--surface-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  padding: 3px 8px;
  pointer-events: none;
}

.graph-loading {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  color: var(--text-tertiary);
}

.graph-loading__spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--border-default);
  border-top-color: var(--accent-primary);
  border-radius: var(--radius-full);
  animation: spin 0.8s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.graph-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  color: var(--text-tertiary);
}

.graph-empty h3 { margin: 0; color: var(--text-secondary); }
.graph-empty p { margin: 0; font-size: var(--font-body-sm); }

.graph-loading__text {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
  margin: 0;
}

.graph-skeleton {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.graph-skeleton__sidebar {
  width: 260px;
  border-right: 1px solid var(--border-default);
  background: var(--bg-secondary);
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  flex-shrink: 0;
}

.graph-skeleton__chart {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-primary);
}

.graph-skeleton__pulse {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  color: var(--text-tertiary);
}

.graph-skeleton__pulse p {
  margin: 0;
  font-size: var(--font-body-sm);
}

.skel-bar {
  height: 32px;
  border-radius: var(--radius-md);
  background: var(--bg-tertiary);
  animation: skel-shimmer 1.4s ease-in-out infinite;
}

.skel-section-title {
  height: 14px;
  width: 80px;
  border-radius: var(--radius-sm);
  background: var(--bg-tertiary);
  margin-bottom: var(--space-1);
  animation: skel-shimmer 1.4s ease-in-out infinite;
}

.skel-filter {
  height: 28px;
  border-radius: var(--radius-md);
  background: var(--bg-tertiary);
  animation: skel-shimmer 1.4s ease-in-out infinite;
}

.skel-legend {
  height: 16px;
  border-radius: var(--radius-sm);
  background: var(--bg-tertiary);
  animation: skel-shimmer 1.4s ease-in-out infinite;
}

@keyframes skel-shimmer {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 0.7; }
}

.skel-bar { animation-delay: 0ms; }
.skel-section-title:nth-of-type(1) { animation-delay: 80ms; }
.skel-filter:nth-of-type(1) { animation-delay: 160ms; }
.skel-filter:nth-of-type(2) { animation-delay: 200ms; }
.skel-filter:nth-of-type(3) { animation-delay: 240ms; }
.skel-filter:nth-of-type(4) { animation-delay: 280ms; }

.graph-error {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  color: var(--text-tertiary);
}

.graph-error h3 { margin: 0; color: var(--text-secondary); }
.graph-error p { margin: 0; font-size: var(--font-body-sm); }

.graph-error__retry {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 8px 20px;
  border: 1px solid var(--accent-primary);
  border-radius: var(--radius-md);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: opacity var(--transition-fast);
  margin-top: var(--space-2);
}

.graph-error__retry:hover { opacity: 0.9; }

@media (prefers-reduced-motion: reduce) {
  .stat-chip { animation: none !important; opacity: 1 !important; transform: none !important; }
  .graph-sidebar { animation: none !important; opacity: 1 !important; transform: none !important; }
  .graph-chart { transition: none !important; opacity: 1 !important; transform: none !important; }
  .graph-loading__spinner { animation-duration: 2s !important; }
  .skel-bar, .skel-section-title, .skel-filter, .skel-legend { animation: none !important; opacity: 0.5 !important; }
  .graph-loading--overlay { transition: none !important; }
}
</style>
