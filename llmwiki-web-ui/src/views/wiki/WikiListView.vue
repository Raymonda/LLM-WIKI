<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import WikiPageRenderer from '@/components/wiki/WikiPageRenderer.vue'
import { listCategories, recentPages, getRecommended, getPromotionStats, getContributors, listPages, mergePages, type WikiPageInfo, type PromotionStats, type ContributorInfo } from '@/api/wiki'
import { listSources, getSourceContent, getSourcePreviewUrl, getSourceDownloadUrl, type SourceInfo, type SourceContentInfo } from '@/api/source'
import { getActivityFeed, type ActivityItem } from '@/api/activity'
import { useAuthStore } from '@/stores/auth'
import { useTaskProgressStore } from '@/stores/taskProgress'
import { useToastStore } from '@/stores/toast'
import { formatSummaryPreview } from '@/utils/summaryFormatter'
import { getEffectiveHealth, getLifecycleStatus } from '@/utils/healthStatus'
import {
  BookOpen, FileText, Clock, CheckCircle, AlertTriangle, Upload, ArrowRight, FolderOpen, Award, TrendingUp, Download, FileSearch, ChevronRight, ChevronDown, Search, CheckSquare, Square, Merge as MergeIcon, X, Loader2, Globe
} from 'lucide-vue-next'

const { t, locale } = useI18n()
const router = useRouter()
const authStore = useAuthStore()
const taskStore = useTaskProgressStore()
const toastStore = useToastStore()

function handleTaskCompleted(e: Event) {
  const detail = (e as CustomEvent).detail
  if (detail?.type === 'merge') {
    if (selectedCategory.value) {
      loadCategoryPages(selectedCategory.value)
    } else {
      recentPages().then(pages => { allPages.value = pages })
    }
  }
}

onMounted(() => {
  window.addEventListener('task-completed', handleTaskCompleted)
})

onUnmounted(() => {
  window.removeEventListener('task-completed', handleTaskCompleted)
})

const allPages = ref<WikiPageInfo[]>([])
const selectedCategory = ref('')
const categoryPages = ref<WikiPageInfo[] | null>(null)
const categoryLoading = ref(false)
const pages = computed(() => categoryPages.value !== null ? categoryPages.value : allPages.value)
const categoryListTitle = computed(() => {
  if (!selectedCategory.value) return ''
  if (categoryLoading.value) return `${selectedCategory.value} · ${t('wiki.loading')}`
  return t('wiki.categoryPagesTitle', [selectedCategory.value, categoryPages.value?.length ?? 0])
})
const recommendedPages = ref<WikiPageInfo[]>([])
const categories = ref<string[]>([])
const expandedCategories = ref<Set<string>>(new Set())
const sources = ref<SourceInfo[]>([])
const promotionStats = ref<PromotionStats | null>(null)
const contributors = ref<ContributorInfo[]>([])
const teamExpanded = ref(false)
const hasTeamStats = computed(() => (promotionStats.value?.promotedPageCount ?? 0) > 0 || contributors.value.length > 0)
const loading = ref(true)
const sourcePreview = ref<SourceContentInfo | null>(null)
const sourcePreviewVisible = ref(false)
const sourcePreviewLoading = ref(false)
const sourcePreviewError = ref('')
const previewSourceId = ref<number | null>(null)
const activityFeed = ref<ActivityItem[]>([])

const searchKeyword = ref('')

// ===== 多选合并模式 =====
const selectionMode = ref(false)
const selectedPageIds = ref<Set<number>>(new Set())
const mergeTitle = ref('')
const mergeLoading = ref(false)
const mergeError = ref('')
const showMergeConfirm = ref(false)

function toggleSelectionMode() {
  selectionMode.value = !selectionMode.value
  if (!selectionMode.value) {
    selectedPageIds.value = new Set()
    mergeTitle.value = ''
    mergeError.value = ''
    showMergeConfirm.value = false
  }
}

function isPageSelectable(page: WikiPageInfo): boolean {
  return getLifecycleStatus(page) === 'ACTIVE'
}

function togglePageSelection(pageId: number, event: Event) {
  event.preventDefault()
  const page = pages.value.find(p => p.id === pageId)
  if (page && !isPageSelectable(page)) return
  const newSet = new Set(selectedPageIds.value)
  if (newSet.has(pageId)) {
    newSet.delete(pageId)
  } else {
    newSet.add(pageId)
  }
  selectedPageIds.value = newSet
}

const canMerge = computed(() => selectedPageIds.value.size >= 2)

async function executeMerge() {
  if (!canMerge.value || mergeLoading.value) return
  showMergeConfirm.value = true
}

async function confirmMerge() {
  if (!canMerge.value || mergeLoading.value) return
  mergeLoading.value = true
  mergeError.value = ''
  showMergeConfirm.value = false
  try {
    const pageIds = [...selectedPageIds.value]
    const title = mergeTitle.value.trim() || undefined
    const result = await mergePages(pageIds, title)
    taskStore.addTask(result.executionId, 'merge', t('wiki.mergeTaskTitle', [pageIds.length]))
    toastStore.success(t('wiki.mergeSubmitted'), t('wiki.mergeSubmittedDetail', [pageIds.length]))
    selectionMode.value = false
    selectedPageIds.value = new Set()
    mergeTitle.value = ''
    allPages.value = await recentPages()
  } catch (e: any) {
    mergeError.value = e.message || t('wiki.mergeRequestFailed')
    toastStore.error(t('wiki.mergeFailed'), e.message || t('wiki.mergeRequestFailed'))
  } finally {
    mergeLoading.value = false
  }
}

