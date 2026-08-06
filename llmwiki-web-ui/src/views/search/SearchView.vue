<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, onActivated, onDeactivated, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import WikiPageRenderer from '@/components/wiki/WikiPageRenderer.vue'
import FunFactTips from '@/components/wiki/FunFactTips.vue'
import { Search, MessageCircle, Loader2, BookOpen, BookmarkPlus, Bot, FileText, AlertTriangle, CheckCircle, Filter, X, ExternalLink, Info, Copy, Send, Pencil, Sparkles, Check, Zap, Brain, ChevronDown, Library } from 'lucide-vue-next'
import { searchPages, searchSuggest, listCategories, type WikiPageInfo, type SearchResultInfo } from '@/api/wiki'
import { useAuthStore } from '@/stores/auth'
import { saveAnswer, resolveLinks, type QueryAnalysisMode } from '@/api/query'
import { useSSEQuery } from '@/composables/useSSEQuery'

const { t } = useI18n()
const route = useRoute()
const sse = useSSEQuery()
const authStore = useAuthStore()

const searchMode = ref<'search' | 'query'>('search')
const searchQuery = ref('')
const localLoading = ref(false)
const searchResults = ref<SearchResultInfo[]>([])
const selectedCategory = ref('')
const selectedQueryScopes = ref<number[]>(authStore.effectiveQueryScopes())
const availableCategories = ref<string[]>([])
const showSuggestions = ref(false)
const suggestions = ref<WikiPageInfo[]>([])
let suggestTimer: ReturnType<typeof setTimeout> | null = null

const queryInput = ref('')
const lastQuestion = ref('')
const isSaving = ref(false)
const isSaved = ref(false)
const savedPage = ref<WikiPageInfo | null>(null)
const showRefineInput = ref(false)
const refineInput = ref('')
const isRefining = ref(false)
const answerLinkResolution = ref<Record<string, number>>({})
const queryAnalysisMode = ref<QueryAnalysisMode>('quick')

const isLoading = computed(() => localLoading.value || sse.isLoading.value)
const isStreaming = computed(() => sse.isStreaming.value)
const aiAnswer = computed(() => sse.aiAnswer.value)
const queryError = computed(() => sse.queryError.value)
const queryMode = computed(() => sse.queryMode.value)
const progressSteps = computed(() => sse.progressSteps.value)
const isSynthesizing = computed(() => sse.isSynthesizing.value)
const funFacts = computed(() => sse.funFacts.value)

const factContent = computed(() => sse.factAnswer.value)
const synthesisStream = computed(() => sse.synthesisStreamContent.value)
const prospectiveContent = computed(() => sse.prospectiveAnswer.value)

const isStreamingSynthesis = computed(() => isStreaming.value && isSynthesizing.value)

interface WikiSourceRef {
  title: string
  path: string
}

const wikiSources = computed<WikiSourceRef[]>(() => {
  if (!aiAnswer.value) return []
  const refs: WikiSourceRef[] = []
  const seen = new Set<string>()
  const wikiLinkRegex = /\[\[([^\]]+)\]\]\(([^)]+)\)/g
  const mdLinkRegex = /(?<!\[)\[([^\]]+)\]\(([^)]+)\)(?!\])/g
  const text = aiAnswer.value

  function addMatch(title: string, rawPath: string) {
    let filePath = rawPath
    const absUrlMatch = /^https?:\/\/[^/]+\/(?:wiki\/)?pages\/(.+)$/.exec(filePath)
    if (absUrlMatch) {
      filePath = 'pages/' + decodeURIComponent(absUrlMatch[1])
    }
    if (filePath.startsWith('wiki/')) filePath = filePath.slice(5)
    if (!filePath.startsWith('pages/')) return
    if (!filePath.endsWith('.md')) filePath += '.md'
    if (!filePath.match(/^pages\/.+\.md$/)) return
    if (!seen.has(filePath)) {
      seen.add(filePath)
      refs.push({ title, path: filePath })
    }
  }

  let match: RegExpExecArray | null
  while ((match = wikiLinkRegex.exec(text)) !== null) {
    addMatch(match[1], match[2])
  }
  while ((match = mdLinkRegex.exec(text)) !== null) {
    addMatch(match[1], match[2])
  }
  return refs
})

const userScrolledUp = ref(false)
let scrollTimer: ReturnType<typeof setTimeout> | null = null
let didScrollToProspectiveStart = false

function getScrollContainer(): HTMLElement | null {
  return document.querySelector('.app-layout__content')
}

function autoScrollToBottom() {
  if (userScrolledUp.value) return
  if (didScrollToProspectiveStart) return
  nextTick(() => {
    const el = getScrollContainer()
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
  })
}

function scrollToProspectiveStart() {
  nextTick(() => {
    const el = document.querySelector('.search-page__prospective-anchor') as HTMLElement | null
    if (el) {
      const container = getScrollContainer()
      if (container) {
        const containerRect = container.getBoundingClientRect()
        const elRect = el.getBoundingClientRect()
        const offset = elRect.top - containerRect.top + container.scrollTop - 16
        container.scrollTo({ top: offset, behavior: 'smooth' })
      }
    }
  })
}

