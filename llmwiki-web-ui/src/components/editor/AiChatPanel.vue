<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AiEditEvent, EditSessionInfo, EditStepInfo, WikiPageInfo } from '@/api/wiki'
import { aiEditStream, listEditSteps, undoEditStep, undoAllSteps, commitEditStep, searchSuggest } from '@/api/wiki'
import EditStepsHistory from './EditStepsHistory.vue'

interface SelectionItem {
  id: number
  text: string
  lines: string
}

interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
  selectedText?: string
  selectedLines?: string
  streaming?: boolean
  mentionedPages?: { id: number; title: string; path: string }[]
}

const props = defineProps<{
  sessionId: number | null
  aiAvailable: boolean
  isPageEmpty?: boolean
  contentHash?: string
}>()

const emit = defineEmits<{
  (e: 'content-updated', content: string): void
  (e: 'patch-received', content: string): void
  (e: 'edit-done', payload: { content: string; explanation: string }): void
  (e: 'retry-received', payload: { round: number; failedCount: number }): void
  (e: 'step-committed', session: EditSessionInfo): void
  (e: 'session-updated', session: EditSessionInfo): void
}>()

const { t } = useI18n()

const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const selections = ref<SelectionItem[]>([])
let selectionIdCounter = 0
const streaming = ref(false)
const acceptLoading = ref(false)
const steps = ref<EditStepInfo[]>([])
const undoLoading = ref(false)
const chatListRef = ref<HTMLElement | null>(null)
const textareaRef = ref<HTMLTextAreaElement | null>(null)

// Pending commit metadata (stored from sendInstruction for two-phase commit)
const pendingInstruction = ref('')
const pendingSelectedText = ref('')
const pendingSelectedLines = ref('')

// Slash mention state
interface MentionedPage {
  id: number
  title: string
  path: string
}
const mentionedPages = ref<MentionedPage[]>([])
const slashActive = ref(false)
const slashResults = ref<WikiPageInfo[]>([])
const slashHighlightIndex = ref(0)
const slashStartPos = ref(-1)
const slashSearchStarted = ref(false)
let slashSearchTimer: ReturnType<typeof setTimeout> | null = null

// 添加选中文本到对话（支持多选，按 lines 去重）
function addSelection(payload: { text: string; lines: string }) {
  const exists = selections.value.some(s => s.lines === payload.lines)
  if (!exists) {
    selections.value.push({ id: ++selectionIdCounter, ...payload })
  }
}

function removeSelectionAt(index: number) {
  selections.value.splice(index, 1)
}