const previewSrc = computed(() => {
  if (previewSourceId.value == null) return ''
  const token = localStorage.getItem('llmwiki-token')
  const baseUrl = getSourcePreviewUrl(previewSourceId.value)
  return token ? `${baseUrl}?token=${token}` : baseUrl
})

function goToSearch() {
  if (searchKeyword.value.trim()) {
    router.push({ path: '/search', query: { q: searchKeyword.value.trim() } })
  } else {
    router.push('/search')
  }
}

async function loadCategoryPages(cat: string) {
  categoryLoading.value = true
  try {
    categoryPages.value = await listPages(1, 200, cat)
  } catch (e) {
    console.error('Failed to load category pages:', e)
    categoryPages.value = []
  } finally {
    categoryLoading.value = false
  }
}

function selectCategory(cat: string, expand = false) {
  if (selectedCategory.value === cat) {
    selectedCategory.value = ''
    categoryPages.value = null
    return
  }
  selectedCategory.value = cat
  if (expand && !expandedCategories.value.has(cat)) {
    const next = new Set(expandedCategories.value)
    next.add(cat)
    expandedCategories.value = next
  }
  loadCategoryPages(cat)
}

interface CategoryNode {
  label: string
  full: string
  children: CategoryNode[]
}

function buildCategoryTree(cats: string[]): CategoryNode[] {
  const root: CategoryNode[] = []
  const map = new Map<string, CategoryNode>()
  for (const cat of cats) {
    const parts = cat.split('/').map(p => p.trim()).filter(p => p)
    let path = ''
    for (let i = 0; i < parts.length; i++) {
      const part = parts[i]
      const parentPath = path
      path = path ? path + '/' + part : part
      if (!map.has(path)) {
        const node: CategoryNode = { label: part, full: path, children: [] }
        map.set(path, node)
        if (i === 0) {
          root.push(node)
        } else {
          const parent = map.get(parentPath)
          if (parent) parent.children.push(node)
        }
      }
    }
  }
  return root
}

const categoryTree = computed(() => buildCategoryTree(categories.value))

function toggleExpand(full: string) {
  const next = new Set(expandedCategories.value)
  if (next.has(full)) {
    next.delete(full)
  } else {
    next.add(full)
  }
  expandedCategories.value = next
}

const healthIconMap: Record<string, any> = {
  healthy: CheckCircle,
  'needs-update': AlertTriangle,
  'has-problems': AlertTriangle,
  'conflict-warning': AlertTriangle,
  deprecated: Clock,
}

const healthColorMap: Record<string, string> = {
  healthy: 'var(--success)',
  'needs-update': 'var(--warning)',
  'has-problems': 'var(--error)',
  'conflict-warning': 'var(--warning)',
  deprecated: 'var(--text-tertiary)',
}

const healthLabelKeyMap: Record<string, string> = {
  healthy: 'wiki.healthy',
  'needs-update': 'wiki.needsUpdate',
  'has-problems': 'wiki.hasProblems',
  'conflict-warning': 'wiki.conflictWarning',
  deprecated: 'wiki.deprecatedLabel',
}

onMounted(async () => {
  try {
    allPages.value = await recentPages()
    const catData = await listCategories()
    categories.value = catData
    if (allPages.value.length > 0) {
      const latestCat = allPages.value[0].category || ''
      if (latestCat) {
        const topCat = latestCat.split('/')[0].trim()
        if (topCat && catData.some(c => c.startsWith(topCat + '/'))) {
          expandedCategories.value = new Set([topCat])
        }
      }
    }
    sources.value = await listSources()
    recommendedPages.value = await getRecommended()
    if (authStore.currentScopeType() === 'personal') {
      promotionStats.value = await getPromotionStats()
    } else {
      contributors.value = await getContributors(authStore.scopeId)
    }
    try { activityFeed.value = await getActivityFeed(10) } catch { activityFeed.value = [] }
  } catch (e) {
    console.error('Failed to load wiki data:', e)
  } finally {
    loading.value = false
  }
})

function openSourcePreview(sourceId: number, format: string) {
  if (format === 'pdf') {
    const url = getSourcePreviewUrl(sourceId)
    const token = localStorage.getItem('llmwiki-token')
    window.open(token ? `${url}?token=${token}` : url, '_blank')
    return
  }
  sourcePreviewLoading.value = true
  sourcePreviewVisible.value = true
  sourcePreview.value = null
  sourcePreviewError.value = ''
  previewSourceId.value = sourceId
  getSourceContent(sourceId)
    .then((data) => {
      sourcePreview.value = data
    })
    .catch((e: unknown) => {
      console.error('Failed to load source preview:', e)
      sourcePreviewError.value = t('wiki.previewLoadFailed')
    })
    .finally(() => {
      sourcePreviewLoading.value = false
    })
}

