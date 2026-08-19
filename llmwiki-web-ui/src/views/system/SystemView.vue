<script setup lang="ts">
import { ref, onMounted, reactive, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToastStore } from '@/stores/toast'
import { useConfirmDialog } from '@/composables/useConfirmDialog'
import { Settings, FileText, Users, Bot, GitPullRequest, Sparkles, History, RotateCcw, AlertTriangle, Plus, Search, ShieldCheck, User, CheckCircle2, XCircle, ArrowUpDown, ChevronLeft, ChevronRight, X, Plug, Ban, KeyRound } from 'lucide-vue-next'
import {
  listSchemas,
  countPendingPatches,
  listSchemaVersions,
  rollbackSchemaVersion,
  getSchemaMigrationReport,
  type SchemaVersionInfo,
  type SchemaMigrationReport,
} from '@/api/harness'
import {
  listUsers as apiListUsers,
  createUser as apiCreateUser,
  updateUserStatus,
  updateUserRole,
  resetPassword as apiResetPassword,
  type UserManageInfo,
} from '@/api/user'
import WikiPageRenderer from '@/components/wiki/WikiPageRenderer.vue'
import McpAgentPanel from '@/components/system/McpAgentPanel.vue'

const { t } = useI18n()
const activeTab = ref<'schema' | 'users' | 'lint' | 'general' | 'mcp'>('schema')
const toastStore = useToastStore()

function switchTab(id: 'schema' | 'users' | 'lint' | 'general' | 'mcp') {
  activeTab.value = id
}

const tabs = [
  { id: 'schema', labelKey: 'system.tabSchema', icon: FileText },
  { id: 'lint', labelKey: 'system.tabLint', icon: Bot },
  { id: 'users', labelKey: 'system.tabUsers', icon: Users },
  { id: 'general', labelKey: 'system.tabGeneral', icon: Settings },
  { id: 'mcp', labelKey: 'system.tabMcp', icon: Plug },
] as const

const wikiSchema = ref('')
const schemaExists = ref(false)
const isLoading = ref(true)
const schemaKey = ref('wiki_schema')
const pendingPatchCount = ref(0)

const schemaVersions = ref<SchemaVersionInfo[]>([])
const selectedVersionId = ref<number | null>(null)
const rollbackBusy = ref(false)

const latestVersionId = computed(() => (schemaVersions.value[0]?.id ?? null))
const selectedVersion = computed(() =>
  schemaVersions.value.find(v => v.id === selectedVersionId.value) || null,
)
const isViewingLatest = computed(() =>
  selectedVersionId.value === null || selectedVersionId.value === latestVersionId.value,
)
const displayedSchemaContent = computed(() =>
  selectedVersion.value ? selectedVersion.value.configValue : wikiSchema.value,
)

const versionSourceLabelKeyMap: Record<string, string> = {
  BOOTSTRAP: 'system.sourceBootstrap',
  PATCH: 'system.sourcePatch',
  ROLLBACK: 'system.sourceRollback',
  MANUAL: 'system.sourceManual',
}

function versionSourceLabel(s: SchemaVersionInfo['sourceType']) {
  return t(versionSourceLabelKeyMap[s] || '') || s
}

async function loadSchemaVersions() {
  try {
    const list = await listSchemaVersions('wiki_schema')
    schemaVersions.value = list
    if (list.length) selectedVersionId.value = list[0].id
  } catch {
    schemaVersions.value = []
  }
}

async function onRollback() {
  if (!selectedVersion.value || isViewingLatest.value) return
  const confirmed = await showConfirm({
    title: t('system.rollbackTitle'),
    message: t('system.rollbackMessage', [selectedVersion.value.versionNumber, (schemaVersions.value[0]?.versionNumber || 0) + 1]),
    confirmText: t('system.rollbackConfirm'),
    cancelText: t('system.rollbackCancel'),
  })
  if (!confirmed) return
  rollbackBusy.value = true
  try {
    await rollbackSchemaVersion(selectedVersion.value.id)
    toastStore.success(t('system.rollbackSuccess'), t('system.rollbackSuccessDetail', [selectedVersion.value.versionNumber]))
    await loadSchemaVersions()
    await loadMigrationReport()
    const schemas = await listSchemas()
    const wikiSchemaConfig = schemas.find(s => s.configKey === 'wiki_schema')
    if (wikiSchemaConfig) wikiSchema.value = wikiSchemaConfig.configValue
  } catch (e: any) {
    if (e === 'cancel') return
    toastStore.error(t('system.rollbackFailed'), e?.message)
  } finally {
    rollbackBusy.value = false
  }
}

const migrationReport = ref<SchemaMigrationReport | null>(null)
const migrationBusy = ref(false)
const migrationExpanded = ref(false)

