<script setup lang="ts">
import { ref, onMounted, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useToastStore } from '@/stores/toast'
import WikiPageRenderer from '@/components/wiki/WikiPageRenderer.vue'
import MarkdownEditor from '@/components/common/MarkdownEditor.vue'
import AiChatPanel from '@/components/editor/AiChatPanel.vue'
import DiffOverlay from '@/components/editor/DiffOverlay.vue'
import { computeDiff, type DiffResult } from '@/utils/diff'
import FloatingToolbar from '@/components/editor/FloatingToolbar.vue'
import SaveBar from '@/components/editor/SaveBar.vue'
import type { EditSessionInfo } from '@/api/wiki'
import {
  getPage,
  getDraft,
  createEditSession,
  abandonEditSession,
  savePage,
  getAiStatus,
} from '@/api/wiki'

const route = useRoute()
const router = useRouter()
const toastStore = useToastStore()
const { t } = useI18n()

// State
const title = ref('')
const category = ref('')
const tags = ref<string[]>([])
const content = ref('')
const pageId = ref<number | null>(null)
const draftId = ref<number | null>(null)
const session = ref<EditSessionInfo | null>(null)
const aiAvailable = ref(false)
const saving = ref(false)
const loading = ref(true)
const aiChatRef = ref<InstanceType<typeof AiChatPanel> | null>(null)
const fallbackMode = ref(false)

// AI edit overlay state
const pendingContent = ref('')
const editStreaming = ref(false)
const patchCount = ref(0)
const diffMode = ref<'preview' | 'diff'>('preview')
const diffResult = ref<DiffResult | null>(null)
const retryRound = ref(0)
const retryFailedCount = ref(0)

const sessionId = computed(() => session.value?.id ?? null)
const isPageEmpty = computed(() => !content.value || content.value.trim().length < 50)

// Dirty tracking
const dirty = ref(false)
const initialContent = ref('')
const initialTitle = ref('')

watch([content, title], () => {
  if (!loading.value) {
    dirty.value = (
      content.value !== initialContent.value ||
      title.value !== initialTitle.value
    )
  }
})

onBeforeRouteLeave((_to, _from, next) => {
  if (dirty.value && !saving.value) {
    const ok = window.confirm(t('editor.unsavedChangesLeave'))
    if (ok) abandonSession()
    next(ok)
  } else {
    abandonSession()
    next()
  }
})

function abandonSession() {
  if (session.value) {
    abandonEditSession(session.value.id).catch(() => {})
  }
}

onMounted(async () => {
  try {
    const aiStatus = await getAiStatus().catch(() => ({ available: false }))
    aiAvailable.value = aiStatus.available

    if (route.params.pageId) {
      const pid = Number(route.params.pageId)
      const page = await getPage(pid)
      title.value = page.title
      content.value = page.content || ''
      category.value = page.category || ''
      tags.value = page.tags || []
      pageId.value = page.id
    } else if (route.params.draftId) {
      const did = Number(route.params.draftId)
      const draft = await getDraft(did)
      title.value = draft.title
      content.value = draft.content || ''
      category.value = draft.category || ''
      tags.value = draft.tags || []
      draftId.value = draft.id
      if (draft.pageId) pageId.value = draft.pageId
    }

    if (aiAvailable.value && !fallbackMode.value) {
      try {
        session.value = await createEditSession({
          pageId: pageId.value || undefined,
          draftId: draftId.value || undefined,
        })
        if (!route.params.pageId && !route.params.draftId) {
          await nextTick()
          aiChatRef.value?.focusInput()
        }
      } catch (e) {
        console.warn('Failed to create edit session:', e)
      }
    }
  } catch (e: any) {
    toastStore.error(t('editor.loadFailedShort'), e.message || String(e))
  } finally {
    loading.value = false
    initialContent.value = content.value
    initialTitle.value = title.value
  }
})

function handleAddToChat(payload: { text: string; lines: string }) {
  aiChatRef.value?.addSelection(payload)
}

function handleContentUpdated(newContent: string) {
  content.value = newContent
}

function handlePatchReceived(newContent: string) {
  editStreaming.value = true
  pendingContent.value = newContent
  patchCount.value++
}

function handleEditDone(payload: { content: string; explanation: string }) {
  editStreaming.value = false
  pendingContent.value = payload.content
  retryRound.value = 0
  retryFailedCount.value = 0
  // 自动计算 diff 并切换到对比模式
  if (payload.content && payload.content !== content.value) {
    diffResult.value = computeDiff(content.value, payload.content)
    diffMode.value = 'diff'
  }
}

function handleRetryReceived(payload: { round: number; failedCount: number }) {
  retryRound.value = payload.round
  retryFailedCount.value = payload.failedCount
}

function handleStepCommitted(updatedSession: EditSessionInfo) {
  session.value = updatedSession
}

function handleSessionUpdated(updatedSession: EditSessionInfo) {
  session.value = updatedSession
}

