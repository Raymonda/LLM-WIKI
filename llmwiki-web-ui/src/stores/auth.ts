import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ScopeBriefInfo } from '@/api/scope'
import { getUserInfo } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('llmwiki-token') || '')
  const username = ref(localStorage.getItem('llmwiki-username') || '')
  const role = ref(localStorage.getItem('llmwiki-role') || '')
  const systemRole = ref(localStorage.getItem('llmwiki-systemRole') || '')
  const scopeId = ref(Number(localStorage.getItem('llmwiki-scopeId')) || 0)
  const userId = ref(Number(localStorage.getItem('llmwiki-userId')) || 0)
  const language = ref(localStorage.getItem('llmwiki-language') || 'zh-CN')
  const scopes = ref<ScopeBriefInfo[]>([])
  const queryScopes = ref<number[]>(JSON.parse(localStorage.getItem('llmwiki-queryScopes') || '[]'))
  const initialized = ref(false)

  const isSystemAdmin = computed(() => systemRole.value === 'admin')

  async function initFromApi() {
    if (!token.value) {
      initialized.value = true
      return
    }
    try {
      const user = await getUserInfo()
      username.value = user.userName
      role.value = user.role
      systemRole.value = user.systemRole
      scopeId.value = user.scopeId
      userId.value = user.id
      language.value = user.language || 'zh-CN'
      localStorage.setItem('llmwiki-username', user.userName)
      localStorage.setItem('llmwiki-role', user.role)
      localStorage.setItem('llmwiki-systemRole', user.systemRole)
      localStorage.setItem('llmwiki-scopeId', String(user.scopeId))
      localStorage.setItem('llmwiki-userId', String(user.id))
      localStorage.setItem('llmwiki-language', language.value)
      scopes.value = user.scopes || []
    } catch {
      clearAuth()
    }
    initialized.value = true
  }

  function setAuth(newToken: string, newUser: { id: number; userName: string; role: string; systemRole?: string; scopeId: number; scopes?: ScopeBriefInfo[] }) {
    token.value = newToken
    username.value = newUser.userName
    role.value = newUser.role
    systemRole.value = newUser.systemRole || ''
    scopeId.value = newUser.scopeId
    userId.value = newUser.id
    scopes.value = newUser.scopes || []
    localStorage.setItem('llmwiki-token', newToken)
    localStorage.setItem('llmwiki-username', newUser.userName)
    localStorage.setItem('llmwiki-role', newUser.role)
    localStorage.setItem('llmwiki-systemRole', systemRole.value)
    localStorage.setItem('llmwiki-scopeId', String(newUser.scopeId))
    localStorage.setItem('llmwiki-userId', String(newUser.id))
  }

  function setScopes(newScopes: ScopeBriefInfo[]) {
    scopes.value = newScopes
  }

  function setSystemRole(newRole: string) {
    systemRole.value = newRole
    localStorage.setItem('llmwiki-systemRole', newRole)
  }

  function switchScope(newScopeId: number) {
    scopeId.value = newScopeId
    const targetScope = scopes.value.find(s => s.scopeId === newScopeId)
    if (targetScope) {
      role.value = targetScope.role
      localStorage.setItem('llmwiki-scopeId', String(newScopeId))
      localStorage.setItem('llmwiki-role', targetScope.role)
    }
  }

  function setQueryScopes(ids: number[]) {
    queryScopes.value = ids
    localStorage.setItem('llmwiki-queryScopes', JSON.stringify(ids))
  }

  function effectiveQueryScopes(): number[] {
    if (queryScopes.value.length > 0) return queryScopes.value
    return scopeId.value ? [scopeId.value] : []
  }

  function clearAuth() {
    token.value = ''
    username.value = ''
    role.value = ''
    systemRole.value = ''
    scopeId.value = 0
    userId.value = 0
    scopes.value = []
    initialized.value = false
    localStorage.removeItem('llmwiki-token')
    localStorage.removeItem('llmwiki-username')
    localStorage.removeItem('llmwiki-role')
    localStorage.removeItem('llmwiki-systemRole')
    localStorage.removeItem('llmwiki-scopeId')
    localStorage.removeItem('llmwiki-userId')
  }

  function isAuthenticated() {
    return !!token.value
  }

  function currentScopeType() {
    const current = scopes.value.find(s => s.scopeId === scopeId.value)
    return current?.scopeType || 'personal'
  }

  return { token, username, role, systemRole, scopeId, userId, language, scopes, queryScopes, initialized, isSystemAdmin, initFromApi, setAuth, setScopes, setSystemRole, switchScope, setQueryScopes, effectiveQueryScopes, clearAuth, isAuthenticated, currentScopeType }
})
