<script setup lang="ts">
import { ref, onMounted, onUnmounted, reactive, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToastStore } from '@/stores/toast'
import { useAuthStore } from '@/stores/auth'
import { useConfirmDialog } from '@/composables/useConfirmDialog'
import { Loader2, KeyRound, Ban, Plus, Copy, X, Search } from 'lucide-vue-next'
import { listApiKeys, listAllApiKeys, createApiKey, revokeApiKey, getMcpPublicUrl, type ApiKeyInfo } from '@/api/apiKey'

const props = defineProps<{ manageAll?: boolean }>()

const { t } = useI18n()
const toastStore = useToastStore()
const authStore = useAuthStore()
const { state: confirmState, showConfirm, onConfirm, onCancel, ConfirmDialog } = useConfirmDialog()

const mcpTools = [
  { name: 'wiki_search', descKey: 'apiKeys.toolWikiSearch' },
  { name: 'wiki_read_page', descKey: 'apiKeys.toolWikiReadPage' },
  { name: 'wiki_ask', descKey: 'apiKeys.toolWikiAsk' },
  { name: 'wiki_ingest_text', descKey: 'apiKeys.toolWikiIngestText' },
  { name: 'wiki_ingest_status', descKey: 'apiKeys.toolWikiIngestStatus' },
  { name: 'wiki_cancel_ingest', descKey: 'apiKeys.toolWikiCancelIngest' },
]

const keys = ref<ApiKeyInfo[]>([])
const keysLoading = ref(false)
const baseUrl = ref('')
const baseUrlLoading = ref(false)
const rawKeyInput = ref('')
const createKeyVisible = ref(false)
const createKeyForm = reactive({ name: '', scopeId: 0, expiresAt: '' })
const creatingKey = ref(false)
const userFilter = ref('')

const filteredKeys = computed(() => {
  const q = userFilter.value.trim().toLowerCase()
  if (!q) return keys.value
  return keys.value.filter(k => (k.username || `#${k.userId}`).toLowerCase().includes(q))
})

const configSnippet = computed(() => {
  const base = baseUrl.value.trim().replace(/\/+$/, '')
  const key = rawKeyInput.value.trim()
  if (!base || !key) return ''
  const config = {
    mcpServers: {
      llmwiki: {
        type: 'http',
        url: `${base}/mcp`,
        headers: { Authorization: `Bearer ${key}` },
      },
    },
  }
  return JSON.stringify(config, null, 2)
})

const alertDialog = reactive({
  visible: false,
  title: '',
  message: '',
  confirmText: '',
  resolve: null as (() => void) | null,
})

function showAlert(title: string, message: string, confirmText?: string): Promise<void> {
  return new Promise(resolve => {
    alertDialog.title = title
    alertDialog.message = message
    alertDialog.confirmText = confirmText || t('apiKeys.copiedConfirm')
    alertDialog.resolve = resolve
    alertDialog.visible = true
  })
}

function onAlertOk() {
  alertDialog.visible = false
  alertDialog.resolve?.()
  alertDialog.resolve = null
}

function scopeDisplay(k: ApiKeyInfo) {
  if (k.scopeType === 'personal') return t('common.personalKB')
  return k.scopeName || `#${k.scopeId}`
}

function keyStatusLabel(k: ApiKeyInfo) {
  if (k.status === 'revoked') return t('apiKeys.keyStatusRevoked')
  if (k.expiresAt && new Date(k.expiresAt) < new Date()) return t('apiKeys.keyStatusExpired')
  return t('apiKeys.keyStatusActive')
}

function keyStatusTone(k: ApiKeyInfo) {
  if (k.status === 'revoked') return 'danger'
  if (k.expiresAt && new Date(k.expiresAt) < new Date()) return 'warning'
  return 'success'
}

function formatTime(s: string | null) {
  if (!s) return '—'
  return s.slice(0, 16).replace('T', ' ')
}

async function loadKeys() {
  keysLoading.value = true
  try {
    keys.value = props.manageAll ? await listAllApiKeys() : await listApiKeys()
  } catch (e: any) {
    toastStore.error(t('apiKeys.loadFailed'), e?.message || t('apiKeys.loadFailed'))
  } finally {
    keysLoading.value = false
  }
}

