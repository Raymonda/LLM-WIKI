<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { useRouter } from 'vue-router'
import {
  listScopes, createScope, updateScope, deleteScope,
  addMember, removeMember, updateMemberRole, listMembers,
  listJoinRequests, reviewJoinRequest,
  type ScopeInfo, type ScopeMemberInfo, type CreateScopeRequest, type JoinRequestInfo,
  type ScopeBriefInfo
} from '@/api/scope'
import { searchUsers, type UserSearchInfo } from '@/api/user'
import {
  Users, User, UserPlus, Trash2, Shield, Edit3, Plus, ChevronRight,
  Crown, ShieldCheck, Pencil, Eye, Check, X
} from 'lucide-vue-next'

const { t } = useI18n()
const authStore = useAuthStore()
const toastStore = useToastStore()
const router = useRouter()

const scopes = ref<ScopeInfo[]>([])
const selectedScope = ref<ScopeInfo | null>(null)
const members = ref<ScopeMemberInfo[]>([])
const showCreateDialog = ref(false)
const showAddMemberDialog = ref(false)
const createForm = ref<CreateScopeRequest>({ name: '', description: '' })
const addMemberForm = ref({ userId: 0, role: 'viewer' })
const addMemberConsent = ref(true)
const addMemberKeyword = ref('')
const addMemberSelected = ref<UserSearchInfo | null>(null)
const isLoading = ref(false)
const editingScope = ref<ScopeInfo | null>(null)
const editForm = ref<Partial<ScopeInfo>>({})
const joinRequests = ref<JoinRequestInfo[]>([])

const roleIcons: Record<string, any> = {
  owner: Crown,
  admin: ShieldCheck,
  editor: Pencil,
  viewer: Eye
}

const canManage = computed(() => {
  if (!selectedScope.value || selectedScope.value.type !== 'team') return false
  const current = authStore.scopes.find(s => s.scopeId === selectedScope.value!.id)
  return current?.role === 'owner' || current?.role === 'admin'
})

onMounted(async () => {
  await loadScopes()
})

async function loadScopes() {
  isLoading.value = true
  try {
    scopes.value = await listScopes()
    if (scopes.value.length > 0 && !selectedScope.value) {
      const teamScope = scopes.value.find(s => s.type === 'team')
      if (teamScope) {
        await selectScope(teamScope)
      }
    }
  } catch (e: any) {
    toastStore.error(t('scope.scopeLoadFailed'), e?.message || t('scope.scopeLoadFailedHint'))
  } finally {
    isLoading.value = false
  }
}

async function selectScope(scope: ScopeInfo) {
  selectedScope.value = scope
  if (scope.type === 'team') {
    members.value = await listMembers(scope.id)
    try { joinRequests.value = await listJoinRequests(scope.id) } catch { joinRequests.value = [] }
  } else {
    members.value = []
    joinRequests.value = []
  }
}

async function handleCreateScope() {
  if (!createForm.value.name) return
  isLoading.value = true
  try {
    const newScope = await createScope(createForm.value)
    scopes.value.push(newScope)
    const brief: ScopeBriefInfo = {
      scopeId: newScope.id,
      scopeName: newScope.name,
      scopeType: 'team',
      role: 'owner',
      language: 'zh-CN',
    }
    authStore.setScopes([...authStore.scopes.filter(s => s.scopeId !== newScope.id), brief])
    showCreateDialog.value = false
    createForm.value = { name: '', description: '' }
    await selectScope(newScope)
    toastStore.success(t('scope.teamCreated'))
  } catch (e: any) {
    toastStore.error(t('scope.createFailed'), e?.message || t('scope.createFailedHint'))
  } finally {
    isLoading.value = false
  }
}

