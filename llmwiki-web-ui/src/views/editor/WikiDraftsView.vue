<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useToastStore } from '@/stores/toast'
import type { WikiPageDraftInfo } from '@/api/wiki'
import { listDrafts, deleteDraft } from '@/api/wiki'

const { t, locale } = useI18n()

const router = useRouter()
const toastStore = useToastStore()
const drafts = ref<WikiPageDraftInfo[]>([])
const loading = ref(true)

onMounted(async () => {
  await loadDrafts()
})

async function loadDrafts() {
  loading.value = true
  try {
    drafts.value = await listDrafts()
  } catch (e: any) {
    toastStore.error(t('common.operationFailed'), t('editor.loadFailed'))
  } finally {
    loading.value = false
  }
}

function openDraft(draft: WikiPageDraftInfo) {
  router.push({ name: 'WikiEditorDraft', params: { draftId: draft.id } })
}

async function handleDelete(draft: WikiPageDraftInfo) {
  const ok = confirm(t('editor.deleteDraftConfirm', [draft.title]))
  if (!ok) return
  try {
    await deleteDraft(draft.id)
    toastStore.success(t('editor.draftDeleted'))
    await loadDrafts()
  } catch {
    toastStore.error(t('editor.deleteFailed'), t('editor.deleteFailedDetail'))
  }
}

function formatDate(dateStr: string): string {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleString(locale.value === 'en' ? 'en-US' : 'zh-CN')
}
</script>

<template>
  <div class="wiki-drafts">
    <div class="wiki-drafts__header">
      <h2>{{ t('editor.draftsTitle') }}</h2>
    </div>

    <div v-if="loading" class="wiki-drafts__loading">
      <div v-for="i in 5" :key="i" class="wiki-drafts__skeleton-row">
        <div class="wiki-drafts__skeleton-cell wiki-drafts__skeleton-cell--title" />
        <div class="wiki-drafts__skeleton-cell wiki-drafts__skeleton-cell--sm" />
        <div class="wiki-drafts__skeleton-cell wiki-drafts__skeleton-cell--sm" />
      </div>
    </div>

    <table v-else-if="drafts.length > 0" class="wiki-drafts__table">
      <thead>
        <tr>
          <th>{{ t('editor.titleColumn') }}</th>
          <th class="wiki-drafts__th--sm">{{ t('wiki.category') }}</th>
          <th class="wiki-drafts__th--sm">{{ t('editor.typeColumn') }}</th>
          <th class="wiki-drafts__th--sm">{{ t('wiki.updatedAt') }}</th>
          <th class="wiki-drafts__th--action">{{ t('editor.actionColumn') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="draft in drafts"
          :key="draft.id"
          class="wiki-drafts__row"
          @click="openDraft(draft)"
        >
          <td class="wiki-drafts__td--title">{{ draft.title || t('editor.noTitle') }}</td>
          <td class="wiki-drafts__td--secondary">{{ draft.category || '' }}</td>
          <td>
            <span
              class="wiki-drafts__badge"
              :class="draft.pageId ? 'wiki-drafts__badge--edit' : 'wiki-drafts__badge--new'"
            >
              {{ draft.pageId ? t('editor.editDraftBadge') : t('editor.newDraft') }}
            </span>
          </td>
          <td class="wiki-drafts__td--secondary">{{ formatDate(draft.updatedAt) }}</td>
          <td class="wiki-drafts__td--action">
            <button
              class="wiki-drafts__delete-btn"
              @click.stop="handleDelete(draft)"
            >
              {{ t('common.delete') }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-else class="wiki-drafts__empty">
      <div class="wiki-drafts__empty-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z" />
          <polyline points="14 2 14 8 20 8" />
        </svg>
      </div>
      <p class="wiki-drafts__empty-text">{{ t('editor.noDrafts') }}</p>
      <p class="wiki-drafts__empty-hint">{{ t('editor.noDraftsHint') }}</p>
    </div>
  </div>
</template>

<style scoped>
.wiki-drafts {
  padding: var(--space-5);
  max-width: 1000px;
  margin: 0 auto;
}

.wiki-drafts__header {
  margin-bottom: var(--space-4);
}

.wiki-drafts__header h2 {
  margin: 0;
  font-size: var(--font-h3);
  font-family: var(--font-heading);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.wiki-drafts__table {
  width: 100%;
  border-collapse: collapse;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.wiki-drafts__table thead {
  background: var(--bg-secondary);
}

.wiki-drafts__table th {
  padding: var(--space-3) var(--space-4);
  text-align: left;
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-default);
}

.wiki-drafts__th--sm {
  width: 120px;
}

.wiki-drafts__th--action {
  width: 80px;
  text-align: center;
}

.wiki-drafts__row {
  cursor: pointer;
  transition: background var(--transition-fast);
}

.wiki-drafts__row:hover {
  background: var(--bg-secondary);
}

.wiki-drafts__row td {
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
  font-size: var(--font-body-sm);
  color: var(--text-primary);
}

.wiki-drafts__row:last-child td {
  border-bottom: none;
}

.wiki-drafts__td--title {
  font-weight: var(--weight-medium);
}

.wiki-drafts__td--secondary {
  color: var(--text-secondary);
}

.wiki-drafts__td--action {
  text-align: center;
}

.wiki-drafts__badge {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 var(--space-2);
  border-radius: var(--radius-pill);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.wiki-drafts__badge--new {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.wiki-drafts__badge--edit {
  background: var(--warning-light);
  color: var(--warning);
}

.wiki-drafts__delete-btn {
  display: inline-flex;
  align-items: center;
  height: var(--btn-height-sm);
  padding: 0 var(--space-2);
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--error);
  font-family: var(--font-body);
  font-size: var(--font-caption);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.wiki-drafts__delete-btn:hover {
  background: var(--error-light);
}

.wiki-drafts__loading {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-4) 0;
}

.wiki-drafts__skeleton-row {
  display: flex;
  gap: var(--space-4);
  padding: var(--space-3) 0;
}

.wiki-drafts__skeleton-cell {
  height: 14px;
  background: var(--bg-tertiary);
  border-radius: var(--radius-sm);
  animation: draft-skeleton-pulse 1.5s ease-in-out infinite;
}

.wiki-drafts__skeleton-cell--title {
  flex: 1;
}

.wiki-drafts__skeleton-cell--sm {
  width: 80px;
}

@keyframes draft-skeleton-pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}

.wiki-drafts__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--space-12) var(--space-5);
}

.wiki-drafts__empty-icon {
  color: var(--text-tertiary);
  opacity: 0.5;
  margin-bottom: var(--space-4);
}

.wiki-drafts__empty-text {
  margin: 0 0 var(--space-1);
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--text-secondary);
}

.wiki-drafts__empty-hint {
  margin: 0;
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}
</style>
