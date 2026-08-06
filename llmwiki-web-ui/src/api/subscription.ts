import api from './index'

export interface SubscriptionInfo {
  id: number
  subscriberScopeId: number
  subscriberScopeName: string
  publisherScopeId: number
  publisherScopeName: string
  status: string
  subscriptionType: string
  createdBy: number
  createdByName: string
  createdAt: string
  updatedAt: string
}

export function listSubscriptions(scopeId: number): Promise<SubscriptionInfo[]> {
  return api.get(`/scope/${scopeId}/subscriptions`)
}

export function createSubscription(scopeId: number, publisherScopeId: number): Promise<SubscriptionInfo> {
  return api.post(`/scope/${scopeId}/subscriptions`, { publisherScopeId })
}

export function cancelSubscription(scopeId: number, subId: number): Promise<void> {
  return api.delete(`/scope/${scopeId}/subscriptions/${subId}`)
}

export function listSubscribers(scopeId: number): Promise<SubscriptionInfo[]> {
  return api.get(`/scope/${scopeId}/subscribers`)
}