function onContainerScroll() {
  const el = getScrollContainer()
  if (!el) return
  const distToBottom = el.scrollHeight - el.scrollTop - el.clientHeight
  userScrolledUp.value = distToBottom > 200
  if (userScrolledUp.value) {
    if (scrollTimer) clearTimeout(scrollTimer)
    scrollTimer = setTimeout(() => { userScrolledUp.value = false }, 3000)
  }
}

watch(aiAnswer, () => { autoScrollToBottom() })

watch(prospectiveContent, (newContent) => {
  if (newContent && !didScrollToProspectiveStart) {
    didScrollToProspectiveStart = true
    scrollToProspectiveStart()
  }
})

watch(isStreaming, (streaming) => {
  if (!streaming && aiAnswer.value) {
    resolveLinks(aiAnswer.value).then(resolution => {
      answerLinkResolution.value = resolution
    }).catch(() => {
      answerLinkResolution.value = {}
    })
  }
})

const hasNoWikiInfo = computed(() => {
  if (!aiAnswer.value || isStreaming.value) return false
  return aiAnswer.value.includes(t('search.noWikiInfoCheck'))
})

const hasAnswer = computed(() => aiAnswer.value.length > 0 || hasSavedAnswer.value)
const hasSavedAnswer = computed(() => isSaved.value && !!savedPage.value)

watch(searchQuery, (val) => {
  if (searchMode.value === 'search' && val.trim().length >= 2) {
    if (suggestTimer) clearTimeout(suggestTimer)
    suggestTimer = setTimeout(async () => {
      try {
        suggestions.value = await searchSuggest(val.trim())
        showSuggestions.value = suggestions.value.length > 0
      } catch {
        suggestions.value = []
        showSuggestions.value = false
      }
    }, 300)
  } else {
    showSuggestions.value = false
    suggestions.value = []
    if (suggestTimer) clearTimeout(suggestTimer)
  }
})

async function loadCategories() {
  try {
    availableCategories.value = await listCategories()
  } catch {
    availableCategories.value = []
  }
}

onMounted(async () => {
  loadCategories()
})

function onScopeSelectChange() {
  authStore.setQueryScopes(selectedQueryScopes.value)
  if (searchQuery.value.trim()) handleSearch()
}

function toggleQueryScope(scopeId: number) {
  const idx = selectedQueryScopes.value.indexOf(scopeId)
  if (idx >= 0) {
    if (selectedQueryScopes.value.length > 1) {
      selectedQueryScopes.value.splice(idx, 1)
    }
  } else {
    selectedQueryScopes.value.push(scopeId)
  }
  onScopeSelectChange()
}

const isAllScopesSelected = computed(() => {
  const allIds = authStore.scopes.map(s => s.scopeId)
  return allIds.length > 0 && allIds.every(id => selectedQueryScopes.value.includes(id))
})

function selectAllScopes() {
  selectedQueryScopes.value = authStore.scopes.map(s => s.scopeId)
  onScopeSelectChange()
}

const showScopeDropdown = ref(false)
let scopeDropdownTimer: ReturnType<typeof setTimeout> | null = null

const scopeSummary = computed(() => {
  if (isAllScopesSelected.value) return t('search.allScopes')
  const count = selectedQueryScopes.value.filter(id => authStore.scopes.some(s => s.scopeId === id)).length
  return t('search.scopeCount', [count])
})

function toggleScopeDropdown() {
  showScopeDropdown.value = !showScopeDropdown.value
}

function onScopeDropdownMouseleave() {
  scopeDropdownTimer = setTimeout(() => { showScopeDropdown.value = false }, 200)
}

function onScopeDropdownMouseenter() {
  if (scopeDropdownTimer) { clearTimeout(scopeDropdownTimer); scopeDropdownTimer = null }
}

watch(
  () => [route.query.q, route.query.query, route.query.mode] as const,
  ([q, query, mode]) => {
    const keyword = ((q || query) as string | undefined)?.trim()
    if (!keyword) return
    if (mode === 'query') {
      if (keyword === queryInput.value.trim() && searchMode.value === 'query') return
      searchMode.value = 'query'
      queryInput.value = keyword
    } else {
      if (keyword === searchQuery.value.trim()) return
      searchQuery.value = keyword
      searchMode.value = 'search'
      handleSearch()
    }
  },
  { immediate: true },
)

function selectSuggestion(item: WikiPageInfo) {
  searchQuery.value = item.title
  showSuggestions.value = false
  handleSearch()
}

function delayHideSuggestions() {
  setTimeout(() => { showSuggestions.value = false }, 200)
}

function clearCategory() {
  selectedCategory.value = ''
  if (searchQuery.value.trim()) handleSearch()
}