function downloadSource(sourceId: number, sourceName: string) {
  const url = getSourceDownloadUrl(sourceId)
  const token = localStorage.getItem('llmwiki-token')
  const headers: Record<string, string> = token ? { Authorization: `Bearer ${token}` } : {}
  fetch(url, { headers })
    .then(res => {
      if (!res.ok) throw new Error('Download failed')
      return res.blob()
    })
    .then(blob => {
      const a = document.createElement('a')
      a.href = URL.createObjectURL(blob)
      a.download = sourceName
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(a.href)
    })
    .catch(() => {})
}

function formatRelativeTime(dateStr: string): string {
  const now = Date.now()
  const then = new Date(dateStr).getTime()
  const diff = now - then
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return t('wiki.justNow')
  if (minutes < 60) return t('wiki.minutesAgo', [minutes])
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return t('wiki.hoursAgo', [hours])
  const days = Math.floor(hours / 24)
  if (days < 7) return t('wiki.daysAgo', [days])
  return new Date(dateStr).toLocaleDateString(locale.value === 'en' ? 'en-US' : 'zh-CN', { month: 'short', day: 'numeric' })
}

function closePreview() {
  sourcePreviewVisible.value = false
  sourcePreview.value = null
  sourcePreviewError.value = ''
  previewSourceId.value = null
}
</script>

