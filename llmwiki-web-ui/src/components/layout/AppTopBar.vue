<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useActivityCenterStore } from '@/stores/activityCenter'
import { Search, Activity, GitPullRequest } from 'lucide-vue-next'
import { getUnreadCount } from '@/api/notification'
import { countPendingPatches } from '@/api/harness'
import KnowledgePulsePanel from './KnowledgePulsePanel.vue'
import SchemaPatchDrawer from '@/components/common/SchemaPatchDrawer.vue'

const { t } = useI18n()

// AI 助手开关（topbar__ai-toggle）已下线：无对应后端实现，待重新规划后再开放

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const activityStore = useActivityCenterStore()

const searchQuery = ref('')
const patchCount = ref(0)
const patchDrawerOpen = ref(false)

const isSearchPage = computed(() => route.path === '/search')

const currentScope = computed(() =>
  authStore.scopes.find(s => s.scopeId === authStore.scopeId)
)
const isTeamMode = computed(() => currentScope.value?.scopeType === 'team')

let pollTimer: number | null = null
let patchPollTimer: number | null = null

async function refreshPatchCount() {
  try {
    const { pending, conflictRulings } = await countPendingPatches()
    patchCount.value = pending + (conflictRulings ?? 0)
  } catch {
    // silent
  }
}

onMounted(async () => {
  try {
    activityStore.unreadCount = await getUnreadCount()
  } catch {
    activityStore.unreadCount = 0
  }
  pollTimer = window.setInterval(async () => {
    try {
      activityStore.unreadCount = await getUnreadCount()
    } catch {
      // silent
    }
  }, 30000)
  refreshPatchCount()
  patchPollTimer = window.setInterval(refreshPatchCount, 30000)

  window.addEventListener('open-patch-drawer', () => {
    patchDrawerOpen.value = true
  })
})

onUnmounted(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  if (patchPollTimer) {
    clearInterval(patchPollTimer)
    patchPollTimer = null
  }
})

function handleSearch() {
  const q = searchQuery.value.trim()
  if (!q) return
  router.push({ path: '/search', query: { q } })
}

function handleClickOutside() {
  if (activityStore.panelOpen) {
    activityStore.closePanel()
  }
}

function onPatchApplied() {
  refreshPatchCount()
}
</script>

<template>
  <header class="topbar" @click="handleClickOutside">
    <div class="topbar__workspace">
      <span
        class="topbar__workspace-tag"
        :class="isTeamMode ? 'topbar__workspace-tag--team' : 'topbar__workspace-tag--personal'"
      >
        {{ isTeamMode ? t('common.team') : t('common.personal') }}
      </span>
      <span class="topbar__workspace-name" :title="currentScope?.scopeName">
        {{ currentScope?.scopeName || t('common.personalKB') }}
      </span>
      <span v-if="currentScope?.role" class="topbar__workspace-role">{{ currentScope.role }}</span>
    </div>
    <div class="topbar__workspace-divider"></div>
    <div v-if="isSearchPage" class="topbar__page-title">{{ t('nav.search') }}</div>
    <div v-else class="topbar__search">
      <Search :size="16" class="topbar__search-icon" />
      <input
        v-model="searchQuery"
        type="text"
        :placeholder="t('common.searchPlaceholder')"
        class="topbar__search-input"
        @keyup.enter="handleSearch"
      />
    </div>
    <div class="topbar__actions">
      <div class="topbar__pulse-wrap" @click.stop>
        <button
          class="topbar__pulse-btn"
          :class="{ 'topbar__pulse-btn--unread': activityStore.badgeCount > 0 }"
          @click="activityStore.togglePanel"
          :title="t('common.knowledgePulse')"
        >
          <Activity :size="18" />
          <span v-if="activityStore.badgeCount > 0" class="topbar__pulse-badge">{{ activityStore.badgeCount }}</span>
          <span v-if="activityStore.badgeCount > 0" class="topbar__pulse-ring"></span>
        </button>
        <KnowledgePulsePanel />
      </div>
      <button
        class="topbar__pulse-btn"
        :class="{ 'topbar__patch-btn--has': patchCount > 0 }"
        @click.stop="patchDrawerOpen = true"
        :title="t('common.schemaPatch')"
      >
        <GitPullRequest :size="18" />
        <span v-if="patchCount > 0" class="topbar__pulse-badge">{{ patchCount }}</span>
      </button>
    </div>
    <SchemaPatchDrawer :open="patchDrawerOpen" @close="patchDrawerOpen = false" @applied="onPatchApplied" />
  </header>
</template>

<style scoped>
.topbar {
  height: var(--topbar-height);
  background: var(--topbar-bg);
  border-bottom: 1px solid var(--topbar-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-6);
}

.topbar__workspace {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

.topbar__workspace-tag {
  font-size: 10px;
  font-weight: var(--weight-semibold);
  line-height: 1;
  padding: 3px var(--space-2);
  border-radius: var(--radius-sm);
  white-space: nowrap;
}

.topbar__workspace-tag--personal {
  background: var(--bg-muted);
  color: var(--text-tertiary);
}

.topbar__workspace-tag--team {
  background: var(--accent-primary);
  color: var(--text-on-accent);
}

.topbar__workspace-name {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar__workspace-role {
  font-size: 10px;
  color: var(--text-tertiary);
  white-space: nowrap;
}

.topbar__workspace-divider {
  width: 1px;
  height: 20px;
  background: var(--border-default);
  flex-shrink: 0;
  margin: 0 var(--space-2);
}

.topbar__page-title {
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.topbar__search {
  display: flex;
  align-items: center;
  background: var(--search-bg);
  border: 1px solid var(--search-border);
  border-radius: var(--radius-pill);
  padding: var(--space-1) var(--space-4);
  width: 320px;
  transition: border-color var(--transition-fast);
}

.topbar__search:focus-within {
  border-color: var(--input-focus-border);
}

.topbar__search-icon {
  color: var(--text-tertiary);
  margin-right: var(--space-2);
}

.topbar__search-input {
  background: none;
  border: none;
  outline: none;
  color: var(--text-primary);
  font-size: var(--font-body);
  width: 100%;
}

.topbar__search-input::placeholder {
  color: var(--text-tertiary);
}

.topbar__actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.topbar__pulse-wrap {
  position: relative;
}

.topbar__pulse-btn {
  background: none;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  cursor: pointer;
  padding: var(--space-2);
  display: flex;
  align-items: center;
  position: relative;
  transition: all var(--transition-fast);
}

.topbar__pulse-btn:hover {
  background: var(--accent-light);
  color: var(--accent-primary);
  border-color: var(--accent-primary);
}

.topbar__pulse-btn--unread {
  color: var(--accent-primary);
  border-color: var(--accent-primary);
  background: var(--accent-light);
}

.topbar__patch-btn--has {
  color: var(--accent-primary);
  border-color: var(--accent-primary);
  background: var(--accent-light);
}

.topbar__pulse-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  background: var(--error);
  color: var(--text-on-accent);
  font-size: 10px;
  font-weight: var(--weight-bold);
  min-width: 16px;
  height: 16px;
  border-radius: var(--radius-full);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 4px;
}

.topbar__pulse-ring {
  position: absolute;
  inset: -3px;
  border-radius: var(--radius-md);
  border: 2px solid var(--accent-primary);
  opacity: 0.5;
  animation: topbar-pulse-ring 2s ease-in-out infinite;
  pointer-events: none;
}

@keyframes topbar-pulse-ring {
  0%, 100% { opacity: 0.3; transform: scale(1); }
  50% { opacity: 0.7; transform: scale(1.04); }
}
</style>