async function handleAcceptChanges() {
  const newContent = pendingContent.value
  if (!newContent) return
  await aiChatRef.value?.acceptPending(newContent)
  content.value = newContent
  pendingContent.value = ''
  patchCount.value = 0
  diffMode.value = 'preview'
  diffResult.value = null
}

function handleRejectChanges() {
  aiChatRef.value?.rejectPending()
  pendingContent.value = ''
  patchCount.value = 0
  editStreaming.value = false
  diffMode.value = 'preview'
  diffResult.value = null
}

async function handleSave(mode: 'draft' | 'save') {
  if (!title.value.trim()) {
    toastStore.warning(t('editor.titleRequired'))
    return
  }
  saving.value = true
  try {
    const result = await savePage({
      pageId: pageId.value || undefined,
      draftId: draftId.value || undefined,
      sessionId: sessionId.value || undefined,
      title: title.value,
      content: content.value,
      category: category.value || undefined,
      tags: tags.value.length > 0 ? tags.value : undefined,
      saveMode: mode,
    })

    const isPostSavePending = mode !== 'draft' && result.postSaveStatus === 'pending'
    toastStore.success(
      mode === 'draft'
        ? t('editor.savedAsDraft')
        : t('editor.saved'),
      isPostSavePending ? t('editor.aiPostSaveHint') : undefined
    )

    dirty.value = false
    initialContent.value = content.value
    initialTitle.value = title.value

    if (mode !== 'draft' && result.id) {
      router.push({ name: 'WikiPage', params: { id: result.id } })
    }
  } catch (e: any) {
    toastStore.error(t('editor.saveFailed'), e.message || String(e))
  } finally {
    saving.value = false
  }
}

function toggleFallback() {
  fallbackMode.value = !fallbackMode.value
  if (fallbackMode.value && session.value) {
    abandonEditSession(session.value.id).catch(() => {})
    session.value = null
  }
}
</script>

<template>
  <div class="wiki-editor">
    <!-- Header: slim toolbar -->
    <header class="editor-header">
      <div class="editor-header__start">
        <input
          v-model="title"
          type="text"
          :placeholder="t('editor.pageTitle')"
          class="editor-header__title"
        />
      </div>
      <div class="editor-header__end">
        <button
          v-if="aiAvailable"
          class="editor-header__mode-btn"
          @click="toggleFallback"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <template v-if="fallbackMode">
              <path d="M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
              <path d="M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z"/>
            </template>
            <template v-else>
              <path d="M15 2H7a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7z"/>
              <path d="M14 2v4a2 2 0 0 0 2 2h4"/>
              <circle cx="10" cy="12" r="2"/>
              <path d="m20 18-4-4 4-4"/>
            </template>
          </svg>
          {{ fallbackMode ? t('editor.sourceMode') : t('editor.aiMode') }}
        </button>
      </div>
    </header>

    <!-- Body: content canvas + AI panel -->
    <div class="editor-body">
      <!-- Content canvas -->
      <div class="editor-canvas">
        <div class="editor-canvas__surface">
          <!-- Loading -->
          <div v-if="loading" class="editor-canvas__loading">
            <div v-for="i in 6" :key="i" class="editor-canvas__skeleton" :style="{ width: (50 + Math.random() * 40) + '%' }" />
          </div>

          <!-- Source mode (fallback) -->
          <div v-else-if="fallbackMode || !aiAvailable" class="editor-canvas__source">
            <MarkdownEditor
              v-model="content"
              :placeholder="t('editor.markdownPlaceholder')"
              min-height="100%"
            />
          </div>

          <!-- AI preview mode -->
          <div v-else class="editor-canvas__preview">
            <!-- Diff view: line-level comparison -->
            <div v-if="diffMode === 'diff' && diffResult" class="editor-diff">
              <div class="editor-diff__summary">
                <span class="editor-diff__stat editor-diff__stat--add">+{{ t('editor.linesAdded', [diffResult.added]) }}</span>
                <span class="editor-diff__stat editor-diff__stat--del">-{{ t('editor.linesRemoved', [diffResult.removed]) }}</span>
              </div>
              <pre class="editor-diff__content"><template
                v-for="(line, idx) in diffResult.lines"
                :key="idx"
              ><span :class="'diff-line diff-line--' + line.type">{{ line.type === 'added' ? '+ ' : line.type === 'removed' ? '- ' : '  ' }}{{ line.text }}