const migrationTotal = computed(() =>
  (migrationReport.value?.untaggedCount || 0) + (migrationReport.value?.outdatedCount || 0),
)

async function loadMigrationReport() {
  migrationBusy.value = true
  try {
    migrationReport.value = await getSchemaMigrationReport('wiki_schema')
  } catch {
    migrationReport.value = null
  } finally {
    migrationBusy.value = false
  }
}

function formatMigrationTime(t: string | null) {
  if (!t) return '—'
  return t.slice(0, 16).replace('T', ' ')
}

function formatUserTime(t: string | null | undefined) {
  if (!t) return '—'
  return t.slice(0, 16).replace('T', ' ')
}

const userList = ref<UserManageInfo[]>([])
const userTotal = ref(0)
const userLoading = ref(false)
const userQuery = reactive({ q: '', role: '', status: '', page: 1, size: 20 })
const createDialogVisible = ref(false)
const createForm = reactive({ userName: '', email: '', password: '', role: 'user' })
const creating = ref(false)

const { state: confirmState, showConfirm, onConfirm, onCancel, ConfirmDialog } = useConfirmDialog()

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
    alertDialog.confirmText = confirmText || t('system.alertConfirmText')
    alertDialog.resolve = resolve
    alertDialog.visible = true
  })
}

function onAlertOk() {
  alertDialog.visible = false
  alertDialog.resolve?.()
}

onMounted(async () => {
  try {
    const schemas = await listSchemas()
    const wikiSchemaConfig = schemas.find(s => s.configKey === 'wiki_schema')
    if (wikiSchemaConfig && wikiSchemaConfig.configValue) {
      wikiSchema.value = wikiSchemaConfig.configValue
      schemaKey.value = wikiSchemaConfig.configKey
      schemaExists.value = true
    } else {
      wikiSchema.value = ''
      schemaExists.value = false
    }
  } catch (e) {
    wikiSchema.value = ''
    schemaExists.value = false
  } finally {
    isLoading.value = false
  }
  await loadUsers()
  await refreshPatchCount()
  await loadSchemaVersions()
  await loadMigrationReport()
})

async function refreshPatchCount() {
  try {
    const res = await countPendingPatches()
    pendingPatchCount.value = res.pending || 0
  } catch (e) {
    pendingPatchCount.value = 0
  }
}

const patchBadge = computed(() =>
  pendingPatchCount.value > 0 ? t('system.patchPendingCount', [pendingPatchCount.value]) : t('system.patchNoPending')
)

async function loadUsers() {
  userLoading.value = true
  try {
    const res = await apiListUsers({
      q: userQuery.q || undefined,
      role: userQuery.role || undefined,
      status: userQuery.status || undefined,
      page: userQuery.page,
      size: userQuery.size,
    })
    userList.value = res.items
    userTotal.value = res.total
  } catch (e: any) {
    toastStore.error(t('system.loadFailed'), e?.message || t('system.loadUsersFailed'))
  } finally {
    userLoading.value = false
  }
}

function resetUserQuery() {
  userQuery.q = ''
  userQuery.role = ''
  userQuery.status = ''
  userQuery.page = 1
  loadUsers()
}

function openCreateDialog() {
  createForm.userName = ''
  createForm.email = ''
  createForm.password = ''
  createForm.role = 'user'
  createDialogVisible.value = true
}

async function submitCreateUser() {
  if (!createForm.userName.trim()) {
    toastStore.warning(t('system.enterUserName'))
    return
  }
  creating.value = true
  try {
    const res = await apiCreateUser({
      userName: createForm.userName.trim(),
      email: createForm.email.trim() || undefined,
      password: createForm.password || undefined,
      role: createForm.role,
    })
    if (res.tempPassword) {
      createDialogVisible.value = false
      await showAlert(t('system.createSuccess'), t('system.createSuccessTempPwd', [res.tempPassword]), t('system.copiedConfirm'))
    } else {
      toastStore.success(t('system.userCreated'))
    }
    createDialogVisible.value = false
    await loadUsers()
  } catch (e: any) {
    toastStore.error(t('system.createFailed'), e?.message || t('system.createUserFailed'))
  } finally {
    creating.value = false
  }
}

async function toggleStatus(user: UserManageInfo) {
  const next = user.status === 'active' ? 'disabled' : 'active'
  try {
    const ok = await showConfirm({
      title: t('system.confirmAction'),
      message: next === 'disabled' ? t('system.confirmDisableUser', [user.userName]) : t('system.confirmEnableUser', [user.userName]),
    })
    if (!ok) return
    await updateUserStatus(user.id, next)
    toastStore.success(next === 'disabled' ? t('system.userDisabled') : t('system.userEnabled'))
    await loadUsers()
  } catch (e: any) {
    toastStore.error(t('system.operationFailed'), e?.message)
  }
}

