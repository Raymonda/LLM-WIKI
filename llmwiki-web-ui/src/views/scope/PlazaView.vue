<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Globe, Users, UserPlus, Check, Loader2, Crown } from 'lucide-vue-next'
import { listPlaza, createJoinRequest, type ScopeInfo } from '@/api/scope'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()

const authStore = useAuthStore()
const scopes = ref<ScopeInfo[]>([])
const loading = ref(true)
const joiningId = ref<number | null>(null)
const joinMessage = ref('')
const showJoinDialog = ref(false)
const targetScope = ref<ScopeInfo | null>(null)
const joinSuccess = ref(false)

const memberScopeIds = computed(() => {
  return new Set(authStore.scopes.map(s => s.scopeId))
})

onMounted(async () => {
  try {
    scopes.value = await listPlaza()
  } catch (e) {
    console.error('Failed to load plaza:', e)
  } finally {
    loading.value = false
  }
})

function openJoinDialog(scope: ScopeInfo) {
  targetScope.value = scope
  joinMessage.value = ''
  joinSuccess.value = false
  showJoinDialog.value = true
}

async function submitJoinRequest() {
  if (!targetScope.value) return
  joiningId.value = targetScope.value.id
  try {
    await createJoinRequest(targetScope.value.id, joinMessage.value)
    joinSuccess.value = true
    setTimeout(() => { showJoinDialog.value = false }, 1500)
  } catch (e: any) {
    alert(e.message || t('scope.joinFailed'))
  } finally {
    joiningId.value = null
  }
}

function isMember(scope: ScopeInfo): boolean {
  return memberScopeIds.value.has(scope.id)
}
</script>

<template>
  <div class="plaza-page">
    <header class="plaza-header">
      <Globe :size="22" />
      <h1>{{ t('scope.plazaTitle') }}</h1>
      <span class="plaza-header__desc">{{ t('scope.plazaDesc') }}</span>
    </header>

    <div v-if="loading" class="plaza-loading">
      <Loader2 :size="24" class="spin" />
      <span>{{ t('common.loading') }}</span>
    </div>

    <div v-else-if="scopes.length === 0" class="plaza-empty">
      <Globe :size="48" />
      <p>{{ t('scope.noPublicScopes') }}</p>
    </div>

    <div v-else class="plaza-grid">
      <div v-for="scope in scopes" :key="scope.id" class="plaza-card">
        <div class="plaza-card__header">
          <h3>{{ scope.name }}</h3>
          <span v-if="isMember(scope)" class="plaza-card__badge plaza-card__badge--member">
            <Check :size="12" /> {{ t('scope.joined') }}
          </span>
        </div>
        <p class="plaza-card__desc">{{ scope.description || t('scope.noDescription') }}</p>
        <div class="plaza-card__meta">
          <span class="plaza-card__owner">
            <Crown :size="13" /> {{ scope.ownerName || t('scope.unknown') }}
          </span>
          <span class="plaza-card__members">
            <Users :size="13" /> {{ scope.members?.length || 0 }} {{ t('scope.memberSuffix') }}
          </span>
        </div>
        <div class="plaza-card__actions">
          <button
            v-if="!isMember(scope)"
            class="plaza-card__join-btn"
            @click="openJoinDialog(scope)"
          >
            <UserPlus :size="14" />
            {{ t('scope.joinNow') }}
          </button>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="showJoinDialog" class="plaza-dialog-overlay" @click.self="showJoinDialog = false">
        <div class="plaza-dialog">
          <h3>{{ t('scope.joinDialogTitle', [targetScope?.name || '']) }}</h3>
          <textarea
            v-model="joinMessage"
            :placeholder="t('scope.joinReasonPlaceholder')"
            rows="3"
            class="plaza-dialog__input"
          ></textarea>
          <div v-if="joinSuccess" class="plaza-dialog__success">
            <Check :size="16" /> {{ t('scope.joinSubmitted') }}
          </div>
          <div class="plaza-dialog__actions">
            <button class="plaza-dialog__cancel" @click="showJoinDialog = false">{{ t('common.cancel') }}</button>
            <button
              class="plaza-dialog__submit"
              :disabled="joiningId !== null || joinSuccess"
              @click="submitJoinRequest"
            >
              <Loader2 v-if="joiningId" :size="14" class="spin" />
              <span v-else>{{ t('scope.submitRequest') }}</span>
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.plaza-page {
  padding: var(--space-6);
  max-width: 1200px;
}

.plaza-header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-6);
}

.plaza-header h1 {
  font-size: var(--font-h1);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
}

.plaza-header__desc {
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
}

.plaza-loading,
.plaza-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-12) 0;
  color: var(--text-secondary);
}

.plaza-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-5);
}

.plaza-card {
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  background: var(--surface-card);
  transition: box-shadow var(--transition-fast);
}

.plaza-card:hover {
  box-shadow: var(--shadow-md);
}

.plaza-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}

.plaza-card__header h3 {
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.plaza-card__badge {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  padding: 2px var(--space-2);
  border-radius: var(--radius-full);
}

.plaza-card__badge--member {
  background: var(--accent-light);
  color: var(--success);
}

.plaza-card__desc {
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  margin-bottom: var(--space-3);
  line-height: 1.5;
}

.plaza-card__meta {
  display: flex;
  gap: var(--space-4);
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  margin-bottom: var(--space-4);
}

.plaza-card__meta span {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.plaza-card__join-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  border: 1px solid var(--accent-primary);
  color: var(--accent-primary);
  background: transparent;
  cursor: pointer;
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  transition: all var(--transition-fast);
}

.plaza-card__join-btn:hover {
  background: var(--accent-primary);
  color: var(--text-on-accent);
}

.plaza-dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.plaza-dialog {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-6);
  width: 400px;
  max-width: 90vw;
  box-shadow: var(--shadow-xl);
}

.plaza-dialog h3 {
  margin-bottom: var(--space-4);
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.plaza-dialog__input {
  width: 100%;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  resize: none;
  font-size: var(--font-body-sm);
  margin-bottom: var(--space-4);
  background: var(--bg-primary);
  color: var(--text-primary);
  outline: none;
  transition: border-color var(--transition-fast);
}

.plaza-dialog__input:focus {
  border-color: var(--accent-primary);
}

.plaza-dialog__success {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--success);
  font-size: var(--font-body-sm);
  margin-bottom: var(--space-4);
}

.plaza-dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
}

.plaza-dialog__cancel {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  border: 1px solid var(--border-default);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: var(--font-body-sm);
  transition: all var(--transition-fast);
}

.plaza-dialog__cancel:hover {
  background: var(--bg-tertiary);
}

.plaza-dialog__submit {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  border: none;
  background: var(--accent-primary);
  color: var(--text-on-accent);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  transition: background var(--transition-fast);
}

.plaza-dialog__submit:hover:not(:disabled) {
  background: var(--accent-hover);
}

.plaza-dialog__submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