</span></template></pre>
            </div>

            <!-- Preview view: rendered wiki -->
            <template v-else>
              <WikiPageRenderer
                v-if="pendingContent || content"
                :content="pendingContent || content"
                :title="title"
              />
              <div v-else class="editor-canvas__empty">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round" opacity="0.3">
                  <path d="M15 2H7a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7z"/>
                  <path d="M14 2v4a2 2 0 0 0 2 2h4"/>
                </svg>
                <p>{{ t('editor.contentEmpty') }}</p>
                <span>{{ t('editor.aiEditHint') }}</span>
              </div>
            </template>

            <DiffOverlay
              :visible="!!pendingContent"
              :streaming="editStreaming"
              :patch-count="patchCount"
              :diff-mode="diffMode"
              :diff-added="diffResult?.added ?? 0"
              :diff-removed="diffResult?.removed ?? 0"
              :retry-round="retryRound"
              :retry-failed-count="retryFailedCount"
              @accept="handleAcceptChanges"
              @reject="handleRejectChanges"
              @update:diff-mode="(m: 'preview' | 'diff') => diffMode = m"
            />
          </div>
        </div>

        <SaveBar
          :saving="saving"
          :ai-available="aiAvailable"
          @save="handleSave"
        />
      </div>

      <!-- AI chat panel -->
      <aside
        v-if="!fallbackMode && aiAvailable"
        class="editor-ai"
      >
        <AiChatPanel
          ref="aiChatRef"
          :session-id="sessionId"
          :ai-available="aiAvailable"
          :is-page-empty="isPageEmpty"
          :content-hash="session?.contentHash"
          @content-updated="handleContentUpdated"
          @patch-received="handlePatchReceived"
          @edit-done="handleEditDone"
          @retry-received="handleRetryReceived"
          @step-committed="handleStepCommitted"
          @session-updated="handleSessionUpdated"
        />
      </aside>
    </div>

    <FloatingToolbar
      v-if="!fallbackMode && aiAvailable"
      container-selector=".editor-canvas__preview"
      @add-to-chat="handleAddToChat"
    />
  </div>
</template>

<style scoped>
/* ─── Shell ─── */
.wiki-editor {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-secondary);
}

/* ─── Header ─── */
.editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-6);
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border-subtle);
  flex-shrink: 0;
}

.editor-header__start {
  flex: 1;
  min-width: 0;
}

.editor-header__title {
  width: 100%;
  padding: var(--space-1) 0;
  border: none;
  background: transparent;
  font-size: var(--font-h3);
  font-family: var(--font-heading);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  line-height: var(--leading-h3);
  outline: none;
}

.editor-header__title::placeholder {
  color: var(--text-tertiary);
  font-weight: var(--weight-regular);
}

.editor-header__end {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

.editor-header__mode-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: var(--btn-height-sm);
  padding: 0 var(--space-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-tertiary);
  font-family: var(--font-body);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
}

.editor-header__mode-btn:hover {
  background: var(--bg-secondary);
  color: var(--text-secondary);
  border-color: var(--border-default);
}

/* ─── Body ─── */
.editor-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

/* ─── Content canvas ─── */
.editor-canvas {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: var(--space-5) var(--space-6);
  overflow: hidden;
}

.editor-canvas__surface {
  flex: 1;
  overflow-y: auto;
  background: var(--bg-primary);
  border-radius: var(--radius-lg);
  border: 1px solid var(--border-subtle);
  box-shadow: var(--shadow-sm);
  padding: var(--space-8) var(--space-10);
  max-width: var(--wiki-content-max);
  width: 100%;
  margin: 0 auto;
}

/* Source editor */
.editor-canvas__source {
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* Preview content */
.editor-canvas__preview {
  min-height: 100%;
}

/* ─── Diff view ─── */
.editor-diff {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.editor-diff__summary {
  display: flex;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--bg-secondary, #f9fafb);
  border-radius: var(--radius-md);
  border: 1px solid var(--border-subtle);
}

.editor-diff__stat {
  font-family: var(--font-code);
  font-size: var(--font-code-sm);
  font-weight: var(--weight-semibold);
}

.editor-diff__stat--add {
  color: var(--success, #22c55e);
}

.editor-diff__stat--del {
  color: var(--error, #ef4444);
}

.editor-diff__content {
  margin: 0;
  padding: var(--space-4);
  background: var(--bg-secondary, #f9fafb);
  border-radius: var(--radius-md);
  border: 1px solid var(--border-subtle);
  font-family: var(--font-code);
  font-size: var(--font-code-sm);
  line-height: var(--leading-code);
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.diff-line {
  display: inline;
}

.diff-line--added {
  background: var(--success-light);
  color: var(--success);
}

.diff-line--removed {
  background: var(--error-light);
  color: var(--error);
  text-decoration: line-through;
}

.diff-line--unchanged {
  color: var(--text-secondary);
}

/* ─── Loading skeleton ─── */
.editor-canvas__loading {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding-top: var(--space-6);
}

.editor-canvas__skeleton {
  height: 12px;
  background: var(--bg-tertiary);
  border-radius: var(--radius-sm);
  animation: skeleton-pulse 1.8s ease-in-out infinite;
}

@keyframes skeleton-pulse {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 0.7; }
}

/* ─── Empty state ─── */
.editor-canvas__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  min-height: 400px;
  color: var(--text-tertiary);
}

.editor-canvas__empty p {
  margin: 0;
  font-size: var(--font-body-lg);
  font-weight: var(--weight-medium);
  color: var(--text-secondary);
}

.editor-canvas__empty span {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}

/* ─── AI panel ─── */
.editor-ai {
  width: 360px;
  flex-shrink: 0;
  border-left: 1px solid var(--border-subtle);
  background: var(--bg-primary);
  overflow: hidden;
}
</style>