async function toggleRole(user: UserManageInfo) {
  const next = user.role === 'admin' ? 'user' : 'admin'
  try {
    const ok = await showConfirm({
      title: t('system.confirmAction'),
      message: next === 'admin' ? t('system.confirmPromoteAdmin', [user.userName]) : t('system.confirmDemoteUser', [user.userName]),
    })
    if (!ok) return
    await updateUserRole(user.id, next)
    toastStore.success(t('system.roleUpdated'))
    await loadUsers()
  } catch (e: any) {
    toastStore.error(t('system.operationFailed'), e?.message)
  }
}

async function resetUserPassword(user: UserManageInfo) {
  try {
    const ok = await showConfirm({
      title: t('system.confirmAction'),
      message: t('system.confirmResetPassword', [user.userName]),
    })
    if (!ok) return
    const res = await apiResetPassword(user.id)
    await showAlert(t('system.resetSuccess'), t('system.resetSuccessTempPwd', [res.tempPassword]), t('system.copiedConfirm'))
  } catch (e: any) {
    toastStore.error(t('system.operationFailed'), e?.message)
  }
}

function onPageChange(newPage: number) {
  userQuery.page = newPage
  loadUsers()
}
</script>

<template>
  <div class="system-view">
    <h1 class="system-view__title">{{ t('system.title') }}</h1>

    <div class="system-view__tabs">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        class="system-view__tab"
        :class="{ 'system-view__tab--active': activeTab === tab.id }"
        @click="switchTab(tab.id)"
      >
        <component :is="tab.icon" :size="16" />
        {{ t(tab.labelKey) }}
      </button>
    </div>

    <div v-if="activeTab === 'schema'" class="system-view__panel">
      <h2 class="system-view__panel-title">{{ t('system.tabSchema') }}</h2>
      <p class="system-view__panel-hint">
        {{ t('system.schemaPanelHint') }}
        <GitPullRequest :size="14" class="system-view__inline-icon" /> {{ t('common.schemaPatch') }}
      </p>

      <div v-if="isLoading" class="system-view__loading">{{ t('common.loading') }}</div>

      <div v-else-if="!schemaExists" class="system-view__schema-empty">
        <Sparkles :size="32" class="system-view__schema-empty-icon" />
        <h3>{{ t('system.schemaEmptyTitle') }}</h3>
        <p>
          {{ t('system.schemaEmptyDesc1') }}
        </p>
        <p class="system-view__schema-empty-hint">
          {{ t('system.schemaEmptyHint') }}
        </p>
      </div>

      <div v-else class="system-view__schema-readonly">
        <div v-if="schemaVersions.length" class="system-view__version-bar">
          <div class="system-view__version-select">
            <History :size="14" class="system-view__version-icon" />
            <span class="system-view__version-label">{{ t('system.versionLabel') }}</span>
            <select v-model="selectedVersionId" class="system-view__config-select system-view__version-picker">
              <option
                v-for="v in schemaVersions"
                :key="v.id"
                :value="v.id"
              >
                v{{ v.versionNumber }} · {{ versionSourceLabel(v.sourceType) }}{{ v.id === latestVersionId ? t('system.currentVersion') : '' }} · {{ v.createdAt?.slice(0, 16) }}
              </option>
            </select>
          </div>
          <div class="system-view__version-actions">
            <span v-if="!isViewingLatest" class="system-view__version-hint">
              {{ t('system.previewHistoryHint') }}
            </span>
            <button
              v-if="!isViewingLatest"
              class="system-view__btn-secondary"
              :disabled="rollbackBusy"
              @click="onRollback"
            >
              <RotateCcw :size="14" :class="{ 'system-view__spin': rollbackBusy }" />
              {{ t('system.rollbackToVersion', [selectedVersion?.versionNumber]) }}
            </button>
          </div>
        </div>
        <WikiPageRenderer :content="displayedSchemaContent" />
      </div>

      <div v-if="schemaExists" class="system-view__schema-actions">
        <div class="system-view__patch-summary">
          <GitPullRequest :size="16" />
          <span>{{ t('system.patchSummary') }}{{ patchBadge }}</span>
        </div>
        <router-link
          v-if="pendingPatchCount > 0"
          to="/token"
          class="system-view__schema-hint"
        >
          {{ t('system.viewGovHealth') }}
        </router-link>
      </div>

      <section v-if="schemaExists && migrationReport" class="system-view__migration">
        <header class="system-view__migration-header">
          <div class="system-view__migration-title">
            <AlertTriangle
              :size="16"
              :class="migrationTotal > 0 ? 'system-view__migration-icon--warn' : 'system-view__migration-icon--ok'"
            />
            <span>{{ t('system.migrationStatus') }}</span>
          </div>
          <button
            class="system-view__migration-refresh"
            :disabled="migrationBusy"
            @click="loadMigrationReport"
          >
            <Loader2 v-if="migrationBusy" :size="12" class="system-view__spin" />
            {{ t('system.refreshBtn') }}
          </button>
        </header>

        <div class="system-view__migration-stats">
          <div class="system-view__migration-stat">
            <span class="system-view__migration-stat-label">{{ t('system.currentVersionLabel') }}</span>
            <span class="system-view__migration-stat-value">
              {{ migrationReport.currentVersionNumber
                  ? `v${migrationReport.currentVersionNumber}`
                  : t('system.notInitialized') }}
            </span>
          </div>
          <div class="system-view__migration-stat">
            <span class="system-view__migration-stat-label">{{ t('system.untaggedLabel') }}</span>
            <span
              class="system-view__migration-stat-value"
              :class="{ 'system-view__migration-stat-value--warn': migrationReport.untaggedCount > 0 }"
            >{{ migrationReport.untaggedCount }}</span>
          </div>
          <div class="system-view__migration-stat">
            <span class="system-view__migration-stat-label">{{ t('system.outdatedLabel') }}</span>
            <span
              class="system-view__migration-stat-value"
              :class="{ 'system-view__migration-stat-value--warn': migrationReport.outdatedCount > 0 }"
            >{{ migrationReport.outdatedCount }}</span>
          </div>
        </div>

        <p v-if="migrationTotal === 0" class="system-view__migration-hint">
          {{ t('system.migrationAllCurrent') }}
        </p>
        <template v-else>
          <p class="system-view__migration-hint">
            {{ t('system.migrationPending') }}
          </p>

          <button
            class="system-view__migration-toggle"
            @click="migrationExpanded = !migrationExpanded"
          >
            {{ migrationExpanded ? t('system.collapseList') : t('system.expandListPrefix', [migrationReport.legacyPages.length]) }}
          </button>

          <ul v-if="migrationExpanded" class="system-view__migration-list">
            <li
              v-for="p in migrationReport.legacyPages"
              :key="p.pageId"
              class="system-view__migration-item"
            >
              <span class="system-view__migration-item-title">{{ p.title }}</span>
              <span class="system-view__migration-item-meta">
                <span v-if="p.category" class="system-view__migration-tag">{{ p.category }}</span>
                <span class="system-view__migration-tag system-view__migration-tag--muted">
                  {{ p.versionNumber ? `v${p.versionNumber}` : t('system.untaggedShort') }}
                </span>
                <span class="system-view__migration-item-time">
                  {{ formatMigrationTime(p.updatedAt) }}
                </span>
              </span>
            </li>
          </ul>
        </template>
      </section>
    </div>

    <div v-if="activeTab === 'lint'" class="system-view__panel">
      <h2 class="system-view__panel-title">{{ t('system.lintPanelTitle') }}</h2>
      <p class="system-view__panel-hint">
        {{ t('system.lintPanelHint') }}
      </p>
      <div class="system-view__config-row">
        <span class="system-view__config-label">{{ t('system.lintFrequency') }}</span>
        <select class="system-view__config-select">
          <option>{{ t('system.frequencyDaily') }}</option>
          <option>{{ t('system.frequencyWeekly') }}</option>
          <option>{{ t('system.frequencyMonthly') }}</option>
        </select>
      </div>
      <div class="system-view__config-row">
        <span class="system-view__config-label">{{ t('system.lintCheckItems') }}</span>
        <div class="system-view__config-checks">
          <label class="system-view__config-check"><input type="checkbox" checked /> {{ t('system.checkOrphaned') }}</label>
          <label class="system-view__config-check"><input type="checkbox" checked /> {{ t('system.checkStale') }}</label>
          <label class="system-view__config-check"><input type="checkbox" checked /> {{ t('system.checkConflicts') }}</label>
          <label class="system-view__config-check"><input type="checkbox" checked /> {{ t('system.checkMissing') }}</label>
        </div>
      </div>
    </div>

    <div v-if="activeTab === 'users'" class="system-view__panel system-view__panel--wide">
      <div class="system-view__panel-header">
        <div class="system-view__panel-header-text">
          <h2 class="system-view__panel-title">{{ t('system.tabUsers') }}</h2>
          <p class="system-view__panel-hint">
            {{ t('system.usersPanelHint') }}
          </p>
        </div>
        <button class="system-view__btn-primary" @click="openCreateDialog">
          <Plus :size="16" />
          {{ t('system.createUserBtn') }}
        </button>
      </div>

      <div class="system-view__user-filters">
        <div class="system-view__user-search">
          <Search :size="16" class="system-view__user-search-icon" />
          <input
            v-model="userQuery.q"
            :placeholder="t('system.searchUserPlaceholder')"
            class="system-view__user-search-input"
            @keyup.enter="() => { userQuery.page = 1; loadUsers() }"
          />
        </div>
        <div class="system-view__user-filter-group">
          <select v-model="userQuery.role" class="system-view__config-select system-view__user-filter-select">
            <option value="">{{ t('system.allRoles') }}</option>
            <option value="admin">admin</option>
            <option value="user">user</option>
          </select>
          <select v-model="userQuery.status" class="system-view__config-select system-view__user-filter-select">
            <option value="">{{ t('system.allStatuses') }}</option>
            <option value="active">{{ t('system.statusActive') }}</option>
            <option value="disabled">{{ t('system.statusDisabled') }}</option>
          </select>
          <button class="system-view__btn-secondary" @click="() => { userQuery.page = 1; loadUsers() }">
            <Search :size="14" />
            {{ t('system.queryBtn') }}
          </button>
          <button class="system-view__btn-ghost" @click="resetUserQuery">{{ t('system.resetBtn') }}</button>
        </div>
      </div>

      <div v-if="userLoading" class="system-view__user-loading">
        <Loader2 :size="20" class="system-view__spin" />
        <span>{{ t('common.loading') }}</span>
      </div>

      <div v-else-if="userList.length === 0" class="system-view__user-empty">
        <Users :size="32" class="system-view__user-empty-icon" />
        <h3>{{ t('system.noUserData') }}</h3>
        <p>{{ t('system.noUserDataHint') }}</p>
      </div>

      <div v-else class="system-view__user-list">
        <div
          v-for="u in userList"
          :key="u.id"
          class="system-view__user-item"
        >
          <div class="system-view__user-row">
            <div class="system-view__user-identity">
              <div class="system-view__user-avatar" :class="u.role === 'admin' ? 'system-view__user-avatar--accent' : ''">
                {{ u.userName.charAt(0).toUpperCase() }}
              </div>
              <div class="system-view__user-info">
                <span class="system-view__user-name">{{ u.userName }}</span>
                <span class="system-view__user-email">{{ u.email || '—' }}</span>
              </div>
            </div>
            <div class="system-view__user-badges">
              <span class="system-view__badge" :class="u.role === 'admin' ? 'system-view__badge--accent' : 'system-view__badge--neutral'">
                <ShieldCheck v-if="u.role === 'admin'" :size="12" />
                <User v-else :size="12" />
                {{ u.role }}
              </span>
              <span class="system-view__badge" :class="u.status === 'active' ? 'system-view__badge--success' : 'system-view__badge--danger'">
                <CheckCircle2 v-if="u.status === 'active'" :size="12" />
                <XCircle v-else :size="12" />
                {{ u.status === 'active' ? t('system.statusActive') : t('system.statusDisabled') }}
              </span>
            </div>
          </div>
          <div class="system-view__user-row system-view__user-row--secondary">
            <span class="system-view__user-time">{{ formatUserTime(u.createdAt) }}</span>
            <div class="system-view__user-actions">
              <button
                class="system-view__user-action"
                :class="u.status === 'active' ? 'system-view__user-action--warn' : 'system-view__user-action--success'"
                @click="toggleStatus(u)"
              >
                <Ban v-if="u.status === 'active'" :size="14" />
                <CheckCircle2 v-else :size="14" />
                {{ u.status === 'active' ? t('system.statusDisabled') : t('system.statusActive') }}
              </button>
              <button
                class="system-view__user-action system-view__user-action--accent"
                @click="toggleRole(u)"
              >
                <ArrowUpDown :size="14" />
                {{ u.role === 'admin' ? t('system.demoteToUser') : t('system.promoteToAdmin') }}
              </button>
              <button
                class="system-view__user-action"
                @click="resetUserPassword(u)"
              >
                <KeyRound :size="14" />
                {{ t('system.resetPasswordBtn') }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="userTotal > userQuery.size" class="system-view__user-pagination">
        <span class="system-view__user-pagination-info">{{ t('system.totalUsers', [userTotal]) }}</span>
        <div class="system-view__user-pagination-controls">
          <button
            class="system-view__user-pagination-btn"
            :disabled="userQuery.page <= 1"
            @click="onPageChange(userQuery.page - 1)"
          >
            <ChevronLeft :size="16" />
          </button>
          <span class="system-view__user-pagination-current">{{ userQuery.page }}</span>
          <button
            class="system-view__user-pagination-btn"
            :disabled="userQuery.page * userQuery.size >= userTotal"
            @click="onPageChange(userQuery.page + 1)"
          >
            <ChevronRight :size="16" />
          </button>
        </div>
      </div>
    </div>

    <div v-if="activeTab === 'general'" class="system-view__panel">
      <h2 class="system-view__panel-title">{{ t('system.generalPanelTitle') }}</h2>
      <p class="system-view__panel-hint">{{ t('system.generalPanelHint') }}</p>
    </div>

    <div v-if="activeTab === 'mcp'" class="system-view__panel system-view__panel--wide">
      <McpAgentPanel manage-all />
    </div>

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

    <div v-if="alertDialog.visible" class="system-view__dialog-overlay" @click.self="onAlertOk">
      <div class="system-view__dialog">
        <div class="system-view__dialog-header">
          <h3 class="system-view__dialog-title">{{ alertDialog.title }}</h3>
          <button class="system-view__dialog-close" @click="onAlertOk">
            <X :size="18" />
          </button>
        </div>
        <div class="system-view__dialog-body">
          <p class="system-view__dialog-message" style="white-space: pre-line">{{ alertDialog.message }}</p>
        </div>
        <div class="system-view__dialog-footer">
          <button class="system-view__btn-primary" @click="onAlertOk">{{ alertDialog.confirmText }}</button>
        </div>
      </div>
    </div>

    <div v-if="createDialogVisible" class="system-view__dialog-overlay" @click.self="createDialogVisible = false">
      <div class="system-view__dialog">
        <div class="system-view__dialog-header">
          <h3 class="system-view__dialog-title">{{ t('system.createDialogTitle') }}</h3>
          <button class="system-view__dialog-close" @click="createDialogVisible = false">
            <X :size="18" />
          </button>
        </div>
        <div class="system-view__dialog-body">
          <div class="system-view__form-field">
            <label class="system-view__form-label">{{ t('system.formUserName') }} <span class="system-view__form-required">*</span></label>
            <input v-model="createForm.userName" class="system-view__form-input" :placeholder="t('system.formUserNamePlaceholder')" />
          </div>
          <div class="system-view__form-field">
            <label class="system-view__form-label">{{ t('system.formEmail') }}</label>
            <input v-model="createForm.email" class="system-view__form-input" :placeholder="t('system.formEmailPlaceholder')" />
          </div>
          <div class="system-view__form-field">
            <label class="system-view__form-label">{{ t('system.formPassword') }}</label>
            <input v-model="createForm.password" type="password" class="system-view__form-input" :placeholder="t('system.formPasswordPlaceholder')" />
          </div>
          <div class="system-view__form-field">
            <label class="system-view__form-label">{{ t('system.formRole') }}</label>
            <select v-model="createForm.role" class="system-view__config-select system-view__form-select">
              <option value="user">{{ t('system.roleUserOption') }}</option>
              <option value="admin">{{ t('system.roleAdminOption') }}</option>
            </select>
          </div>
        </div>
        <div class="system-view__dialog-footer">
          <button class="system-view__btn-secondary" @click="createDialogVisible = false">{{ t('system.cancelBtn') }}</button>
          <button class="system-view__btn-primary system-view__btn-primary--lg" :disabled="creating" @click="submitCreateUser">
            <Loader2 v-if="creating" :size="14" class="system-view__spin" />
            {{ t('system.createUserBtn2') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.system-view {
  max-width: 720px;
}

.system-view__panel--wide {
  max-width: none;
}

.system-view__title {
  font-size: var(--font-h1);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
  margin-bottom: var(--space-6);
}

.system-view__tabs {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-6);
  padding: var(--space-2);
  background: var(--bg-tertiary);
  border-radius: var(--radius-lg);
}

.system-view__tab {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  background: none;
  color: var(--text-secondary);
  border: none;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.system-view__tab--active {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.system-view__tab:hover:not(.system-view__tab--active) {
  background: var(--surface-card);
}

.system-view__panel {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  box-shadow: var(--shadow-sm);
}

.system-view__panel-title {
  font-size: var(--font-h2);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-bottom: var(--space-2);
}

.system-view__panel-hint {
  font-size: var(--font-body);
  color: var(--text-secondary);
  margin-bottom: var(--space-5);
  line-height: 1.6;
}

.system-view__loading {
  text-align: center;
  padding: var(--space-4);
  color: var(--text-secondary);
}

.system-view__schema-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-default);
}

.system-view__inline-icon {
  display: inline;
  vertical-align: -2px;
  color: var(--accent-primary);
}

.system-view__schema-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: var(--space-6) var(--space-4);
  background: var(--surface-card);
  border: 1px dashed var(--border-default);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
}

.system-view__schema-empty-icon {
  color: var(--accent-primary);
  margin-bottom: var(--space-3);
}

.system-view__schema-empty h3 {
  margin: 0 0 var(--space-2) 0;
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.system-view__schema-empty p {
  margin: 0 0 var(--space-2) 0;
  max-width: 520px;
  line-height: 1.6;
}

.system-view__schema-empty-hint {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}

.system-view__schema-readonly {
  padding: var(--space-4);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  max-height: 640px;
  overflow: auto;
}

.system-view__patch-summary {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
}

.system-view__schema-hint {
  font-size: var(--font-caption);
  color: var(--accent-primary);
  text-decoration: none;
  font-weight: var(--weight-medium);
}

.system-view__schema-hint:hover {
  text-decoration: underline;
}

.system-view__btn-primary {
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
}

.system-view__btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.system-view__btn-secondary {
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
}

.system-view__spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.system-view__config-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.system-view__config-label {
  font-size: var(--font-body);
  color: var(--text-primary);
  font-weight: var(--weight-medium);
  min-width: 120px;
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.system-view__config-select {
  padding: var(--space-2) var(--space-3);
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: var(--font-body);
}

.system-view__config-checks {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.system-view__config-check {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body);
  color: var(--text-primary);
  cursor: pointer;
}

.system-view__panel-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.system-view__panel-header-text {
  flex: 1;
}

.system-view__user-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  align-items: center;
  margin-bottom: var(--space-4);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--border-default);
}

.system-view__user-search {
  display: flex;
  align-items: center;
  position: relative;
  flex: 1;
  min-width: 200px;
}

.system-view__user-search-icon {
  position: absolute;
  left: var(--space-3);
  color: var(--text-tertiary);
}

.system-view__user-search-input {
  width: 100%;
  height: var(--input-height);
  padding: var(--space-2) var(--space-3) var(--space-2) var(--space-8);
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: var(--font-body);
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.system-view__user-search-input:focus {
  outline: none;
  border-color: var(--input-focus-border);
  box-shadow: 0 0 0 3px var(--input-focus-ring);
}

.system-view__user-search-input::placeholder {
  color: var(--text-tertiary);
}

.system-view__user-filter-group {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}

.system-view__user-filter-select {
  min-width: 120px;
}

.system-view__btn-ghost {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  background: transparent;
  color: var(--btn-ghost-text);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.system-view__btn-ghost:hover {
  background: var(--accent-light);
}

.system-view__user-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-8);
  color: var(--text-secondary);
  font-size: var(--font-body);
}

.system-view__user-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-10);
  text-align: center;
  background: var(--bg-tertiary);
  border-radius: var(--radius-lg);
  color: var(--text-secondary);
}

