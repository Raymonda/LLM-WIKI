<script setup lang="ts">
import { ref, onMounted, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ScrollText, ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { queryAuditLogs, type AuditLogInfo, type AuditLogQuery } from '@/api/audit'
import { listScopes, type ScopeInfo } from '@/api/scope'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'

const authStore = useAuthStore()
const toastStore = useToastStore()
const { t, locale } = useI18n()
const scopes = ref<ScopeInfo[]>([])
const logs = ref<AuditLogInfo[]>([])
const total = ref(0)
const loading = ref(false)

const PAGE_SIZE = 20
const query = ref({
  scopeId: authStore.scopeId,
  action: undefined as string | undefined,
  from: undefined as string | undefined,
  to: undefined as string | undefined,
  page: 1,
  size: PAGE_SIZE,
})

const fromDate = ref('')
const toDate = ref('')

const actionOptions = [
  { labelKey: 'scope.auditAllActions', value: '' },
  { labelKey: 'scope.auditIngestStart', value: 'INGEST_START' },
  { labelKey: 'scope.auditIngestComplete', value: 'INGEST_COMPLETE' },
  { labelKey: 'scope.auditPageModify', value: 'PAGE_MODIFY' },
  { labelKey: 'scope.auditPageDelete', value: 'PAGE_DELETE' },
  { labelKey: 'scope.auditPageVisibility', value: 'PAGE_VISIBILITY' },
  { labelKey: 'scope.auditPageSensitivity', value: 'PAGE_SENSITIVITY' },
  { labelKey: 'scope.auditSourceUpload', value: 'SOURCE_UPLOAD' },
  { labelKey: 'scope.auditSourceDelete', value: 'SOURCE_DELETE' },
  { labelKey: 'scope.auditSubCreate', value: 'SUBSCRIPTION_CREATE' },
  { labelKey: 'scope.auditSubCancel', value: 'SUBSCRIPTION_CANCEL' },
  { labelKey: 'scope.auditMemberAdd', value: 'MEMBER_ADD' },
  { labelKey: 'scope.auditMemberRemove', value: 'MEMBER_REMOVE' },
  { labelKey: 'scope.auditMemberRole', value: 'MEMBER_ROLE_CHANGE' },
  { labelKey: 'scope.auditSchemaUpdate', value: 'SCHEMA_UPDATE' },
  { labelKey: 'scope.auditScopeCreate', value: 'SCOPE_CREATE' },
  { labelKey: 'scope.auditScopeDelete', value: 'SCOPE_DELETE' },
]

const actionOptionsLocalized = computed(() =>
  actionOptions.map(o => ({ ...o, label: t(o.labelKey) }))
)

const totalPages = ref(0)

function updateTotalPages() {
  totalPages.value = Math.max(1, Math.ceil(total.value / PAGE_SIZE))
}

onMounted(async () => {
  try {
    scopes.value = await listScopes()
  } catch (_) {}
  await loadLogs()
})

watch([() => query.value.action, fromDate, toDate], async () => {
  query.value.page = 1
  await loadLogs()
})

async function loadLogs() {
  loading.value = true
  try {
    const params: AuditLogQuery = { scopeId: query.value.scopeId, action: query.value.action, page: query.value.page, size: PAGE_SIZE }
    if (fromDate.value) {
      params.from = fromDate.value + 'T00:00:00'
    } else {
      params.from = undefined
    }
    if (toDate.value) {
      params.to = toDate.value + 'T23:59:59'
    } else {
      params.to = undefined
    }
    if (!params.action) {
      params.action = undefined
    }
    const result = await queryAuditLogs(params)
    logs.value = result.items
    total.value = result.total
    updateTotalPages()
  } catch (e: any) {
    toastStore.error(t('scope.auditLoadFailed'), e.message || t('scope.auditLoadFailedDetail'))
  } finally {
    loading.value = false
  }
}

function handlePageChange(page: number) {
  query.value.page = page
  loadLogs()
}

function formatDate(dateStr: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString(locale.value === 'en' ? 'en-US' : 'zh-CN')
}

function actionLabel(action: string) {
  const found = actionOptions.find((o) => o.value === action)
  return found ? t(found.labelKey) : action
}
</script>

<template>
  <div class="audit-log">
    <div class="audit-log__header">
      <div class="audit-log__title">
        <ScrollText :size="24" />
        <h1>{{ t('scope.auditTitle') }}</h1>
      </div>
    </div>

    <div class="audit-log__filters">
      <select
        class="audit-log__select"
        :value="query.scopeId"
        @change="query.scopeId = Number(($event.target as HTMLSelectElement).value); loadLogs()"
      >
        <option v-for="scope in scopes" :key="scope.id" :value="scope.id">
          {{ scope.name }}
        </option>
      </select>
      <select
        class="audit-log__select"
        :value="query.action || ''"
        @change="query.action = ($event.target as HTMLSelectElement).value || undefined"
      >
        <option v-for="opt in actionOptionsLocalized" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </option>
      </select>
      <div class="audit-log__date-range">
        <input
          type="date"
          class="audit-log__date-input"
          v-model="fromDate"
          :placeholder="t('scope.auditStartDate')"
        />
        <span class="audit-log__date-sep">{{ t('scope.auditDateSep') }}</span>
        <input
          type="date"
          class="audit-log__date-input"
          v-model="toDate"
          :placeholder="t('scope.auditEndDate')"
        />
      </div>
    </div>

    <div class="audit-log__table-wrap">
      <div v-if="loading" class="audit-log__loading">{{ t('scope.auditLoading') }}</div>
      <table v-else class="audit-log__table">
        <thead>
          <tr>
            <th>{{ t('scope.auditColTime') }}</th>
            <th>{{ t('scope.auditColOperator') }}</th>
            <th>{{ t('scope.auditColAction') }}</th>
            <th>{{ t('scope.auditColTargetType') }}</th>
            <th>{{ t('scope.auditColTargetName') }}</th>
            <th>{{ t('scope.auditColIp') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="log in logs" :key="log.id" class="audit-log__row">
            <td class="audit-log__td--date">{{ formatDate(log.createdAt) }}</td>
            <td>{{ log.actorUsername || '-' }}</td>
            <td>
              <span class="audit-log__badge">{{ actionLabel(log.action) }}</span>
            </td>
            <td>{{ log.targetType || '-' }}</td>
            <td class="audit-log__td--name" :title="log.targetName">{{ log.targetName || '-' }}</td>
            <td class="audit-log__td--ip">{{ log.ipAddress || '-' }}</td>
          </tr>
          <tr v-if="logs.length === 0">
            <td colspan="6" class="audit-log__empty">
              <ScrollText :size="32" class="audit-log__empty-icon" />
              <p>{{ t('scope.auditNoLogs') }}</p>
              <p class="audit-log__empty-hint">{{ t('scope.auditNoLogsHint') }}</p>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="total > PAGE_SIZE" class="audit-log__pagination">
      <span class="audit-log__pagination-info">{{ t('scope.auditTotalCount', [total]) }}</span>
      <div class="audit-log__pagination-controls">
        <button
          class="audit-log__page-btn"
          :disabled="query.page <= 1"
          @click="handlePageChange(query.page - 1)"
        >
          <ChevronLeft :size="14" />
        </button>
        <template v-for="p in totalPages" :key="p">
          <button
            v-if="p === 1 || p === totalPages || Math.abs(p - query.page) <= 2"
            class="audit-log__page-btn"
            :class="{ 'audit-log__page-btn--active': p === query.page }"
            @click="handlePageChange(p)"
          >
            {{ p }}
          </button>
          <span
            v-else-if="p === 2 && query.page > 4"
            class="audit-log__page-ellipsis"
          >...</span>
          <span
            v-else-if="p === totalPages - 1 && query.page < totalPages - 3"
            class="audit-log__page-ellipsis"
          >...</span>
        </template>
        <button
          class="audit-log__page-btn"
          :disabled="query.page >= totalPages"
          @click="handlePageChange(query.page + 1)"
        >
          <ChevronRight :size="14" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.audit-log {
  padding: var(--space-6);
  max-width: 1400px;
}

.audit-log__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-6);
}

.audit-log__title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-primary);
}

