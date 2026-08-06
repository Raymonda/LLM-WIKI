<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Rss, Plus, Trash2 } from 'lucide-vue-next'
import {
  listSubscriptions,
  createSubscription,
  cancelSubscription,
  type SubscriptionInfo,
} from '@/api/subscription'
import { listScopes, type ScopeInfo } from '@/api/scope'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'

const authStore = useAuthStore()
const toastStore = useToastStore()
const { t, locale } = useI18n()
const scopeId = ref(authStore.scopeId)
const subscriptions = ref<SubscriptionInfo[]>([])
const allScopes = ref<ScopeInfo[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const selectedPublisherScopeId = ref<number | null>(null)

onMounted(async () => {
  await loadData()
})

async function loadData() {
  loading.value = true
  try {
    const [subs, scopes] = await Promise.all([
      listSubscriptions(scopeId.value),
      listScopes(),
    ])
    subscriptions.value = subs
    allScopes.value = scopes.filter(
      (s) => s.type === 'team' && s.id !== scopeId.value
    )
  } catch (e: any) {
    toastStore.error(t('scope.subLoadFailed'), e.message || t('scope.subLoadFailedDetail'))
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  if (!selectedPublisherScopeId.value) {
    return
  }
  try {
    await createSubscription(scopeId.value, selectedPublisherScopeId.value)
    toastStore.success(t('scope.subCreated'), t('scope.subCreatedDetail'))
    dialogVisible.value = false
    selectedPublisherScopeId.value = null
    await loadData()
  } catch (e: any) {
    toastStore.error(t('scope.subCreateFailed'), e.message || t('scope.subCreateFailedDetail'))
  }
}

async function handleCancel(sub: SubscriptionInfo) {
  if (!confirm(t('scope.subCancelConfirm', [sub.publisherScopeName]))) return
  try {
    await cancelSubscription(scopeId.value, sub.id)
    toastStore.success(t('scope.subCancelled'), t('scope.subCancelledDetail', [sub.publisherScopeName]))
    await loadData()
  } catch (e: any) {
    toastStore.error(t('scope.subCancelFailed'), e.message || t('scope.subCancelFailedDetail'))
  }
}

function formatDate(dateStr: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString(locale.value === 'en' ? 'en-US' : 'zh-CN')
}
</script>

<template>
  <div class="subscription-manage">
    <div class="subscription-manage__header">
      <div class="subscription-manage__title">
        <Rss :size="24" />
        <h1>{{ t('scope.subscriptionsTitle') }}</h1>
      </div>
      <button class="subscription-manage__add-btn" @click="dialogVisible = true">
        <Plus :size="16" />
        {{ t('scope.addSubscription') }}
      </button>
    </div>

    <div class="subscription-manage__table-wrap">
      <div v-if="loading" class="subscription-manage__loading">{{ t('scope.subLoading') }}</div>
      <table v-else class="subscription-manage__table">
        <thead>
          <tr>
            <th>{{ t('scope.subKnowledgeBase') }}</th>
            <th>{{ t('scope.subStatus') }}</th>
            <th>{{ t('scope.subCreatedBy') }}</th>
            <th>{{ t('scope.subCreatedAt') }}</th>
            <th class="subscription-manage__th--action">{{ t('scope.subAction') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="sub in subscriptions" :key="sub.id" class="subscription-manage__row">
            <td class="subscription-manage__td--name">{{ sub.publisherScopeName }}</td>
            <td>
              <span
                class="subscription-manage__badge"
                :class="sub.status === 'active' ? 'subscription-manage__badge--active' : ''"
              >
                {{ sub.status === 'active' ? t('scope.subStatusActive') : sub.status }}
              </span>
            </td>
            <td>{{ sub.createdByName || '-' }}</td>
            <td class="subscription-manage__td--date">{{ formatDate(sub.createdAt) }}</td>
            <td>
              <button class="subscription-manage__delete-btn" @click="handleCancel(sub)" :title="t('scope.subCancelTitle')">
                <Trash2 :size="14" />
              </button>
            </td>
          </tr>
          <tr v-if="subscriptions.length === 0">
            <td colspan="5" class="subscription-manage__empty">
              <Rss :size="32" class="subscription-manage__empty-icon" />
              <p>{{ t('scope.noSubscriptions') }}</p>
              <p class="subscription-manage__empty-hint">{{ t('scope.subEmptyHint') }}</p>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <Transition name="dialog">
      <div v-if="dialogVisible" class="subscription-manage__overlay" @click.self="dialogVisible = false">
        <div class="subscription-manage__dialog">
          <h2>{{ t('scope.addSubscription') }}</h2>
          <div class="subscription-manage__field">
            <label>{{ t('scope.subSelectLabel') }}</label>
            <select v-model="selectedPublisherScopeId">
              <option :value="null" disabled>{{ t('scope.subSelectPlaceholder') }}</option>
              <option v-for="scope in allScopes" :key="scope.id" :value="scope.id">
                {{ scope.name }}
              </option>
            </select>
          </div>
          <div class="subscription-manage__dialog-actions">
            <button @click="dialogVisible = false">{{ t('scope.subCancelBtn') }}</button>
            <button class="subscription-manage__primary-btn" @click="handleCreate" :disabled="!selectedPublisherScopeId">{{ t('scope.subConfirmBtn') }}</button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.subscription-manage {
  padding: var(--space-6);
  max-width: 1200px;
}

.subscription-manage__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-6);
}

.subscription-manage__title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-primary);
}