.system-view__user-empty-icon {
  color: var(--text-tertiary);
  margin-bottom: var(--space-3);
}

.system-view__user-empty h3 {
  margin: 0 0 var(--space-1);
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.system-view__user-empty p {
  margin: 0;
  font-size: var(--font-body-sm);
}

.system-view__user-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.system-view__user-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
  transition: background var(--transition-fast);
}

.system-view__user-item:last-child {
  border-bottom: none;
}

.system-view__user-item:hover {
  background: var(--bg-tertiary);
}

.system-view__user-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.system-view__user-row--secondary {
  padding-left: calc(36px + var(--space-3));
}

.system-view__user-identity {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex: 1;
  min-width: 0;
}

.system-view__user-avatar {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-full);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  flex-shrink: 0;
}

.system-view__user-avatar--accent {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.system-view__user-info {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0;
}

.system-view__user-name {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}

.system-view__user-email {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}

.system-view__user-badges {
  display: flex;
  gap: var(--space-1);
  flex-shrink: 0;
}

.system-view__badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.system-view__badge--accent {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.system-view__badge--neutral {
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}

.system-view__badge--success {
  background: var(--success-light);
  color: var(--success);
}

.system-view__badge--danger {
  background: var(--error-light);
  color: var(--error);
}

.system-view__badge--warning {
  background: var(--warning-light);
  color: var(--warning);
}

.system-view__user-time {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  flex-shrink: 0;
}

.system-view__user-actions {
  display: flex;
  gap: var(--space-1);
  margin-left: auto;
}

.system-view__user-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: var(--space-1) var(--space-2);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.system-view__user-action:hover {
  background: var(--bg-tertiary);
  border-color: var(--border-default);
  color: var(--text-primary);
}

.system-view__user-action--warn:hover {
  background: var(--warning-light);
  border-color: var(--warning);
  color: var(--warning);
}

.system-view__user-action--success:hover {
  background: var(--success-light);
  border-color: var(--success);
  color: var(--success);
}

.system-view__user-action--accent:hover {
  background: var(--accent-light);
  border-color: var(--accent-primary);
  color: var(--accent-primary);
}

.system-view__user-pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: var(--space-4);
  border-top: 1px solid var(--border-default);
  margin-top: var(--space-4);
}

.system-view__user-pagination-info {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.system-view__user-pagination-controls {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.system-view__user-pagination-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: var(--btn-secondary-bg);
  border: 1px solid var(--btn-secondary-border);
  border-radius: var(--radius-md);
  color: var(--btn-secondary-text);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.system-view__user-pagination-btn:hover:not(:disabled) {
  background: var(--accent-light);
  border-color: var(--accent-primary);
  color: var(--accent-primary);
}

.system-view__user-pagination-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.system-view__user-pagination-current {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  min-width: 32px;
  text-align: center;
}

.system-view__dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  animation: system-view-fade-in 200ms ease-out;
}

.system-view__dialog {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-xl);
  width: 440px;
  max-width: 90vw;
  display: flex;
  flex-direction: column;
  animation: system-view-slide-up 250ms ease-out;
}

.system-view__dialog-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-5);
  border-bottom: 1px solid var(--border-default);
}