async function handleSearch() {
  if (!searchQuery.value.trim()) return
  showSuggestions.value = false
  if (suggestTimer) { clearTimeout(suggestTimer); suggestTimer = null }
  localLoading.value = true
  try {
    searchResults.value = await searchPages(searchQuery.value, selectedCategory.value || undefined, false, selectedQueryScopes.value.join(','))
  } catch (e) {
    console.error('Search failed:', e)
    searchResults.value = []
  } finally {
    localLoading.value = false
  }
}

function handleQuery() {
  const q = queryInput.value.trim()
  if (!q || isStreaming.value || isRefining.value) return
  lastQuestion.value = q
  isSaved.value = false
  savedPage.value = null
  showRefineInput.value = false
  refineInput.value = ''
  isSaving.value = false
  didScrollToProspectiveStart = false
  sse.startQuery(q, queryAnalysisMode.value)
}

function handleRefine() {
  const instruction = refineInput.value.trim()
  if (!instruction || isStreaming.value) return
  isRefining.value = true
  refineInput.value = ''
  showRefineInput.value = false

  const combinedQuestion = `当前文档内容：\n\n${aiAnswer.value}\n\n用户的完善要求：${instruction}\n\n请基于以上信息，更新和完善这份文档，保持原有结构，补充和完善用户要求的内容。直接输出更新后的完整文档。`

  isSaved.value = false
  savedPage.value = null

  sse.startQuery(combinedQuestion, 'quick', () => {
    isRefining.value = false
  })
}

async function handleSave() {
  if (isSaving.value || isSaved.value) return
  isSaving.value = true
  try {
    savedPage.value = await saveAnswer(lastQuestion.value, aiAnswer.value)
    isSaved.value = true
  } catch (e: any) {
    sse.queryError.value = e.message || t('search.saveFailed')
  } finally {
    isSaving.value = false
  }
}

async function handleCopy() {
  try {
    await navigator.clipboard.writeText(aiAnswer.value)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = aiAnswer.value
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
  }
}

function startNewQuestion() {
  sse.reset()
  queryInput.value = ''
  lastQuestion.value = ''
  isSaved.value = false
  savedPage.value = null
  showRefineInput.value = false
  refineInput.value = ''
  isRefining.value = false
  isSaving.value = false
  localLoading.value = false
}

function toggleRefineInput() {
  showRefineInput.value = !showRefineInput.value
  if (showRefineInput.value) {
    nextTick(() => {
      const el = document.querySelector('.search-page__refine-input') as HTMLTextAreaElement
      el?.focus()
    })
  }
}

function onRefineKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleRefine() }
  if (e.key === 'Escape') { showRefineInput.value = false }
}

function onQueryKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleQuery() }
}

function renderHighlight(text: string | null, highlights: string[] | null): string {
  if (highlights && highlights.length > 0) return highlights.join(' ... ')
  return text || ''
}

function resultKey(result: SearchResultInfo, index: number): string {
  return `wiki_${result.id ?? index}`
}

let wasStreamingOnLeave = false

onDeactivated(() => {
  wasStreamingOnLeave = sse.isStreaming.value || sse.isLoading.value
})

onActivated(() => {
  if (wasStreamingOnLeave) {
    wasStreamingOnLeave = false
  }
  nextTick(() => {
    const container = getScrollContainer()
    if (container) container.addEventListener('scroll', onContainerScroll, { passive: true })
  })
})

onUnmounted(() => {
  if (suggestTimer) clearTimeout(suggestTimer)
  if (scopeDropdownTimer) clearTimeout(scopeDropdownTimer)
  const container = getScrollContainer()
  if (container) container.removeEventListener('scroll', onContainerScroll)
})
</script>

