<script setup lang="ts">
import { ref, onMounted, computed, watch, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import HealthIndicator from '@/components/wiki/HealthIndicator.vue'
import WikiPageRenderer from '@/components/wiki/WikiPageRenderer.vue'
import LocalGraph from '@/components/wiki/LocalGraph.vue'
import { getPage, getPageByPath, getHealth, updateVisibility, deprecatePage, undeprecatePage, softDeletePage, getDeleteImpact, searchPages, mergePages, type WikiPageInfo, type HealthInfo, type DeleteImpactInfo, type SearchResultInfo } from '@/api/wiki'
import { getSourceContent, getSourcePreviewUrl, getSourceDownloadUrl, type SourceContentInfo } from '@/api/source'
import { MessageCircle, AlertTriangle, Eye, EyeOff, Award, FileWarning, ArrowUp, Clock, ArrowRight, Stethoscope, Loader2, AlertCircle, MoreVertical, Tag, Trash2, Clock as ClockIcon, Ban, Merge as MergeIcon, Search, X, AlertOctagon } from 'lucide-vue-next'
import { useTaskProgressStore } from '@/stores/taskProgress'
import { useToastStore } from '@/stores/toast'
import { getEffectiveHealth, isLintIssue } from '@/utils/healthStatus'
const { t } = useI18n()
const taskStore = useTaskProgressStore()
const toastStore = useToastStore()
const route = useRoute()
const router = useRouter()

const isByPath = computed(() => route.name === 'WikiPageByPath')
const pageId = computed(() => Number(route.params.id))
const pageFilePath = computed(() => (route.params.filePath as string) || '')

const page = ref<WikiPageInfo | null>(null)
const healthInfo = ref<HealthInfo | null>(null)
const loading = ref(true)
const errorMsg = ref('')

const tocItems = ref<TocItem[]>([])
const activeHeadingId = ref('')
const readingProgress = ref(0)
const showBackToTop = ref(false)

const pageTitle = computed(() => page.value?.title || t('wiki.defaultPageTitle'))
const pageHealth = computed(() => getEffectiveHealth(page.value))

const hasConflictWarning = computed(() => pageHealth.value === 'conflict-warning')

const hasHealthIssues = computed(() => isLintIssue(pageHealth.value))

function navigateToLintDiagnostics() {
  router.push('/lint')
}

const estimatedReadingTime = computed(() => {
  if (!page.value?.content) return t('wiki.minuteReading', [1])
  const charCount = page.value.content.length
  const minutes = Math.max(1, Math.ceil(charCount / 600))
  return t('wiki.minuteReading', [minutes])
})

const categoryBreadcrumb = computed(() => {
  if (!page.value?.category) return null
  return page.value.category
})

const lastModified = computed(() => {
  if (!page.value?.lastModified) return null
  return page.value.lastModified
})

interface TocItem {
  id: string
  text: string
  level: number
  children?: TocItem[]
}

function buildNestedToc(flat: TocItem[]): TocItem[] {
  const result: TocItem[] = []
  const stack: TocItem[] = []
  for (const item of flat) {
    const node: TocItem = { ...item, children: [] }
    while (stack.length && stack[stack.length - 1].level >= item.level) {
      stack.pop()
    }
    if (stack.length) {
      stack[stack.length - 1].children!.push(node)
    } else {
      result.push(node)
    }
    stack.push(node)
  }
  return result
}

const hasToc = computed(() => tocItems.value.length > 0)

function handleTocUpdate(headings: TocItem[]) {
  tocItems.value = buildNestedToc(headings)
}

function scrollToHeading(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' })
}

function updateScrollProgress() {
  const el = document.documentElement
  const scrollTop = el.scrollTop || document.body.scrollTop
  const scrollHeight = el.scrollHeight - el.clientHeight
  readingProgress.value = scrollHeight > 0 ? Math.min(100, (scrollTop / scrollHeight) * 100) : 0
  showBackToTop.value = scrollTop > window.innerHeight * 2
}

function updateActiveHeading() {
  const headings = document.querySelectorAll('.wiki-content--enhanced h1[id], .wiki-content--enhanced h2[id], .wiki-content--enhanced h3[id]')
  let currentId = ''
  for (const heading of headings) {
    const rect = heading.getBoundingClientRect()
    if (rect.top <= 120) {
      currentId = heading.id
    }
  }
  if (currentId) {
    activeHeadingId.value = currentId
  }
}

function handleScroll() {
  updateScrollProgress()
  updateActiveHeading()
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function loadPage() {
  loading.value = true
  errorMsg.value = ''
  try {
    if (isByPath.value && pageFilePath.value) {
      page.value = await getPageByPath(pageFilePath.value)
    } else {
      page.value = await getPage(pageId.value)
    }
    if (page.value) {
      healthInfo.value = await getHealth(page.value.id)
      if (isPostSaveProcessing.value) {
        startPostSavePolling()
      }
    }
  } catch (e: any) {
    errorMsg.value = e.message || t('wiki.pageLoadFailed')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadPage()
  window.addEventListener('scroll', handleScroll, { passive: true })
  window.addEventListener('task-completed', handleTaskCompleted)
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
  window.removeEventListener('task-completed', handleTaskCompleted)
})

function handleTaskCompleted(e: Event) {
  const detail = (e as CustomEvent).detail
  if (detail?.type === 'merge') {
    loadPage()
  }
}

watch(() => pageId.value || pageFilePath.value, () => {
  showRecallConfirm.value = false
  loadPage()
})

const showRecallConfirm = ref(false)

// 操作菜单状态
const showActionsMenu = ref(false)
const showDeprecateDialog = ref(false)
const deprecateReason = ref('')
const deprecateSubmitting = ref(false)
const showDeleteConfirm = ref(false)
const deleteImpact = ref<DeleteImpactInfo | null>(null)
const deleteLoading = ref(false)
const deleteSubmitting = ref(false)

const showMergeDialog = ref(false)
const showMergeConfirm = ref(false)
const mergeSearchQuery = ref('')
const mergeSearchResults = ref<SearchResultInfo[]>([])
const mergeSearchLoading = ref(false)
const mergeSelectedTargetId = ref<number | null>(null)
const mergeSelectedTargetTitle = ref('')
const mergeTitle = ref('')
const mergeSubmitting = ref(false)
let mergeSearchTimer: ReturnType<typeof setTimeout> | null = null

const isDeprecated = computed(() => page.value?.lifecycleStatus === 'DEPRECATED')
const isMerged = computed(() => page.value?.lifecycleStatus === 'MERGED')
const isMerging = computed(() => page.value?.lifecycleStatus === 'MERGING')
const isActive = computed(() => page.value?.lifecycleStatus === 'ACTIVE')

const relatedMergeCandidates = computed(() => {
  if (!page.value?.relatedPages) return []
  return page.value.relatedPages.filter(
    p => p.id !== page.value?.id && p.lifecycleStatus === 'ACTIVE'
  )
})
const sourcePreview = ref<SourceContentInfo | null>(null)
const sourcePreviewVisible = ref(false)
const sourcePreviewLoading = ref(false)
const sourcePreviewError = ref('')
const previewSourceId = ref<number | null>(null)

const previewSrc = computed(() => {
  if (previewSourceId.value == null) return ''
  const token = localStorage.getItem('llmwiki-token')
  const baseUrl = getSourcePreviewUrl(previewSourceId.value)
  return token ? `${baseUrl}?token=${token}` : baseUrl
})

const isPromotedPage = computed(() => page.value?.promotedFromScopeId != null)
const isRecalledPage = computed(() => page.value?.healthStatus === 'recalled')
const isEmptyContent = computed(() => !isRecalledPage.value && !page.value?.content)

const postSaveStatus = computed(() => page.value?.postSaveStatus || null)
const postSaveError = computed(() => page.value?.postSaveError || '')
const isPostSaveProcessing = computed(() => postSaveStatus.value === 'pending' || postSaveStatus.value === 'processing')
const isPostSaveFailed = computed(() => postSaveStatus.value === 'failed')
const isPostSavePartial = computed(() => postSaveStatus.value === 'partial')

let postSavePollTimer: ReturnType<typeof setInterval> | null = null

function startPostSavePolling() {
  stopPostSavePolling()
  postSavePollTimer = setInterval(async () => {
    try {
      const updated = isByPath.value && pageFilePath.value
        ? await getPageByPath(pageFilePath.value)
        : await getPage(pageId.value)
      if (updated) {
        const prev = page.value?.postSaveStatus
        page.value = { ...page.value, ...updated }
        if (updated.postSaveStatus !== prev && !isPostSaveProcessing.value) {
          stopPostSavePolling()
        }
      }
    } catch {
      // 轮询失败忽略，下次再试
    }
  }, 3000)
}

function stopPostSavePolling() {
  if (postSavePollTimer) {
    clearInterval(postSavePollTimer)
    postSavePollTimer = null
  }
}

watch(postSaveStatus, (status) => {
  if (status === 'pending' || status === 'processing') {
    startPostSavePolling()
  } else {
    stopPostSavePolling()
  }
})

onUnmounted(() => {
  stopPostSavePolling()
})

function toggleVisibility() {
  if (page.value?.visibility === 'open') {
    showRecallConfirm.value = true
  } else {
    handleVisibilityChange('open')
  }
}

function handleVisibilityChange(visibility: string) {
  if (!page.value) return
  updateVisibility(page.value.id, visibility)
    .then(() => {
      page.value!.visibility = visibility
      showRecallConfirm.value = false
    })
    .catch((e: unknown) => {
      console.error('Visibility update failed:', e)
    })
}

function openDeprecateDialog() {
  showActionsMenu.value = false
  deprecateReason.value = page.value?.deprecatedReason || ''
  showDeprecateDialog.value = true
}

async function submitDeprecate() {
  if (!page.value || !deprecateReason.value.trim()) return
  deprecateSubmitting.value = true
  try {
    if (page.value.lifecycleStatus === 'DEPRECATED') {
      // 更新过期原因
      await undeprecatePage(page.value.id)
      await deprecatePage(page.value.id, deprecateReason.value.trim())
    } else {
      await deprecatePage(page.value.id, deprecateReason.value.trim())
    }
    showDeprecateDialog.value = false
    await loadPage()
  } catch (e) {
    console.error('Deprecate failed:', e)
  } finally {
    deprecateSubmitting.value = false
  }
}

async function handleUndeprecate() {
  if (!page.value) return
  showActionsMenu.value = false
  try {
    await undeprecatePage(page.value.id)
    await loadPage()
  } catch (e) {
    console.error('Undeprecate failed:', e)
  }
}

function openMergeDialog() {
  showActionsMenu.value = false
  showMergeDialog.value = true
  mergeSearchQuery.value = ''
  mergeSearchResults.value = []
  mergeSelectedTargetId.value = null
  mergeSelectedTargetTitle.value = ''
  mergeTitle.value = ''
  showMergeConfirm.value = false
}

function formatSummaryPreview(summary: string): string {
  return summary.length > 60 ? summary.slice(0, 60) + '...' : summary
}

function onMergeSearchInput() {
  if (mergeSearchTimer) clearTimeout(mergeSearchTimer)
  const q = mergeSearchQuery.value.trim()
  if (q.length < 1) {
    mergeSearchResults.value = []
    return
  }
  mergeSearchTimer = setTimeout(async () => {
    mergeSearchLoading.value = true
    try {
      const results = await searchPages(q)
      mergeSearchResults.value = results.filter(
        r => r.id != null && r.id !== page.value?.id && r.resultType === 'WIKI_PAGE'
      )
    } catch {
      mergeSearchResults.value = []
    } finally {
      mergeSearchLoading.value = false
    }
  }, 300)
}

function selectMergeTarget(p: SearchResultInfo | WikiPageInfo) {
  mergeSelectedTargetId.value = p.id!
  mergeSelectedTargetTitle.value = p.title
  mergeSearchQuery.value = ''
  mergeSearchResults.value = []
}

function clearMergeTarget() {
  mergeSelectedTargetId.value = null
  mergeSelectedTargetTitle.value = ''
}

async function submitMerge() {
  if (!page.value || !mergeSelectedTargetId.value || mergeSubmitting.value) return
  mergeSubmitting.value = true
  try {
    const result = await mergePages(
      [page.value.id, mergeSelectedTargetId.value],
      mergeTitle.value.trim() || undefined
    )
    showMergeConfirm.value = false
    showMergeDialog.value = false
    taskStore.addTask(result.executionId, 'merge', t('wiki.mergeTaskLabel', [page.value.title, mergeSelectedTargetTitle.value]))
    toastStore.success(t('wiki.mergeSubmitted'), t('wiki.mergeSuccessDetail', [page.value.title, mergeSelectedTargetTitle.value]))
    await loadPage()
  } catch (e: any) {
    showMergeConfirm.value = false
    const msg = e?.message || e?.extra?.message || t('wiki.mergeRequestFailed')
    toastStore.error(t('wiki.mergeFailed'), msg)
    console.error('Merge failed:', e)
  } finally {
    mergeSubmitting.value = false
  }
}

async function openDeleteConfirm() {
  showActionsMenu.value = false
  if (!page.value) return
  deleteLoading.value = true
  showDeleteConfirm.value = true
  deleteImpact.value = null
  try {
    deleteImpact.value = await getDeleteImpact(page.value.id)
  } catch (e) {
    console.error('Delete impact failed:', e)
  } finally {
    deleteLoading.value = false
  }
}

async function handleSoftDelete() {
  if (!page.value) return
  deleteSubmitting.value = true
  try {
    await softDeletePage(page.value.id)
    showDeleteConfirm.value = false
    router.push({ name: 'WikiHome' })
  } catch (e) {
    console.error('Delete failed:', e)
  } finally {
    deleteSubmitting.value = false
  }
}

function toggleActionsMenu() {
  showActionsMenu.value = !showActionsMenu.value
}

function askAboutPage() {
  router.push({ path: '/search', query: { query: pageTitle.value, mode: 'query' } })
}

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
      console.error('Failed to load source content:', e)
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
    .catch((e: unknown) => {
      console.error('Download failed:', e)
    })
}

function closePreview() {
  sourcePreviewVisible.value = false
  sourcePreview.value = null
  sourcePreviewError.value = ''
  previewSourceId.value = null
}
</script>

<template>
  <div class="wiki-page">
    <div class="wiki-page__progress-bar" :style="{ width: readingProgress + '%' }"></div>

    <div v-if="loading" class="wiki-page__loading">{{ t('common.loading') }}</div>
    <div v-else-if="errorMsg" class="wiki-page__error">{{ errorMsg }}</div>
    <template v-else-if="page">
      <div class="wiki-page__header">
        <h1 class="wiki-page__title">{{ pageTitle }}</h1>
        <div class="wiki-page__meta-row">
          <div class="wiki-page__meta-left">
            <span v-if="categoryBreadcrumb" class="wiki-page__breadcrumb">
              {{ t('wiki.knowledgeBase') }}
              <ArrowRight :size="12" />
              {{ categoryBreadcrumb }}
            </span>
            <HealthIndicator :health="pageHealth" />
            <button
              v-if="hasHealthIssues"
              class="wiki-page__diagnostics-link"
              @click="navigateToLintDiagnostics"
              :title="t('wiki.viewDiagnosticsTitle')"
            >
              <Stethoscope :size="12" />
              {{ t('wiki.viewDiagnostics') }}
            </button>
            <span v-if="lastModified" class="wiki-page__updated">
              <Clock :size="12" />
              {{ lastModified }}
            </span>
            <span class="wiki-page__reading-time">{{ estimatedReadingTime }}{{ t('wiki.readingSuffix') }}</span>
          </div>
          <div class="wiki-page__actions">
            <div v-if="!isRecalledPage && !isMerging" class="wiki-page__actions-menu">
              <button class="wiki-page__action-btn wiki-page__action-btn--icon" @click="toggleActionsMenu" :title="t('wiki.moreActions')">
                <MoreVertical :size="14" />
              </button>
              <div v-if="showActionsMenu" class="wiki-page__actions-dropdown">
                <button v-if="!isDeprecated" class="wiki-page__dropdown-item" @click="openDeprecateDialog">
                  <ClockIcon :size="14" />
                  {{ t('wiki.markDeprecated') }}
                </button>
                <button v-else class="wiki-page__dropdown-item" @click="handleUndeprecate">
                  <Ban :size="14" />
                  {{ t('wiki.unmarkDeprecated') }}
                </button>
                <button v-if="isActive" class="wiki-page__dropdown-item" @click="openMergeDialog">
                  <MergeIcon :size="14" />
                  {{ t('wiki.mergeToOther') }}
                </button>
                <button class="wiki-page__dropdown-item wiki-page__dropdown-item--danger" @click="openDeleteConfirm">
                  <Trash2 :size="14" />
                  {{ t('wiki.deletePage') }}
                </button>
                <button class="wiki-page__dropdown-item" @click="router.push({ name: 'WikiEditorEdit', params: { pageId: page!.id } })">
                  <Tag :size="14" />
                  {{ t('wiki.editPage') }}
                </button>
              </div>
            </div>
            <button class="wiki-page__action-btn" @click="askAboutPage">
              <MessageCircle :size="14" />
              {{ t('wiki.askQuestion') }}
            </button>
            <button
              v-if="!isPromotedPage && !isRecalledPage"
              class="wiki-page__action-btn"
              :class="{ 'wiki-page__action-btn--private': page?.visibility === 'private' }"
              @click="toggleVisibility"
            >
              <EyeOff v-if="page?.visibility === 'private'" :size="14" />
              <Eye v-else :size="14" />
              {{ page?.visibility === 'private' ? t('wiki.privateLabel') : t('wiki.openSharing') }}
            </button>
          </div>
        </div>

        <div v-if="showRecallConfirm" class="wiki-page__recall-confirm">
          <p class="wiki-page__recall-confirm-text">
            {{ t('wiki.recallConfirmText') }}
          </p>
          <div class="wiki-page__recall-confirm-actions">
            <button class="wiki-page__modify-cancel" @click="showRecallConfirm = false">{{ t('wiki.cancel') }}</button>
            <button class="wiki-page__recall-confirm-submit" @click="handleVisibilityChange('private')">{{ t('wiki.confirmExit') }}</button>
          </div>
        </div>
      </div>

      <Transition name="post-save-fade">
        <div v-if="isPostSaveProcessing" class="wiki-page__post-save-banner wiki-page__post-save-banner--processing">
          <Loader2 :size="14" class="wiki-page__post-save-spin" />
          <span>{{ t('wiki.postSaveProcessing') }}</span>
        </div>
      </Transition>

      <Transition name="post-save-fade">
        <div v-if="isPostSaveFailed" class="wiki-page__post-save-banner wiki-page__post-save-banner--failed">
          <AlertOctagon :size="14" />
          <span class="wiki-page__post-save-text">{{ t('wiki.postSaveFailedPrefix') }}{{ postSaveError ? t('common.colonSeparator') + postSaveError : '' }}</span>
        </div>
      </Transition>

      <Transition name="post-save-fade">
        <div v-if="isPostSavePartial" class="wiki-page__post-save-banner wiki-page__post-save-banner--partial">
          <AlertTriangle :size="14" />
          <span class="wiki-page__post-save-text">{{ t('wiki.postSavePartialPrefix') }}{{ postSaveError ? t('common.colonSeparator') + postSaveError : '' }}</span>
        </div>
      </Transition>

      <div v-if="hasConflictWarning" class="wiki-page__conflict-banner">
        <AlertCircle :size="16" />
        <span class="wiki-page__conflict-banner-text">{{ t('wiki.conflictWarningText') }}</span>
        <button class="wiki-page__conflict-banner-action" @click="navigateToLintDiagnostics">{{ t('wiki.viewDetails') }}</button>
      </div>

            <div v-if="isMerging" class="wiki-page__merging-banner">
              <Loader2 :size="16" class="wiki-page__merging-banner-spin" />
              <div class="wiki-page__merging-banner-content">
                <strong>{{ t('wiki.mergingInProgress') }}</strong>
                <span>{{ t('wiki.mergingHint') }}</span>
              </div>
            </div>
      
      <div v-if="isDeprecated && !isMerging" class="wiki-page__deprecated-banner">
        <ClockIcon :size="16" />
        <div class="wiki-page__deprecated-banner-content">
          <strong>{{ t('wiki.contentDeprecated') }}</strong>
          <span v-if="page?.deprecatedReason">{{ page.deprecatedReason }}</span>
          <span class="wiki-page__deprecated-banner-time">{{ t('wiki.markedAt') }} {{ page?.deprecatedAt }}</span>
        </div>
        <button class="wiki-page__deprecated-banner-action" @click="handleUndeprecate">{{ t('wiki.unmarkLabel') }}</button>
      </div>

      <div v-if="isMerged && !isMerging" class="wiki-page__merged-banner">
        <MergeIcon :size="16" />
        <div class="wiki-page__merged-banner-content">
          <strong>{{ t('wiki.pageMerged') }}</strong>
          <span v-if="page?.mergedIntoPageId">
            {{ t('wiki.contentMergedTo') }}
            <router-link :to="`/wiki/${page.mergedIntoPageId}`" class="wiki-page__merged-banner-link">{{ t('wiki.targetPage') }}</router-link>
          </span>
        </div>
      </div>

      <div v-if="showDeprecateDialog" class="wiki-page__modify-dialog">
        <h3 class="wiki-page__modify-title">{{ isDeprecated ? t('wiki.updateDeprecateReason') : t('wiki.markDeprecated') }}</h3>
        <p class="wiki-page__modify-hint">{{ t('wiki.deprecateHint') }}</p>
        <textarea
          v-model="deprecateReason"
          class="wiki-page__modify-input"
          :placeholder="t('wiki.deprecatePlaceholder')"
          rows="3"
          :disabled="deprecateSubmitting"
        ></textarea>
        <div class="wiki-page__modify-actions">
          <button class="wiki-page__modify-cancel" @click="showDeprecateDialog = false" :disabled="deprecateSubmitting">{{ t('wiki.cancel') }}</button>
          <button class="wiki-page__modify-submit" :disabled="deprecateSubmitting || !deprecateReason.trim()" @click="submitDeprecate">
            {{ deprecateSubmitting ? t('wiki.submitting') : t('wiki.confirmMark') }}
          </button>
        </div>
      </div>

      <div v-if="showDeleteConfirm" class="wiki-page__delete-confirm">
        <h3 class="wiki-page__delete-confirm-title">
          <Trash2 :size="18" />
          {{ t('wiki.deletePage') }}
        </h3>
        <div v-if="deleteLoading" class="wiki-page__delete-confirm-loading">
          <Loader2 :size="16" class="wiki-page__modify-status-icon" />
          {{ t('wiki.loadImpact') }}
        </div>
        <template v-else-if="deleteImpact">
          <p class="wiki-page__delete-confirm-hint">{{ t('wiki.deleteHint') }}</p>
          <div class="wiki-page__delete-impact">
            <div class="wiki-page__delete-impact-item">
              <span class="wiki-page__delete-impact-label">{{ t('wiki.inboundLinks') }}</span>
              <span class="wiki-page__delete-impact-value">{{ t('wiki.inboundLinksDesc', [deleteImpact.inboundLinks]) }}</span>
            </div>
            <div class="wiki-page__delete-impact-item">
              <span class="wiki-page__delete-impact-label">{{ t('wiki.outboundLinks') }}</span>
              <span class="wiki-page__delete-impact-value">{{ t('wiki.outboundLinksDesc', [deleteImpact.outboundLinks]) }}</span>
            </div>
            <div class="wiki-page__delete-impact-item">
              <span class="wiki-page__delete-impact-label">{{ t('wiki.sourceLinks') }}</span>
              <span class="wiki-page__delete-impact-value">{{ t('wiki.sourceLinksDesc', [deleteImpact.sourceCount]) }}</span>
            </div>
          </div>
        </template>
        <div class="wiki-page__modify-actions">
          <button class="wiki-page__modify-cancel" @click="showDeleteConfirm = false" :disabled="deleteSubmitting">{{ t('wiki.cancel') }}</button>
          <button class="wiki-page__delete-confirm-btn" :disabled="deleteSubmitting || deleteLoading" @click="handleSoftDelete">
            {{ deleteSubmitting ? t('wiki.deleting') : t('wiki.confirmDeleteTitle') }}
          </button>
        </div>
      </div>

      <div v-if="showMergeDialog" class="wiki-page__merge-overlay" @click.self="showMergeDialog = false">
        <div class="wiki-page__merge-dialog">
          <div class="wiki-page__merge-dialog-header">
            <h3 class="wiki-page__merge-dialog-title">
              <MergeIcon :size="18" />
              {{ t('wiki.mergeToOther') }}
            </h3>
            <button class="wiki-page__merge-dialog-close" @click="showMergeDialog = false">&times;</button>
          </div>
          <p class="wiki-page__merge-dialog-hint">
            {{ t('wiki.mergeDialogHint', [page?.title]) }}
          </p>

        <template v-if="!mergeSelectedTargetId">
          <div v-if="relatedMergeCandidates.length > 0" class="wiki-page__merge-candidates">
            <label class="wiki-page__merge-label">{{ t('wiki.relatedPagesLabel') }}</label>
            <div class="wiki-page__merge-candidate-list">
              <button
                v-for="p in relatedMergeCandidates"
                :key="p.id"
                class="wiki-page__merge-candidate"
                @click="selectMergeTarget(p)"
              >
                <span class="wiki-page__merge-candidate-title">{{ p.title }}</span>
                <span class="wiki-page__merge-candidate-cat">{{ p.category || t('wiki.uncategorized') }}</span>
              </button>
            </div>
            <div class="wiki-page__merge-divider">
              <span>{{ t('wiki.orSearchOther') }}</span>
            </div>
          </div>
          <div class="wiki-page__merge-search">
            <label v-if="relatedMergeCandidates.length === 0" class="wiki-page__merge-label">{{ t('wiki.selectTargetPage') }}</label>
            <div class="wiki-page__merge-search-box">
              <Search :size="14" class="wiki-page__merge-search-icon" />
              <input
                v-model="mergeSearchQuery"
                class="wiki-page__merge-search-input"
                :placeholder="t('wiki.searchPagePlaceholder')"
                @input="onMergeSearchInput"
              />
              <Loader2 v-if="mergeSearchLoading" :size="14" class="wiki-page__merge-search-spinner" />
            </div>
            <div v-if="mergeSearchResults.length > 0" class="wiki-page__merge-results">
              <button
                v-for="p in mergeSearchResults"
                :key="p.id!"
                class="wiki-page__merge-result"
                @click="selectMergeTarget(p)"
              >
                <div class="wiki-page__merge-result-main">
                  <span class="wiki-page__merge-result-title">{{ p.title }}</span>
                  <span v-if="p.summary" class="wiki-page__merge-result-summary">{{ formatSummaryPreview(p.summary) }}</span>
                </div>
                <span class="wiki-page__merge-result-cat">{{ p.category || t('wiki.uncategorized') }}</span>
              </button>
            </div>
            <p v-else-if="mergeSearchQuery && !mergeSearchLoading" class="wiki-page__merge-no-result">{{ t('wiki.noMatchFound') }}</p>
          </div>
        </template>

        <div v-else class="wiki-page__merge-selected">
          <MergeIcon :size="14" class="wiki-page__merge-selected-icon" />
          <div class="wiki-page__merge-selected-info">
            <span class="wiki-page__merge-selected-title">{{ mergeSelectedTargetTitle }}</span>
            <span class="wiki-page__merge-selected-hint">{{ t('wiki.mergeTarget') }}</span>
          </div>
          <button class="wiki-page__merge-selected-clear" @click="clearMergeTarget" :title="t('wiki.reselectTitle')">
            <X :size="14" />
          </button>
        </div>

        <div class="wiki-page__merge-title-field">
          <label class="wiki-page__merge-label">{{ t('wiki.mergeTitleOptional') }}</label>
          <input
            v-model="mergeTitle"
            class="wiki-page__merge-title-input"
            :placeholder="t('wiki.mergeTitleAutoPlaceholder')"
          />
        </div>
        <div class="wiki-page__modify-actions">
          <button class="wiki-page__modify-cancel" @click="showMergeDialog = false" :disabled="mergeSubmitting">{{ t('wiki.cancel') }}</button>
          <button
            class="wiki-page__merge-submit"
            :disabled="!mergeSelectedTargetId || mergeSubmitting"
            @click="showMergeConfirm = true"
          >
            <ArrowRight :size="14" />
            {{ t('wiki.nextStep') }}
          </button>
        </div>

          <div v-if="showMergeConfirm" class="wiki-page__merge-confirm">
            <div class="wiki-page__merge-confirm-overlay" @click.self="showMergeConfirm = false">
              <div class="wiki-page__merge-confirm-card">
                <div class="wiki-page__merge-confirm-header">
                  <AlertTriangle :size="20" class="wiki-page__merge-confirm-icon" />
                  <h4 class="wiki-page__merge-confirm-title">{{ t('wiki.confirmMerge') }}</h4>
                </div>
                <div class="wiki-page__merge-confirm-body">
                  <div class="wiki-page__merge-confirm-flow">
                    <div class="wiki-page__merge-confirm-page">
                      <span class="wiki-page__merge-confirm-page-label">{{ t('wiki.currentPage') }}</span>
                      <span class="wiki-page__merge-confirm-page-name">{{ page?.title }}</span>
                    </div>
                    <ArrowRight :size="16" class="wiki-page__merge-confirm-arrow" />
                    <div class="wiki-page__merge-confirm-page wiki-page__merge-confirm-page--target">
                      <span class="wiki-page__merge-confirm-page-label">{{ t('wiki.mergeTo') }}</span>
                      <span class="wiki-page__merge-confirm-page-name">{{ mergeSelectedTargetTitle }}</span>
                    </div>
                  </div>
                  <p class="wiki-page__merge-confirm-warning">
                    {{ t('wiki.mergeConfirmWarning') }}
                  </p>
                </div>
                <div class="wiki-page__modify-actions">
                  <button class="wiki-page__modify-cancel" @click="showMergeConfirm = false" :disabled="mergeSubmitting">{{ t('wiki.back') }}</button>
                  <button
                    class="wiki-page__merge-confirm-btn"
                    :disabled="mergeSubmitting"
                    @click="submitMerge"
                  >
                    <Loader2 v-if="mergeSubmitting" :size="14" class="wiki-page__merge-btn-spinner" />
                    <MergeIcon v-else :size="14" />
                    {{ mergeSubmitting ? t('wiki.mergingEllipsis') : t('wiki.confirmMerge') }}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="wiki-page__body">
        <div class="wiki-page__content">
          <WikiPageRenderer
            :content="page.content"
            :link-resolution="page.linkResolution"
            :sources="page.sources"
            @toc="handleTocUpdate"
            @preview-source="openSourcePreview"
          />
        </div>

        <aside v-if="hasToc" class="wiki-page__sidebar">
          <div class="wiki-page__sidebar-inner">
            <h4 class="wiki-page__toc-title">{{ t('wiki.tableOfContents') }}</h4>
            <nav class="wiki-page__toc-nav">
              <template v-for="item in tocItems" :key="item.id">
                <a
                  :href="`#${item.id}`"
                  class="wiki-page__toc-link"
                  :class="{
                    'wiki-page__toc-link--active': activeHeadingId === item.id,
                    [`wiki-page__toc-link--h${item.level}`]: true
                  }"
                  @click.prevent="scrollToHeading(item.id)"
                >{{ item.text }}</a>
                <template v-for="child in item.children" :key="child.id">
                  <a
                    :href="`#${child.id}`"
                    class="wiki-page__toc-link wiki-page__toc-link--h3"
                    :class="{ 'wiki-page__toc-link--active': activeHeadingId === child.id }"
                    @click.prevent="scrollToHeading(child.id)"
                  >{{ child.text }}</a>
                </template>
              </template>
            </nav>
          </div>
        </aside>
      </div>

      <div v-if="page" class="wiki-page__relations">
        <LocalGraph
          :page-id="page.id"
          :sources="page.sources"
          @preview-source="openSourcePreview"
          @download-source="downloadSource"
        />
      </div>

      <div v-if="isEmptyContent" class="wiki-page__recalled-stub">
        <FileWarning :size="16" />
        {{ t('wiki.emptyContentHint') }}
      </div>

      <div v-if="isRecalledPage" class="wiki-page__recalled-stub">
        <AlertTriangle :size="16" />
        {{ t('wiki.recalledStub') }}
      </div>

      <div v-if="isPromotedPage && page?.promotedFromUsername" class="wiki-page__honor-badge">
        <Award :size="14" />
        {{ t('wiki.promotedFromPrefix') }} {{ page.promotedFromUsername }} {{ t('wiki.promotedFromSuffix') }}
      </div>

      <div v-if="healthInfo && healthInfo.issues.length > 0" class="wiki-page__health-detail">
        <h3 class="wiki-page__health-detail-title">
          <AlertTriangle :size="16" />
          {{ t('wiki.healthDetailTitle') }}
        </h3>
        <div class="wiki-page__health-issues">
          <div v-for="issue in healthInfo.issues" :key="issue" class="wiki-page__health-issue">
            {{ issue }}
          </div>
        </div>
        <div v-if="healthInfo.suggestions.length > 0" class="wiki-page__health-suggestions">
          <div v-for="suggestion in healthInfo.suggestions" :key="suggestion" class="wiki-page__health-suggestion">
            {{ suggestion }}
          </div>
        </div>
      </div>

      <div v-if="sourcePreviewVisible" class="wiki-page__source-preview-overlay" @click.self="closePreview">
        <div class="wiki-page__source-preview" :class="{ 'wiki-page__source-preview--wide': sourcePreview?.previewType === 'pdf' }">
          <div class="wiki-page__source-preview-header">
            <h3 class="wiki-page__source-preview-title">{{ sourcePreview?.name || t('wiki.sourcePreview') }}</h3>
            <button class="wiki-page__source-preview-close" @click="closePreview">&times;</button>
          </div>
          <div v-if="sourcePreviewLoading" class="wiki-page__source-preview-loading">{{ t('common.loading') }}</div>
          <div v-else-if="sourcePreviewError" class="wiki-page__source-preview-error">{{ sourcePreviewError }}</div>
          <template v-else-if="sourcePreview">
            <div v-if="sourcePreview.previewType === 'image'" class="wiki-page__source-preview-media">
              <img :src="previewSrc" :alt="t('wiki.previewImage')" class="wiki-page__source-preview-img" />
            </div>
            <div v-else-if="sourcePreview.previewType === 'pdf'" class="wiki-page__source-preview-media wiki-page__source-preview-media--pdf">
              <iframe :src="previewSrc" class="wiki-page__source-preview-pdf" frameborder="0" />
            </div>
            <div v-else-if="sourcePreview.hasParsedContent" class="wiki-page__source-preview-content">
              <WikiPageRenderer :content="sourcePreview.parsedContent" />
            </div>
            <div v-else class="wiki-page__source-preview-empty">
              <p>{{ sourcePreview.previewType === 'text' ? t('wiki.textFileNoContent') : t('wiki.noParsedContent') }}</p>
              <p v-if="sourcePreview.previewType !== 'text'" class="wiki-page__source-preview-empty-hint">{{ t('wiki.noParsedContentHint') }}</p>
            </div>
          </template>
        </div>
      </div>

    </template>

    <button v-if="showBackToTop" class="wiki-page__back-to-top" @click="scrollToTop">
      <ArrowUp :size="18" />
    </button>
  </div>