.system-view__dialog-title {
  font-size: var(--font-h3);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin: 0;
}

.system-view__dialog-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.system-view__dialog-close:hover {
  background: var(--bg-tertiary);
  border-color: var(--border-default);
  color: var(--text-primary);
}

.system-view__dialog-body {
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.system-view__dialog-message {
  font-size: var(--font-body);
  color: var(--text-secondary);
  line-height: 1.6;
  margin: 0;
}

.system-view__dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-top: 1px solid var(--border-default);
}

.system-view__btn-primary--lg {
  height: var(--btn-height-lg);
  padding: var(--space-3) var(--space-5);
}

.system-view__form-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.system-view__form-label {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}

.system-view__form-required {
  color: var(--error);
}

.system-view__form-input {
  height: var(--btn-height-lg);
  padding: var(--space-3) var(--space-4);
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: var(--font-body);
  font-family: var(--font-body);
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.system-view__form-input:focus {
  outline: none;
  border-color: var(--input-focus-border);
  box-shadow: 0 0 0 3px var(--input-focus-ring);
}

.system-view__form-input::placeholder {
  color: var(--text-tertiary);
}

.system-view__form-select {
  width: 100%;
  height: var(--btn-height-lg);
  padding: var(--space-3) var(--space-4);
}

@keyframes system-view-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes system-view-slide-up {
  from { opacity: 0; transform: translateY(16px); }
  to { opacity: 1; transform: translateY(0); }
}

.system-view__version-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  padding-bottom: var(--space-3);
  margin-bottom: var(--space-3);
  border-bottom: 1px dashed var(--border-default);
  flex-wrap: wrap;
}