async function loadPublicUrl() {
  baseUrlLoading.value = true
  try {
    baseUrl.value = (await getMcpPublicUrl()) || ''
  } catch {
    baseUrl.value = fallbackBaseUrl()
  } finally {
    baseUrlLoading.value = false
  }
}

function fallbackBaseUrl() {
  return import.meta.env.DEV
    ? `${window.location.protocol}//${window.location.hostname}:8080`
    : window.location.origin
}

async function copyConfig() {
  if (!configSnippet.value) return
  try {
    await navigator.clipboard.writeText(configSnippet.value)
    toastStore.success(t('apiKeys.copySuccess'))
  } catch {
    toastStore.error(t('apiKeys.operationFailed'))
  }
}

function openCreateKeyDialog() {
  createKeyForm.name = ''
  createKeyForm.scopeId = 0
  createKeyForm.expiresAt = ''
  createKeyVisible.value = true
}

async function submitCreateKey() {
  if (!createKeyForm.name.trim()) {
    toastStore.warning(t('apiKeys.keyNameRequired'))
    return
  }
  if (!createKeyForm.scopeId) {
    toastStore.warning(t('apiKeys.keyScopeRequired'))
    return
  }
  creatingKey.value = true
  try {
    const res = await createApiKey({
      name: createKeyForm.name.trim(),
      scopeId: createKeyForm.scopeId,
      expiresAt: createKeyForm.expiresAt ? `${createKeyForm.expiresAt}:00` : undefined,
    })
    createKeyVisible.value = false
    rawKeyInput.value = res.key
    await showAlert(t('apiKeys.keyCreatedTitle'), t('apiKeys.keyCreatedMsg', [res.key]))
    await loadKeys()
  } catch (e: any) {
    toastStore.error(t('apiKeys.createFailed'), e?.message)
  } finally {
    creatingKey.value = false
  }
}