// 发送指令
async function sendInstruction() {
  if (!props.sessionId || !inputText.value.trim() || streaming.value) return

  const instruction = inputText.value.trim()
  const sel = selections.value.length > 0
    ? {
        text: selections.value.map(s => s.text).join('\n\n---\n\n'),
        lines: selections.value.map(s => s.lines).join(', '),
      }
    : null
  const mentionIds = mentionedPages.value.map(p => p.id)

  // Store metadata for two-phase commit (will be used in acceptDiff)
  pendingInstruction.value = instruction
  pendingSelectedText.value = sel?.text || ''
  pendingSelectedLines.value = sel?.lines || ''

  const mentionSnapshot = mentionedPages.value.map(p => ({ ...p }))

  inputText.value = ''
  selections.value = []
  mentionedPages.value = []
  closeSlashMenu()

  // 添加用户消息
  messages.value.push({
    role: 'user',
    content: instruction,
    selectedText: sel?.text,
    selectedLines: sel?.lines,
    mentionedPages: mentionSnapshot.length > 0 ? mentionSnapshot : undefined,
  })

  // 添加 AI 占位消息
  const editHint = sel
    ? t('editor.editingHint', [sel.lines, sel.text.substring(0, 30) + (sel.text.length > 30 ? '...' : '')])
    : t('editor.processingHint', [instruction.substring(0, 40) + (instruction.length > 40 ? '...' : '')])
  const aiMsg: ChatMessage = {
    role: 'assistant',
    content: editHint + '\n',
    streaming: true,
  }
  messages.value.push(aiMsg)
  await scrollToBottom()

  streaming.value = true
  let firstTokenReceived = false
  let hadPatches = false
  let waitingTimer: ReturnType<typeof setTimeout> | null = null

  // 5 秒无 token 时显示等待提示
  waitingTimer = setTimeout(() => {
    if (!firstTokenReceived && aiMsg.streaming) {
      aiMsg.content = `${t('editor.processingHint', [instruction.substring(0, 40) + '...'])} (${t('editor.aiThinkingHint')})\n`
      scrollToBottom()
    }
  }, 5000)

  try {
    // 有 / 引用的页面 → 直接作为确认的知识库资料，跳过 Phase 1
    const result = aiEditStream(
      props.sessionId,
      instruction,
      sel?.lines,
      sel?.text,
      mentionIds.length > 0 ? mentionIds : undefined,
      props.contentHash,
    )

    await result.consume((event: AiEditEvent) => {
      if (event.type === 'anchor') {
        if (event.anchorStatus && event.anchorStatus !== 'exact') {
          messages.value.push({ role: 'system', content: t('editor.anchorFallbackWarning') })
          scrollToBottom()
        }
      } else if (event.type === 'retry') {
        aiMsg.content = t('editor.aiRetrying', [event.failedBlockCount ?? 0, event.retryRound ?? 0]) + '\n'
        emit('retry-received', { round: event.retryRound ?? 0, failedCount: event.failedBlockCount ?? 0 })
        scrollToBottom()
      } else if (event.type === 'patch') {
        hadPatches = true
        if (!firstTokenReceived) {
          firstTokenReceived = true
          if (waitingTimer) { clearTimeout(waitingTimer); waitingTimer = null }
        }
        emit('patch-received', event.content)
      } else if (event.type === 'token') {
        if (!firstTokenReceived) {
          firstTokenReceived = true
          if (waitingTimer) { clearTimeout(waitingTimer); waitingTimer = null }
          aiMsg.content = ''
        }
        aiMsg.content += event.content || ''
        scrollToBottom()
      } else if (event.type === 'done') {
        streaming.value = false
        aiMsg.streaming = false
        if (waitingTimer) { clearTimeout(waitingTimer); waitingTimer = null }
        if (event.content) {
          emit('edit-done', { content: event.content, explanation: event.explanation || '' })
          if (!hadPatches && event.mode !== 'create') {
            if (!aiMsg.content.trim()) {
              aiMsg.content = event.explanation || t('editor.aiNoChange')
            }
          } else {
            aiMsg.content = t('editor.changesReady')
          }
        }
        if (event.failedBlocks && event.failedBlocks.length > 0) {
          const lines: string[] = [t('editor.failedBlocksTitle', [event.failedBlocks.length])]
          for (const fb of event.failedBlocks) {
            if (fb.reason === 'AMBIGUOUS') {
              lines.push('- ' + t('editor.failedAmbiguous', [fb.matchCount ?? 0, (fb.matchLines && fb.matchLines[0]) || '-']))
            } else {
              lines.push('- ' + t('editor.failedNotFound'))
            }
            if (fb.searchPreview) {
              lines.push('  > ' + fb.searchPreview)
            }
          }
          lines.push(t('editor.failedActionHint'))
          aiMsg.content += (aiMsg.content.endsWith('\n') ? '' : '\n') + lines.join('\n')
          scrollToBottom()
        }
      } else if (event.type === 'error') {
        aiMsg.streaming = false
        if (event.code === 'HASH_CONFLICT') {
          aiMsg.content = t('editor.hashConflictEdit')
        } else {
          aiMsg.content = t('editor.errorPrefix') + event.content
        }
      }
    })
  } catch (e: any) {
    aiMsg.streaming = false
    if (waitingTimer) { clearTimeout(waitingTimer); waitingTimer = null }
    const msg = e?.message || String(e)
    if (msg.toLowerCase().includes('network') || msg.toLowerCase().includes('fetch')) {
      aiMsg.content = t('editor.networkError')
    } else {
      aiMsg.content = t('editor.requestFailed') + msg
    }
  } finally {
    streaming.value = false
    aiMsg.streaming = false
    if (waitingTimer) { clearTimeout(waitingTimer); waitingTimer = null }
  }
}

