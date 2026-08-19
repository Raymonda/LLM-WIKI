import api from './index'
import type { ScopeBriefInfo } from './scope'

export interface LoginResult {
  token: string
  user: {
    id: number
    userName: string
    email: string
    role: string
    systemRole: string
    scopeId: number
    scopes: ScopeBriefInfo[]
  }
}

export interface UserInfo {
  id: number
  userName: string
  email: string
  role: string
  systemRole: string
  scopeId: number
  language: string
  scopes: ScopeBriefInfo[]
}

export function login(username: string, password: string): Promise<LoginResult> {
  return api.post('/auth/login', { username, password })
}

export function register(username: string, password: string): Promise<LoginResult> {
  return api.post('/auth/register', { username, password })
}

export function getUserInfo(): Promise<UserInfo> {
  return api.post('/auth/info')
}