<template>
  <div class="wiki-home">
    <div class="wiki-home__header">
      <h1 class="wiki-home__title">{{ t('wiki.knowledgeBase') }}</h1>
      <div class="wiki-home__header-actions">
        <div class="wiki-home__search-entry" @click="goToSearch">
          <Search :size="16" class="wiki-home__search-icon" />
          <input
            v-model="searchKeyword"
            class="wiki-home__search-input"
            :placeholder="t('wiki.searchOrAsk')"
            @keydown.enter="goToSearch"
          />
        </div>
        <button class="wiki-home__add-btn" @click="router.push('/ingest')">
          <Upload :size="16" />
          {{ t('wiki.addSource') }}
        </button>
        <button class="wiki-home__add-btn wiki-home__add-btn--secondary" @click="router.push({ name: 'WikiEditorNew' })">
          <FileText :size="16" />
          {{ t('wiki.newPage') }}
        </button>
      </div>
    </div>

    <div class="wiki-home__layout">
      <div class="wiki-home__sidebar">
        <div class="wiki-home__categories">
          <h3 class="wiki-home__section-title">
            <FolderOpen :size="16" />
            {{ t('wiki.categoryNav') }}
          </h3>
          <div class="wiki-home__category-list" :class="{ 'wiki-home__category-list--loading': categoryLoading }">
            <div v-for="node in categoryTree" :key="node.full" class="wiki-home__category-group">
              <div
                class="wiki-home__category-item"
                :class="{ 'wiki-home__category-item--active': selectedCategory === node.full, 'wiki-home__category-item--parent': node.children.length > 0 }"
              >
                <component
                  :is="expandedCategories.has(node.full) ? ChevronDown : ChevronRight"
                  v-if="node.children.length > 0"
                  :size="14"
                  class="wiki-home__category-chevron"
                  @click.stop="toggleExpand(node.full)"
                />
                <span v-else class="wiki-home__category-marker">
                  <span class="wiki-home__category-dot"></span>
                </span>
                <span class="wiki-home__category-name" @click="selectCategory(node.full, node.children.length > 0)">{{ node.label }}</span>
              </div>
              <div v-if="expandedCategories.has(node.full) && node.children.length > 0" class="wiki-home__category-children">
                <div
                  v-for="child in node.children"
                  :key="child.full"
                  class="wiki-home__category-item wiki-home__category-item--child"
                  :class="{ 'wiki-home__category-item--active': selectedCategory === child.full }"
                  @click="selectCategory(child.full)"
                >
                  <span class="wiki-home__category-marker">
                    <span class="wiki-home__category-dot"></span>
                  </span>
                  <span class="wiki-home__category-name">{{ child.label }}</span>
                </div>
              </div>
            </div>
            <button v-if="selectedCategory" class="wiki-home__category-clear" @click="selectCategory(selectedCategory)">
              <ChevronRight :size="12" />
              {{ t('wiki.clearFilter') }}
            </button>
          </div>
        </div>
        <div v-if="sources.length > 0" class="wiki-home__sources">
          <h3 class="wiki-home__section-title">
            <FileText :size="16" />
            {{ t('wiki.recentSources') }}
          </h3>
          <div class="wiki-home__source-list">
            <div v-for="source in sources.slice(0, 5)" :key="source.id" class="wiki-home__source-item">
              <div class="wiki-home__source-row">
                <FileText :size="15" class="wiki-home__source-icon" />
                <span class="wiki-home__source-name" :title="source.name">{{ source.name }}</span>
              </div>
              <div class="wiki-home__source-row wiki-home__source-row--meta">
                <span class="wiki-home__source-format">{{ source.format }}</span>
                <div class="wiki-home__source-actions">
                  <button class="wiki-home__source-action" @click="openSourcePreview(source.id, source.format)" :title="t('wiki.preview')">
                    <FileSearch :size="14" />
                  </button>
                  <button class="wiki-home__source-action" @click="downloadSource(source.id, source.name)" :title="t('wiki.download')">
                    <Download :size="14" />
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div v-if="hasTeamStats" class="wiki-home__team">
          <button class="wiki-home__team-toggle" @click="teamExpanded = !teamExpanded">
            <Award :size="16" />
            <span>{{ t('wiki.teamHighlights') }}</span>
            <ChevronDown v-if="teamExpanded" :size="14" class="wiki-home__team-chevron" />
            <ChevronRight v-else :size="14" class="wiki-home__team-chevron" />
          </button>
          <template v-if="teamExpanded">
            <div v-if="promotionStats && promotionStats.promotedPageCount > 0" class="wiki-home__impact">
              <h4 class="wiki-home__section-title">
                <TrendingUp :size="16" />
                {{ t('wiki.impact') }}
              </h4>
              <div class="wiki-home__impact-stats">
                <div class="wiki-home__impact-stat">
                  <span class="wiki-home__impact-number">{{ promotionStats.adoptedTeamCount }}</span>
                  <span class="wiki-home__impact-label">{{ t('wiki.teamsAdopted') }}</span>
                </div>
                <div class="wiki-home__impact-stat">
                  <span class="wiki-home__impact-number">{{ promotionStats.promotedPageCount }}</span>
                  <span class="wiki-home__impact-label">{{ t('wiki.knowledgeRefined') }}</span>
                </div>
              </div>
              <div v-if="promotionStats.recentPromotedPages.length > 0" class="wiki-home__impact-pages">
                <div v-for="p in promotionStats.recentPromotedPages.slice(0, 3)" :key="p.pageId" class="wiki-home__impact-page">
                  <Award :size="12" />
                  <router-link :to="`/wiki/${p.pageId}?scopeId=${p.targetScopeId}`" class="wiki-home__impact-page-link">
                    {{ p.title }} → {{ p.targetScopeName }}
                  </router-link>
                </div>
              </div>
            </div>

            <div v-if="contributors.length > 0" class="wiki-home__contributors">
              <h4 class="wiki-home__section-title">
                <Award :size="16" />
                {{ t('wiki.contributors') }}
              </h4>
              <div class="wiki-home__contributor-list">
                <div v-for="contributor in contributors.slice(0, 5)" :key="contributor.userName" class="wiki-home__contributor-item">
                  <span class="wiki-home__contributor-name">{{ contributor.userName }}</span>
                  <span class="wiki-home__contributor-count">{{ contributor.promotedPageCount }} {{ t('wiki.items') }}</span>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>

      <div class="wiki-home__main">
        <div class="wiki-home__recent">
          <div class="wiki-home__section-header">
            <h3 class="wiki-home__section-title">
              <FolderOpen v-if="selectedCategory" :size="16" />
              <Clock v-else :size="16" />
              <template v-if="selectedCategory">{{ categoryListTitle }}</template>
              <template v-else>{{ t('wiki.recentUpdates') }}</template>
            </h3>
            <button
              v-if="!selectionMode"
              class="wiki-home__select-btn"
              @click="toggleSelectionMode"
              :title="t('wiki.selectForMerge')"
            >
              <CheckSquare :size="14" />
              {{ t('wiki.select') }}
            </button>
            <button
              v-else
              class="wiki-home__select-btn wiki-home__select-btn--active"
              @click="toggleSelectionMode"
            >
              <X :size="14" />
              {{ t('wiki.cancel') }}
            </button>
          </div>
          <div class="wiki-home__page-list">
            <div v-if="pages.length === 0" class="wiki-home__empty-state">
              <p>{{ t('wiki.emptyPages') }}</p>
              <p class="wiki-home__empty-hint">{{ t('wiki.emptyPagesHint') }}</p>
            </div>
            <router-link
              v-for="page in pages"
              :key="page.id"
              :to="selectionMode ? '#' : `/wiki/${page.id}`"
              class="wiki-home__page-card"
              :class="{
                'wiki-home__page-card--deprecated': page.lifecycleStatus === 'DEPRECATED',
                'wiki-home__page-card--merged': page.lifecycleStatus === 'MERGED',
                'wiki-home__page-card--merging': page.lifecycleStatus === 'MERGING',
                'wiki-home__page-card--selectable': selectionMode,
                'wiki-home__page-card--selected': selectedPageIds.has(page.id),
                'wiki-home__page-card--disabled': selectionMode && !isPageSelectable(page)
              }"
              @click="selectionMode ? togglePageSelection(page.id, $event) : undefined"
            >
              <component
                :is="selectedPageIds.has(page.id) ? CheckSquare : Square"
                v-if="selectionMode"
                :size="16"
                class="wiki-home__page-checkbox"
              />
              <div class="wiki-home__page-info">
                <span class="wiki-home__page-title">
                  {{ page.title }}
                  <span v-if="page.lifecycleStatus === 'DEPRECATED'" class="wiki-home__deprecated-tag">{{ t('wiki.deprecated') }}</span>
                  <span v-else-if="page.lifecycleStatus === 'MERGED'" class="wiki-home__merged-tag">{{ t('wiki.merged') }}</span>
                  <span v-else-if="page.lifecycleStatus === 'MERGING'" class="wiki-home__merging-tag">{{ t('wiki.merging') }}</span>
                </span>
                <span class="wiki-home__page-meta">{{ page.category || t('wiki.uncategorized') }}</span>
              </div>
              <div v-if="getEffectiveHealth(page) !== 'healthy'" class="wiki-home__page-health" :style="{ color: healthColorMap[getEffectiveHealth(page)] }">
                <component :is="healthIconMap[getEffectiveHealth(page)]" :size="14" />
                <span>{{ t(healthLabelKeyMap[getEffectiveHealth(page)] || 'wiki.healthy') }}</span>
              </div>
              <ArrowRight v-if="!selectionMode" :size="16" class="wiki-home__page-arrow" />
            </router-link>
          </div>
        </div>

        <div class="wiki-home__recommended">
          <h3 class="wiki-home__section-title">
            <BookOpen :size="16" />
            {{ t('wiki.recommended') }}
          </h3>
          <div v-if="recommendedPages.length > 0" class="wiki-home__rec-list">
            <router-link
              v-for="page in recommendedPages"
              :key="'rec-' + page.id"
              :to="`/wiki/${page.id}`"
              class="wiki-home__rec-item"
            >
              <div class="wiki-home__page-info">
                <span class="wiki-home__page-title">
                  {{ page.title }}
                  <span v-if="page.lifecycleStatus === 'DEPRECATED'" class="wiki-home__deprecated-tag">{{ t('wiki.deprecated') }}</span>
                </span>
                <span class="wiki-home__page-meta">{{ formatSummaryPreview(page.summary) || t('wiki.noSummary') }}</span>
              </div>
              <ArrowRight :size="14" class="wiki-home__page-arrow" />
            </router-link>
          </div>
          <div v-else class="wiki-home__empty-state">
            <p>{{ t('wiki.startAddingSources') }}</p>
            <p class="wiki-home__empty-hint">{{ t('wiki.startAddingSourcesHint') }}</p>
          </div>
        </div>

        <div v-if="activityFeed.length > 0 && authStore.scopes.length > 1" class="wiki-home__activity">
          <h3 class="wiki-home__section-title">
            <Globe :size="16" />
            {{ t('wiki.crossScopeFeed') }}
          </h3>
          <div class="wiki-home__activity-list">
            <router-link v-for="item in activityFeed" :key="item.pageId" :to="`/wiki/${item.pageId}?scopeId=${item.scopeId}`" class="wiki-home__activity-item">
              <div class="wiki-home__activity-item-main">
                <span class="wiki-home__activity-title">{{ item.title }}</span>
                <span v-if="item.category" class="wiki-home__activity-category">{{ item.category }}</span>
              </div>
              <div class="wiki-home__activity-item-meta">
                <span class="wiki-home__activity-scope">{{ item.scopeName }}</span>
                <span class="wiki-home__activity-time">{{ formatRelativeTime(item.updatedAt) }}</span>
              </div>
            </router-link>
          </div>
        </div>
      </div>
    </div>

    <div v-if="sourcePreviewVisible" class="wiki-home__source-preview-overlay" @click.self="closePreview">
      <div class="wiki-home__source-preview" :class="{ 'wiki-home__source-preview--wide': sourcePreview?.previewType === 'pdf' }">
        <div class="wiki-home__source-preview-header">
          <h3 class="wiki-home__source-preview-title">{{ sourcePreview?.name || t('wiki.sourcePreview') }}</h3>
          <button class="wiki-home__source-preview-close" @click="closePreview">&times;</button>
        </div>
        <div v-if="sourcePreviewLoading" class="wiki-home__source-preview-loading">{{ t('wiki.loading') }}</div>
        <div v-else-if="sourcePreviewError" class="wiki-home__source-preview-error">{{ sourcePreviewError }}</div>
        <template v-else-if="sourcePreview">
          <div v-if="sourcePreview.previewType === 'image'" class="wiki-home__source-preview-media">
            <img :src="previewSrc" :alt="t('wiki.previewImage')" class="wiki-home__source-preview-img" />
          </div>
          <div v-else-if="sourcePreview.previewType === 'pdf'" class="wiki-home__source-preview-media wiki-home__source-preview-media--pdf">
            <iframe :src="previewSrc" class="wiki-home__source-preview-pdf" frameborder="0" />
          </div>
          <div v-else-if="sourcePreview.hasParsedContent" class="wiki-home__source-preview-content">
            <WikiPageRenderer :content="sourcePreview.parsedContent" />
          </div>
          <div v-else-if="sourcePreview.previewType === 'text'" class="wiki-home__source-preview-content">
            <pre class="wiki-home__source-preview-text">{{ sourcePreview.parsedContent }}</pre>
          </div>
          <div v-else class="wiki-home__source-preview-empty">
            <p>{{ t('wiki.noParsedContent') }}</p>
            <p class="wiki-home__source-preview-empty-hint">{{ t('wiki.noParsedContentHint') }}</p>
          </div>
        </template>
      </div>
    </div>

    <!-- 合并操作栏 -->
    <div v-if="selectionMode && selectedPageIds.size > 0" class="wiki-home__merge-bar">
      <div class="wiki-home__merge-bar-info">
        <MergeIcon :size="16" />
        <span>{{ t('wiki.selectedPages', [selectedPageIds.size]) }}</span>
      </div>
      <div class="wiki-home__merge-bar-actions">
        <input
          v-model="mergeTitle"
          type="text"
          class="wiki-home__merge-title-input"
          :placeholder="t('wiki.mergeTitlePlaceholder')"
        />
        <button
          class="wiki-home__merge-btn"
          :class="{ 'wiki-home__merge-btn--disabled': !canMerge }"
          :disabled="!canMerge || mergeLoading"
          @click="executeMerge"
        >
          <Loader2 v-if="mergeLoading" :size="14" class="wiki-home__merge-btn-icon--spin" />
          <MergeIcon v-else :size="14" />
          {{ mergeLoading ? t('wiki.mergingEllipsis') : t('wiki.merge') }}
        </button>
      </div>

      <div v-if="showMergeConfirm" class="wiki-home__merge-confirm">
        <div class="wiki-home__merge-confirm-body">
          <AlertTriangle :size="16" class="wiki-home__merge-confirm-icon" />
          <span v-html="t('wiki.mergeConfirmMsg', [`<strong>${selectedPageIds.size}</strong>`])"></span>
        </div>
        <div class="wiki-home__merge-confirm-actions">
          <button class="wiki-home__merge-confirm-cancel" @click="showMergeConfirm = false">{{ t('wiki.back') }}</button>
          <button class="wiki-home__merge-confirm-ok" :disabled="mergeLoading" @click="confirmMerge">
            <Loader2 v-if="mergeLoading" :size="14" class="wiki-home__merge-btn-icon--spin" />
            {{ t('wiki.confirmMerge') }}
          </button>
        </div>
      </div>

      <p v-if="mergeError" class="wiki-home__merge-error">{{ mergeError }}</p>
      <p v-if="selectedPageIds.size === 1" class="wiki-home__merge-hint">{{ t('wiki.needMorePages') }}</p>
    </div>
  </div>
