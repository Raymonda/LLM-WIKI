<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  Activity, Bell, X, Trash2,
  Upload, Shield, AlertTriangle, CheckCircle2, Info, Loader2, ClipboardCheck,
} from 'lucide-vue-next'
import { useActivityCenterStore, type NotificationInfo } from '@/stores/activityCenter'

const store = useActivityCenterStore()
const router = useRouter()
const { t, locale } = useI18n()

const typeMeta: Record<string, { icon: any; labelKey: string; color: string }> = {
  ingest_started: { icon: Upload, labelKey: 'common.pulseTypeIngestStarted', color: 'var(--accent-primary)' },
  ingest_completed: { icon: CheckCircle2, labelKey: 'common.pulseTypeIngestCompleted', color: 'var(--success)' },
  ingest_failed: { icon: AlertTriangle, labelKey: 'common.pulseTypeIngestFailed', color: 'var(--error)' },
  merge_completed: { icon: CheckCircle2, labelKey: 'common.pulseTypeMergeCompleted', color: 'var(--success)' },
  merge_failed: { icon: AlertTriangle, labelKey: 'common.pulseTypeMergeFailed', color: 'var(--error)' },
  lint_completed: { icon: Shield, labelKey: 'common.pulseTypeLintCompleted', color: 'var(--accent-primary)' },
  budget_warning: { icon: AlertTriangle, labelKey: 'common.pulseTypeBudgetWarning', color: 'var(--warning)' },
  budget_exceeded: { icon: AlertTriangle, labelKey: 'common.pulseTypeBudgetExceeded', color: 'var(--error)' },
  awaiting_expired: { icon: Info, labelKey: 'common.pulseTypeAwaitingExpired', color: 'var(--warning)' },
  page_recalled: { icon: Info, labelKey: 'common.pulseTypePageRecalled', color: 'var(--text-secondary)' },
  ingest_batch_awaiting: { icon: ClipboardCheck, labelKey: 'common.pulseTypeIngestBatchAwaiting', color: 'var(--accent-primary)' },
  ingest_batch_analyzed: { icon: CheckCircle2, labelKey: 'common.pulseTypeIngestBatchAnalyzed', color: 'var(--success)' },
  ingest_batch_completed: { icon: CheckCircle2, labelKey: 'common.pulseTypeIngestBatchCompleted', color: 'var(--success)' },
}

function getTypeMeta(type: string) {
  return typeMeta[type] || { icon: Bell, labelKey: '', color: 'var(--text-tertiary)' }
}

function getTypeLabel(type: string) {
  const meta = typeMeta[type]
  return meta ? t(meta.labelKey) : type
}

function handleNotificationClick(notif: NotificationInfo) {
  if (notif.isRead === 0) {
    store.markNotificationRead(notif.id)
  }
  if (notif.batchId) {
    router.push({ path: '/ingest', query: { batch: String(notif.batchId) } })
    store.closePanel()
    return
  }
  if (notif.executionId) {
    if (notif.type.startsWith('merge_')) {
      router.push(`/harness/${notif.executionId}`)
    } else if (notif.type.startsWith('ingest_')) {
      router.push('/ingest')
    } else if (notif.type.startsWith('lint_')) {
      router.push('/lint')
    } else {
      router.push(`/harness/${notif.executionId}`)
    }
    store.closePanel()
  } else if (notif.type === 'lint_completed') {
    router.push('/lint')
    store.closePanel()
  }
}

function handleMarkRead(id: number, e: Event) {
  e.stopPropagation()
  store.markNotificationRead(id)
}

function handleDelete(id: number, e: Event) {
  e.stopPropagation()
  store.handleDeleteNotification(id)
}

function formatTime(dateStr: string): string {
  if (!dateStr) return ''
  try {
    const d = new Date(dateStr)
    const now = new Date()
    const diffMs = now.getTime() - d.getTime()
    const diffMin = Math.floor(diffMs / 60000)
    if (diffMin < 1) return t('common.justNow')
    if (diffMin < 60) return t('common.minutesAgo', [diffMin])
    const diffHour = Math.floor(diffMin / 60)
    if (diffHour < 24) return t('common.hoursAgo', [diffHour])
    const diffDay = Math.floor(diffHour / 24)
    if (diffDay < 7) return t('common.daysAgo', [diffDay])
    return d.toLocaleDateString(locale.value, { month: 'short', day: 'numeric' })
  } catch {
    return dateStr
  }
}
</script>

