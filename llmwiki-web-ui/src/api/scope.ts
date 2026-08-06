import api from './index'

export interface ScopeBriefInfo {
  scopeId: number
  scopeName: string
  scopeType: string
  role: string
  language: string
}

export interface ScopeInfo {
  id: number
  name: string
  description: string
  type: string
  ownerId: number
  ownerName: string
  monthlyBudget: number
  defaultApproval: string
  maxFileSize: number
  maxConcurrent: number
  visibility: string
  createdAt: string
  updatedAt: string
  members: ScopeMemberInfo[]
}

export interface ScopeMemberInfo {
  id: number
  scopeId: number
  userId: number
  userName: string
  role: string
  joinedAt: string
}

export interface CreateScopeRequest {
  name: string
  description: string
}

export interface AddMemberRequest {
  userId: number
  role: string
}

export function listScopes(): Promise<ScopeInfo[]> {
  return api.get('/scope/list')
}

export function getScope(scopeId: number): Promise<ScopeInfo> {
  return api.get(`/scope/${scopeId}`)
}

export function createScope(data: CreateScopeRequest): Promise<ScopeInfo> {
  return api.post('/scope', data)
}

export function updateScope(scopeId: number, data: Partial<ScopeInfo>): Promise<ScopeInfo> {
  return api.put(`/scope/${scopeId}`, data)
}

export function deleteScope(scopeId: number): Promise<void> {
  return api.delete(`/scope/${scopeId}`)
}

export function addMember(scopeId: number, data: AddMemberRequest): Promise<ScopeMemberInfo> {
  return api.post(`/scope/${scopeId}/members`, data)
}

export function removeMember(scopeId: number, userId: number): Promise<void> {
  return api.delete(`/scope/${scopeId}/members/${userId}`)
}

export function updateMemberRole(scopeId: number, userId: number, role: string): Promise<void> {
  return api.put(`/scope/${scopeId}/members/${userId}/role`, { role })
}

export function listMembers(scopeId: number): Promise<ScopeMemberInfo[]> {
  return api.get(`/scope/${scopeId}/members`)
}

export interface JoinRequestInfo {
  id: number
  scopeId: number
  userId: number
  message: string
  status: string
  reviewerId: number | null
  reviewMessage: string | null
  createdAt: string
  reviewedAt: string | null
}

export function listPlaza(): Promise<ScopeInfo[]> {
  return api.get('/scope/plaza')
}

export function createJoinRequest(scopeId: number, message: string): Promise<void> {
  return api.post(`/scope/${scopeId}/join-requests`, { message })
}

export function listJoinRequests(scopeId: number): Promise<JoinRequestInfo[]> {
  return api.get(`/scope/${scopeId}/join-requests`)
}

export function reviewJoinRequest(scopeId: number, requestId: number, approve: boolean, message: string): Promise<void> {
  return api.post(`/scope/${scopeId}/join-requests/${requestId}/review`, { approve, message })
}