</template>

<style scoped>
.wiki-home {
  max-width: 960px;
}

.wiki-home__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-6);
}

.wiki-home__title {
  font-size: var(--font-h1);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
}

.wiki-home__header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.wiki-home__search-entry {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--bg-secondary);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-home__search-entry:hover {
  border-color: var(--accent-primary);
  background: var(--accent-light);
}

.wiki-home__search-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.wiki-home__search-input {
  background: transparent;
  border: none;
  outline: none;
  font-size: var(--font-body);
  color: var(--text-primary);
  width: 180px;
}

.wiki-home__search-input::placeholder {
  color: var(--text-tertiary);
}

.wiki-home__add-btn {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: opacity var(--transition-fast);
}

.wiki-home__add-btn:hover {
  opacity: 0.9;
}

.wiki-home__add-btn--secondary {
  background: var(--surface-card);
  color: var(--text-primary);
  border: 1px solid var(--border-default);
}

.wiki-home__add-btn--secondary:hover {
  background: var(--surface-hover);
  opacity: 1;
}

.wiki-home__layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: var(--space-6);
}

.wiki-home__sidebar {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  position: sticky;
  top: var(--space-6);
  align-self: start;
  max-height: calc(100vh - 2 * var(--space-6));
  overflow-y: auto;
}