<template>
  <div class="search-page">
    <div class="search-page__toolbar">
      <div class="search-page__mode-toggle">
        <button
          class="search-page__mode-btn"
          :class="{ 'search-page__mode-btn--active': searchMode === 'search' }"
          @click="searchMode = 'search'; showSuggestions = false"
          :aria-label="t('search.searchModeAria')"
        >
          <Search :size="14" />
          {{ t('search.searchMode') }}
        </button>
        <button
          class="search-page__mode-btn"
          :class="{ 'search-page__mode-btn--active': searchMode === 'query' }"
          @click="searchMode = 'query'; showSuggestions = false"
          :aria-label="t('search.queryModeAria')"
        >
          <MessageCircle :size="14" />
          {{ t('search.askMode') }}
        </button>
      </div>
      <div v-if="authStore.scopes.length > 1" class="search-page__scope-select" @mouseenter="onScopeDropdownMouseenter" @mouseleave="onScopeDropdownMouseleave">
        <button class="search-page__scope-trigger" @click="toggleScopeDropdown">
          <Library :size="14" />
          <span>{{ scopeSummary }}</span>
          <ChevronDown :size="12" class="search-page__scope-chevron" :class="{ 'search-page__scope-chevron--open': showScopeDropdown }" />
        </button>
        <div v-show="showScopeDropdown" class="search-page__scope-dropdown">
          <button
            class="search-page__scope-option"
            :class="{ 'search-page__scope-option--active': isAllScopesSelected }"
            @click="selectAllScopes"
          >
            <Check :size="14" class="search-page__scope-check" />
            <span>{{ t('search.allScopes') }}</span>
          </button>
          <button
            v-for="s in authStore.scopes"
            :key="s.scopeId"
            class="search-page__scope-option"
            :class="{ 'search-page__scope-option--active': selectedQueryScopes.includes(s.scopeId) }"
            @click="toggleQueryScope(s.scopeId)"
          >
            <Check :size="14" class="search-page__scope-check" />
            <span>{{ s.scopeName }}</span>
          </button>
        </div>
      </div>
    </div>

    <div v-if="searchMode === 'search'" class="search-page__search-area">
      <div class="search-page__input-wrap">
        <Search :size="20" class="search-page__input-icon" />
        <input
          v-model="searchQuery"
          type="text"
          class="search-page__input"
          :placeholder="t('search.searchInputPlaceholder')"
          @keydown.enter="handleSearch"
          @focus="searchMode === 'search' && suggestions.length > 0 && (showSuggestions = true)"
          @blur="delayHideSuggestions()"
        />
        <button v-if="searchQuery" class="search-page__input-clear" @click="searchQuery = ''; searchResults = []; showSuggestions = false" :aria-label="t('search.clearSearch')">
          <X :size="16" />
        </button>
      </div>

      <div v-if="showSuggestions && searchMode === 'search'" class="search-page__suggestions">
        <div v-for="item in suggestions" :key="item.id" class="search-page__suggestion-item" @mousedown="selectSuggestion(item)">
          <FileText :size="14" class="search-page__suggestion-icon" />
          <span class="search-page__suggestion-title">{{ item.title }}</span>
          <span v-if="item.category" class="search-page__suggestion-category">{{ item.category }}</span>
        </div>
      </div>

      <div v-if="availableCategories.length > 0" class="search-page__filter-row">
        <div class="search-page__filter">
          <Filter :size="14" class="search-page__filter-icon" />
          <select v-model="selectedCategory" class="search-page__filter-select" @change="searchQuery.trim() && handleSearch()">
            <option value="">{{ t('search.allCategories') }}</option>
            <option v-for="cat in availableCategories" :key="cat" :value="cat">{{ cat }}</option>
          </select>
          <button v-if="selectedCategory" class="search-page__filter-clear" @click="clearCategory" :aria-label="t('search.clearCategoryFilter')">
            <X :size="14" />
          </button>
        </div>
      </div>

      <div v-if="!searchQuery" class="search-page__empty">
        <BookOpen :size="48" class="search-page__empty-icon" />
        <p>{{ t('search.searchEmpty') }}</p>
      </div>

      <div v-if="localLoading" class="search-page__loading">
        <Loader2 :size="24" class="search-page__loading-icon" />
        <span>{{ t('search.searching') }}</span>
      </div>

      <div v-if="searchQuery && !localLoading" class="search-page__results">
        <div v-if="searchResults.length === 0" class="search-page__no-results">
          <p>{{ t('search.noResults') }}</p>
        </div>
        <div v-for="(result, idx) in searchResults" :key="resultKey(result, idx)" class="search-page__result-card">
          <FileText :size="16" class="search-page__result-icon" />
          <div class="search-page__result-info">
            <div class="search-page__result-title-row">
              <router-link :to="`/wiki/${result.id}`" class="search-page__result-title" v-html="renderHighlight(result.title, result.highlightedTitle)"></router-link>
              <span v-if="result.sourceScopeName" class="search-page__result-scope-badge">{{ result.sourceScopeName }}</span>
            </div>
            <p class="search-page__result-snippet" v-html="renderHighlight(result.summary, result.highlightedSummary)"></p>
            <div class="search-page__result-meta">
              <span v-if="result.category" class="search-page__result-category">{{ result.category }}</span>
              <span v-if="result.score" class="search-page__result-score">{{ t('search.matchScore') }} {{ Math.round(result.score * 10) }}%</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="searchMode === 'query'" class="search-page__query-area">
      <div class="search-page__query-input-wrap">
        <textarea
          v-model="queryInput"
          class="search-page__query-input"
          rows="1"
          :placeholder="t('search.queryInputPlaceholder')"
          @keydown="onQueryKeydown"
          @input="(e: Event) => { const t = e.target as HTMLTextAreaElement; t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 160) + 'px' }"
          :disabled="isStreaming || isRefining"
        ></textarea>
        <button
          class="search-page__query-send"
          :class="{ 'search-page__query-send--disabled': !queryInput.trim() || isStreaming || isRefining }"
          @click="handleQuery"
          :disabled="!queryInput.trim() || isStreaming || isRefining"
          :aria-label="t('search.sendQuestion')"
        >
          <Loader2 v-if="isStreaming || isRefining" :size="16" class="search-page__loading-icon" />
          <Send v-else :size="16" />
        </button>
      </div>

      <div class="search-page__analysis-mode">
        <button
          class="search-page__mode-option"
          :class="{ 'search-page__mode-option--active': queryAnalysisMode === 'quick' }"
          @click="queryAnalysisMode = 'quick'"
          :disabled="isStreaming || isRefining"
        >
          <Zap :size="14" />
          <span>{{ t('search.quickAnswer') }}</span>
        </button>
        <button
          class="search-page__mode-option"
          :class="{ 'search-page__mode-option--active': queryAnalysisMode === 'deep' }"
          @click="queryAnalysisMode = 'deep'"
          :disabled="isStreaming || isRefining"
        >
          <Brain :size="14" />
          <span>{{ t('search.deepAnalysis') }}</span>
        </button>
      </div>

      <div v-if="!hasAnswer && !isLoading && !queryError" class="search-page__empty">
        <MessageCircle :size="48" class="search-page__empty-icon" />
        <p>{{ t('search.queryEmpty') }}</p>
      </div>

      <div v-if="isStreaming && !factContent" class="search-page__progress">
        <div
          v-for="step in progressSteps"
          :key="step.id"
          class="search-page__progress-step"
          :class="{
            'search-page__progress-step--running': step.status === 'running',
            'search-page__progress-step--done': step.status === 'done',
          }"
        >
          <div class="search-page__progress-indicator">
            <Loader2 v-if="step.status === 'running'" :size="14" class="search-page__loading-icon" />
            <Check v-else-if="step.status === 'done'" :size="14" />
            <span v-else class="search-page__progress-dot"></span>
          </div>
          <span class="search-page__progress-label">{{ step.label }}</span>
        </div>
      </div>

      <FunFactTips :active="isStreaming" :mode="queryAnalysisMode" :facts="funFacts" />

      <div v-if="queryError && !aiAnswer" class="search-page__error">
        <AlertTriangle :size="16" />
        {{ queryError }}
      </div>

      <div v-if="hasAnswer" class="search-page__answer">
        <div v-if="queryMode === 'fallback' && !isStreaming" class="search-page__fallback-notice">
          <Info :size="14" />
          <span>{{ t('search.fallbackNotice') }}</span>
        </div>
        <div v-if="queryMode === 'rate-limited' && !isStreaming" class="search-page__fallback-notice search-page__fallback-notice--warning">
          <AlertTriangle :size="14" />
          <span>{{ t('search.rateLimited') }}</span>
        </div>

        <div class="search-page__answer-header">
          <Bot :size="16" />
          <span>{{ t('search.answer') }}</span>
          <Loader2 v-if="isStreaming" :size="14" class="search-page__streaming-indicator" />
          <span v-if="isSaved" class="search-page__saved-badge">
            <CheckCircle :size="14" />
            {{ t('search.saved') }}
          </span>
        </div>

        <div class="search-page__answer-content">
          <WikiPageRenderer :content="factContent" :streaming="!isSynthesizing && (isStreaming || isRefining)" :link-resolution="answerLinkResolution" />
          <div v-if="isStreamingSynthesis && !synthesisStream" class="search-page__synthesis-hint" :class="'search-page__synthesis-hint--' + queryAnalysisMode">
            <Brain v-if="queryAnalysisMode === 'deep'" :size="16" class="search-page__brain-icon" />
            <Zap v-else :size="16" class="search-page__zap-icon" />
            <span>{{ queryAnalysisMode === 'deep' ? t('search.deepThinking') : t('search.quickSynthesis') }}</span>
          </div>
          <WikiPageRenderer
            v-if="isStreamingSynthesis && synthesisStream"
            :content="synthesisStream"
            :streaming="true"
            :link-resolution="answerLinkResolution"
          />
          <template v-if="!isStreaming && synthesisStream">
            <WikiPageRenderer
              :content="synthesisStream"
              :streaming="false"
              :link-resolution="answerLinkResolution"
            />
            <div v-if="prospectiveContent" class="search-page__prospective-anchor" />
          </template>
        </div>

        <div v-if="isSaving" class="search-page__saving-inline">
          <Loader2 :size="14" class="search-page__loading-icon" />
          <span>{{ t('search.savingToWiki') }}</span>
        </div>

        <div v-if="savedPage" class="search-page__saved-inline">
          <CheckCircle :size="14" />
          <span>{{ t('search.savedAsWiki') }}</span>
          <router-link :to="`/wiki/${savedPage.id}`" class="search-page__saved-link">{{ t('search.viewPage') }}</router-link>
        </div>

        <div v-if="!isStreaming && !isRefining && wikiSources.length > 0" class="search-page__sources">
          <div class="search-page__sources-header">
            <BookOpen :size="14" />
            <span>{{ t('search.answerSources') }}</span>
          </div>
          <div class="search-page__sources-list">
            <div v-for="src in wikiSources" :key="src.path" class="search-page__source-item">
              <ExternalLink :size="12" class="search-page__source-icon" />
              <router-link :to="`/wiki/p/${src.path}`" class="search-page__source-link">{{ src.title }}</router-link>
            </div>
          </div>
        </div>

        <div v-if="!isStreaming && !isRefining && hasNoWikiInfo" class="search-page__no-info">
          <AlertTriangle :size="14" />
          {{ t('search.noWikiInfo') }}
        </div>

        <div v-if="!isStreaming && !isRefining && !isSaving" class="search-page__answer-actions">
          <button
            class="search-page__action-btn"
            :class="{ 'search-page__action-btn--saved': isSaved }"
            @click="handleSave"
            :disabled="isSaved"
            :title="isSaved ? t('search.savedToWiki') : t('search.saveToWiki')"
          >
            <BookmarkPlus v-if="!isSaved" :size="14" />
            <CheckCircle v-else :size="14" />
            {{ isSaved ? t('search.saved') : t('search.saveToWiki') }}
          </button>
          <button class="search-page__action-btn search-page__action-btn--secondary" @click="handleCopy" :title="t('search.copyAnswer')">
            <Copy :size="14" />
            {{ t('search.copy') }}
          </button>
        </div>

        <div v-if="!isStreaming && !isRefining && !showRefineInput" class="search-page__refine-trigger" @click="toggleRefineInput">
          <Pencil :size="14" />
          <span>{{ t('search.refineHint') }}</span>
        </div>

        <div v-if="showRefineInput" class="search-page__refine-area">
          <textarea
            v-model="refineInput"
            class="search-page__refine-input"
            rows="1"
            :placeholder="t('search.refinePlaceholder')"
            @keydown="onRefineKeydown"
            @input="(e: Event) => { const t = e.target as HTMLTextAreaElement; t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 120) + 'px' }"
          ></textarea>
          <button
            class="search-page__refine-send"
            :class="{ 'search-page__refine-send--disabled': !refineInput.trim() }"
            @click="handleRefine"
            :disabled="!refineInput.trim()"
            :aria-label="t('search.sendRefine')"
          >
            <Sparkles :size="14" />
            {{ t('search.updateBtn') }}
          </button>
        </div>
      </div>

      <button
        v-if="hasAnswer && !isStreaming && !isRefining && !showRefineInput"
        class="search-page__new-question"
        @click="startNewQuestion"
      >
        <Sparkles :size="14" />
        {{ t('search.newQuestion') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.search-page {
  max-width: var(--wiki-layout-max);
}

.search-page__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-5);
  flex-wrap: wrap;
}

