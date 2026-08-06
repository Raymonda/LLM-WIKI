import type { WikiPageInfo } from '@/api/wiki'

export type HealthStatus = 'healthy' | 'needs-update' | 'has-problems' | 'conflict-warning' | 'deprecated'

export type LifecycleStatus = 'ACTIVE' | 'DEPRECATED' | 'MERGED' | 'DELETED'

export function isPageActive(page: WikiPageInfo | null | undefined): boolean {
  if (!page) return false
  return page.lifecycleStatus === 'ACTIVE'
}

export function getLifecycleStatus(page: WikiPageInfo | null | undefined): LifecycleStatus {
  if (!page) return 'DELETED'
  return (page.lifecycleStatus as LifecycleStatus) || 'ACTIVE'
}

export function getEffectiveHealth(page: WikiPageInfo | null | undefined): HealthStatus {
  if (!page) return 'healthy'
  if (page.lifecycleStatus && page.lifecycleStatus !== 'ACTIVE') return 'deprecated'
  const status = page.healthStatus || 'healthy'
  if (status === 'conflict-warning') return 'conflict-warning'
  if (status === 'needs-update') return 'needs-update'
  if (status === 'has-problems') return 'has-problems'
  return 'healthy'
}

export function isLintIssue(status: HealthStatus): boolean {
  return status === 'needs-update' || status === 'has-problems' || status === 'conflict-warning'
}