async function handleDeleteScope(scopeId: number) {
  if (!confirm(t('scope.deleteScopeConfirm'))) return
  isLoading.value = true
  try {
    await deleteScope(scopeId)
    scopes.value = scopes.value.filter(s => s.id !== scopeId)
    if (selectedScope.value?.id === scopeId) {
      selectedScope.value = null
      members.value = []
    }
    toastStore.success(t('common.deleteSuccess'))
  } catch (e: any) {
    toastStore.error(t('scope.operationFailed'), e?.message || t('common.operationFailed'))
  } finally {
    isLoading.value = false
  }
}

async function handleAddMember() {
  if (!selectedScope.value || !addMemberForm.value.userId) return
  isLoading.value = true
  try {
    const newMember = await addMember(selectedScope.value.id, addMemberForm.value)
    members.value.push(newMember)
    showAddMemberDialog.value = false
    addMemberForm.value = { userId: 0, role: 'viewer' }
    addMemberKeyword.value = ''
    addMemberSelected.value = null
  } catch (e: any) {
    toastStore.error(t('scope.operationFailed'), e?.message || t('common.operationFailed'))
  } finally {
    isLoading.value = false
  }
}

async function fetchUserSuggestions(queryString: string, cb: (suggestions: any[]) => void) {
  const q = (queryString || '').trim()
  if (q.length < 1) {
    cb([])
    return
  }
  try {
    const users = await searchUsers(q, 10)
    cb(users.map(u => ({ ...u, value: `${u.userName}${u.email ? ' (' + u.email + ')' : ''}` })))
  } catch {
    cb([])
  }
}

function handleSelectUser(item: any) {
  addMemberSelected.value = { id: item.id, userName: item.userName, email: item.email }
  addMemberForm.value.userId = item.id
  addMemberKeyword.value = item.userName
}

function handleKeywordInput(value: string) {
  addMemberKeyword.value = value
  if (!value) {
    addMemberForm.value.userId = 0
    addMemberSelected.value = null
  } else if (addMemberSelected.value && value !== addMemberSelected.value.userName) {
    addMemberForm.value.userId = 0
    addMemberSelected.value = null
  }
}

async function handleRemoveMember(userId: number) {
  if (!selectedScope.value) return
  if (!confirm(t('scope.removeMemberConfirm'))) return
  isLoading.value = true
  try {
    await removeMember(selectedScope.value.id, userId)
    members.value = members.value.filter(m => m.userId !== userId)
  } catch (e: any) {
    toastStore.error(t('scope.operationFailed'), e?.message || t('common.operationFailed'))
  } finally {
    isLoading.value = false
  }
}

async function handleReviewRequest(requestId: number, approve: boolean) {
  if (!selectedScope.value) return
  isLoading.value = true
  try {
    await reviewJoinRequest(selectedScope.value.id, requestId, approve, '')
    joinRequests.value = joinRequests.value.filter(r => r.id !== requestId)
    if (approve) {
      members.value = await listMembers(selectedScope.value.id)
      toastStore.success(t('scope.requestApproved'))
    } else {
      toastStore.success(t('scope.requestRejected'))
    }
  } catch (e: any) {
    toastStore.error(t('scope.operationFailed'), e?.message || '')
  } finally {
    isLoading.value = false
  }
}

async function handleChangeRole(userId: number, newRole: string) {
  if (!selectedScope.value) return
  isLoading.value = true
  try {
    await updateMemberRole(selectedScope.value.id, userId, newRole)
    members.value = members.value.map(m =>
      m.userId === userId ? { ...m, role: newRole } : m
    )
  } catch (e: any) {
    toastStore.error(t('scope.operationFailed'), e?.message || t('common.operationFailed'))
  } finally {
    isLoading.value = false
  }
}

function startEditScope(scope: ScopeInfo) {
  editingScope.value = scope
  editForm.value = {
    name: scope.name,
    description: scope.description,
    monthlyBudget: scope.monthlyBudget,
    defaultApproval: scope.defaultApproval,
  }
}