async function acceptPending(newContent: string) {
  if (!props.sessionId || !newContent) return

  const sid = props.sessionId
  const instructionToCommit = pendingInstruction.value
  const selectedLinesToCommit = pendingSelectedLines.value || undefined
  const selectedTextToCommit = pendingSelectedText.value || undefined

  pendingInstruction.value = ''
  pendingSelectedText.value = ''
  pendingSelectedLines.value = ''

  emit('content-updated', newContent)

  acceptLoading.value = true
  const statusMsg: ChatMessage = { role: 'assistant', content: t('editor.savingChange'), streaming: false }
  messages.value.push(statusMsg)
  scrollToBottom()

  try {
    const commitResult = await commitEditStep(sid, {
      newContent,
      instruction: instructionToCommit,
      selectedLines: selectedLinesToCommit,
      selectedText: selectedTextToCommit,
      expectedHash: props.contentHash,
    })
    statusMsg.content = commitResult.versionBehind
      ? t('editor.versionBehindSaved')
      : t('editor.changeSaved')
    emit('step-committed', commitResult.session)
    refreshSteps()
  } catch (e: any) {
    console.error('Commit step failed:', e)
    statusMsg.content = t('editor.saveFailedPrefix') + (e?.message || e)
  } finally {
    acceptLoading.value = false
  }
}

function rejectPending() {
  pendingInstruction.value = ''
  pendingSelectedText.value = ''
  pendingSelectedLines.value = ''
}

async function refreshSteps() {
  if (!props.sessionId) return
  try {
    steps.value = await listEditSteps(props.sessionId)
  } catch { /* ignore */ }
}

async function handleUndoStep(stepId: number) {
  if (!props.sessionId) return
  undoLoading.value = true
  try {
    const session = await undoEditStep(props.sessionId, stepId)
    steps.value = await listEditSteps(props.sessionId)
    emit('session-updated', session)
    if (session.currentContent) {
      emit('content-updated', session.currentContent)
    }
  } catch (e: any) {
    console.error('Undo step failed:', e)
  } finally {
    undoLoading.value = false
  }
}

async function handleUndoAll() {
  if (!props.sessionId) return
  undoLoading.value = true
  try {
    const session = await undoAllSteps(props.sessionId)
    steps.value = []
    emit('session-updated', session)
    if (session.currentContent) {
      emit('content-updated', session.currentContent)
    }
  } catch (e: any) {
    console.error('Undo all failed:', e)
  } finally {
    undoLoading.value = false
  }
}

function handleTextareaKeydown(e: KeyboardEvent) {
  if (slashActive.value) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      slashHighlightIndex.value = Math.min(slashHighlightIndex.value + 1, slashResults.value.length - 1)
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      slashHighlightIndex.value = Math.max(slashHighlightIndex.value - 1, 0)
      return
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      if (slashResults.value.length > 0) {
        selectSlashResult(slashResults.value[slashHighlightIndex.value])
      }
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      closeSlashMenu()
      return
    }
  }
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendInstruction()
  }
}

function handleTextareaInput() {
  autoResize()
  detectSlashQuery()
}

function detectSlashQuery() {
  const text = inputText.value
  const cursorPos = textareaRef.value?.selectionStart ?? text.length
  const textBeforeCursor = text.substring(0, cursorPos)
  const lastSlash = textBeforeCursor.lastIndexOf('/')
  if (lastSlash < 0) {
    closeSlashMenu()
    return
  }
  const queryAfterSlash = textBeforeCursor.substring(lastSlash + 1)
  if (queryAfterSlash.includes('\n') || queryAfterSlash.includes('  ')) {
    closeSlashMenu()
    return
  }
  slashStartPos.value = lastSlash
  slashActive.value = true
  slashSearchStarted.value = true
  if (slashSearchTimer) clearTimeout(slashSearchTimer)
  slashSearchTimer = setTimeout(() => {
    fetchSlashSuggestions(queryAfterSlash)
  }, 200)
}