<template>
  <Transition name="pulse-panel">
    <div v-if="store.panelOpen" class="pulse-panel" @click.stop>
      <div class="pulse-panel__header">
        <div class="pulse-panel__title-wrap">
          <Activity :size="16" class="pulse-panel__title-icon" />
          <span class="pulse-panel__title">{{ t('common.knowledgePulse') }}</span>
        </div>
        <div class="pulse-panel__header-actions">
          <button
            v-if="store.unreadCount > 0"
            class="pulse-panel__mark-read"
            @click="store.handleMarkAllRead"
          >
            {{ t('common.markAllRead') }}
          </button>
          <button class="pulse-panel__close" @click="store.closePanel" :aria-label="t('common.close')">
            <X :size="14" />
          </button>
        </div>
      </div>

      <div class="pulse-panel__body">
        <div
          v-if="store.recentNotifications.length === 0"
          class="pulse-panel__empty"
        >
          <Bell :size="24" class="pulse-panel__empty-icon" />
          <span>{{ t('common.noMessages') }}</span>
        </div>

        <div v-else class="pulse-panel__list">
          <div
            v-for="notif in store.recentNotifications"
            :key="notif.id"
            class="pulse-panel__item"
            :class="{ 'pulse-panel__item--unread': notif.isRead === 0 }"
            @click="handleNotificationClick(notif)"
          >
            <div
              class="pulse-panel__item-indicator"
              :class="{
                'pulse-panel__item-indicator--running': notif.type === 'ingest_started',
                'pulse-panel__item-indicator--success': notif.type === 'ingest_completed' || notif.type === 'lint_completed' || notif.type === 'merge_completed',
                'pulse-panel__item-indicator--error': notif.type === 'ingest_failed' || notif.type === 'budget_exceeded' || notif.type === 'merge_failed',
                'pulse-panel__item-indicator--warning': notif.type === 'budget_warning' || notif.type === 'awaiting_expired',
              }"
            >
              <Loader2
                v-if="notif.type === 'ingest_started'"
                :size="14"
                class="pulse-panel__spin"
              />
              <component v-else :is="getTypeMeta(notif.type).icon" :size="14" />
            </div>
            <div class="pulse-panel__item-body">
              <div class="pulse-panel__item-head">
                <span
                  class="pulse-panel__item-type-badge"
                  :style="{ color: getTypeMeta(notif.type).color }"
                >
                  {{ getTypeLabel(notif.type) }}
                </span>
                <span class="pulse-panel__item-time">{{ formatTime(notif.createdAt) }}</span>
              </div>
              <span class="pulse-panel__item-title">{{ notif.title }}</span>
              <span class="pulse-panel__item-sub">{{ notif.content }}</span>
            </div>
            <div class="pulse-panel__item-actions">
              <button
                v-if="notif.isRead === 0"
                class="pulse-panel__item-dismiss"
                @click="handleMarkRead(notif.id, $event)"
                :aria-label="t('common.markRead')"
                :title="t('common.markRead')"
              >
                <CheckCircle2 :size="14" />
              </button>
              <button
                class="pulse-panel__item-dismiss"
                @click="handleDelete(notif.id, $event)"
                :aria-label="t('common.delete')"
                :title="t('common.delete')"
              >
                <Trash2 :size="14" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.pulse-panel {
  position: absolute;
  top: var(--topbar-height);
  right: var(--space-6);
  width: 380px;
  max-height: 480px;
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xl);
  z-index: 100;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.pulse-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--border-default);
  flex-shrink: 0;
}

.pulse-panel__title-wrap {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.pulse-panel__title-icon {
  color: var(--accent-primary);
}

.pulse-panel__title {
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.pulse-panel__header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.pulse-panel__mark-read {
  background: none;
  border: none;
  color: var(--accent-primary);
  font-size: var(--font-caption);
  cursor: pointer;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  transition: background var(--transition-fast);
}

.pulse-panel__mark-read:hover {
  background: var(--accent-light);
}

.pulse-panel__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--text-tertiary);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.pulse-panel__close:hover {
  background: var(--bg-tertiary);
  color: var(--text-primary);
}

.pulse-panel__body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-2) 0;
}

.pulse-panel__list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 var(--space-2);
}

.pulse-panel__item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.pulse-panel__item:hover {
  background: var(--bg-tertiary);
}

.pulse-panel__item--unread {
  background: var(--accent-light);
}

.pulse-panel__item--unread:hover {
  background: var(--bg-tertiary);
}

.pulse-panel__item-indicator {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-full);
  margin-top: 1px;
}

.pulse-panel__item-indicator--running {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.pulse-panel__item-indicator--success {
  background: var(--success-light);
  color: var(--success);
}

.pulse-panel__item-indicator--error {
  background: var(--error-light);
  color: var(--error);
}

.pulse-panel__item-indicator--warning {
  background: var(--warning-light, #fff8e1);
  color: var(--warning);
}

.pulse-panel__spin {
  animation: pulse-spin 1s linear infinite;
}

.pulse-panel__item-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.pulse-panel__item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.pulse-panel__item-type-badge {
  font-size: 11px;
  font-weight: var(--weight-medium);
}

.pulse-panel__item-time {
  font-size: 11px;
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.pulse-panel__item-title {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pulse-panel__item-sub {
  font-size: var(--font-caption);
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pulse-panel__item-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 2px;
  margin-top: 2px;
}

.pulse-panel__item-dismiss {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--text-tertiary);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.pulse-panel__item-dismiss:hover {
  background: var(--bg-tertiary);
  color: var(--text-primary);
}

.pulse-panel__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-8) var(--space-4);
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
}

.pulse-panel__empty-icon {
  opacity: 0.4;
}

@keyframes pulse-spin {
  to {
    transform: rotate(360deg);
  }
}

.pulse-panel-enter-active,
.pulse-panel-leave-active {
  transition: opacity 150ms ease, transform 150ms ease;
}

.pulse-panel-enter-from,
.pulse-panel-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (prefers-reduced-motion: reduce) {
  .pulse-panel__spin {
    animation: none;
  }
  .pulse-panel-enter-active,
  .pulse-panel-leave-active {
    transition: none;
  }
}
</style>
