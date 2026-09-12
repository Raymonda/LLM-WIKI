import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

const { getUserInfo } = vi.hoisted(() => ({ getUserInfo: vi.fn() }))

vi.mock('@/api/auth', () => ({ getUserInfo }))

const storage = new Map<string, string>()
vi.stubGlobal('localStorage', {
  getItem: (k: string) => storage.get(k) ?? null,
  setItem: (k: string, v: string) => { storage.set(k, v) },
  removeItem: (k: string) => { storage.delete(k) },
  clear: () => storage.clear(),
})

import { useAuthStore } from '../auth'

const scope5 = { scopeId: 5, scopeName: 'Test03', scopeType: 'personal', role: 'owner', language: 'zh-CN' }

function mockUserInfo(scopes = [scope5], scopeId = 5) {
  getUserInfo.mockResolvedValue({
    id: 4,
    userName: 'Test03',
    email: '',
    role: 'owner',
    systemRole: 'user',
    scopeId,
    language: 'zh-CN',
    scopes,
  })
}

describe('auth store query scope isolation', () => {
  beforeEach(() => {
    storage.clear()
    getUserInfo.mockReset()
    setActivePinia(createPinia())
  })

  it('should drop persisted scopes the user cannot access when initialized', async () => {
    storage.set('llmwiki-token', 't')
    storage.set('llmwiki-queryScopes', '[1,2]')
    mockUserInfo()

    const store = useAuthStore()
    await store.initFromApi()

    expect(store.effectiveQueryScopes()).toEqual([5])
    expect(storage.get('llmwiki-queryScopes')).toBeUndefined()
  })

  it('should keep persisted scopes that are still accessible', async () => {
    storage.set('llmwiki-token', 't')
    storage.set('llmwiki-queryScopes', '[5]')
    mockUserInfo()

    const store = useAuthStore()
    await store.initFromApi()

    expect(store.effectiveQueryScopes()).toEqual([5])
    expect(storage.get('llmwiki-queryScopes')).toBe('[5]')
  })

  it('should remove persisted query scopes when logging out', () => {
    storage.set('llmwiki-queryScopes', '[2]')

    const store = useAuthStore()
    store.clearAuth()

    expect(storage.get('llmwiki-queryScopes')).toBeUndefined()
    expect(store.effectiveQueryScopes()).toEqual([])
  })

  it('should never return inaccessible scopes from effectiveQueryScopes', () => {
    storage.set('llmwiki-queryScopes', '[2]')

    const store = useAuthStore()
    store.setScopes([scope5])
    store.switchScope(5)

    expect(store.effectiveQueryScopes()).toEqual([5])
  })

  it('should prefer accessible persisted selection over current scope', async () => {
    storage.set('llmwiki-token', 't')
    storage.set('llmwiki-queryScopes', '[5]')
    mockUserInfo()

    const store = useAuthStore()
    await store.initFromApi()
    store.setScopes([scope5])

    expect(store.effectiveQueryScopes()).toEqual([5])
  })
})