async function fetchSlashSuggestions(query: string) {
  if (query.length > 30) { closeSlashMenu(); return }
  try {
    const results = query.trim().length === 0
      ? await searchSuggest('')
      : await searchSuggest(query.trim())
    slashResults.value = results.filter(
      r => !mentionedPages.value.some(m => m.id === r.id)
    ).slice(0, 8)
    slashActive.value = true
    slashHighlightIndex.value = 0
  } catch {
    closeSlashMenu()
  }
}

function selectSlashResult(page: WikiPageInfo) {
  if (!page) return
  const text = inputText.value
  const before = text.substring(0, slashStartPos.value)
  const cursorPos = textareaRef.value?.selectionStart ?? text.length
  const after = text.substring(cursorPos)
  inputText.value = before + after
  mentionedPages.value.push({
    id: page.id,
    title: page.title,
    path: page.path,
  })
  closeSlashMenu()
  nextTick(() => {
    if (textareaRef.value) {
      textareaRef.value.focus()
      const newPos = before.length
      textareaRef.value.setSelectionRange(newPos, newPos)
    }
  })
}

function removeMentionedPage(id: number) {
  mentionedPages.value = mentionedPages.value.filter(p => p.id !== id)
}

function closeSlashMenu() {
  slashActive.value = false
  slashResults.value = []
  slashHighlightIndex.value = 0
  slashStartPos.value = -1
  slashSearchStarted.value = false
}

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  const maxH = 140
  el.style.height = Math.min(el.scrollHeight, maxH) + 'px'
}

async function scrollToBottom() {
  await nextTick()
  if (chatListRef.value) {
    chatListRef.value.scrollTop = chatListRef.value.scrollHeight
  }
}

// 初始加载步骤
watch(() => props.sessionId, (newId) => {
  if (newId) refreshSteps()
  messages.value = []
}, { immediate: true })

function focusInput() {
  nextTick(() => {
    if (textareaRef.value && !streaming.value) {
      textareaRef.value.focus()
    }
  })
}

defineExpose({ addSelection, focusInput, acceptPending, rejectPending })
</script>

