import api from './index'

export interface ActivityItem {
  pageId: number
  title: string
  category: string | null
  scopeId: number
  scopeName: string
  updatedAt: string
}

export function getActivityFeed(limit: number = 20): Promise<ActivityItem[]> {
  return api.get('/activity/feed', { params: { limit } })
}