async function revokeKey(k: ApiKeyInfo) {
  const ok = await showConfirm({
    title: t('apiKeys.confirmTitle'),
    message: t('apiKeys.revokeConfirm', [k.name]),
    type: 'danger',
    confirmVariant: 'danger',
  })
  if (!ok) return
  try {
    await revokeApiKey(k.id)
    toastStore.success(t('apiKeys.revoked'))
    await loadKeys()
  } catch (e: any) {
    toastStore.error(t('apiKeys.operationFailed'), e?.message)
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  if (createKeyVisible.value) {
    createKeyVisible.value = false
  } else if (alertDialog.visible) {
    onAlertOk()
  }
}

onMounted(() => {
  loadKeys()
  loadPublicUrl()
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="mcp-panel">
    <div class="mcp-panel__header">
      <div class="mcp-panel__header-text">
        <h2 class="mcp-panel__title">{{ manageAll ? t('apiKeys.adminPanelTitle') : t('apiKeys.panelTitle') }}</h2>
        <p class="mcp-panel__hint">{{ manageAll ? t('apiKeys.adminPanelHint') : t('apiKeys.panelHint') }}</p>
        <p v-if="manageAll" class="mcp-panel__hint mcp-panel__hint--admin">{{ t('apiKeys.adminSectionHint') }}</p>
      </div>
      <button v-if="!manageAll" class="mcp-panel__btn-primary" @click="openCreateKeyDialog">
        <Plus :size="16" />
        {{ t('apiKeys.createKeyBtn') }}
      </button>
    </div>

    <section v-if="!manageAll" class="mcp-panel__section">
      <h3 class="mcp-panel__section-title">{{ t('apiKeys.stepsTitle') }}</h3>
      <ol class="mcp-panel__steps">
        <li>{{ t('apiKeys.step1') }}</li>
        <li>{{ t('apiKeys.step2') }}</li>
        <li>{{ t('apiKeys.step3') }}</li>
      </ol>
      <p class="mcp-panel__note">{{ t('apiKeys.authNote') }}</p>
    </section>

    <section v-if="!manageAll" class="mcp-panel__section">
      <h3 class="mcp-panel__section-title">{{ t('apiKeys.toolsTitle') }}</h3>
      <div class="mcp-panel__tools">
        <div v-for="tool in mcpTools" :key="tool.name" class="mcp-panel__tool">
          <code class="mcp-panel__tool-name">{{ tool.name }}</code>
          <span class="mcp-panel__tool-desc">{{ t(tool.descKey) }}</span>
        </div>
      </div>
      <p class="mcp-panel__note">{{ t('apiKeys.toolsNote') }}</p>
    </section>

    <section v-if="!manageAll" class="mcp-panel__section">
      <h3 class="mcp-panel__section-title">{{ t('apiKeys.configTitle') }}</h3>
      <div class="mcp-panel__config-row">
        <label class="mcp-panel__config-label">{{ t('apiKeys.urlLabel') }}</label>
        <div class="mcp-panel__config-input">
          <input :value="baseUrl" class="mcp-panel__form-input" readonly :placeholder="t('apiKeys.urlLoading')" />
          <p class="mcp-panel__note mcp-panel__note--tight">{{ t('apiKeys.urlHint') }}</p>
        </div>
      </div>
      <div class="mcp-panel__config-row">
        <label class="mcp-panel__config-label">{{ t('apiKeys.keyInputLabel') }}</label>
        <input v-model="rawKeyInput" class="mcp-panel__form-input" :placeholder="t('apiKeys.keyInputPlaceholder')" />
      </div>
      <pre v-if="configSnippet" class="mcp-panel__snippet">{{ configSnippet }}</pre>
      <button class="mcp-panel__btn-secondary" :disabled="!configSnippet" @click="copyConfig">
        <Copy :size="14" />
        {{ t('apiKeys.copyBtn') }}
      </button>
    </section>

    <section class="mcp-panel__section">
      <h3 class="mcp-panel__section-title">{{ manageAll ? t('apiKeys.adminKeySectionTitle') : t('apiKeys.keySectionTitle') }}</h3>
      <p class="mcp-panel__note">{{ manageAll ? t('apiKeys.adminKeySectionHint') : t('apiKeys.keySectionHint') }}</p>

      <div v-if="manageAll && !keysLoading && keys.length > 0" class="mcp-panel__search">
        <Search :size="16" class="mcp-panel__search-icon" />
        <input v-model="userFilter" class="mcp-panel__search-input" :placeholder="t('apiKeys.keySearchPlaceholder')" />
        <button v-if="userFilter" class="mcp-panel__search-clear" :title="t('apiKeys.keySearchClear')" @click="userFilter = ''">
          <X :size="14" />
        </button>
      </div>

      <div v-if="keysLoading" class="mcp-panel__loading">
        <Loader2 :size="20" class="mcp-panel__spin" />
        <span>{{ t('common.loading') }}</span>
      </div>

      <div v-else-if="keys.length === 0" class="mcp-panel__empty">
        <KeyRound :size="28" class="mcp-panel__empty-icon" />
        <span>{{ t('apiKeys.keyListEmpty') }}</span>
        <span class="mcp-panel__empty-hint">{{ manageAll ? t('apiKeys.keyListEmptyHintAdmin') : t('apiKeys.keyListEmptyHint') }}</span>
      </div>

      <div v-else-if="filteredKeys.length === 0" class="mcp-panel__empty">
        <Search :size="28" class="mcp-panel__empty-icon" />
        <span>{{ t('apiKeys.keySearchEmpty') }}</span>
      </div>

      <div v-else class="mcp-panel__table-scroll">
        <div class="mcp-panel__key-table" :class="{ 'mcp-panel__key-table--all': manageAll }">
          <div class="mcp-panel__key-row mcp-panel__key-row--head">
            <span>{{ t('apiKeys.keyName') }}</span>
            <span v-if="manageAll">{{ t('apiKeys.keyUser') }}</span>
            <span>{{ t('apiKeys.keyScope') }}</span>
            <span>{{ t('apiKeys.keyStatus') }}</span>
            <span>{{ t('apiKeys.keyExpires') }}</span>
            <span>{{ t('apiKeys.keyLastUsed') }}</span>
            <span>{{ t('apiKeys.keyActions') }}</span>
          </div>
          <div v-for="k in filteredKeys" :key="k.id" class="mcp-panel__key-row">
            <span class="mcp-panel__key-name">{{ k.name }}</span>
            <span v-if="manageAll">{{ k.username || `#${k.userId}` }}</span>
            <span>{{ scopeDisplay(k) }}</span>
            <span>
              <span class="mcp-panel__badge" :class="`mcp-panel__badge--${keyStatusTone(k)}`">
                {{ keyStatusLabel(k) }}
              </span>
            </span>
            <span class="mcp-panel__key-muted">{{ k.expiresAt ? formatTime(k.expiresAt) : t('apiKeys.noExpiry') }}</span>
            <span class="mcp-panel__key-muted">{{ k.lastUsedAt ? formatTime(k.lastUsedAt) : t('apiKeys.neverUsed') }}</span>
            <span>
              <button v-if="k.status === 'active'" class="mcp-panel__revoke-btn" @click="revokeKey(k)">
                <Ban :size="14" />
                {{ t('apiKeys.revokeBtn') }}
              </button>
            </span>
          </div>
        </div>
      </div>
    </section>

    <ConfirmDialog
      :open="confirmState.open"
      :title="confirmState.title"
      :message="confirmState.message"
      :confirm-text="confirmState.confirmText"
      :cancel-text="confirmState.cancelText"
      :type="confirmState.type"
      :confirm-variant="confirmState.confirmVariant"
      @confirm="onConfirm"
      @cancel="onCancel"
    />

    <div v-if="alertDialog.visible" class="mcp-panel__dialog-overlay" @click.self="onAlertOk">
      <div class="mcp-panel__dialog">
        <div class="mcp-panel__dialog-header">
          <h3 class="mcp-panel__dialog-title">{{ alertDialog.title }}</h3>
          <button class="mcp-panel__dialog-close" @click="onAlertOk">
            <X :size="18" />
          </button>
        </div>
        <div class="mcp-panel__dialog-body">
          <p class="mcp-panel__dialog-message">{{ alertDialog.message }}</p>
        </div>
        <div class="mcp-panel__dialog-footer">
          <button class="mcp-panel__btn-primary" @click="onAlertOk">{{ alertDialog.confirmText }}</button>
        </div>
      </div>
    </div>

    <div v-if="createKeyVisible" class="mcp-panel__dialog-overlay" @click.self="createKeyVisible = false">
      <div class="mcp-panel__dialog">
        <div class="mcp-panel__dialog-header">
          <h3 class="mcp-panel__dialog-title">{{ t('apiKeys.createKeyDialogTitle') }}</h3>
          <button class="mcp-panel__dialog-close" @click="createKeyVisible = false">
            <X :size="18" />
          </button>
        </div>
        <div class="mcp-panel__dialog-body">
          <div class="mcp-panel__form-field">
            <label class="mcp-panel__form-label">{{ t('apiKeys.keyName') }} <span class="mcp-panel__form-required">*</span></label>
            <input v-model="createKeyForm.name" class="mcp-panel__form-input" :placeholder="t('apiKeys.keyNamePlaceholder')" />
          </div>
          <div class="mcp-panel__form-field">
            <label class="mcp-panel__form-label">{{ t('apiKeys.keyScope') }} <span class="mcp-panel__form-required">*</span></label>
            <select v-model="createKeyForm.scopeId" class="mcp-panel__form-select">
              <option :value="0" disabled>{{ t('apiKeys.selectScopePlaceholder') }}</option>
              <option v-for="s in authStore.scopes" :key="s.scopeId" :value="s.scopeId">
                {{ s.scopeType === 'personal' ? t('common.personalKB') : s.scopeName }}
              </option>
            </select>
          </div>
          <div class="mcp-panel__form-field">
            <label class="mcp-panel__form-label">{{ t('apiKeys.keyExpires') }}</label>
            <input v-model="createKeyForm.expiresAt" type="datetime-local" class="mcp-panel__form-input" :placeholder="t('apiKeys.keyExpiresPlaceholder')" />
          </div>
        </div>
        <div class="mcp-panel__dialog-footer">
          <button class="mcp-panel__btn-secondary" @click="createKeyVisible = false">{{ t('apiKeys.cancelBtn') }}</button>
          <button class="mcp-panel__btn-primary mcp-panel__btn-primary--lg" :disabled="creatingKey" @click="submitCreateKey">
            <Loader2 v-if="creatingKey" :size="14" class="mcp-panel__spin" />
            {{ t('apiKeys.createKeyBtn2') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mcp-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.mcp-panel__title {
  margin: 0 0 var(--space-2) 0;
  font-size: var(--font-h2);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.mcp-panel__hint {
  margin: 0;
  font-size: var(--font-body);
  color: var(--text-secondary);
  line-height: 1.6;
  max-width: 640px;
}

.mcp-panel__hint--admin {
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--warning-light);
  border: 1px solid var(--warning);
  border-radius: var(--radius-md);
  color: var(--text-primary);
}

.mcp-panel__section {
  margin-bottom: var(--space-8);
}

.mcp-panel__section-title {
  margin: 0 0 var(--space-3) 0;
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.mcp-panel__steps {
  margin: 0 0 var(--space-3) 0;
  padding-left: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.mcp-panel__steps li {
  font-size: var(--font-body);
  color: var(--text-secondary);
  line-height: 1.6;
}

.mcp-panel__note {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
  line-height: 1.6;
  margin: 0;
}

.mcp-panel__note--tight {
  margin-top: var(--space-1);
}

.mcp-panel__tools {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.mcp-panel__tool {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  background: var(--bg-tertiary);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.mcp-panel__tool:hover {
  background: var(--bg-secondary);
  border-color: var(--border-default);
}

.mcp-panel__tool-name {
  font-family: var(--font-code);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--accent-primary);
  background: var(--code-bg);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  align-self: flex-start;
}

.mcp-panel__tool-desc {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.5;
}

.mcp-panel__config-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-4);
  margin-bottom: var(--space-3);
}

.mcp-panel__config-label {
  min-width: 120px;
  padding-top: var(--space-3);
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}

.mcp-panel__config-input {
  flex: 1;
}

.mcp-panel__config-row > .mcp-panel__form-input {
  flex: 1;
}

.mcp-panel__snippet {
  margin: 0 0 var(--space-3) 0;
  padding: var(--space-3) var(--space-4);
  background: var(--code-bg);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  font-family: var(--font-code);
  font-size: var(--font-body-sm);
  color: var(--code-text);
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.mcp-panel__loading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-8);
  color: var(--text-secondary);
}

.mcp-panel__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-8);
  text-align: center;
  background: var(--bg-tertiary);
  border-radius: var(--radius-lg);
  color: var(--text-secondary);
}

.mcp-panel__empty-icon {
  color: var(--text-tertiary);
}

.mcp-panel__empty-hint {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}

.mcp-panel__search {
  position: relative;
  margin-top: var(--space-3);
  max-width: 320px;
}

.mcp-panel__search-icon {
  position: absolute;
  left: var(--space-2);
  top: 50%;
  transform: translateY(-50%);
  color: var(--text-tertiary);
  pointer-events: none;
}

.mcp-panel__search-input {
  width: 100%;
  box-sizing: border-box;
  padding: var(--space-2) var(--space-8) var(--space-2) var(--space-8);
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-family: var(--font-body);
  color: var(--text-primary);
  outline: none;
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.mcp-panel__search-input:focus {
  border-color: var(--accent-primary);
}

.mcp-panel__search-clear {
  position: absolute;
  right: var(--space-2);
  top: 50%;
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  background: none;
  color: var(--text-tertiary);
  cursor: pointer;
}

.mcp-panel__search-clear:hover {
  color: var(--text-primary);
}

.mcp-panel__table-scroll {
  overflow-x: auto;
  margin-top: var(--space-3);
}

.mcp-panel__key-table {
  display: flex;
  flex-direction: column;
  min-width: 720px;
}

.mcp-panel__key-table--all {
  min-width: 860px;
}

.mcp-panel__key-row {
  display: grid;
  grid-template-columns: minmax(160px, 1.6fr) minmax(120px, 1.2fr) 100px minmax(140px, 1fr) minmax(140px, 1fr) 88px;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
  font-size: var(--font-body-sm);
  color: var(--text-primary);
}

.mcp-panel__key-table--all .mcp-panel__key-row {
  grid-template-columns: minmax(140px, 1.4fr) minmax(100px, 1fr) minmax(120px, 1.1fr) 100px minmax(140px, 1fr) minmax(140px, 1fr) 88px;
}

.mcp-panel__key-row:hover {
  background: var(--bg-tertiary);
}

.mcp-panel__key-row--head:hover {
  background: none;
}

.mcp-panel__key-row:last-child {
  border-bottom: none;
}

.mcp-panel__key-row--head {
  font-weight: var(--weight-medium);
  color: var(--text-tertiary);
  font-size: var(--font-caption);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding-top: 0;
}

.mcp-panel__key-name {
  font-weight: var(--weight-medium);
}

.mcp-panel__key-muted {
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
}

.mcp-panel__badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
}

.mcp-panel__badge--success {
  background: var(--success-light);
  color: var(--success);
}

.mcp-panel__badge--warning {
  background: var(--warning-light);
  color: var(--warning);
}

.mcp-panel__badge--danger {
  background: var(--error-light);
  color: var(--error);
}

.mcp-panel__revoke-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: var(--btn-height-sm);
  padding: 0 var(--space-2);
  background: transparent;
  border: 1px solid var(--error);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--error);
  cursor: pointer;
  white-space: nowrap;
  transition: all var(--transition-fast);
}

.mcp-panel__revoke-btn:hover {
  background: var(--error-light);
  transform: scale(1.02);
}

.mcp-panel__revoke-btn:active {
  background: var(--error-light);
  transform: scale(0.98);
}

.mcp-panel__btn-primary {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.mcp-panel__btn-primary:hover:not(:disabled) {
  transform: scale(1.02);
}

.mcp-panel__btn-primary:active:not(:disabled) {
  transform: scale(0.98);
}

.mcp-panel__btn-primary:disabled {
  opacity: 0.38;
  cursor: not-allowed;
}

.mcp-panel__btn-primary--lg {
  min-height: var(--btn-height);
}

.mcp-panel__btn-secondary {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  border: 1px solid var(--btn-secondary-border);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.mcp-panel__btn-secondary:hover:not(:disabled) {
  transform: scale(1.02);
}

.mcp-panel__btn-secondary:active:not(:disabled) {
  transform: scale(0.98);
}

.mcp-panel__btn-secondary:disabled {
  opacity: 0.38;
  cursor: not-allowed;
}

.mcp-panel__dialog-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
}

.mcp-panel__dialog {
  width: 480px;
  max-width: calc(100vw - var(--space-8));
  background: var(--bg-primary);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
}

.mcp-panel__dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border-subtle);
}