<template>
  <div class="ai-chat-panel">
    <div class="ai-chat-panel__header">
      <span>{{ t('editor.aiEditAssistant') }}</span>
      <span v-if="!aiAvailable" class="ai-chat-panel__badge">{{ t('editor.unavailable') }}</span>
    </div>

    <div ref="chatListRef" class="ai-chat-panel__messages">
      <div v-if="messages.length === 0" class="ai-chat-panel__placeholder">
        <p>{{ t('editor.chatPlaceholder') }}</p>
      </div>

      <div
        v-for="(msg, i) in messages"
        :key="i"
        class="chat-message"
        :class="`chat-message--${msg.role}`"
      >
        <div v-if="msg.selectedText" class="chat-message__quote">
          <span class="chat-message__quote-label">{{ msg.selectedLines }}</span>
          <div class="chat-message__quote-text">{{ msg.selectedText }}</div>
        </div>
        <div v-if="msg.mentionedPages && msg.mentionedPages.length > 0" class="chat-message__refs">
          <span
            v-for="page in msg.mentionedPages"
            :key="page.id"
            class="chat-ref-chip"
          >
            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/></svg>
            {{ page.title }}
          </span>
        </div>
        <div class="chat-message__content">
          {{ msg.content }}
          <span v-if="msg.streaming" class="chat-message__cursor">|</span>
        </div>
      </div>
    </div>

    <EditStepsHistory
      :steps="steps"
      :loading="undoLoading"
      @undo="handleUndoStep"
      @undo-all="handleUndoAll"
    />

    <div class="ai-chat-panel__input-area">
      <div v-if="selections.length > 0" class="ai-chat-panel__selections">
        <div
          v-for="(sel, idx) in selections"
          :key="sel.id"
          class="ai-chat-panel__selected"
        >
          <span class="ai-chat-panel__selected-label">{{ sel.lines }}</span>
          <span class="ai-chat-panel__selected-text">{{ sel.text.substring(0, 50) }}{{ sel.text.length > 50 ? '...' : '' }}</span>
          <button class="ai-chat-panel__selected-close" @click="removeSelectionAt(idx)">×</button>
        </div>
      </div>
      <div v-if="mentionedPages.length > 0" class="ai-chat-panel__mentions">
        <span
          v-for="page in mentionedPages"
          :key="page.id"
          class="mention-chip"
        >
          <svg class="mention-chip__icon" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/></svg>
          <span class="mention-chip__title">{{ page.title }}</span>
          <button class="mention-chip__remove" @click="removeMentionedPage(page.id)">×</button>
        </span>
      </div>
      <div class="ai-chat-panel__input-box">
        <textarea
          ref="textareaRef"
          v-model="inputText"
          class="ai-chat-panel__textarea"
          :placeholder="mentionedPages.length > 0
            ? t('editor.inputPlaceholderMention')
            : t('editor.inputPlaceholderChat')
          "
          rows="1"
          :disabled="!aiAvailable || streaming || !sessionId"
          @keydown="handleTextareaKeydown"
          @input="handleTextareaInput"
        />
        <button
          class="ai-chat-panel__send-btn"
          :disabled="!aiAvailable || !inputText.trim() || !sessionId"
          @click="sendInstruction"
        >
          <svg v-if="streaming" class="ai-chat-panel__spinner" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10" stroke-opacity="0.25"/><path d="M12 2a10 10 0 0 1 10 10" stroke-linecap="round"/></svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
        </button>
      </div>
      <div class="ai-chat-panel__input-hint">
        <span>/</span> {{ t('editor.refPage') }} · {{ t('editor.enterToSend') }} · {{ t('editor.shiftEnterNewline') }}
      </div>

      <div v-if="slashSearchStarted" class="slash-dropdown">
        <div v-if="slashResults.length > 0">
          <div
            v-for="(page, idx) in slashResults"
            :key="page.id"
            class="slash-dropdown__item"
            :class="{ 'slash-dropdown__item--active': idx === slashHighlightIndex }"
            @click="selectSlashResult(page)"
            @mouseenter="slashHighlightIndex = idx"
          >
            <span class="slash-dropdown__title">{{ page.title }}</span>
            <span v-if="page.category" class="slash-dropdown__category">{{ page.category }}</span>
          </div>
        </div>
        <div v-else class="slash-dropdown__empty">
          {{ slashActive ? t('editor.noMatchPage') : t('editor.searchKeyword') }}
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

.ai-chat-panel__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  font-weight: var(--weight-semibold);
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  border-bottom: 1px solid var(--border-subtle);
}

.ai-chat-panel__badge {
  display: inline-flex;
  align-items: center;
  padding: 0 var(--space-2);
  height: 20px;
  border-radius: var(--radius-sm);
  background: var(--error-light);
  color: var(--error);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.ai-chat-panel__messages {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.ai-chat-panel__placeholder {
  text-align: center;
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
  padding: var(--space-6) var(--space-3);
}

.chat-message {
  max-width: 90%;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  line-height: var(--leading-body-sm);
}

.chat-message--user {
  align-self: flex-end;
  background: var(--accent-light);
  color: var(--text-primary);
}

.chat-message--assistant {
  align-self: flex-start;
  background: var(--bg-secondary);
  color: var(--text-primary);
}

.chat-message__quote {
  background: var(--bg-tertiary);
  border-left: 2px solid var(--accent-primary);
  padding: var(--space-1) var(--space-2);
  margin-bottom: var(--space-2);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  font-size: var(--font-caption);
}

.chat-message__quote-label {
  color: var(--accent-primary);
  font-family: var(--font-code);
  font-weight: var(--weight-semibold);
}

.chat-message__quote-text {
  color: var(--text-secondary);
  white-space: pre-wrap;
  max-height: 60px;
  overflow: hidden;
}

.chat-message__refs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
  margin-bottom: var(--space-2);
}

.chat-ref-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) 6px;
  border-radius: var(--radius-sm);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  opacity: 0.85;
}