.audit-log__title h1 {
  font-size: var(--font-h2);
  font-weight: var(--weight-semibold);
  margin: 0;
}

.audit-log__filters {
  display: flex;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  flex-wrap: wrap;
  align-items: center;
}

.audit-log__select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  background: var(--bg-primary);
  color: var(--text-primary);
  height: var(--input-height);
  min-width: 160px;
}

.audit-log__select:focus {
  outline: none;
  border-color: var(--accent-primary);
  box-shadow: 0 0 0 2px var(--accent-light);
}

.audit-log__date-range {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.audit-log__date-sep {
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
}

.audit-log__date-input {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  background: var(--bg-primary);
  color: var(--text-primary);
  height: var(--input-height);
  font-variant-numeric: tabular-nums;
}

.audit-log__date-input:focus {
  outline: none;
  border-color: var(--accent-primary);
  box-shadow: 0 0 0 2px var(--accent-light);
}

.audit-log__loading {
  text-align: center;
  padding: var(--space-8);
  color: var(--text-secondary);
}

.audit-log__table-wrap {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.audit-log__table {
  width: 100%;
  border-collapse: collapse;
}

.audit-log__table th {
  text-align: left;
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-default);
  white-space: nowrap;
}

.audit-log__row {
  border-bottom: 1px solid var(--border-subtle);
  transition: background var(--transition-fast);
}

.audit-log__row:last-child {
  border-bottom: none;
}

.audit-log__row:hover {
  background: var(--bg-secondary);
}

.audit-log__table td {
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-body);
  color: var(--text-primary);
  vertical-align: middle;
}

