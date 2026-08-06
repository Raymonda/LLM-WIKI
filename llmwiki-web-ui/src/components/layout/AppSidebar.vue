<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useThemeStore } from '@/stores/theme'
import { useAuthStore } from '@/stores/auth'
import { useIngestProgressStore } from '@/stores/ingestProgress'
import { useLocale } from '@/composables/useLocale'
import { SUPPORTED_LOCALES } from '@/locales'
import type { SupportedLocale } from '@/locales'
import { updateUserLanguage } from '@/api/language'
import {
  BookOpen, Search, Settings, Sun, Moon, ChevronLeft, ChevronRight, LogOut,
  Bot, Activity, Upload, Zap, ChevronDown, ChevronUp, Users, User, ShieldCheck,
  Network, Trash2, FileEdit, Rss, ScrollText, Globe, Languages
} from 'lucide-vue-next'
import ScopeSwitcher from './ScopeSwitcher.vue'

const { t } = useI18n()
const { locale, setLocale } = useLocale()

const emit = defineEmits<{
  openAiPanel: []
  openAddMaterial: []
}>()

const router = useRouter()
const themeStore = useThemeStore()
const authStore = useAuthStore()
const activityStore = useIngestProgressStore()
const collapsed = ref(false)
const settingsExpanded = ref(false)

const currentScope = computed(() =>
  authStore.scopes.find(s => s.scopeId === authStore.scopeId)
)

const isTeamMode = computed(() => currentScope.value?.scopeType === 'team')
const canEdit = computed(() => {
  if (!isTeamMode.value) return true
  const role = currentScope.value?.role
  return role === 'owner' || role === 'admin' || role === 'editor'
})
const canManage = computed(() => {
  if (!isTeamMode.value) return false
  const role = currentScope.value?.role
  return role === 'owner' || role === 'admin'
})

const mainNavItems = computed(() => {
  const items = [
    { icon: BookOpen, label: t('nav.wikiHome'), path: '/' },
    { icon: Network, label: t('nav.knowledgeGraph'), path: '/graph' },
    { icon: Search, label: t('nav.search'), path: '/search' },
    { icon: Globe, label: t('nav.plaza'), path: '/plaza' },
  ]
  if (isTeamMode.value) {
    if (canManage.value) {
      items.push({ icon: Users, label: t('nav.scopeManage'), path: '/scope/manage' })
    }
    items.push({ icon: Rss, label: t('nav.subscriptions'), path: '/scope/subscriptions' })
    if (canManage.value) {
      items.push({ icon: ScrollText, label: t('nav.auditLog'), path: '/scope/audit' })
    }
  }
  return items
})

const settingsSubItems = computed(() => {
  const items = [
    { icon: Activity, label: t('nav.harnessList'), path: '/harness' },
    { icon: ShieldCheck, label: t('nav.lint'), path: '/lint' },
    { icon: Zap, label: t('nav.tokenMonitor'), path: '/token' },
    { icon: FileEdit, label: t('nav.drafts'), path: '/drafts' },
    { icon: Trash2, label: t('nav.trash'), path: '/trash' },
  ]
  if (authStore.isSystemAdmin) {
    items.push({ icon: Settings, label: t('nav.system'), path: '/system' })
  }
  return items
})

const hasRunningActivities = computed(() => {
  return Array.from(activityStore.tasks.values()).some(
    t => t.executionId > 0 && t.currentStep !== 'upload' && t.currentStep !== 'done'
  )
})

const currentScopeName = computed(() =>
  currentScope?.value?.scopeName || t('common.personalKB')
)

const currentScopeIcon = computed(() =>
  currentScope?.value?.scopeType === 'team' ? Users : User
)

function handleLogout() {
  activityStore.clear()
  authStore.clearAuth()
  router.push('/login')
}

function handleOpenAiPanel() {
  emit('openAiPanel')
}

function handleAddMaterial() {
  emit('openAddMaterial')
}

const langDropdownOpen = ref(false)

function handleLangSwitch(lang: SupportedLocale) {
  setLocale(lang)
  langDropdownOpen.value = false
  updateUserLanguage(lang).catch(() => {})
}
</script>