.wiki-home__section-title {
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-bottom: var(--space-3);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.wiki-home__categories {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
}

.wiki-home__category-list {
  display: flex;
  flex-direction: column;
}

.wiki-home__category-list--loading {
  opacity: 0.5;
  pointer-events: none;
}

.wiki-home__category-group {
  border-bottom: 1px solid var(--border-subtle);
}

.wiki-home__category-group:last-of-type {
  border-bottom: none;
}

.wiki-home__category-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-2);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background var(--transition-fast);
  min-height: 32px;
}

.wiki-home__category-item--parent {
  padding: var(--space-3) var(--space-3);
  border-radius: var(--radius-md);
}

.wiki-home__category-item--child {
  padding: var(--space-2) var(--space-2) var(--space-2) var(--space-6);
  border-radius: var(--radius-md);
}

.wiki-home__category-chevron {
  color: var(--text-tertiary);
  flex-shrink: 0;
  transition: transform var(--transition-fast);
}

.wiki-home__category-marker {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.wiki-home__category-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--accent-primary);
  opacity: 0.6;
}

.wiki-home__category-item:hover {
  background: var(--bg-tertiary);
}

.wiki-home__category-item--active {
  background: var(--accent-light);
  color: var(--accent-primary);
  font-weight: var(--weight-semibold);
}

.wiki-home__category-item--active .wiki-home__category-name {
  color: var(--accent-primary);
}

.wiki-home__category-item--active .wiki-home__category-dot {
  opacity: 1;
}

.wiki-home__category-children {
  padding-bottom: var(--space-1);
}

.wiki-home__category-clear {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  margin-top: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-home__category-clear:hover {
  color: var(--accent-primary);
  border-color: var(--accent-primary);
  background: var(--accent-light);
}

.wiki-home__category-name {
  font-size: var(--font-body);
  color: var(--text-primary);
}

.wiki-home__category-count {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  background: var(--bg-tertiary);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-full);
}