async function handleSaveEdit() {
  if (!editingScope.value) return
  isLoading.value = true
  try {
    const updated = await updateScope(editingScope.value.id, editForm.value)
    scopes.value = scopes.value.map(s => s.id === updated.id ? updated : s)
    if (selectedScope.value?.id === updated.id) {
      selectedScope.value = updated
    }
    editingScope.value = null
    toastStore.success(t('common.operationSuccess'))
  } catch (e: any) {
    toastStore.error(t('scope.operationFailed'), e?.message || t('common.operationFailed'))
  } finally {
    isLoading.value = false
  }
}

function goToScope(scopeId: number) {
  authStore.switchScope(scopeId)
  router.push('/')
}
</script>

<template>
  <div class="scope-manage">
    <div class="scope-manage__header">
      <h1>{{ t('scope.managePageTitle') }}</h1>
      <button class="scope-manage__create-btn" @click="showCreateDialog = true">
        <Plus :size="16" />
        {{ t('scope.createTeam') }}
      </button>
    </div>

    <div v-if="isLoading" class="scope-manage__loading">{{ t('scope.loading') }}</div>

    <div v-else class="scope-manage__content">
      <div class="scope-manage__list">
        <h2>{{ t('scope.myScopes') }}</h2>
        <div
          v-for="scope in scopes"
          :key="scope.id"
          class="scope-manage__card"
          :class="{ 'scope-manage__card--active': selectedScope?.id === scope.id }"
          @click="selectScope(scope)"
        >
          <div class="scope-manage__card-header">
            <Users v-if="scope.type === 'team'" :size="18" />
            <User v-else :size="18" />
            <span class="scope-manage__card-name">{{ scope.name }}</span>
            <span class="scope-manage__card-type">{{ scope.type === 'team' ? t('scope.typeTeam') : t('scope.typePersonal') }}</span>
          </div>
          <div class="scope-manage__card-desc">{{ scope.description }}</div>
          <div class="scope-manage__card-footer">
            <span>{{ t('scope.budget') }}: {{ scope.monthlyBudget / 1000 }}K</span>
            <span>{{ t('scope.approval') }}: {{ scope.defaultApproval === 'auto' ? t('scope.approvalAuto') : t('scope.approvalConfirm') }}</span>
            <button class="scope-manage__go-btn" @click.stop="goToScope(scope.id)">
              {{ t('scope.enter') }} <ChevronRight :size="14" />
            </button>
          </div>
        </div>
      </div>

      <div v-if="selectedScope && selectedScope.type === 'team'" class="scope-manage__detail">
        <div class="scope-manage__detail-header">
          <h2>{{ selectedScope.name }}</h2>
          <div v-if="canManage" class="scope-manage__detail-actions">
            <button @click="startEditScope(selectedScope!)">
              <Edit3 :size="14" /> {{ t('scope.editConfig') }}
            </button>
            <button class="scope-manage__delete-btn" @click="handleDeleteScope(selectedScope!.id)">
              <Trash2 :size="14" /> {{ t('scope.deleteTeamBtn') }}
            </button>
          </div>
        </div>

        <div class="scope-manage__members-header">
          <h3>{{ t('scope.memberManage') }}</h3>
          <button v-if="canManage" @click="showAddMemberDialog = true">
            <UserPlus :size="14" /> {{ t('scope.addMemberBtn') }}
          </button>
        </div>

        <div class="scope-manage__members-list">
          <div v-for="member in members" :key="member.id" class="scope-manage__member-row">
            <component :is="roleIcons[member.role] || Shield" :size="16" class="scope-manage__role-icon" />
            <span class="scope-manage__member-name">{{ member.userName || `${t('scope.userPrefix')}${member.userId}` }}</span>
            <select
              class="scope-manage__role-select"
              :value="member.role"
              @change="handleChangeRole(member.userId, ($event.target as HTMLSelectElement).value)"
              :disabled="member.role === 'owner' || !canManage"
            >
              <option value="owner">Owner</option>
              <option value="admin">Admin</option>
              <option value="editor">Editor</option>
              <option value="viewer">Viewer</option>
            </select>
            <button
              v-if="member.role !== 'owner' && canManage"
              class="scope-manage__remove-btn"
              @click="handleRemoveMember(member.userId)"
            >
              <Trash2 :size="14" />
            </button>
          </div>
          <div v-if="members.length === 0" class="scope-manage__empty">{{ t('scope.noMembers') }}</div>
        </div>

        <div v-if="joinRequests.length > 0" class="scope-manage__join-requests">
          <h4>{{ t('scope.pendingRequests') }} ({{ joinRequests.length }})</h4>
          <div v-for="req in joinRequests" :key="req.id" class="scope-manage__request-row">
            <span class="scope-manage__request-user">{{ t('scope.userPrefix') }}{{ req.userId }}</span>
            <span v-if="req.message" class="scope-manage__request-msg">{{ req.message }}</span>
            <span class="scope-manage__request-time">{{ new Date(req.createdAt).toLocaleDateString() }}</span>
            <button class="scope-manage__approve-btn" @click="handleReviewRequest(req.id, true)">
              <Check :size="14" /> {{ t('scope.approveBtn') }}
            </button>
            <button class="scope-manage__reject-btn" @click="handleReviewRequest(req.id, false)">
              <X :size="14" /> {{ t('scope.rejectBtn') }}
            </button>
          </div>
        </div>
      </div>

      <div v-else-if="selectedScope && selectedScope.type === 'personal'" class="scope-manage__detail">
        <div class="scope-manage__detail-header">
          <h2>{{ selectedScope.name }}</h2>
        </div>
        <p class="scope-manage__personal-note">{{ t('scope.personalNote') }}</p>
        <div class="scope-manage__card-footer">
          <span>{{ t('scope.budget') }}: {{ selectedScope.monthlyBudget / 1000 }}K</span>
          <span>{{ t('scope.approval') }}: {{ selectedScope.defaultApproval === 'auto' ? t('scope.approvalAuto') : t('scope.approvalConfirm') }}</span>
        </div>
      </div>

      <div v-else class="scope-manage__detail scope-manage__detail--empty">
        <p>{{ t('scope.selectScopeHint') }}</p>
      </div>
    </div>

    <Transition name="dialog">
      <div v-if="showCreateDialog" class="scope-manage__dialog-overlay" @click.self="showCreateDialog = false">
        <div class="scope-manage__dialog">
          <h2>{{ t('scope.createTeamDialog') }}</h2>
          <div class="scope-manage__field">
            <label>{{ t('scope.teamName') }}</label>
            <input v-model="createForm.name" :placeholder="t('scope.teamNamePlaceholder')" />
          </div>
          <div class="scope-manage__field">
            <label>{{ t('scope.descLabel') }}</label>
            <textarea v-model="createForm.description" :placeholder="t('scope.teamDescPlaceholder')" rows="3" />
          </div>
          <div class="scope-manage__dialog-actions">
            <button @click="showCreateDialog = false">{{ t('scope.cancel') }}</button>
            <button class="scope-manage__primary-btn" @click="handleCreateScope" :disabled="!createForm.name || isLoading">
              {{ t('scope.create') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <Transition name="dialog">
      <div v-if="showAddMemberDialog" class="scope-manage__dialog-overlay" @click.self="showAddMemberDialog = false">
        <div class="scope-manage__dialog">
          <h2>{{ t('scope.addMemberDialog') }}</h2>
          <div class="scope-manage__field">
            <label>{{ t('scope.selectUser') }}</label>
            <el-autocomplete
              :model-value="addMemberKeyword"
              :fetch-suggestions="fetchUserSuggestions"
              :placeholder="t('scope.searchUserPlaceholder')"
              clearable
              :debounce="300"
              value-key="value"
              style="width: 100%"
              @select="handleSelectUser"
              @update:model-value="handleKeywordInput"
            >
              <template #default="{ item }">
                <div style="display: flex; flex-direction: column; line-height: 1.3; padding: 4px 0;">
                  <span style="font-weight: 500;">{{ item.userName }}</span>
                  <span style="font-size: 12px; color: var(--text-tertiary);">{{ item.email || '—' }}</span>
                </div>
              </template>
            </el-autocomplete>
          </div>
          <div class="scope-manage__field">
            <label>{{ t('scope.roleLabel') }}</label>
            <select v-model="addMemberForm.role">
              <option value="admin">Admin</option>
              <option value="editor">Editor</option>
              <option value="viewer">Viewer</option>
            </select>
          </div>
          <div class="scope-manage__consent-field">
            <label class="scope-manage__consent-label">
              <input type="checkbox" v-model="addMemberConsent" class="scope-manage__consent-checkbox" />
              <span class="scope-manage__consent-text">{{ t('scope.consentText') }}</span>
            </label>
          </div>
          <div class="scope-manage__dialog-actions">
            <button @click="showAddMemberDialog = false">{{ t('scope.cancel') }}</button>
            <button class="scope-manage__primary-btn" @click="handleAddMember" :disabled="!addMemberForm.userId || !addMemberConsent || isLoading">
              {{ t('scope.add') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <Transition name="dialog">
      <div v-if="editingScope" class="scope-manage__dialog-overlay" @click.self="editingScope = null">
        <div class="scope-manage__dialog">
          <h2>{{ t('scope.editConfigDialog') }}</h2>
          <div class="scope-manage__field">
            <label>{{ t('scope.nameLabel') }}</label>
            <input v-model="editForm.name" />
          </div>
          <div class="scope-manage__field">
            <label>{{ t('scope.descLabel') }}</label>
            <textarea v-model="editForm.description" rows="3" />
          </div>
          <div class="scope-manage__field">
            <label>{{ t('scope.monthlyBudgetLabel') }}</label>
            <input v-model.number="editForm.monthlyBudget" type="number" />
          </div>
          <div class="scope-manage__field">
            <label>{{ t('scope.defaultApprovalLabel') }}</label>
            <select v-model="editForm.defaultApproval">
              <option value="auto">{{ t('scope.approvalAutoFull') }}</option>
              <option value="confirm">{{ t('scope.approvalConfirmFull') }}</option>
              <option value="review">{{ t('scope.approvalReviewFull') }}</option>
            </select>
          </div>
          <div class="scope-manage__dialog-actions">
            <button @click="editingScope = null">{{ t('scope.cancel') }}</button>
            <button class="scope-manage__primary-btn" @click="handleSaveEdit" :disabled="isLoading">
              {{ t('scope.save') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.scope-manage {
  padding: var(--space-6);
  max-width: 1200px;
  margin: 0 auto;
}

.scope-manage__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-6);
}

.scope-manage__header h1 {
  font-size: var(--font-h2);
  color: var(--text-primary);
}

.scope-manage__create-btn {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  cursor: pointer;
}

.scope-manage__create-btn:hover {
  opacity: 0.9;
}

.scope-manage__loading {
  text-align: center;
  padding: var(--space-8);
  color: var(--text-secondary);
}

.scope-manage__content {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: var(--space-6);
}

.scope-manage__list h2 {
  font-size: var(--font-h3);
  color: var(--text-primary);
  margin-bottom: var(--space-4);
}

.scope-manage__card {
  padding: var(--space-4);
  background: var(--surface-elevated);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  margin-bottom: var(--space-3);
}

.scope-manage__card:hover {
  border-color: var(--accent-primary);
}

.scope-manage__card--active {
  border-color: var(--accent-primary);
  background: var(--accent-light);
}

.scope-manage__card-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.scope-manage__card-name {
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.scope-manage__card-type {
  font-size: var(--font-caption);
  padding: 0 var(--space-1);
  border-radius: var(--radius-sm);
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
}

.scope-manage__card-desc {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin-top: var(--space-1);
}

.scope-manage__card-footer {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  margin-top: var(--space-2);
}

.scope-manage__go-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--font-caption);
  cursor: pointer;
  margin-left: auto;
}

.scope-manage__detail {
  padding: var(--space-4);
  background: var(--surface-elevated);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
}

.scope-manage__detail--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  color: var(--text-tertiary);
}

.scope-manage__detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.scope-manage__detail-header h2 {
  font-size: var(--font-h3);
  color: var(--text-primary);
}

.scope-manage__detail-actions {
  display: flex;
  gap: var(--space-2);
}

.scope-manage__detail-actions button {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  background: none;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  cursor: pointer;
}

.scope-manage__detail-actions button:hover {
  background: var(--sidebar-item-hover);
}

.scope-manage__delete-btn {
  color: var(--error) !important;
  border-color: var(--error) !important;
}

.scope-manage__delete-btn:hover {
  background: var(--error-light) !important;
}

.scope-manage__members-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-3);
}