</template>

<style scoped>
.wiki-page {
  max-width: var(--wiki-layout-max);
  margin: 0 auto;
  position: relative;
}

.wiki-page__progress-bar {
  position: fixed;
  top: 0;
  left: 0;
  height: 2px;
  background: var(--accent-primary);
  z-index: 100;
  transition: width 0.1s linear;
}

.wiki-page__header {
  margin-bottom: var(--space-6);
}

.wiki-page__title {
  font-family: var(--font-heading);
  font-size: var(--font-h1);
  font-weight: var(--weight-bold);
  line-height: var(--leading-h1);
  color: var(--text-primary);
  margin-bottom: var(--space-3);
}

.wiki-page__meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.wiki-page__meta-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.wiki-page__breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}

.wiki-page__updated {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.wiki-page__reading-time {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}

.wiki-page__diagnostics-link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  background: none;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
  font-size: var(--font-caption);
  color: var(--accent-primary);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.wiki-page__diagnostics-link:hover {
  background: var(--accent-light);
}

.wiki-page__actions {
  display: flex;
  gap: var(--space-2);
}

.wiki-page__action-btn {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  border: none;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
  font-family: var(--font-body);
}

.wiki-page__action-btn:hover {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.wiki-page__action-btn--primary {
  background: var(--accent-primary);
  color: var(--text-on-accent);
}

.wiki-page__action-btn--primary:hover {
  background: var(--accent-hover);
}

.wiki-page__action-btn--private {
  background: var(--warning);
  color: var(--text-on-accent);
}

.wiki-page__modify-dialog {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  margin-bottom: var(--space-6);
  box-shadow: var(--shadow-sm);
}

.wiki-page__modify-title {
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-bottom: var(--space-2);
}

.wiki-page__modify-hint {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin-bottom: var(--space-3);
}

.wiki-page__modify-input {
  width: 100%;
  padding: var(--space-3);
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: var(--font-body);
  font-family: var(--font-body);
  resize: vertical;
  outline: none;
  transition: border-color var(--transition-fast);
}

.wiki-page__modify-input:focus {
  border-color: var(--input-focus-border);
}

.wiki-page__modify-input::placeholder {
  color: var(--text-tertiary);
}

.wiki-page__modify-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.wiki-page__modify-cancel {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  border: 1px solid var(--btn-secondary-border);
  font-size: var(--font-body-sm);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-page__modify-cancel:hover {
  opacity: 0.85;
}

.wiki-page__modify-submit {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-page__modify-submit:hover {
  background: var(--accent-hover);
}

.wiki-page__modify-submit:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.wiki-page__body {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-6);
  margin-bottom: var(--space-6);
  max-width: var(--wiki-layout-max);
}

@media (min-width: 1024px) {
  .wiki-page__body:has(.wiki-page__sidebar) {
    grid-template-columns: 1fr var(--wiki-toc-width);
  }
}

.wiki-page__content {
  min-width: 0;
}

.wiki-page__sidebar {
  position: relative;
}

.wiki-page__sidebar-inner {
  position: sticky;
  top: calc(var(--topbar-height) + var(--space-4));
  max-height: calc(100vh - var(--topbar-height) - var(--space-8));
  overflow-y: auto;
}

.wiki-page__relations {
  max-width: var(--wiki-layout-max);
  margin-bottom: var(--space-6);
}

.wiki-page__toc-title {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-tertiary);
  letter-spacing: 0.05em;
  margin-bottom: var(--space-3);
}

.wiki-page__toc-nav {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.wiki-page__toc-link {
  display: block;
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  text-decoration: none;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wiki-page__toc-link:hover {
  color: var(--accent-primary);
  background: var(--accent-light);
}

.wiki-page__toc-link--active {
  color: var(--accent-primary);
  background: var(--accent-light);
  font-weight: var(--weight-medium);
}

.wiki-page__toc-link--h2 {
  font-size: var(--font-body-sm);
}

.wiki-page__toc-link--h3 {
  font-size: var(--font-caption);
  padding-left: var(--space-4);
  color: var(--text-tertiary);
}

.wiki-page__toc-link--h3.wiki-page__toc-link--active {
  color: var(--accent-primary);
}

.wiki-page__health-detail {
  border-top: 1px solid var(--border-default);
  padding-top: var(--space-5);
  margin-bottom: var(--space-6);
}

.wiki-page__health-detail-title {
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--warning);
  margin-bottom: var(--space-3);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.wiki-page__health-issues {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.wiki-page__health-issue {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--bg-tertiary);
}

.wiki-page__health-suggestions {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.wiki-page__health-suggestion {
  font-size: var(--font-body-sm);
  color: var(--accent-primary);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--accent-light);
}

.wiki-page__source-preview-overlay {
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

.wiki-page__source-preview {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  width: 90%;
  max-width: 720px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-sm);
}

.wiki-page__source-preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border-default);
}

.wiki-page__source-preview-title {
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin: 0;
}

.wiki-page__source-preview-close {
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
  transition: all var(--transition-fast);
}

.wiki-page__source-preview-close:hover {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.wiki-page__source-preview-loading,
.wiki-page__source-preview-empty,
.wiki-page__source-preview-error {
  padding: var(--space-8) var(--space-5);
  text-align: center;
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
}

.wiki-page__source-preview-error {
  color: var(--accent-red, #e74c3c);
}

.wiki-page__source-preview-empty-hint {
  margin-top: var(--space-2);
  font-size: var(--font-body-xs, 12px);
  color: var(--text-tertiary);
}

.wiki-page__source-preview-media {
  padding: var(--space-4);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: auto;
  flex: 1;
}

.wiki-page__source-preview-img {
  max-width: 100%;
  max-height: 60vh;
  object-fit: contain;
  border-radius: var(--radius-sm);
}

.wiki-page__source-preview-media--pdf {
  padding: 0;
}

.wiki-page__source-preview-pdf {
  width: 100%;
  height: 70vh;
  border: none;
}

.wiki-page__source-preview--wide {
  max-width: 900px;
}

.wiki-page__source-preview-content {
  padding: var(--space-5);
  overflow-y: auto;
  flex: 1;
}

.wiki-page__recall-confirm {
  background: var(--surface-card);
  border: 1px solid var(--warning);
  border-radius: var(--radius-lg);
  padding: var(--space-4);
  margin-bottom: var(--space-4);
}

.wiki-page__recall-confirm-text {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  margin-bottom: var(--space-3);
}

.wiki-page__recall-confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}

.wiki-page__recall-confirm-submit {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--warning);
  color: var(--text-on-accent);
  border: none;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-page__recall-confirm-submit:hover {
  opacity: 0.85;
}

.wiki-page__recalled-stub {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  margin-bottom: var(--space-4);
}

.wiki-page__honor-badge {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--accent-light);
  color: var(--accent-primary);
  font-size: var(--font-body-sm);
  margin-bottom: var(--space-4);
}

.wiki-page__conflict-banner {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--warning-light, #fff3cd);
  border: 1px solid var(--warning);
  color: var(--warning);
  font-size: var(--font-body-sm);
  margin-bottom: var(--space-4);
}

.wiki-page__conflict-banner-text {
  flex: 1;
}

.wiki-page__conflict-banner-action {
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--warning);
  color: var(--text-on-accent);
  border: none;
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-page__conflict-banner-action:hover {
  opacity: 0.85;
}

.wiki-page__back-to-top {
  position: fixed;
  bottom: var(--space-6);
  right: var(--space-6);
  width: 40px;
  height: 40px;
  border-radius: var(--radius-full);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow-sm);
  transition: all var(--transition-fast);
  z-index: 50;
}

.wiki-page__back-to-top:hover {
  background: var(--accent-light);
  color: var(--accent-primary);
  border-color: var(--accent-primary);
}

.wiki-page__modify-status-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 操作菜单下拉 */
.wiki-page__actions-menu {
  position: relative;
}

.wiki-page__action-btn--icon {
  padding: var(--space-2);
  min-width: 32px;
  justify-content: center;
}

.wiki-page__actions-dropdown {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: var(--space-1);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  z-index: 50;
  min-width: 180px;
  padding: var(--space-1) 0;
}

.wiki-page__dropdown-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-2) var(--space-3);
  background: none;
  border: none;
  font-size: var(--font-body-sm);
  font-family: var(--font-body);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
  text-align: left;
}

