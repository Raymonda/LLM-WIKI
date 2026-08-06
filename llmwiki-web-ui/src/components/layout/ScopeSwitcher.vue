<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { ChevronDown, Users, User, Plus } from 'lucide-vue-next'
import { useRouter } from 'vue-router'

const { t } = useI18n()
const authStore = useAuthStore()
const toastStore = useToastStore()
const router = useRouter()
const dropdownOpen = ref(false)
const triggerRef = ref<HTMLElement | null>(null)
const dropdownStyle = ref<Record<string, string>>({})

const currentScope = computed(() =>
  authStore.scopes.find(s => s.scopeId === authStore.scopeId)
)

const isTeamMode = computed(() => currentScope.value?.scopeType === 'team')

const otherScopes = computed(() =>
  authStore.scopes.filter(s => s.scopeId !== authStore.scopeId)
)

async function toggleDropdown() {
  if (!dropdownOpen.value) {
    dropdownOpen.value = true
    await nextTick()
    if (triggerRef.value) {
      const rect = triggerRef.value.getBoundingClientRect()
      dropdownStyle.value = {
        left: `${rect.left}px`,
        bottom: `${window.innerHeight - rect.top + 4}px`,
        width: `${rect.width}px`,
      }
    }
  } else {
    dropdownOpen.value = false
  }
}

function handleClickOutside(e: MouseEvent) {
  if (!dropdownOpen.value) return
  const target = e.target as Node
  const switcher = triggerRef.value?.parentElement
  if (switcher && !switcher.contains(target)) {
    dropdownOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})

function selectScope(scopeId: number) {
  const targetScope = authStore.scopes.find(s => s.scopeId === scopeId)
  authStore.switchScope(scopeId)
  dropdownOpen.value = false
  router.push('/')
  toastStore.info(t('common.switchedTo'), targetScope?.scopeName || t('common.newWorkspace'))
}

function goToTeamManagement() {
  dropdownOpen.value = false
  router.push('/scope/manage')
}
</script>

<template>
  <div class="scope-switcher" :class="{ 'scope-switcher--team': isTeamMode }">
    <button ref="triggerRef" class="scope-switcher__trigger" @click="toggleDropdown">
      <component :is="isTeamMode ? Users : User" :size="16" class="scope-switcher__icon" />
      <span class="scope-switcher__name" :title="currentScope?.scopeName">{{ currentScope?.scopeName || t('common.personalKB') }}</span>
      <span class="scope-switcher__type-tag" :class="isTeamMode ? 'scope-switcher__type-tag--team' : 'scope-switcher__type-tag--personal'">
        {{ isTeamMode ? t('common.team') : t('common.personal') }}
      </span>
      <ChevronDown :size="14" class="scope-switcher__arrow" :class="{ 'scope-switcher__arrow--open': dropdownOpen }" />
    </button>
    <Transition name="dropdown">
      <div v-if="dropdownOpen" class="scope-switcher__dropdown" :style="dropdownStyle">
        <div class="scope-switcher__current">
          <component :is="isTeamMode ? Users : User" :size="14" />
          <span class="scope-switcher__dropdown-name" :title="currentScope?.scopeName">{{ currentScope?.scopeName || t('common.personalKB') }}</span>
          <span class="scope-switcher__type-tag" :class="isTeamMode ? 'scope-switcher__type-tag--team' : 'scope-switcher__type-tag--personal'">
            {{ isTeamMode ? t('common.team') : t('common.personal') }}
          </span>
        </div>
        <div class="scope-switcher__divider"></div>
        <template v-if="otherScopes.length > 0">
          <button
            v-for="scope in otherScopes"
            :key="scope.scopeId"
            class="scope-switcher__option"
            @click="selectScope(scope.scopeId)"
          >
            <component :is="scope.scopeType === 'team' ? Users : User" :size="14" />
            <span class="scope-switcher__dropdown-name" :title="scope.scopeName">{{ scope.scopeName }}</span>
            <span class="scope-switcher__type-tag" :class="scope.scopeType === 'team' ? 'scope-switcher__type-tag--team' : 'scope-switcher__type-tag--personal'">
              {{ scope.scopeType === 'team' ? t('common.team') : t('common.personal') }}
            </span>
          </button>
        </template>
        <div v-else class="scope-switcher__empty">
          {{ t('common.noOtherScopes') }}
        </div>
        <div class="scope-switcher__divider"></div>
        <button class="scope-switcher__manage" @click="goToTeamManagement">
          <Plus :size="14" />
          <span>{{ t('common.manageTeams') }}</span>
        </button>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.scope-switcher {
  position: relative;
}