<template>
  <aside class="sidebar" :class="{ 'sidebar--collapsed': collapsed }">
    <div class="sidebar__header">
      <div v-if="!collapsed" class="sidebar__brand">
        <img src="/logo-v2.png" alt="Zilio" class="sidebar__brand-img" />
        <div class="sidebar__brand-text-group">
          <span class="sidebar__brand-text">{{ t('common.brandName') }}</span>
          <span class="sidebar__brand-tagline">{{ t('common.brandTagline') }}</span>
        </div>
      </div>
      <button class="sidebar__toggle" @click="collapsed = !collapsed">
        <ChevronLeft v-if="!collapsed" :size="16" />
        <ChevronRight v-else :size="16" />
      </button>
    </div>

    <div v-if="canEdit" class="sidebar__add-btn-wrap">
      <button class="sidebar__add-btn" @click="handleAddMaterial" :title="collapsed ? t('common.addMaterial') : ''">
        <Upload :size="18" />
        <span v-if="!collapsed">{{ t('common.addMaterial') }}</span>
      </button>
    </div>

    <nav class="sidebar__nav">
      <RouterLink
        v-for="item in mainNavItems"
        :key="item.path"
        :to="item.path"
        class="sidebar__nav-item"
        :title="item.label"
      >
        <div class="sidebar__nav-icon-wrap">
          <component :is="item.icon" :size="20" />
          <span
            v-if="hasRunningActivities && item.path === '/'"
            class="sidebar__badge sidebar__badge--running"
          ></span>
        </div>
        <span v-if="!collapsed" class="sidebar__nav-label">{{ item.label }}</span>
      </RouterLink>

      <div class="sidebar__settings-group">
        <button
          class="sidebar__nav-item sidebar__settings-toggle"
          @click="settingsExpanded = !settingsExpanded"
          :title="collapsed ? t('nav.settings') : ''"
        >
          <Settings :size="20" />
          <span v-if="!collapsed" class="sidebar__nav-label">{{ t('nav.settings') }}</span>
          <component
            v-if="!collapsed"
            :is="settingsExpanded ? ChevronUp : ChevronDown"
            :size="14"
            class="sidebar__settings-arrow"
          />
        </button>
        <Transition name="settings-sub">
          <div v-if="settingsExpanded && !collapsed" class="sidebar__settings-sub">
            <RouterLink
              v-for="subItem in settingsSubItems"
              :key="subItem.path"
              :to="subItem.path"
              class="sidebar__nav-item sidebar__nav-item--sub"
            >
              <component :is="subItem.icon" :size="16" />
              <span class="sidebar__nav-label">{{ subItem.label }}</span>
            </RouterLink>
          </div>
        </Transition>
      </div>
    </nav>

    <div class="sidebar__footer">
      <ScopeSwitcher v-if="!collapsed" />
      <button
        v-else
        class="sidebar__scope-icon"
        :title="currentScopeName"
        @click="collapsed = false"
      >
        <component :is="currentScopeIcon" :size="18" />
      </button>
      <div class="sidebar__footer-actions">
      <button class="sidebar__ai-btn" @click="handleOpenAiPanel" :title="collapsed ? t('common.aiAssistant') : ''">
        <Bot :size="18" />
        <span v-if="!collapsed">{{ t('common.aiAssistant') }}</span>
      </button>
      <div class="sidebar__lang-wrap">
        <button class="sidebar__theme-toggle" @click="langDropdownOpen = !langDropdownOpen" :title="collapsed ? t('common.language') : ''">
          <Languages :size="18" />
          <span v-if="!collapsed">{{ locale === 'en' ? 'EN' : '中文' }}</span>
        </button>
        <Transition name="settings-sub">
          <div v-if="langDropdownOpen && !collapsed" class="sidebar__lang-dropdown">
            <button
              v-for="item in SUPPORTED_LOCALES"
              :key="item.value"
              class="sidebar__lang-option"
              :class="{ 'sidebar__lang-option--active': locale === item.value }"
              @click="handleLangSwitch(item.value)"
            >
              {{ item.label }}
            </button>
          </div>
        </Transition>
      </div>
      <button class="sidebar__theme-toggle" @click="themeStore.toggleTheme()" :title="collapsed ? t('common.darkMode') : ''">
        <Sun v-if="themeStore.theme === 'dark'" :size="18" />
        <Moon v-else :size="18" />
        <span v-if="!collapsed">{{ themeStore.theme === 'dark' ? t('common.lightMode') : t('common.darkMode') }}</span>
      </button>
      <button class="sidebar__logout" @click="handleLogout" :title="collapsed ? t('common.logout') : ''">
        <LogOut :size="18" />
        <span v-if="!collapsed">{{ t('common.logout') }}</span>
      </button>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: var(--sidebar-width);
  background: var(--sidebar-bg);
  border-right: 1px solid var(--sidebar-border);
  display: flex;
  flex-direction: column;
  transition: width var(--transition-normal);
  overflow: hidden;
}