.audit-log__td--date {
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.audit-log__td--name {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.audit-log__td--ip {
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  font-variant-numeric: tabular-nums;
}

.audit-log__badge {
  display: inline-block;
  font-size: var(--font-caption);
  font-weight: var(--weight-medium);
  padding: 2px var(--space-2);
  border-radius: var(--radius-full);
  background: var(--accent-light);
  color: var(--accent-primary);
  white-space: nowrap;
}

.audit-log__empty {
  text-align: center;
  padding: var(--space-8) var(--space-4) !important;
  color: var(--text-tertiary);
}

.audit-log__empty-icon {
  opacity: 0.3;
  margin-bottom: var(--space-3);
}

.audit-log__empty p {
  color: var(--text-secondary);
  font-size: var(--font-body);
  margin: 0;
}

.audit-log__empty-hint {
  color: var(--text-tertiary) !important;
  font-size: var(--font-body-sm) !important;
  margin-top: var(--space-2) !important;
}

/* Pagination */
.audit-log__pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-4);
  padding: 0 var(--space-2);
}

.audit-log__pagination-info {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.audit-log__pagination-controls {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.audit-log__page-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 28px;
  padding: 0 var(--space-2);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.audit-log__page-btn:hover:not(:disabled) {
  background: var(--bg-tertiary);
  color: var(--text-primary);
}

.audit-log__page-btn:disabled {
  opacity: 0.38;
  cursor: not-allowed;
}

.audit-log__page-btn--active {
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border-color: var(--accent-primary);
}

.audit-log__page-btn--active:hover {
  background: var(--accent-hover);
  color: var(--text-on-accent);
}

.audit-log__page-ellipsis {
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
  padding: 0 var(--space-1);
}
</style>