.system-view__version-select {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.system-view__version-icon {
  color: var(--text-secondary);
}

.system-view__version-label {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.system-view__version-picker {
  min-width: 280px;
  font-size: var(--font-body-sm);
}

.system-view__version-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.system-view__version-hint {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}

.system-view__migration {
  margin-top: var(--space-4);
  padding: var(--space-4);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.system-view__migration-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.system-view__migration-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body);
  font-weight: 600;
  color: var(--text-primary);
}

.system-view__migration-icon--warn { color: var(--color-warning, #e3a008); }
.system-view__migration-icon--ok   { color: var(--color-success, #22c55e); }

.system-view__migration-refresh {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  background: transparent;
  border: 1px solid var(--input-border);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  cursor: pointer;
}
.system-view__migration-refresh:disabled { opacity: 0.6; cursor: not-allowed; }

.system-view__migration-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
}

.system-view__migration-stat {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  background: var(--input-bg);
  border-radius: var(--radius-md);
}

.system-view__migration-stat-label {
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
}

.system-view__migration-stat-value {
  font-size: var(--font-heading-sm);
  font-weight: 600;
  color: var(--text-primary);
}
.system-view__migration-stat-value--warn { color: var(--color-warning, #e3a008); }

.system-view__migration-hint {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.6;
}

.system-view__migration-toggle {
  align-self: flex-start;
  background: transparent;
  border: none;
  color: var(--accent-primary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  padding: 0;
}

.system-view__migration-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-height: 320px;
  overflow-y: auto;
}

.system-view__migration-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-2) var(--space-3);
  background: var(--input-bg);
  border-radius: var(--radius-md);
  gap: var(--space-3);
}

.system-view__migration-item-title {
  font-size: var(--font-body-sm);
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.system-view__migration-item-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

.system-view__migration-tag {
  font-size: var(--font-caption);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  background: var(--accent-primary-soft, rgba(99, 102, 241, 0.12));
  color: var(--accent-primary);
}
.system-view__migration-tag--muted {
  background: transparent;
  color: var(--text-tertiary);
  border: 1px dashed var(--border-default);
}

.system-view__migration-item-time {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

</style>