.wiki-page__dropdown-item:hover {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.wiki-page__dropdown-item--danger:hover {
  background: var(--error-light, #fde8e8);
  color: var(--error);
}

.wiki-page__dropdown-item--disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.wiki-page__dropdown-item--disabled:hover {
  background: none;
  color: var(--text-secondary);
}

/* 合并进行中横幅 */
.wiki-page__merging-banner {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  background: oklch(0.95 0.02 250);
  border: 1px solid oklch(0.85 0.04 250);
  margin-bottom: var(--space-4);
}

.wiki-page__merging-banner-spin {
  color: var(--accent-primary);
  animation: pulse-spin 1s linear infinite;
  flex-shrink: 0;
  margin-top: 2px;
}

.wiki-page__merging-banner-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.wiki-page__merging-banner-content strong {
  font-size: var(--font-body-sm);
  color: var(--accent-primary);
}

.wiki-page__merging-banner-content span {
  font-size: var(--font-caption);
  color: var(--text-secondary);
}

/* 过期横幅 */
.wiki-page__deprecated-banner {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--warning-light, #fff3cd);
  border: 1px solid var(--warning);
  color: var(--warning);
  font-size: var(--font-body-sm);
  margin-bottom: var(--space-4);
}

.wiki-page__deprecated-banner-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.wiki-page__deprecated-banner-time {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.wiki-page__deprecated-banner-action {
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--warning);
  color: var(--text-on-accent);
  border: none;
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  cursor: pointer;
  white-space: nowrap;
  transition: all var(--transition-fast);
}

.wiki-page__deprecated-banner-action:hover {
  opacity: 0.85;
}

.wiki-page__merged-banner {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--accent-light);
  border: 1px solid var(--accent-primary);
  color: var(--accent-primary);
  font-size: var(--font-body-sm);
  margin-bottom: var(--space-4);
}

.wiki-page__merged-banner-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.wiki-page__merged-banner-link {
  color: var(--accent-primary);
  font-weight: var(--weight-medium);
  text-decoration: underline;
}

/* 删除确认弹窗 */
.wiki-page__delete-confirm {
  background: var(--surface-card);
  border: 1px solid var(--error, #e74c3c);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  margin-bottom: var(--space-6);
  box-shadow: var(--shadow-sm);
}

.wiki-page__delete-confirm-title {
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--error, #e74c3c);
  margin-bottom: var(--space-3);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.wiki-page__delete-confirm-hint {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin-bottom: var(--space-3);
}

.wiki-page__delete-confirm-loading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) 0;
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
}

.wiki-page__delete-impact {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.wiki-page__delete-impact-item {
  display: flex;
  justify-content: space-between;
  padding: var(--space-2) var(--space-3);
  background: var(--bg-tertiary);
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
}

.wiki-page__delete-impact-label {
  color: var(--text-tertiary);
  font-weight: var(--weight-medium);
}

.wiki-page__delete-impact-value {
  color: var(--text-primary);
}

.wiki-page__delete-confirm-btn {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--error, #e74c3c);
  color: #fff;
  border: none;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-page__delete-confirm-btn:hover {
  opacity: 0.85;
}

.wiki-page__delete-confirm-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 合并对话框 */
.wiki-page__merge-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(2px);
}

.wiki-page__merge-dialog {
  width: 90%;
  max-width: 520px;
  max-height: 80vh;
  overflow-y: auto;
  background: var(--surface-card);
  border: 1px solid var(--accent-primary);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  box-shadow: var(--shadow-lg, 0 8px 24px rgba(0, 0, 0, 0.15));
}

.wiki-page__merge-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}

.wiki-page__merge-dialog-title {
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--accent-primary);
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
}

