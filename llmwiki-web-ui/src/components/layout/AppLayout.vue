<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AppSidebar from './AppSidebar.vue'
import AppTopBar from './AppTopBar.vue'
// AI 助手面板已下线：原功能无对应后端实现，保留组件文件（AiAssistantPanel.vue / stores/execution.ts）待重新规划后再开放
import IngestProgressFloating from './IngestProgressFloating.vue'
import TaskProgressFloating from './TaskProgressFloating.vue'
import SchemaBootstrapDialog from '@/components/common/SchemaBootstrapDialog.vue'
import { getBootstrapStatus } from '@/api/harness'
import { useAuthStore } from '@/stores/auth'

import { useIngestProgressStore } from '@/stores/ingestProgress'
import { useTaskProgressStore } from '@/stores/taskProgress'

const router = useRouter()
const { t } = useI18n()
const auth = useAuthStore()
const ingestStore = useIngestProgressStore()
const taskStore = useTaskProgressStore()
const bootstrapOpen = ref(false)
const aiNotConfigured = ref(false)

function openAddMaterial() {
  if (router.currentRoute.value.path === '/ingest') {
    const hasRunning = ingestStore.allTaskSummaries.some(
      t => t.currentStep !== 'upload' && t.currentStep !== 'done'
    )
    if (hasRunning) {
      ingestStore.setActiveTask(null)
    } else {
      ingestStore.startNewSource()
    }
  } else {
    router.push('/ingest')
  }
}

async function checkBootstrap() {
  if (!auth.scopeId || auth.scopeId <= 0) return
  try {
    const status = await getBootstrapStatus()
    if (!status.aiConfigured) {
      aiNotConfigured.value = true
      bootstrapOpen.value = false
      return
    }
    aiNotConfigured.value = false
    bootstrapOpen.value = !!status.required
  } catch {
    bootstrapOpen.value = false
    aiNotConfigured.value = false
  }
}

function onBootstrapDone() {
  bootstrapOpen.value = false
}

onMounted(async () => {
  await checkBootstrap()
  if (auth.scopeId && auth.scopeId > 0) {
    await ingestStore.recoverActiveTasks(auth.scopeId)
    await taskStore.recoverActiveTasks()
  }
})
watch(() => auth.scopeId, async (newScopeId) => {
  await checkBootstrap()
  if (newScopeId && newScopeId > 0) {
    await ingestStore.recoverActiveTasks(newScopeId)
    await taskStore.recoverActiveTasks()
  }
})
</script>

<template>
  <div class="app-layout">
    <AppSidebar @open-add-material="openAddMaterial" />
    <div class="app-layout__main">
      <AppTopBar />
      <div class="app-layout__content">
        <RouterView v-slot="{ Component }">
          <Transition name="scope-switch" mode="out-in">
            <KeepAlive :include="['SearchView']">
              <component :is="Component" :key="auth.scopeId" />
            </KeepAlive>
          </Transition>
        </RouterView>
      </div>
    </div>
    <!-- AI 助手（AiAssistantPanel）已下线：无对应后端实现，待重新规划后再开放 -->
    <IngestProgressFloating />
    <TaskProgressFloating :ingest-floating-active="ingestStore.active" />
    <SchemaBootstrapDialog :open="bootstrapOpen" @done="onBootstrapDone" />

    <Transition name="ai-warn">
      <div v-if="aiNotConfigured" class="ai-config-warning">
        <div class="ai-config-warning__inner">
          <svg class="ai-config-warning__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="20" height="20">
            <path d="M12 9v4M12 17h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
          </svg>
          <div class="ai-config-warning__content">
            <p class="ai-config-warning__title">{{ t('common.aiConfigWarningTitle') }}</p>
            <p class="ai-config-warning__desc">{{ t('common.aiConfigWarningDescPre') }}<code>AI_DASHSCOPE_API_KEY</code>{{ t('common.aiConfigWarningDescMid') }}<code>docker-compose restart app</code>{{ t('common.aiConfigWarningDescPost') }}</p>
          </div>
          <button class="ai-config-warning__close" @click="aiNotConfigured = false">×</button>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--bg-primary);
}

.app-layout__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.app-layout__content {
  flex: 1;
  padding: var(--space-6);
  overflow-y: auto;
}

.scope-switch-enter-active {
  transition: opacity 0.15s ease-out;
}

.scope-switch-leave-active {
  transition: opacity 0.1s ease-in;
}

.scope-switch-enter-from,
.scope-switch-leave-to {
  opacity: 0;
}

.ai-config-warning {
  position: fixed;
  top: var(--space-4);
  left: 50%;
  transform: translateX(-50%);
  z-index: 1000;
  max-width: 600px;
  width: calc(100% - var(--space-8));
}

.ai-config-warning__inner {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  background: var(--error-bg, #fef2f2);
  border: 1px solid var(--error, #ef4444);
  border-radius: var(--radius-lg);
  padding: var(--space-4);
  box-shadow: var(--shadow-md);
}

.ai-config-warning__icon {
  flex-shrink: 0;
  color: var(--error, #ef4444);
  margin-top: 2px;
}

.ai-config-warning__content {
  flex: 1;
}

.ai-config-warning__title {
  font-size: var(--font-body);
  font-weight: var(--weight-semibold);
  color: var(--error, #ef4444);
  margin-bottom: var(--space-1);
}

.ai-config-warning__desc {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
  line-height: 1.5;
}

.ai-config-warning__desc code {
  background: var(--bg-secondary);
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 0.85em;
}

.ai-config-warning__close {
  flex-shrink: 0;
  background: none;
  border: none;
  color: var(--text-tertiary);
  font-size: 20px;
  cursor: pointer;
  padding: 0;
  line-height: 1;
}

.ai-warn-enter-active {
  transition: opacity 0.3s ease, transform 0.3s ease;
}

.ai-warn-leave-active {
  transition: opacity 0.2s ease;
}

.ai-warn-enter-from {
  opacity: 0;
  transform: translateX(-50%) translateY(-20px);
}

.ai-warn-leave-to {
  opacity: 0;
}
</style>