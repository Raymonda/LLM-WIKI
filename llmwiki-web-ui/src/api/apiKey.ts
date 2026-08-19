import api from './index'

export interface ApiKeyInfo {
  id: number
  name: string
  userId: number
  scopeId: number
  scopeName: string | null
  scopeType: string | null
  username?: string | null
  status: string
  expiresAt: string | null
  lastUsedAt: string | null
  createdAt: string
}

export interface CreateApiKeyRequest {
  name: string
  scopeId: number
  expiresAt?: string
}

export interface CreateApiKeyResponse {
  id: number
  key: string
}

export function createApiKey(data: CreateApiKeyRequest): Promise<CreateApiKeyResponse> {
  return api.post('/keys', data)
}

export function listApiKeys(): Promise<ApiKeyInfo[]> {
  return api.get('/keys')
}

export function listAllApiKeys(): Promise<ApiKeyInfo[]> {
  return api.get('/keys/all')
}

export function revokeApiKey(id: number): Promise<void> {
  return api.delete(`/keys/${id}`)
}

export function getMcpPublicUrl(): Promise<string> {
  return api.get('/mcp-info/public-url')
}