.mcp-panel__dialog-title {
  margin: 0;
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.mcp-panel__dialog-close {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-1);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--text-tertiary);
  cursor: pointer;
  transition: color var(--transition-fast);
}

.mcp-panel__dialog-close:hover {
  color: var(--text-primary);
}

.mcp-panel__dialog-body {
  padding: var(--space-5);
}

.mcp-panel__dialog-message {
  margin: 0;
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.6;
  white-space: pre-line;
  word-break: break-all;
}

.mcp-panel__dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-5) var(--space-4);
  border-top: 1px solid var(--border-subtle);
}

.mcp-panel__form-field {
  margin-bottom: var(--space-4);
}

.mcp-panel__form-label {
  display: block;
  margin-bottom: var(--space-2);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}

.mcp-panel__form-required {
  color: var(--error);
}

.mcp-panel__form-input {
  width: 100%;
  box-sizing: border-box;
  padding: var(--space-2) var(--space-3);
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-family: var(--font-body);
  color: var(--text-primary);
  outline: none;
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.mcp-panel__form-input:focus {
  border-color: var(--accent-primary);
}

.mcp-panel__form-input[readonly] {
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  cursor: default;
}

.mcp-panel__form-select {
  width: 100%;
  box-sizing: border-box;
  padding: var(--space-2) var(--space-3);
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-family: var(--font-body);
  color: var(--text-primary);
  outline: none;
}

.mcp-panel__spin {
  animation: mcp-panel-spin 1s linear infinite;
}

@keyframes mcp-panel-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