.search-page__mode-toggle {
  display: flex;
  gap: var(--space-1);
}

.search-page__mode-btn {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-pill);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  border: 1px solid var(--border-default);
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.search-page__mode-btn:hover {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.search-page__mode-btn--active {
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border-color: var(--accent-primary);
}

.search-page__mode-btn--active:hover {
  background: var(--accent-hover);
}

.search-page__search-area {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  position: relative;
}

.search-page__input-wrap {
  display: flex;
  align-items: center;
  background: var(--input-bg);
  border: 2px solid var(--input-border);
  border-radius: var(--radius-lg);
  padding: var(--space-3) var(--space-4);
  transition: border-color var(--transition-fast);
}

.search-page__input-wrap:focus-within {
  border-color: var(--input-focus-border);
  box-shadow: 0 0 0 3px var(--accent-light);
}

.search-page__input-icon {
  color: var(--accent-primary);
  margin-right: var(--space-3);
  flex-shrink: 0;
}

.search-page__input {
  background: none;
  border: none;
  outline: none;
  color: var(--text-primary);
  font-size: var(--font-body-lg);
  width: 100%;
}

.search-page__input::placeholder {
  color: var(--text-tertiary);
}

.search-page__input-clear {
  background: none;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  padding: var(--space-1);
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.search-page__input-clear:hover {
  color: var(--text-secondary);
}

.search-page__suggestions {
  position: absolute;
  top: calc(var(--space-2) + 52px);
  left: 0;
  right: 0;
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  z-index: 10;
  max-height: 200px;
  overflow-y: auto;
}

.search-page__suggestion-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.search-page__suggestion-item:hover {
  background: var(--accent-light);
}

.search-page__suggestion-icon {
  color: var(--accent-primary);
  flex-shrink: 0;
}

.search-page__suggestion-title {
  font-size: var(--font-body);
  color: var(--text-primary);
}

.search-page__suggestion-category {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
  margin-left: auto;
}

.search-page__filter-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.search-page__filter {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.search-page__scope-select {
  position: relative;
}

.search-page__scope-trigger {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
}

.search-page__scope-trigger:hover {
  border-color: var(--accent-primary);
  color: var(--accent-primary);
}

.search-page__scope-chevron {
  transition: transform var(--transition-fast);
}

.search-page__scope-chevron--open {
  transform: rotate(180deg);
}

.search-page__scope-dropdown {
  position: absolute;
  top: calc(100% + var(--space-1));
  right: 0;
  min-width: 180px;
  max-height: 280px;
  overflow-y: auto;
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  z-index: 100;
  padding: var(--space-1);
}

.search-page__scope-option {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: none;
  border-radius: var(--radius-md);
  background: none;
  color: var(--text-primary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: background var(--transition-fast);
  text-align: left;
}

.search-page__scope-option:hover {
  background: var(--accent-light);
}

.search-page__scope-check {
  opacity: 0;
  color: var(--accent-primary);
  flex-shrink: 0;
}

.search-page__scope-option--active .search-page__scope-check {
  opacity: 1;
}

.search-page__scope-option--active {
  color: var(--accent-primary);
  font-weight: var(--weight-medium);
}

.search-page__filter-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.search-page__filter-select {
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: var(--font-body-sm);
  padding: var(--space-1) var(--space-3);
  outline: none;
  cursor: pointer;
}

.search-page__filter-select:focus {
  border-color: var(--input-focus-border);
}

.search-page__filter-clear {
  background: none;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  display: flex;
  align-items: center;
}

.search-page__filter-clear:hover {
  color: var(--text-secondary);
}

.search-page__empty {
  text-align: center;
  padding: var(--space-10) 0;
}

.search-page__empty-icon {
  color: var(--text-tertiary);
  margin-bottom: var(--space-4);
}

.search-page__empty p {
  color: var(--text-secondary);
  font-size: var(--font-body);
}

.search-page__loading {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  color: var(--accent-primary);
  font-size: var(--font-body);
  padding: var(--space-4) 0;
}

.search-page__loading-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.search-page__results {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.search-page__result-card {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-4);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  transition: box-shadow var(--transition-fast);
}

.search-page__result-card:hover {
  box-shadow: var(--shadow-sm);
}

.search-page__result-icon {
  color: var(--accent-primary);
  margin-top: var(--space-1);
  flex-shrink: 0;
}

.search-page__result-info {
  flex: 1;
  min-width: 0;
}

.search-page__result-title-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.search-page__result-title {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--accent-primary);
  text-decoration: none;
}

.search-page__result-scope-badge {
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: var(--accent-primary);
  background: var(--accent-light);
  padding: 1px var(--space-2);
  border-radius: var(--radius-sm);
  white-space: nowrap;
}

.search-page__result-title:hover {
  text-decoration: underline;
}

.search-page__result-title:deep(em) {
  font-style: normal;
  background: var(--accent-light);
  color: var(--accent-primary);
  padding: 0 2px;
  border-radius: 2px;
}

.search-page__result-snippet {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin-top: var(--space-1);
  line-height: 1.5;
}

.search-page__result-snippet:deep(em) {
  font-style: normal;
  background: var(--accent-light);
  color: var(--accent-primary);
  padding: 0 2px;
  border-radius: 2px;
}

.search-page__result-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.search-page__result-category {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
  background: var(--bg-tertiary);
  padding: 1px var(--space-2);
  border-radius: var(--radius-sm);
}

.search-page__result-score {
  font-size: var(--font-body-sm);
  color: var(--success);
}

.search-page__no-results {
  text-align: center;
  padding: var(--space-8);
}

.search-page__no-results p {
  color: var(--text-secondary);
  font-size: var(--font-body);
}

.search-page__query-area {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.search-page__query-input-wrap {
  display: flex;
  align-items: flex-end;
  background: var(--input-bg);
  border: 2px solid var(--input-border);
  border-radius: var(--radius-lg);
  padding: var(--space-2) var(--space-2) var(--space-2) var(--space-4);
  transition: border-color var(--transition-fast);
}

.search-page__query-input-wrap:focus-within {
  border-color: var(--input-focus-border);
  box-shadow: 0 0 0 3px var(--accent-light);
}

.search-page__query-input {
  background: none;
  border: none;
  outline: none;
  color: var(--text-primary);
  font-size: var(--font-body-lg);
  width: 100%;
  resize: none;
  line-height: 1.5;
  padding: var(--space-2) 0;
  min-height: 28px;
  max-height: 160px;
  overflow-y: auto;
}

.search-page__query-input::placeholder {
  color: var(--text-tertiary);
}

.search-page__query-input:disabled {
  opacity: 0.6;
}

.search-page__query-send {
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  padding: var(--space-2);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: opacity var(--transition-fast);
  margin-left: var(--space-2);
}

.search-page__query-send:hover:not(:disabled) {
  opacity: 0.9;
}

.search-page__query-send--disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.search-page__analysis-mode {
  display: flex;
  gap: var(--space-2);
  padding: var(--space-2) 0;
}

.search-page__mode-option {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-primary);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.search-page__mode-option:hover:not(:disabled) {
  background: var(--bg-tertiary);
  border-color: var(--border-secondary);
}

.search-page__mode-option--active {
  background: var(--accent-light);
  border-color: var(--accent-primary);
  color: var(--accent-primary);
}

.search-page__mode-option:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.search-page__progress {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-4);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
}

.search-page__progress-step {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-1) 0;
  transition: opacity var(--transition-normal), color var(--transition-normal);
}

.search-page__progress-step--done {
  opacity: 0.5;
  color: var(--text-tertiary);
}

.search-page__progress-step--running {
  color: var(--accent-primary);
}

.search-page__progress-indicator {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.search-page__progress-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-full);
  background: var(--text-tertiary);
}

.search-page__progress-label {
  font-size: var(--font-body);
}

.search-page__error {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3);
  background: var(--error-light);
  color: var(--error);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
}

