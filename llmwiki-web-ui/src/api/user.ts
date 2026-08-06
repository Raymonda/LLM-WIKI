import api from './index'

export interface UserManageInfo {
  id: number
  userName: string
  email: string
  role: string
  status: string
  scopeId: number
  consentKnowledgePromotion: number
  createdAt: string
  updatedAt: string
}

export interface UserSearchInfo {
  id: number
  userName: string
  email: string
}

export interface CreateUserRequest {
  userName: string
  email?: string
  password?: string
  role: string
}

export interface CreateUserResponse {
  user: UserManageInfo
  tempPassword?: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

export interface ResetPasswordResult {
  tempPassword: string
}

export function listUsers(params: {
  q?: string
  role?: string
  status?: string
  page?: number
  size?: number
}): Promise<PageResult<UserManageInfo>> {
  return api.get('/users', { params })
}

export function searchUsers(q: string, limit = 10): Promise<UserSearchInfo[]> {
  return api.get('/users/search', { params: { q, limit } })
}

export function getUser(id: number): Promise<UserManageInfo> {
  return api.get(`/users/${id}`)
}

export function createUser(request: CreateUserRequest): Promise<CreateUserResponse> {
  return api.post('/users', request)
}

export function updateUserStatus(id: number, status: string): Promise<void> {
  return api.patch(`/users/${id}/status`, { status })
}

export function updateUserRole(id: number, role: string): Promise<void> {
  return api.patch(`/users/${id}/role`, { role })
}

export function resetPassword(id: number): Promise<ResetPasswordResult> {
  return api.post(`/users/${id}/reset-password`)
}