.subscription-manage__title h1 {
  font-size: var(--font-h2);
  font-weight: var(--weight-semibold);
  margin: 0;
}

.subscription-manage__add-btn {
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

.subscription-manage__add-btn:hover {
  opacity: 0.9;
}

.subscription-manage__loading {
  text-align: center;
  padding: var(--space-8);
  color: var(--text-secondary);
}

.subscription-manage__table-wrap {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.subscription-manage__table {
  width: 100%;
  border-collapse: collapse;
}

.subscription-manage__table th {
  text-align: left;
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-default);
}

.subscription-manage__th--action {
  width: 80px;
  text-align: center !important;
}

.subscription-manage__row {
  border-bottom: 1px solid var(--border-subtle);
  transition: background var(--transition-fast);
}

.subscription-manage__row:last-child {
  border-bottom: none;
}

.subscription-manage__row:hover {
  background: var(--bg-secondary);
}

.subscription-manage__table td {
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-body);
  color: var(--text-primary);
  vertical-align: middle;
}

.subscription-manage__td--name {
  font-weight: var(--weight-medium);
}

.subscription-manage__td--date {
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  font-variant-numeric: tabular-nums;
}

.subscription-manage__badge {
  display: inline-block;
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  padding: 2px var(--space-2);
  border-radius: var(--radius-full);
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
}

.subscription-manage__badge--active {
  background: var(--success-light);
  color: var(--success);
}

.subscription-manage__delete-btn {
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
  margin: 0 auto;
}

.subscription-manage__delete-btn:hover {
  background: var(--error-light);
  color: var(--error);
}

.subscription-manage__empty {
  text-align: center;
  padding: var(--space-8) var(--space-4) !important;
  color: var(--text-tertiary);
}

.subscription-manage__empty-icon {
  opacity: 0.3;
  margin-bottom: var(--space-3);
}

.subscription-manage__empty p {
  color: var(--text-secondary);
  font-size: var(--font-body);
  margin: 0;
}

.subscription-manage__empty-hint {
  color: var(--text-tertiary) !important;
  font-size: var(--font-body-sm) !important;
  margin-top: var(--space-2) !important;
}

/* Dialog */
.subscription-manage__overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
}

.subscription-manage__dialog {
  background: var(--surface-elevated);
  border-radius: var(--radius-lg);
  padding: var(--space-6);
  width: 480px;
  max-height: 80vh;
  overflow-y: auto;
}

.subscription-manage__dialog h2 {
  font-size: var(--font-h3);
  color: var(--text-primary);
  margin-bottom: var(--space-4);
}

.subscription-manage__field {
  margin-bottom: var(--space-4);
}

.subscription-manage__field label {
  display: block;
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin-bottom: var(--space-1);
}

.subscription-manage__field select {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  background: var(--bg-primary);
  color: var(--text-primary);
  height: var(--input-height);
}

.subscription-manage__field select:focus {
  outline: none;
  border-color: var(--accent-primary);
  box-shadow: 0 0 0 2px var(--accent-light);
}

.subscription-manage__dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

.subscription-manage__dialog-actions button {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  cursor: pointer;
  background: none;
  border: 1px solid var(--border-default);
  color: var(--text-secondary);
  transition: all var(--transition-fast);
}

.subscription-manage__dialog-actions button:hover {
  background: var(--bg-tertiary);
}

.subscription-manage__primary-btn {
  background: var(--accent-primary) !important;
  color: var(--text-on-accent) !important;
  border: none !important;
}

.subscription-manage__primary-btn:hover {
  opacity: 0.9;
}

.subscription-manage__primary-btn:disabled {
  opacity: 0.38;
  cursor: not-allowed;
}

/* Transition */
.dialog-enter-active,
.dialog-leave-active {
  transition: all var(--transition-normal);
}

.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}
</style>