.scope-manage__members-header h3 {
  font-size: var(--font-body-lg);
  color: var(--text-primary);
}

.scope-manage__members-header button {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
  cursor: pointer;
}

.scope-manage__members-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.scope-manage__member-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  background: var(--bg-primary);
  border-radius: var(--radius-sm);
}

.scope-manage__role-icon {
  color: var(--accent-primary);
}

.scope-manage__member-name {
  font-size: var(--font-body);
  color: var(--text-primary);
}

.scope-manage__role-select {
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  font-size: var(--font-body-sm);
  background: var(--bg-primary);
  color: var(--text-secondary);
}

.scope-manage__remove-btn {
  background: none;
  border: none;
  color: var(--error);
  cursor: pointer;
  padding: var(--space-1);
  border-radius: var(--radius-sm);
  margin-left: auto;
}

.scope-manage__remove-btn:hover {
  background: var(--error-light);
}

.scope-manage__empty {
  text-align: center;
  color: var(--text-tertiary);
  padding: var(--space-4);
}

.scope-manage__personal-note {
  color: var(--text-secondary);
  font-size: var(--font-body);
  margin-bottom: var(--space-4);
}

.scope-manage__dialog-overlay {
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

.scope-manage__dialog {
  background: var(--surface-elevated);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-6);
  width: 480px;
  max-height: 80vh;
  overflow-y: auto;
  box-shadow: var(--shadow-xl);
}

