<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { listTrashPages, restorePage, permanentDeletePage, type WikiPageInfo } from '@/api/wiki'
import { Trash2, RotateCcw, AlertTriangle, Loader2 } from 'lucide-vue-next'

const { t, locale } = useI18n()

const pages = ref<WikiPageInfo[]>([])
const loading = ref(true)
const errorMsg = ref('')
const operatingId = ref<number | null>(null)
const confirmDeleteId = ref<number | null>(null)

async function loadTrash() {
  loading.value = true
  errorMsg.value = ''
  try {
    pages.value = await listTrashPages()
  } catch (e: any) {
    errorMsg.value = e.message || t('wiki.loadFailed')
  } finally {
    loading.value = false
  }
}

async function handleRestore(page: WikiPageInfo) {
  operatingId.value = page.id
  try {
    await restorePage(page.id)
    pages.value = pages.value.filter(p => p.id !== page.id)
  } catch (e) {
    console.error('Restore failed:', e)
  } finally {
    operatingId.value = null
  }
}

async function handlePermanentDelete(page: WikiPageInfo) {
  if (confirmDeleteId.value !== page.id) {
    confirmDeleteId.value = page.id
    return
  }
  operatingId.value = page.id
  try {
    await permanentDeletePage(page.id)
    pages.value = pages.value.filter(p => p.id !== page.id)
    confirmDeleteId.value = null
  } catch (e) {
    console.error('Permanent delete failed:', e)
  } finally {
    operatingId.value = null
  }
}

function cancelConfirmDelete() {
  confirmDeleteId.value = null
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '—'
  try {
    return new Date(dateStr).toLocaleString(locale.value === 'en' ? 'en-US' : 'zh-CN')
  } catch {
    return dateStr
  }
}

onMounted(loadTrash)
</script>

<template>
  <div class="trash-view">
    <div class="trash-view__header">
      <h1 class="trash-view__title">
        <Trash2 :size="24" />
        {{ t('wiki.trashTitle') }}
      </h1>
      <p class="trash-view__subtitle">{{ t('wiki.trashSubtitle') }}</p>
    </div>

    <div v-if="loading" class="trash-view__loading">
      <Loader2 :size="20" class="trash-view__spinner" />
      {{ t('common.loading') }}
    </div>
    <div v-else-if="errorMsg" class="trash-view__error">{{ errorMsg }}</div>
    <div v-else-if="pages.length === 0" class="trash-view__empty">
      <Trash2 :size="48" />
      <p>{{ t('wiki.trashEmpty') }}</p>
      <span>{{ t('wiki.trashEmptyHint') }}</span>
    </div>
    <div v-else class="trash-view__list">
      <div v-for="page in pages" :key="page.id" class="trash-view__item">
        <div class="trash-view__item-info">
          <h3 class="trash-view__item-title">{{ page.title }}</h3>
          <div class="trash-view__item-meta">
            <span v-if="page.category" class="trash-view__item-category">{{ page.category }}</span>
            <span>{{ t('wiki.deletedAt', [formatDate(page.deletedAt)]) }}</span>
            <span v-if="page.mergedIntoPageId" class="trash-view__item-merged">{{ t('wiki.mergedInto', [page.mergedIntoPageId]) }}</span>
          </div>
          <p v-if="page.summary" class="trash-view__item-summary">{{ page.summary }}</p>
        </div>
        <div class="trash-view__item-actions">
          <button
            class="trash-view__btn trash-view__btn--restore"
            :disabled="operatingId === page.id"
            @click="handleRestore(page)"
          >
            <RotateCcw :size="14" />
            {{ operatingId === page.id ? t('wiki.restoring') : t('wiki.restore') }}
          </button>
          <button
            v-if="confirmDeleteId === page.id"
            class="trash-view__btn trash-view__btn--confirm-delete"
            :disabled="operatingId === page.id"
            @click="handlePermanentDelete(page)"
          >
            <AlertTriangle :size="14" />
            {{ operatingId === page.id ? t('wiki.deleting') : t('wiki.confirmPermanentDelete') }}
          </button>
          <button
            v-else
            class="trash-view__btn trash-view__btn--danger"
            :disabled="operatingId === page.id"
            @click="handlePermanentDelete(page)"
          >
            <Trash2 :size="14" />
            {{ t('wiki.permanentDelete') }}
          </button>
          <button
            v-if="confirmDeleteId === page.id"
            class="trash-view__btn trash-view__btn--cancel"
            @click="cancelConfirmDelete"
          >
            {{ t('common.cancel') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trash-view {
  max-width: 900px;
  margin: 0 auto;
  padding: var(--space-6) var(--space-4);
}

.trash-view__header {
  margin-bottom: var(--space-6);
}

.trash-view__title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-h2, 1.5rem);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
  margin-bottom: var(--space-2);
}

.trash-view__subtitle {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.trash-view__loading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-8);
  justify-content: center;
  color: var(--text-tertiary);
}

.trash-view__spinner {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.trash-view__error {
  padding: var(--space-4);
  text-align: center;
  color: var(--error, #e74c3c);
}

.trash-view__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-8);
  color: var(--text-tertiary);
}

.trash-view__empty p {
  font-size: var(--font-body-lg, 1.1rem);
  font-weight: var(--weight-medium);
  margin: 0;
}

.trash-view__empty span {
  font-size: var(--font-body-sm);
}

.trash-view__list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.trash-view__item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-4);
  padding: var(--space-4);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  transition: box-shadow var(--transition-fast);
}

.trash-view__item:hover {
  box-shadow: var(--shadow-sm);
}

.trash-view__item-info {
  flex: 1;
  min-width: 0;
}

.trash-view__item-title {
  font-size: var(--font-body-lg, 1.1rem);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-bottom: var(--space-1);
}

.trash-view__item-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  margin-bottom: var(--space-2);
}

.trash-view__item-category {
  padding: 1px var(--space-2);
  background: var(--bg-tertiary);
  border-radius: var(--radius-sm);
}

.trash-view__item-merged {
  color: var(--accent-primary);
}

.trash-view__item-summary {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin: 0;
}

.trash-view__item-actions {
  display: flex;
  gap: var(--space-2);
  flex-shrink: 0;
}

.trash-view__btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  border: none;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
}

.trash-view__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.trash-view__btn--restore {
  background: var(--accent-primary);
  color: var(--text-on-accent);
}

.trash-view__btn--restore:hover:not(:disabled) {
  background: var(--accent-hover);
}

.trash-view__btn--danger {
  background: var(--bg-tertiary);
  color: var(--error, #e74c3c);
  border: 1px solid var(--border-default);
}

.trash-view__btn--danger:hover:not(:disabled) {
  background: var(--error-light, #fde8e8);
}

.trash-view__btn--confirm-delete {
  background: var(--error, #e74c3c);
  color: #fff;
}

.trash-view__btn--confirm-delete:hover:not(:disabled) {
  opacity: 0.85;
}

.trash-view__btn--cancel {
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  border: 1px solid var(--btn-secondary-border);
}

.trash-view__btn--cancel:hover {
  opacity: 0.85;
}
</style>