.wiki-page__merge-dialog-close {
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

.wiki-page__merge-dialog-close:hover {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.wiki-page__merge-dialog-hint {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin-bottom: var(--space-4);
}

.wiki-page__merge-label {
  display: block;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  margin-bottom: var(--space-2);
}

.wiki-page__merge-search {
  margin-bottom: var(--space-4);
}

.wiki-page__merge-search-box {
  position: relative;
  display: flex;
  align-items: center;
}

.wiki-page__merge-search-icon {
  position: absolute;
  left: var(--space-3);
  color: var(--text-tertiary);
  pointer-events: none;
}

.wiki-page__merge-search-input {
  width: 100%;
  padding: var(--space-2) var(--space-3) var(--space-2) var(--space-8);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-family: var(--font-body);
  color: var(--text-primary);
  background: var(--bg-primary);
  outline: none;
}

.wiki-page__merge-search-input:focus {
  border-color: var(--accent-primary);
}

.wiki-page__merge-search-spinner {
  position: absolute;
  right: var(--space-3);
  color: var(--text-tertiary);
  animation: spin 1s linear infinite;
}

.wiki-page__merge-results {
  max-height: 200px;
  overflow-y: auto;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  margin-top: var(--space-2);
}

.wiki-page__merge-result {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  width: 100%;
  padding: var(--space-2) var(--space-3);
  background: transparent;
  border: none;
  cursor: pointer;
  font-family: var(--font-body);
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  text-align: left;
  transition: background var(--transition-fast);
}

.wiki-page__merge-result:hover {
  background: var(--accent-light);
}

.wiki-page__merge-result-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.wiki-page__merge-result-title {
  font-weight: var(--weight-medium);
}

.wiki-page__merge-result-summary {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wiki-page__merge-result-cat {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  flex-shrink: 0;
  padding-top: 2px;
}

.wiki-page__merge-no-result {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  text-align: center;
  padding: var(--space-2);
  margin: 0;
}

.wiki-page__merge-selected {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--accent-light);
  border: 1px solid var(--accent-primary);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-4);
}

.wiki-page__merge-selected-icon {
  color: var(--accent-primary);
  flex-shrink: 0;
}

.wiki-page__merge-selected-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.wiki-page__merge-selected-title {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.wiki-page__merge-selected-hint {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.wiki-page__merge-selected-clear {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all var(--transition-fast);
}

.wiki-page__merge-selected-clear:hover {
  background: var(--accent-light);
  color: var(--accent-primary);
}

/* Candidate list (related pages) */
.wiki-page__merge-candidates {
  margin-bottom: var(--space-4);
}

.wiki-page__merge-candidate-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
  max-height: 180px;
  overflow-y: auto;
}

.wiki-page__merge-candidate {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: var(--space-3) var(--space-4);
  background: var(--bg-secondary);
  border: none;
  cursor: pointer;
  font-family: var(--font-body);
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  text-align: left;
  transition: background var(--transition-fast);
}

.wiki-page__merge-candidate:hover {
  background: var(--accent-light);
}

.wiki-page__merge-candidate-title {
  font-weight: var(--weight-medium);
}

.wiki-page__merge-candidate-cat {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  flex-shrink: 0;
  margin-left: var(--space-3);
}

.wiki-page__merge-divider {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin: var(--space-3) 0;
  color: var(--text-tertiary);
  font-size: var(--font-caption);
}

.wiki-page__merge-divider::before,
.wiki-page__merge-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--border-subtle);
}

