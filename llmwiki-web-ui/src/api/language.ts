import api from './index'

export function updateUserLanguage(language: string): Promise<void> {
  return api.put('/auth/language', { language })
}

export function updateScopeLanguage(scopeId: number, language: string): Promise<void> {
  return api.put(`/scope/${scopeId}/language`, { language })
}