.sidebar--collapsed {
  width: var(--sidebar-collapsed);
}

.sidebar__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4);
  min-height: var(--topbar-height);
  border-bottom: 1px solid var(--border-subtle);
}

.sidebar__brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.sidebar__brand-img {
  height: 28px;
  width: auto;
  object-fit: contain;
  vertical-align: middle;
}

.sidebar__brand-text-group {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.sidebar__brand-text {
  font-size: 18px;
  font-weight: var(--weight-bold);
  color: var(--text-primary);
  line-height: 1.3;
  letter-spacing: 0.02em;
}

.sidebar__brand-tagline {
  font-size: 11px;
  font-weight: var(--weight-normal);
  color: var(--text-tertiary);
  letter-spacing: 0.04em;
}

.sidebar__toggle {
  background: none;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: var(--space-1);
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
}

.sidebar__toggle:hover {
  background: var(--sidebar-item-hover);
  color: var(--text-primary);
}

.sidebar__add-btn-wrap {
  padding: var(--space-2) var(--space-2) 0;
}

.sidebar__add-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-2) var(--space-3);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: opacity var(--transition-fast);
}

.sidebar__add-btn:hover {
  opacity: 0.9;
}

.sidebar__nav {
  flex: 1;
  padding: var(--space-2);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.sidebar__nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  text-decoration: none;
  transition: all var(--transition-fast);
  white-space: nowrap;
  cursor: pointer;
  background: none;
  border: none;
  width: 100%;
  font-size: var(--font-body);
}

.sidebar__nav-item:hover {
  background: var(--sidebar-item-hover);
  color: var(--text-primary);
}

.sidebar__nav-item.router-link-active {
  background: var(--sidebar-item-active-bg);
  color: var(--sidebar-item-active-text);
}

.sidebar__nav-item--sub {
  padding: var(--space-1) var(--space-3) var(--space-1) var(--space-6);
  font-size: var(--font-body-sm);
}

.sidebar__nav-icon-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.sidebar__badge {
  position: absolute;
  top: -2px;
  right: -4px;
  width: 8px;
  height: 8px;
  border-radius: var(--radius-full);
}

.sidebar__badge--running {
  background: var(--warning);
  animation: pulse 2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.6; transform: scale(1.3); }
}

.sidebar__nav-label {
  flex: 1;
}

.sidebar__settings-group {
  display: flex;
  flex-direction: column;
}

.sidebar__settings-toggle {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
  background: none;
  border: none;
  width: 100%;
  font-size: var(--font-body);
}

.sidebar__settings-toggle:hover {
  background: var(--sidebar-item-hover);
  color: var(--text-primary);
}

.sidebar__settings-arrow {
  color: var(--text-tertiary);
  margin-left: auto;
}

.sidebar__settings-sub {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.settings-sub-enter-active,
.settings-sub-leave-active {
  transition: all var(--transition-fast);
}

.settings-sub-enter-from,
.settings-sub-leave-to {
  opacity: 0;
  max-height: 0;
}

.sidebar__footer {
  padding: var(--space-2);
  border-top: 1px solid var(--border-subtle);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.sidebar__scope-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-2);
  border-radius: var(--radius-md);
  background: none;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.sidebar__scope-icon:hover {
  background: var(--sidebar-item-hover);
  color: var(--text-primary);
}

.sidebar__ai-btn,
.sidebar__theme-toggle,
.sidebar__logout {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  background: none;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
  width: 100%;
}

.sidebar__ai-btn:hover {
  background: var(--accent-light);
  color: var(--accent-primary);
}

.sidebar__theme-toggle:hover,
.sidebar__logout:hover {
  background: var(--sidebar-item-hover);
  color: var(--text-primary);
}

[data-theme="dark"] .sidebar__brand-img {
  filter: drop-shadow(0 0 6px rgba(255, 255, 255, 0.12));
}

.sidebar__lang-wrap {
  position: relative;
}

.sidebar__lang-dropdown {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--space-1) 0;
}

.sidebar__lang-option {
  display: flex;
  align-items: center;
  padding: var(--space-1) var(--space-3) var(--space-1) var(--space-8);
  border: none;
  background: none;
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
  white-space: nowrap;
  width: 100%;
}

.sidebar__lang-option:hover {
  background: var(--sidebar-item-hover);
  color: var(--text-primary);
}

.sidebar__lang-option--active {
  color: var(--accent-primary);
  font-weight: var(--weight-medium);
}
</style>