.scope-manage__dialog h2 {
  font-size: var(--font-h3);
  color: var(--text-primary);
  margin-bottom: var(--space-4);
}

.scope-manage__field {
  margin-bottom: var(--space-4);
}

.scope-manage__field label {
  display: block;
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  margin-bottom: var(--space-1);
}

.scope-manage__field input,
.scope-manage__field textarea,
.scope-manage__field select {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  background: var(--bg-primary);
  color: var(--text-primary);
}

.scope-manage__field textarea {
  resize: vertical;
}

.scope-manage__dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

.scope-manage__dialog-actions button {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  cursor: pointer;
  background: none;
  border: 1px solid var(--border-default);
  color: var(--text-secondary);
}

.scope-manage__primary-btn {
  background: var(--accent-primary) !important;
  color: var(--text-on-accent) !important;
  border: none !important;
}

.dialog-enter-active,
.dialog-leave-active {
  transition: all var(--transition-normal);
}

.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}

.scope-manage__consent-field {
  margin-bottom: var(--space-4);
}

.scope-manage__consent-label {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  cursor: pointer;
}

.scope-manage__consent-checkbox {
  width: 16px;
  height: 16px;
  margin-top: 2px;
  accent-color: var(--accent-primary);
  cursor: pointer;
}

.scope-manage__consent-text {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.5;
}
</style>