.wiki-page__merge-title-field {
  margin-bottom: var(--space-4);
}

.wiki-page__merge-title-input {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-family: var(--font-body);
  color: var(--text-primary);
  background: var(--bg-primary);
  outline: none;
}

.wiki-page__merge-title-input:focus {
  border-color: var(--accent-primary);
}

.wiki-page__merge-submit {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-page__merge-submit:hover:not(:disabled) {
  background: var(--accent-hover);
}

.wiki-page__merge-submit:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.wiki-page__merge-btn-spinner {
  animation: spin 1s linear infinite;
}

/* Merge confirmation overlay */
.wiki-page__merge-confirm {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal, 1000);
  display: flex;
  align-items: center;
  justify-content: center;
}

.wiki-page__merge-confirm-overlay {
  position: fixed;
  inset: 0;
  z-index: 1010;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--overlay-bg, rgba(0, 0, 0, 0.5));
  backdrop-filter: blur(2px);
}

.wiki-page__merge-confirm-card {
  position: relative;
  width: 90%;
  max-width: 420px;
  background: var(--bg-primary);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xl);
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  animation: mergeConfirmSlideIn 0.2s ease-out;
}

@keyframes mergeConfirmSlideIn {
  from { opacity: 0; transform: translateY(8px) scale(0.98); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

.wiki-page__merge-confirm-header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.wiki-page__merge-confirm-icon {
  color: var(--warning);
  flex-shrink: 0;
}

.wiki-page__merge-confirm-title {
  font-family: var(--font-heading);
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin: 0;
}

.wiki-page__merge-confirm-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.wiki-page__merge-confirm-flow {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
}

.wiki-page__merge-confirm-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.wiki-page__merge-confirm-page-label {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.wiki-page__merge-confirm-page-name {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wiki-page__merge-confirm-page--target .wiki-page__merge-confirm-page-name {
  color: var(--accent-primary);
}

.wiki-page__merge-confirm-arrow {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.wiki-page__merge-confirm-warning {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.6;
  margin: 0;
  padding: var(--space-3) var(--space-4);
  background: var(--warning-light, rgba(245, 158, 11, 0.08));
  border-radius: var(--radius-md);
}

.wiki-page__merge-confirm-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--error);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-family: var(--font-body);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.wiki-page__merge-confirm-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.wiki-page__merge-confirm-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.wiki-page__loading {
  padding: var(--space-8);
  text-align: center;
  color: var(--text-tertiary);
}

.wiki-page__error {
  padding: var(--space-8);
  text-align: center;
  color: var(--error);
}

@media (max-width: 1024px) {
  .wiki-page__body {
    grid-template-columns: 1fr;
  }

  .wiki-page__sidebar {
    display: none;
  }
}

@media (max-width: 768px) {
  .wiki-page__meta-row {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
  }

  .wiki-page__actions {
    width: 100%;
    overflow-x: auto;
  }
}

/* AI 后处理状态横幅 */
.wiki-page__post-save-banner {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  margin-bottom: var(--space-3);
}

.wiki-page__post-save-banner--processing {
  background: oklch(0.96 0.02 240);
  border: 1px solid oklch(0.88 0.04 240);
  color: oklch(0.55 0.12 240);
}

.wiki-page__post-save-banner--failed {
  background: var(--error-light, rgba(239, 68, 68, 0.08));
  border: 1px solid var(--error, #ef4444);
  color: var(--error, #ef4444);
}

.wiki-page__post-save-banner--partial {
  background: var(--warning-light, rgba(245, 158, 11, 0.08));
  border: 1px solid var(--warning, #f59e0b);
  color: var(--warning, #b45309);
}

.wiki-page__post-save-spin {
  animation: spin 1s linear infinite;
  flex-shrink: 0;
}

.wiki-page__post-save-text {
  flex: 1;
}

.post-save-fade-enter-active,
.post-save-fade-leave-active {
  transition: opacity 200ms ease, transform 200ms ease;
}

.post-save-fade-enter-from,
.post-save-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>