.search-page__answer {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  box-shadow: var(--shadow-sm);
  animation: answerFadeIn 0.3s ease-out;
}

@keyframes answerFadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.search-page__fallback-notice {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  background: var(--accent-light);
  color: var(--accent-primary);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-3);
  font-size: var(--font-body-sm);
  border: 1px solid var(--accent-primary);
}

.search-page__fallback-notice--warning {
  background: var(--error-light);
  color: var(--error);
  border-color: var(--error);
}

.search-page__answer-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--accent-primary);
  margin-bottom: var(--space-4);
}

.search-page__streaming-indicator {
  animation: spin 1s linear infinite;
  color: var(--accent-primary);
}

.search-page__saved-badge {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin-left: auto;
  color: var(--success);
  font-size: var(--font-body-sm);
}

.search-page__answer-content {
  margin-bottom: var(--space-2);
}

.search-page__synthesis-hint {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  margin: var(--space-4) 0;
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  animation: fadeIn 0.3s ease-out;
}

.search-page__synthesis-hint--deep {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.08), rgba(168, 85, 247, 0.04));
  border: 1px solid rgba(139, 92, 246, 0.2);
  color: #8b5cf6;
}

.search-page__synthesis-hint--quick {
  background: var(--bg-tertiary);
  color: var(--accent-primary);
}

