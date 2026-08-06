import api from './index'

export interface NotificationInfo {
  id: number
  type: string
  title: string
  content: string
  scopeId: number | null
  relatedPageId: number | null
  executionId: number | null
  isRead: number          // 0 = 未读, 1 = 已读
  createdAt: string
}

export function listNotifications(): Promise<NotificationInfo[]> {
  return api.get('/notifications')
}

export function getUnreadCount(): Promise<number> {
  return api.get('/notifications/unread-count')
}

export function markAsRead(id: number): Promise<void> {
  return api.put(`/notifications/${id}/read`)
}

export function markAllAsRead(): Promise<void> {
  return api.put('/notifications/read-all')
}

export function deleteNotification(id: number): Promise<void> {
  return api.delete(`/notifications/${id}`)
}