.wiki-home__sources {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
}

.wiki-home__source-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.wiki-home__source-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  transition: all var(--transition-fast);
}

.wiki-home__source-item:hover {
  border-color: var(--border-default);
  background: var(--bg-tertiary);
}

.wiki-home__source-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.wiki-home__source-row--meta {
  justify-content: space-between;
}

.wiki-home__source-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.wiki-home__source-name {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  line-height: var(--leading-body-sm);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wiki-home__source-format {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  background: var(--bg-tertiary);
  padding: 1px var(--space-2);
  border-radius: var(--radius-full);
  font-weight: var(--weight-medium);
  text-transform: uppercase;
}

.wiki-home__source-actions {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.wiki-home__source-action {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  transition: all var(--transition-fast);
  flex-shrink: 0;
}

.wiki-home__source-action:hover {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.wiki-home__source-preview-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.wiki-home__source-preview {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  width: 90%;
  max-width: 720px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
}

.wiki-home__source-preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border-default);
}

.wiki-home__source-preview-title {
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin: 0;
}

.wiki-home__source-preview-close {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  font-size: var(--font-body-lg);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.wiki-home__source-preview-close:hover {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.wiki-home__source-preview-loading,
.wiki-home__source-preview-empty,
.wiki-home__source-preview-error {
  padding: var(--space-8) var(--space-5);
  text-align: center;
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
}

.wiki-home__source-preview-error {
  color: var(--accent-red, #e74c3c);
}

.wiki-home__source-preview-empty-hint {
  margin-top: var(--space-2);
  font-size: var(--font-body-xs, 12px);
  color: var(--text-tertiary);
}

.wiki-home__source-preview-media {
  padding: var(--space-4);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: auto;
  flex: 1;
}

.wiki-home__source-preview-img {
  max-width: 100%;
  max-height: 50vh;
  object-fit: contain;
  border-radius: var(--radius-sm);
}

.wiki-home__source-preview-media--pdf {
  padding: 0;
}

.wiki-home__source-preview-pdf {
  width: 100%;
  height: 65vh;
  border: none;
}

.wiki-home__source-preview--wide {
  max-width: 860px;
}

.wiki-home__source-preview-content {
  padding: var(--space-5);
  overflow-y: auto;
  flex: 1;
}

.wiki-home__source-preview-text {
  font-size: var(--font-body-sm);
  line-height: 1.7;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}

.wiki-home__main {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
}

.wiki-home__recent {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
}

.wiki-home__page-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.wiki-home__page-card {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  color: var(--text-primary);
  text-decoration: none;
  transition: all var(--transition-fast);
}

.wiki-home__page-card:hover {
  background: var(--accent-light);
  border-color: var(--accent-primary);
}

.wiki-home__page-card--deprecated {
  opacity: 0.7;
}

.wiki-home__page-card--deprecated:hover {
  opacity: 0.85;
}

.wiki-home__page-card--merged {
  opacity: 0.5;
}

.wiki-home__deprecated-tag {
  display: inline-block;
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: var(--text-tertiary);
  background: var(--surface-muted, var(--bg-tertiary));
  padding: 1px var(--space-1);
  border-radius: var(--radius-sm);
  margin-left: var(--space-1);
  vertical-align: middle;
}

.wiki-home__merged-tag {
  display: inline-block;
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: var(--accent-primary);
  background: var(--accent-light);
  padding: 1px var(--space-1);
  border-radius: var(--radius-sm);
  margin-left: var(--space-1);
  vertical-align: middle;
}

.wiki-home__merging-tag {
  display: inline-block;
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: oklch(0.65 0.12 250);
  background: oklch(0.95 0.03 250);
  padding: 1px var(--space-1);
  border-radius: var(--radius-sm);
  margin-left: var(--space-1);
  vertical-align: middle;
}

.wiki-home__page-card--merging {
  opacity: 0.7;
}

.wiki-home__page-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  overflow: hidden;
}

.wiki-home__page-title {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wiki-home__page-meta {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.wiki-home__page-health {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  flex-shrink: 0;
}

.wiki-home__page-arrow {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.wiki-home__recommended {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
}

.wiki-home__rec-list {
  display: flex;
  flex-direction: column;
}

.wiki-home__rec-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-top: 1px solid var(--border-default);
  color: inherit;
  text-decoration: none;
  transition: color 150ms;
}

.wiki-home__rec-item:hover {
  color: var(--accent-primary);
}

.wiki-home__rec-item:hover .wiki-home__page-arrow {
  transform: translateX(2px);
}

.wiki-home__rec-item .wiki-home__page-arrow {
  transition: transform 150ms;
}

.wiki-home__empty-state {
  text-align: center;
  padding: var(--space-4);
}

.wiki-home__empty-state p {
  color: var(--text-secondary);
  font-size: var(--font-body);
}

.wiki-home__empty-hint {
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
  margin-top: var(--space-2);
}

.wiki-home__team {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
}

.wiki-home__team-toggle {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  padding: 0;
  border: none;
  background: none;
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  cursor: pointer;
  transition: color 150ms;
}

.wiki-home__team-toggle:hover {
  color: var(--accent-primary);
}

.wiki-home__team-chevron {
  margin-left: auto;
  color: var(--text-tertiary);
}

.wiki-home__impact {
  padding: var(--space-4) 0 0;
  margin-top: var(--space-4);
  border-top: 1px solid var(--border-default);
}

.wiki-home__impact-stats {
  display: flex;
  gap: var(--space-4);
  margin-bottom: var(--space-3);
}

.wiki-home__impact-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.wiki-home__impact-number {
  font-size: var(--font-h2);
  font-weight: var(--weight-bold);
  color: var(--accent-primary);
}

.wiki-home__impact-label {
  font-size: var(--font-caption);
  color: var(--text-secondary);
}

.wiki-home__impact-pages {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.wiki-home__impact-page {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
}

.wiki-home__impact-page-link {
  color: var(--accent-primary);
  text-decoration: none;
}

.wiki-home__impact-page-link:hover {
  text-decoration: underline;
}

.wiki-home__contributors {
  padding: var(--space-4) 0 0;
  margin-top: var(--space-4);
  border-top: 1px solid var(--border-default);
}

.wiki-home__contributor-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.wiki-home__contributor-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--bg-tertiary);
}

.wiki-home__contributor-name {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  font-weight: var(--weight-medium);
}

.wiki-home__contributor-count {
  font-size: var(--font-caption);
  color: var(--accent-primary);
  font-weight: var(--weight-semibold);
}

/* 选择模式 */
.wiki-home__section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-3);
}

.wiki-home__select-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-secondary);
  background: var(--bg-tertiary);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-home__select-btn:hover {
  color: var(--text-primary);
  background: var(--bg-secondary);
}

.wiki-home__select-btn--active {
  color: var(--accent-primary);
  border-color: var(--accent-primary);
}

.wiki-home__page-card--selectable {
  cursor: pointer;
}

.wiki-home__page-card--selectable:hover {
  background: var(--accent-light);
}

.wiki-home__page-card--selected {
  background: var(--accent-light);
  border-color: var(--accent-primary);
}

.wiki-home__page-card--disabled {
  opacity: 0.45;
  cursor: not-allowed !important;
  pointer-events: none;
}

.wiki-home__page-checkbox {
  color: var(--accent-primary);
  flex-shrink: 0;
}

/* 合并栏 */
.wiki-home__merge-bar {
  position: fixed;
  bottom: var(--space-5);
  left: 50%;
  transform: translateX(-50%);
  background: var(--surface-elevated);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-3) var(--space-4);
  box-shadow: var(--shadow-lg);
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  z-index: 100;
  max-width: 600px;
}