.search-page__brain-icon {
  animation: brainPulse 2s ease-in-out infinite;
}

.search-page__zap-icon {
  animation: zapFlash 1s ease-in-out infinite;
}

@keyframes brainPulse {
  0%, 100% { 
    transform: scale(1); 
    opacity: 0.8;
    filter: drop-shadow(0 0 0 rgba(139, 92, 246, 0));
  }
  50% { 
    transform: scale(1.1); 
    opacity: 1;
    filter: drop-shadow(0 0 4px rgba(139, 92, 246, 0.4));
  }
}

@keyframes zapFlash {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 1; }
}

.search-page__saving-inline {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--accent-primary);
  font-size: var(--font-body-sm);
  padding: var(--space-3) 0;
}

.search-page__saved-inline {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--success);
  font-size: var(--font-body-sm);
  padding: var(--space-3) 0;
}

.search-page__saved-link {
  color: var(--accent-primary);
  text-decoration: none;
  margin-left: var(--space-2);
}

.search-page__saved-link:hover {
  text-decoration: underline;
}

.search-page__answer-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-4);
  padding-top: var(--space-4);
  border-top: 1px solid var(--border-subtle);
}

.search-page__action-btn {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.search-page__action-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}

.search-page__action-btn--saved {
  background: var(--bg-tertiary);
  color: var(--success);
  cursor: default;
  border: 1px solid var(--border-default);
}

