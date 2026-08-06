import api from './index'

export interface AuditLogInfo {
  id: number
  actorUserId: number
  actorUsername: string
  action: string
  targetType: string
  targetId: number
  targetName: string
  scopeId: number
  detailJson: string
  ipAddress: string
  createdAt: string
}

export interface AuditLogQuery {
  scopeId: number
  action?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

export interface AuditLogPageResult {
  items: AuditLogInfo[]
  total: number
  page: number
  size: number
  totalPages: number
}

export function queryAuditLogs(params: AuditLogQuery): Promise<AuditLogPageResult> {
  return api.get('/audit/logs', { params })
}

export function getAuditStats(scopeId: number): Promise<Record<string, number>> {
  return api.get('/audit/stats', { params: { scopeId } })
}
