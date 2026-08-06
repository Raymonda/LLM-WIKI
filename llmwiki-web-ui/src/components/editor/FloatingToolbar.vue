<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{
  containerSelector: string
}>()

const emit = defineEmits<{
  (e: 'add-to-chat', payload: { text: string; lines: string }): void
}>()

const { t } = useI18n()

const visible = ref(false)
const posX = ref(0)
const posY = ref(0)
const selectedText = ref('')
const selectedLines = ref('')
const justAdded = ref(false)

function onMouseUp() {
  if (justAdded.value) return
  const sel = window.getSelection()
  if (!sel || sel.isCollapsed || !sel.toString().trim()) {
    hide()
    return
  }

  const container = document.querySelector(props.containerSelector)
  if (!container || !container.contains(sel.anchorNode)) {
    hide()
    return
  }

  selectedText.value = sel.toString()
  selectedLines.value = computeLineRange(sel, container)

  const rect = sel.getRangeAt(0).getBoundingClientRect()
  posX.value = rect.left + rect.width / 2
  posY.value = rect.top - 10

  visible.value = true
}

function computeLineRange(sel: Selection, container: Element): string {
  const fullText = container.textContent || ''
  const anchorOffset = getCharOffset(sel.anchorNode!, sel.anchorOffset, container)
  const focusOffset = getCharOffset(sel.focusNode!, sel.focusOffset, container)
  const start = Math.min(anchorOffset, focusOffset)
  const end = Math.max(anchorOffset, focusOffset)

  const lines = fullText.substring(0, start).split('\n')
  const startLine = lines.length
  const endLine = startLine + fullText.substring(start, end).split('\n').length - 1
  return `L${startLine}-L${endLine}`
}

function getCharOffset(node: Node, offset: number, root: Element): number {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  let count = 0
  while (walker.nextNode()) {
    if (walker.currentNode === node) {
      return count + offset
    }
    count += (walker.currentNode.textContent?.length || 0)
  }
  return count
}

function handleAddToChat() {
  emit('add-to-chat', { text: selectedText.value, lines: selectedLines.value })
  justAdded.value = true
  setTimeout(() => {
    justAdded.value = false
    hide()
    window.getSelection()?.removeAllRanges()
  }, 300)
}

function hide() {
  visible.value = false
}

onMounted(() => {
  document.addEventListener('mouseup', onMouseUp)
  document.addEventListener('mousedown', (e) => {
    if (!(e.target as HTMLElement).closest('.floating-toolbar')) {
      hide()
    }
  })
})

onUnmounted(() => {
  document.removeEventListener('mouseup', onMouseUp)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div
        v-if="visible"
        class="floating-toolbar"
        :class="{ 'floating-toolbar--added': justAdded }"
        :style="{ left: posX + 'px', top: posY + 'px' }"
      >
        <button
          class="floating-toolbar__btn"
          :class="{ 'floating-toolbar__btn--done': justAdded }"
          @click="handleAddToChat"
        >
          {{ justAdded ? t('editor.addedToChat') : t('editor.addToChat') }}
        </button>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.floating-toolbar {
  position: fixed;
  transform: translate(-50%, -100%);
  z-index: 9999;
  background: var(--surface-elevated);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  padding: var(--space-1);
}

.floating-toolbar__btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  border: none;
  background: none;
  cursor: pointer;
  font-family: var(--font-body);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--accent-primary);
  border-radius: var(--radius-sm);
  white-space: nowrap;
  transition: all var(--transition-fast);
}

.floating-toolbar__btn:hover:not(.floating-toolbar__btn--done) {
  background: var(--accent-light);
}

.floating-toolbar__btn--done {
  color: var(--success);
  pointer-events: none;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 150ms ease, transform 150ms ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translate(-50%, -100%) translateY(4px);
}
</style>