.search-page__action-btn--secondary {
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  border: 1px solid var(--btn-secondary-border);
}

.search-page__action-btn--secondary:hover {
  background: var(--bg-tertiary);
}

.search-page__sources {
  margin-top: var(--space-3);
  padding: var(--space-3);
  background: var(--accent-light);
  border-radius: var(--radius-md);
}

.search-page__sources-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--accent-primary);
  margin-bottom: var(--space-2);
}

.search-page__sources-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.search-page__source-item {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
}

.search-page__source-icon {
  color: var(--accent-primary);
}

.search-page__source-link {
  font-size: var(--font-body-sm);
  color: var(--accent-primary);
  text-decoration: none;
}

.search-page__source-link:hover {
  text-decoration: underline;
}

.search-page__no-info {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3);
  background: var(--error-light);
  color: var(--error);
  border-radius: var(--radius-md);
  margin-top: var(--space-3);
  font-size: var(--font-body-sm);
}

.search-page__refine-trigger {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-4);
  padding: var(--space-2) 0;
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: color var(--transition-fast);
}

.search-page__refine-trigger:hover {
  color: var(--accent-primary);
}

.search-page__refine-area {
  display: flex;
  align-items: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-3);
  padding: var(--space-3);
  background: var(--bg-tertiary);
  border-radius: var(--radius-md);
  animation: refineFadeIn 0.2s ease-out;
}

@keyframes refineFadeIn {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

.search-page__refine-input {
  flex: 1;
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: var(--font-body);
  outline: none;
  resize: none;
  line-height: 1.5;
  padding: var(--space-2) var(--space-3);
  min-height: 28px;
  max-height: 120px;
  overflow-y: auto;
}

.search-page__refine-input:focus {
  border-color: var(--input-focus-border);
}

.search-page__refine-send {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  flex-shrink: 0;
  transition: opacity var(--transition-fast);
}

.search-page__refine-send:hover:not(:disabled) {
  opacity: 0.9;
}

.search-page__refine-send--disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.search-page__new-question {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: none;
  border: 1px dashed var(--border-default);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
  align-self: center;
}

.search-page__new-question:hover {
  border-color: var(--accent-primary);
  color: var(--accent-primary);
  background: var(--accent-light);
}
</style>