.scope-switcher--team .scope-switcher__trigger {
  border-color: var(--accent-primary);
  background: var(--accent-light);
}

.scope-switcher__trigger {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  height: 36px;
  padding: 0 var(--space-3);
  border-radius: var(--radius-md);
  background: none;
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  cursor: pointer;
  width: 100%;
  transition: all var(--transition-fast);
}

.scope-switcher__trigger:hover {
  background: var(--sidebar-item-hover);
  color: var(--text-primary);
  border-color: var(--border-default);
}

.scope-switcher--team .scope-switcher__trigger:hover {
  background: var(--accent-light);
  border-color: var(--accent-primary);
}

.scope-switcher__icon {
  flex-shrink: 0;
  color: var(--text-tertiary);
  transition: color var(--transition-fast);
}

.scope-switcher__trigger:hover .scope-switcher__icon {
  color: var(--text-secondary);
}

.scope-switcher__name {
  flex: 1;
  min-width: 0;
  font-size: var(--font-body-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scope-switcher__arrow {
  flex-shrink: 0;
  color: var(--text-tertiary);
  transition: transform var(--transition-fast);
}

.scope-switcher__arrow--open {
  transform: rotate(180deg);
}

.scope-switcher__type-tag {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: var(--weight-medium);
  line-height: 1;
  padding: 2px var(--space-1);
  border-radius: var(--radius-sm);
  white-space: nowrap;
}

.scope-switcher__type-tag--personal {
  background: var(--bg-muted);
  color: var(--text-tertiary);
}

.scope-switcher__type-tag--team {
  background: var(--accent-primary);
  color: var(--text-on-accent);
}

.scope-switcher__role-badge {
  flex-shrink: 0;
  font-size: 10px;
  line-height: 1;
  padding: 1px var(--space-1);
  border-radius: var(--radius-sm);
  background: var(--bg-muted);
  color: var(--text-tertiary);
  transition: background var(--transition-fast), color var(--transition-fast);
}

.scope-switcher__role-badge--trigger {
  max-width: 56px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scope-switcher__trigger:hover .scope-switcher__role-badge--trigger {
  background: var(--bg-subtle);
  color: var(--text-secondary);
}

.scope-switcher__dropdown {
  position: fixed;
  bottom: auto;
  background: var(--bg-elevated);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  z-index: 2000;
  padding: var(--space-2);
  min-width: 240px;
  max-height: 320px;
  overflow-y: auto;
}

.scope-switcher__current {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2);
  color: var(--accent-primary);
  font-size: var(--font-body-sm);
  border-radius: var(--radius-sm);
  background: var(--accent-light);
}

.scope-switcher__dropdown-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scope-switcher__divider {
  height: 1px;
  background: var(--border-subtle);
  margin: var(--space-1) 0;
}

.scope-switcher__option {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2);
  border-radius: var(--radius-sm);
  background: none;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  width: 100%;
  font-size: var(--font-body-sm);
  transition: all var(--transition-fast);
}

.scope-switcher__option:hover {
  background: var(--sidebar-item-hover);
  color: var(--text-primary);
}

.scope-switcher__empty {
  padding: var(--space-2) var(--space-3);
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
  text-align: center;
}

.scope-switcher__manage {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2);
  border-radius: var(--radius-sm);
  background: none;
  border: none;
  color: var(--accent-primary);
  cursor: pointer;
  width: 100%;
  font-size: var(--font-body-sm);
  transition: all var(--transition-fast);
}

.scope-switcher__manage:hover {
  background: var(--accent-light);
}

.dropdown-enter-active,
.dropdown-leave-active {
  transition: all var(--transition-fast);
}

.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
</style>