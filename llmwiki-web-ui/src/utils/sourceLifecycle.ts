export interface SourceLifecycleLike {
  lifecycleStatus?: string | null
  deprecatedReason?: string | null
}

export function isSourceDeprecated(source: SourceLifecycleLike | null | undefined): boolean {
  return source?.lifecycleStatus === 'DEPRECATED'
}

export function validateDeprecateForm(category: string, reason: string): boolean {
  if (category === 'OTHER' && !reason.trim()) return false
  return true
}