.chat-message__cursor {
  animation: blink 0.8s infinite;
  color: var(--accent-primary);
}

@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

.ai-chat-panel__input-area {
  padding: var(--space-2) var(--space-3) var(--space-1);
  border-top: 1px solid var(--border-subtle);
  position: relative;
}

.ai-chat-panel__selections {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  max-height: 120px;
  overflow-y: auto;
  margin-bottom: var(--space-2);
}

.ai-chat-panel__selected {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-2);
  background: var(--accent-light);
  border-radius: var(--radius-sm);
  font-size: var(--font-caption);
}

.ai-chat-panel__selected-label {
  color: var(--accent-primary);
  font-family: var(--font-code);
  font-weight: var(--weight-semibold);
}

.ai-chat-panel__selected-text {
  color: var(--text-secondary);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-chat-panel__selected-close {
  width: 20px;
  height: 20px;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--text-tertiary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-body-lg);
  transition: all var(--transition-fast);
  flex-shrink: 0;
}

.ai-chat-panel__selected-close:hover {
  background: var(--bg-tertiary);
  color: var(--text-primary);
}

.ai-chat-panel__input-box {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-lg);
  background: var(--input-bg);
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.ai-chat-panel__input-box:focus-within {
  border-color: var(--input-focus-border);
  box-shadow: 0 0 0 3px var(--input-focus-ring);
}

.ai-chat-panel__textarea {
  flex: 1;
  min-height: 22px;
  max-height: 140px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--text-primary);
  font-family: var(--font-body);
  font-size: var(--font-body-sm);
  line-height: 1.5;
  outline: none;
  resize: none;
  overflow-y: auto;
}

.ai-chat-panel__textarea::placeholder {
  color: var(--text-tertiary);
}

.ai-chat-panel__textarea:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ai-chat-panel__send-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border: none;
  border-radius: var(--radius-md);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.ai-chat-panel__send-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}

.ai-chat-panel__send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.ai-chat-panel__spinner {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.ai-chat-panel__input-hint {
  padding: var(--space-1) var(--space-1) 0;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  opacity: 0.7;
}

/* Slash mention dropdown */
.slash-dropdown {
  position: absolute;
  bottom: 100%;
  left: var(--space-3);
  right: var(--space-3);
  background: var(--bg-primary);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  max-height: 220px;
  overflow-y: auto;
  z-index: 20;
  margin-bottom: 2px;
}

.slash-dropdown__empty {
  padding: var(--space-3);
  text-align: center;
  color: var(--text-tertiary);
  font-size: var(--font-caption);
}

.slash-dropdown__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2) var(--space-3);
  cursor: pointer;
  transition: background var(--transition-fast);
  border-bottom: 1px solid var(--border-subtle);
}

.slash-dropdown__item:last-child {
  border-bottom: none;
}

.slash-dropdown__item--active {
  background: var(--accent-light);
}

.slash-dropdown__title {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  font-weight: var(--weight-medium);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.slash-dropdown__category {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  margin-left: var(--space-2);
  flex-shrink: 0;
}

/* Mention chips */
.ai-chat-panel__mentions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
  padding: 0 var(--space-2) var(--space-1);
}

.mention-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2) var(--space-1) var(--space-1);
  background: var(--accent-light);
  border: 1px solid var(--accent-primary);
  border-radius: var(--radius-sm);
  font-size: var(--font-caption);
  color: var(--text-primary);
  max-width: 160px;
}

.mention-chip__icon {
  flex-shrink: 0;
  opacity: 0.7;
}

.mention-chip__title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mention-chip__remove {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border: none;
  border-radius: 50%;
  background: none;
  color: var(--text-tertiary);
  cursor: pointer;
  font-size: var(--font-caption);
  flex-shrink: 0;
  transition: all var(--transition-fast);
}

.mention-chip__remove:hover {
  background: var(--bg-tertiary);
  color: var(--text-primary);
}

.ai-chat-panel__input-hint span {
  font-family: var(--font-code);
  font-weight: var(--weight-semibold);
  color: var(--accent-primary);
}

</style>