.wiki-home__merge-bar-info {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  white-space: nowrap;
}

.wiki-home__merge-bar-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex: 1;
}

.wiki-home__merge-title-input {
  padding: var(--space-1) var(--space-2);
  font-size: var(--font-body-sm);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--bg-primary);
  color: var(--text-primary);
  flex: 1;
  min-width: 120px;
}

.wiki-home__merge-title-input:focus {
  outline: none;
  border-color: var(--accent-primary);
}

.wiki-home__merge-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-on-accent);
  background: var(--accent-primary);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
}

.wiki-home__merge-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}

.wiki-home__merge-btn--disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.wiki-home__merge-btn-icon--spin {
  animation: spin 1s linear infinite;
}

.wiki-home__merge-confirm {
  margin-top: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--warning-light, rgba(245, 158, 11, 0.08));
  border: 1px solid var(--warning);
  border-radius: var(--radius-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.wiki-home__merge-confirm-body {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.5;
}

.wiki-home__merge-confirm-icon {
  color: var(--warning);
  flex-shrink: 0;
  margin-top: 2px;
}

.wiki-home__merge-confirm-actions {
  display: flex;
  gap: var(--space-2);
  justify-content: flex-end;
}

.wiki-home__merge-confirm-cancel {
  padding: var(--space-1) var(--space-3);
  background: transparent;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-home__merge-confirm-cancel:hover {
  background: var(--bg-tertiary);
}

.wiki-home__merge-confirm-ok {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  background: var(--error);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-home__merge-confirm-ok:hover:not(:disabled) {
  opacity: 0.9;
}

.wiki-home__merge-confirm-ok:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.wiki-home__merge-error {
  font-size: var(--font-caption);
  color: var(--error);
  margin: 0;
}

.wiki-home__merge-hint {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  margin: 0;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.wiki-home__activity {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
}

.wiki-home__activity-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.wiki-home__activity-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  text-decoration: none;
  transition: all var(--transition-fast);
}

.wiki-home__activity-item:hover {
  background: var(--accent-light);
  border-color: var(--accent-primary);
}

.wiki-home__activity-item-main {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0;
  flex: 1;
}

.wiki-home__activity-title {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wiki-home__activity-category {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.wiki-home__activity-item-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

.wiki-home__activity-scope {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: var(--accent-primary);
  background: var(--accent-light);
  padding: 1px var(--space-2);
  border-radius: var(--radius-sm);
  white-space: nowrap;
}

.wiki-home__activity-time {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  white-space: nowrap;
}
</style>