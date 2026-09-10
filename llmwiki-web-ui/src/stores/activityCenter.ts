import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  listNotifications,
  getUnreadCount,
  markAsRead,
  markAllAsRead,
  deleteNotification as deleteNotificationApi,
  type NotificationInfo,
} from '@/api/notification'

const NOTIFICATION_ICON_MAP: Record<string, string> = {
  ingest_started: 'loading',
  ingest_completed: 'success',
  ingest_failed: 'error',
  ingest_batch_awaiting: 'warning',
  ingest_batch_analyzed: 'success',
  ingest_batch_completed: 'success',
  merge_completed: 'success',
  merge_failed: 'error',
  lint_completed: 'shield',
  budget_warning: 'warning',
  budget_exceeded: 'error',
  awaiting_expired: 'warning',
  page_recalled: 'info',
  conflict_auto_resolved: 'success',
  conflict_pending_review: 'warning',
  conflict_deferred: 'info',
  conflict_review_reminder: 'warning',
  conflict_auto_executed: 'success',
}

export function notificationIconClass(type: string): string {
  return NOTIFICATION_ICON_MAP[type] || 'info'
}

export const useActivityCenterStore = defineStore('activityCenter', () => {
  const notifications = ref<NotificationInfo[]>([])
  const unreadCount = ref(0)
  const panelOpen = ref(false)

  const recentNotifications = computed(() => {
    return notifications.value.slice(0, 50)
  })

  const badgeCount = computed(() => unreadCount.value)

  async function fetchNotifications() {
    try {
      const [notifs, count] = await Promise.all([
        listNotifications(),
        getUnreadCount(),
      ])
      notifications.value = notifs
      unreadCount.value = count
    } catch {
      notifications.value = []
      unreadCount.value = 0
    }
  }

  async function handleMarkAllRead() {
    try {
      await markAllAsRead()
      notifications.value = notifications.value.map(n => ({ ...n, isRead: 1 }))
      unreadCount.value = 0
    } catch (e) {
      console.error('Failed to mark all as read:', e)
    }
  }

  async function markNotificationRead(id: number) {
    try {
      await markAsRead(id)
      unreadCount.value = Math.max(0, unreadCount.value - 1)
      const notif = notifications.value.find(n => n.id === id)
      if (notif) {
        notif.isRead = 1
      }
    } catch (e) {
      console.error('Failed to mark notification as read:', e)
    }
  }

  async function handleDeleteNotification(id: number) {
    try {
      await deleteNotificationApi(id)
      const notif = notifications.value.find(n => n.id === id)
      const wasUnread = notif && notif.isRead === 0
      notifications.value = notifications.value.filter(n => n.id !== id)
      if (wasUnread) {
        unreadCount.value = Math.max(0, unreadCount.value - 1)
      }
    } catch (e) {
      console.error('Failed to delete notification:', e)
    }
  }

  function openPanel() {
    panelOpen.value = true
    fetchNotifications()
  }

  function closePanel() {
    panelOpen.value = false
  }

  function togglePanel() {
    if (panelOpen.value) {
      closePanel()
    } else {
      openPanel()
    }
  }

  return {
    notifications,
    unreadCount,
    panelOpen,
    recentNotifications,
    badgeCount,
    fetchNotifications,
    handleMarkAllRead,
    markNotificationRead,
    handleDeleteNotification,
    openPanel,
    closePanel,
    togglePanel,
  }
})

export type { NotificationInfo }
