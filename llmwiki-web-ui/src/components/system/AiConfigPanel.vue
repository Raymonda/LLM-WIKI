<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToastStore } from '@/stores/toast'
import { ChevronRight, Loader2, Plus, Trash2, PlugZap } from 'lucide-vue-next'
import {
  getAiRuntimeConfig,
  saveAiRuntimeConfig,
  testAiConnection,
  type AiProviderInput,
  type AiSlotInput,
} from '@/api/system'

const { t } = useI18n()
const toastStore = useToastStore()

const BASIC_SLOTS = ['main', 'multimodal', 'ocr']
const OVERRIDE_SLOTS = ['deep-analysis', 'diagram']
const MULTIMODAL_TOGGLE_SLOTS = new Set(['main', 'deep-analysis'])
const PROVIDER_NAME_PATTERN = /^[a-z0-9][a-z0-9-]{0,31}$/
const DEFAULT_PROVIDER_NAME = 'dashscope'
const DEFAULT_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode'

interface ProviderRow {
  name: string
  baseUrl: string
  apiKey: string
  enabled: boolean
  apiKeyConfigured: boolean
  apiKeyMasked: string
}

interface SlotRow {
  slot: string
  provider: string
  model: string
  multimodal: boolean
}

interface TestState {
  testing: boolean
  ok: boolean | null
  latencyMs: number
  message: string
}

const loading = ref(true)
const saving = ref(false)
const providers = ref<ProviderRow[]>([])
const basicSlots = ref<SlotRow[]>([])
const overrideSlots = ref<SlotRow[]>([])
const showOverrides = ref(false)
const providerTests = ref<TestState[]>([])
const slotTests = ref<Record<string, TestState>>({})

function newTestState(): TestState {
  return { testing: false, ok: null, latencyMs: 0, message: '' }
}

function slotLabel(slot: string): string {
  switch (slot) {
    case 'main': return t('system.slotMain')
    case 'multimodal': return t('system.slotMultimodal')
    case 'deep-analysis': return t('system.slotDeepAnalysis')
    case 'ocr': return t('system.slotOcr')
    case 'diagram': return t('system.slotDiagram')
    default: return slot
  }
}

function slotHint(slot: string): string {
  switch (slot) {
    case 'main': return t('system.slotMainHint')
    case 'multimodal': return t('system.slotMultimodalHint')
    case 'deep-analysis': return t('system.slotDeepAnalysisHint')
    case 'ocr': return t('system.slotOcrHint')
    case 'diagram': return t('system.slotDiagramHint')
    default: return ''
  }
}

function modelPlaceholder(slot: string): string {
  switch (slot) {
    case 'main': return t('system.slotModelPlaceholderMain')
    case 'multimodal': return t('system.slotModelPlaceholderMultimodal')
    case 'ocr': return t('system.slotModelPlaceholderOcr')
    default: return t('system.modelName')
  }
}

async function load() {
  loading.value = true
  try {
    const view = await getAiRuntimeConfig()
    providers.value = view.providers.map(p => ({
      name: p.name,
      baseUrl: p.baseUrl,
      apiKey: '',
      enabled: p.enabled,
      apiKeyConfigured: p.apiKeyConfigured,
      apiKeyMasked: p.apiKeyMasked,
    }))
    const bySlot = new Map(view.slots.map(s => [s.slot, s]))
    const toRow = (slot: string): SlotRow => {
      const existing = bySlot.get(slot)
      return {
        slot,
        provider: existing?.provider ?? '',
        model: existing?.model ?? '',
        multimodal: existing?.multimodal ?? false,
      }
    }
    basicSlots.value = BASIC_SLOTS.map(toRow)
    overrideSlots.value = OVERRIDE_SLOTS.map(toRow)
    if (providers.value.length === 0) {
      providers.value.push({
        name: DEFAULT_PROVIDER_NAME,
        baseUrl: DEFAULT_BASE_URL,
        apiKey: '',
        enabled: true,
        apiKeyConfigured: false,
        apiKeyMasked: '',
      })
      basicSlots.value.forEach(s => { s.provider = DEFAULT_PROVIDER_NAME })
    }
    providerTests.value = providers.value.map(() => newTestState())
    slotTests.value = {}
  } catch (e: any) {
    toastStore.error(t('system.loadFailed'), e?.message)
  } finally {
    loading.value = false
  }
}

function addProvider() {
  providers.value.push({ name: '', baseUrl: '', apiKey: '', enabled: true, apiKeyConfigured: false, apiKeyMasked: '' })
  providerTests.value.push(newTestState())
}

function removeProvider(index: number) {
  const removed = providers.value[index]
  providers.value.splice(index, 1)
  providerTests.value.splice(index, 1)
  if (removed) {
    basicSlots.value.forEach(s => { if (s.provider === removed.name) s.provider = '' })
    overrideSlots.value.forEach(s => { if (s.provider === removed.name) s.provider = '' })
  }
}

function apiKeyPlaceholder(p: ProviderRow): string {
  return p.apiKeyConfigured ? `${p.apiKeyMasked} · ${t('system.apiKeyKeepHint')}` : t('system.apiKeyPlaceholder')
}

async function testProvider(index: number) {
  const p = providers.value[index]
  const state = providerTests.value[index]
  if (!p || !state || state.testing) return
  state.testing = true
  state.ok = null
  try {
    const res = await testAiConnection({ baseUrl: p.baseUrl, apiKey: p.apiKey, providerName: p.name, model: '' })
    state.ok = res.ok
    state.latencyMs = res.latencyMs
    state.message = res.message
  } catch (e: any) {
    state.ok = false
    state.latencyMs = 0
    state.message = e?.message || ''
  } finally {
    state.testing = false
  }
}

async function testSlot(slot: SlotRow) {
  const p = providers.value.find(x => x.name === slot.provider)
  if (!p) return
  const state = (slotTests.value[slot.slot] ??= newTestState())
  if (state.testing) return
  state.testing = true
  state.ok = null
  try {
    const res = await testAiConnection({ baseUrl: p.baseUrl, apiKey: p.apiKey, providerName: p.name, model: slot.model })
    state.ok = res.ok
    state.latencyMs = res.latencyMs
    state.message = res.message
  } catch (e: any) {
    state.ok = false
    state.latencyMs = 0
    state.message = e?.message || ''
  } finally {
    state.testing = false
  }
}

async function save() {
  for (const p of providers.value) {
    if (!PROVIDER_NAME_PATTERN.test(p.name)) {
      toastStore.warning(t('system.providerNameInvalid'))
      return
    }
  }
  const enabledNames = new Set(providers.value.filter(p => p.enabled).map(p => p.name))
  const mainSlot = basicSlots.value.find(s => s.slot === 'main')
  if (!mainSlot || !enabledNames.has(mainSlot.provider)) {
    toastStore.warning(t('system.mainSlotRequired'))
    return
  }
  saving.value = true
  try {
    const payloadProviders: AiProviderInput[] = providers.value.map(p => ({
      name: p.name,
      baseUrl: p.baseUrl,
      apiKey: p.apiKey,
      enabled: p.enabled,
    }))
    const payloadSlots: AiSlotInput[] = [...basicSlots.value, ...overrideSlots.value]
      .filter(s => s.provider)
      .map(s => ({ slot: s.slot, provider: s.provider, model: s.model, multimodal: s.multimodal }))
    await saveAiRuntimeConfig({ providers: payloadProviders, slots: payloadSlots })
    toastStore.success(t('system.aiConfigSaved'))
    await load()
  } catch (e: any) {
    toastStore.error(t('system.operationFailed'), e?.message)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="ai-config">
    <div v-if="loading" class="ai-config__loading">
      <Loader2 :size="20" class="ai-config__spin" />
      <span>{{ t('common.loading') }}</span>
    </div>

    <template v-else>
      <section class="ai-config__section">
        <h3 class="ai-config__section-title">{{ t('system.providerConfig') }}</h3>
        <div class="ai-config__table-scroll">
          <div class="ai-config__table">
            <div class="ai-config__provider-row ai-config__provider-row--head">
              <span>{{ t('system.providerName') }}</span>
              <span>{{ t('system.baseUrl') }}</span>
              <span>{{ t('system.apiKey') }}</span>
              <span>{{ t('system.enabled') }}</span>
              <span></span>
            </div>
            <div v-for="(p, i) in providers" :key="i" class="ai-config__provider-row">
              <input v-model="p.name" class="ai-config__input" :placeholder="t('system.providerName')" />
              <input v-model="p.baseUrl" class="ai-config__input" placeholder="https://" />
              <input v-model="p.apiKey" type="password" class="ai-config__input" :placeholder="apiKeyPlaceholder(p)" />
              <span class="ai-config__switch">
                <input v-model="p.enabled" type="checkbox" class="ai-config__checkbox" />
              </span>
              <span class="ai-config__actions">
                <button class="ai-config__btn-secondary" :disabled="providerTests[i]?.testing" @click="testProvider(i)">
                  <Loader2 v-if="providerTests[i]?.testing" :size="14" class="ai-config__spin" />
                  <PlugZap v-else :size="14" />
                  {{ t('system.testConnection') }}
                </button>
                <button class="ai-config__btn-danger" :title="t('system.deleteProvider')" @click="removeProvider(i)">
                  <Trash2 :size="14" />
                </button>
              </span>
              <span
                v-if="providerTests[i] && providerTests[i].ok !== null"
                class="ai-config__test-result"
                :class="providerTests[i].ok ? 'ai-config__test-result--ok' : 'ai-config__test-result--fail'"
              >
                <template v-if="providerTests[i].ok">
                  {{ t('system.connectionOk') }} · {{ t('system.testLatency', [providerTests[i].latencyMs]) }}
                </template>
                <template v-else>
                  {{ t('system.connectionFailed') }}: {{ providerTests[i].message }}
                </template>
              </span>
            </div>
          </div>
        </div>
        <button class="ai-config__btn-secondary" @click="addProvider">
          <Plus :size="14" />
          {{ t('system.addProvider') }}
        </button>
      </section>

      <section class="ai-config__section">
        <h3 class="ai-config__section-title">{{ t('system.basicSlots') }}</h3>
        <div class="ai-config__table-scroll">
          <div class="ai-config__table">
            <div class="ai-config__slot-row ai-config__slot-row--head">
              <span>{{ t('system.slot') }}</span>
              <span>{{ t('system.providerConfig') }}</span>
              <span>{{ t('system.modelName') }}</span>
              <span>{{ t('system.slotMultimodalToggle') }}</span>
              <span></span>
            </div>
            <div v-for="s in basicSlots" :key="s.slot" class="ai-config__slot-row">
              <span class="ai-config__slot-name">{{ slotLabel(s.slot) }}</span>
              <select v-model="s.provider" class="ai-config__input">
                <option value="" disabled>{{ t('system.providerName') }}</option>
                <option v-for="p in providers" :key="p.name" :value="p.name" :disabled="!p.enabled">
                  {{ p.name }}
                </option>
              </select>
              <input v-model="s.model" class="ai-config__input" :placeholder="modelPlaceholder(s.slot)" />
              <span class="ai-config__switch">
                <input
                  v-if="MULTIMODAL_TOGGLE_SLOTS.has(s.slot)"
                  v-model="s.multimodal"
                  type="checkbox"
                  class="ai-config__checkbox"
                  :title="t('system.slotMultimodalToggle')"
                />
              </span>
              <span class="ai-config__actions">
                <button
                  class="ai-config__btn-secondary"
                  :disabled="!s.provider || slotTests[s.slot]?.testing"
                  @click="testSlot(s)"
                >
                  <Loader2 v-if="slotTests[s.slot]?.testing" :size="14" class="ai-config__spin" />
                  <PlugZap v-else :size="14" />
                  {{ t('system.testConnection') }}
                </button>
              </span>
              <span class="ai-config__slot-hint">{{ slotHint(s.slot) }}</span>
              <span
                v-if="slotTests[s.slot] && slotTests[s.slot].ok !== null"
                class="ai-config__test-result"
                :class="slotTests[s.slot].ok ? 'ai-config__test-result--ok' : 'ai-config__test-result--fail'"
              >
                <template v-if="slotTests[s.slot].ok">
                  {{ t('system.connectionOk') }} · {{ t('system.testLatency', [slotTests[s.slot].latencyMs]) }}
                </template>
                <template v-else>
                  {{ t('system.connectionFailed') }}: {{ slotTests[s.slot].message }}
                </template>
              </span>
            </div>
          </div>
        </div>

        <div class="ai-config__override">
          <button class="ai-config__override-toggle" @click="showOverrides = !showOverrides">
            <ChevronRight
              :size="14"
              class="ai-config__override-chevron"
              :class="{ 'ai-config__override-chevron--open': showOverrides }"
            />
            {{ t('system.overrideSlots') }}
          </button>
          <template v-if="showOverrides">
            <p class="ai-config__override-hint">{{ t('system.overrideSlotsHint') }}</p>
            <div class="ai-config__table-scroll">
              <div class="ai-config__table">
                <div class="ai-config__slot-row ai-config__slot-row--head">
                  <span>{{ t('system.slot') }}</span>
                  <span>{{ t('system.providerConfig') }}</span>
                  <span>{{ t('system.modelName') }}</span>
                  <span>{{ t('system.slotMultimodalToggle') }}</span>
                  <span></span>
                </div>
                <div v-for="s in overrideSlots" :key="s.slot" class="ai-config__slot-row">
                  <span class="ai-config__slot-name">{{ slotLabel(s.slot) }}</span>
                  <select v-model="s.provider" class="ai-config__input">
                    <option value="">{{ t('system.slotFollowMain') }}</option>
                    <option v-for="p in providers" :key="p.name" :value="p.name" :disabled="!p.enabled">
                      {{ p.name }}
                    </option>
                  </select>
                  <input v-model="s.model" class="ai-config__input" :placeholder="modelPlaceholder(s.slot)" />
                  <span class="ai-config__switch">
                    <input
                      v-if="MULTIMODAL_TOGGLE_SLOTS.has(s.slot)"
                      v-model="s.multimodal"
                      type="checkbox"
                      class="ai-config__checkbox"
                      :title="t('system.slotMultimodalToggle')"
                    />
                  </span>
                  <span class="ai-config__actions">
                    <button
                      class="ai-config__btn-secondary"
                      :disabled="!s.provider || slotTests[s.slot]?.testing"
                      @click="testSlot(s)"
                    >
                      <Loader2 v-if="slotTests[s.slot]?.testing" :size="14" class="ai-config__spin" />
                      <PlugZap v-else :size="14" />
                      {{ t('system.testConnection') }}
                    </button>
                  </span>
                  <span class="ai-config__slot-hint">{{ slotHint(s.slot) }}</span>
                  <span
                    v-if="slotTests[s.slot] && slotTests[s.slot].ok !== null"
                    class="ai-config__test-result"
                    :class="slotTests[s.slot].ok ? 'ai-config__test-result--ok' : 'ai-config__test-result--fail'"
                  >
                    <template v-if="slotTests[s.slot].ok">
                      {{ t('system.connectionOk') }} · {{ t('system.testLatency', [slotTests[s.slot].latencyMs]) }}
                    </template>
                    <template v-else>
                      {{ t('system.connectionFailed') }}: {{ slotTests[s.slot].message }}
                    </template>
                  </span>
                </div>
              </div>
            </div>
          </template>
        </div>
      </section>

      <p class="ai-config__hint">{{ t('system.aiConfigHint') }}</p>
      <button class="ai-config__btn-primary" :disabled="saving" @click="save">
        <Loader2 v-if="saving" :size="14" class="ai-config__spin" />
        {{ t('system.saveAiConfig') }}
      </button>
    </template>
  </div>
</template>

<style scoped>
.ai-config__loading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-8);
  color: var(--text-secondary);
}

.ai-config__section {
  margin-bottom: var(--space-8);
}

.ai-config__section-title {
  margin: 0 0 var(--space-3) 0;
  font-size: var(--font-body-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
}

.ai-config__table-scroll {
  overflow-x: auto;
  margin-bottom: var(--space-3);
}

.ai-config__table {
  display: flex;
  flex-direction: column;
  min-width: 860px;
}

.ai-config__provider-row {
  display: grid;
  grid-template-columns: 150px minmax(220px, 1fr) 220px 60px auto;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
  font-size: var(--font-body-sm);
  color: var(--text-primary);
}

.ai-config__slot-row {
  display: grid;
  grid-template-columns: 150px 220px minmax(200px, 1fr) 90px auto;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
  font-size: var(--font-body-sm);
  color: var(--text-primary);
}

.ai-config__provider-row:last-child,
.ai-config__slot-row:last-child {
  border-bottom: none;
}

.ai-config__provider-row--head,
.ai-config__slot-row--head {
  font-weight: var(--weight-medium);
  color: var(--text-tertiary);
  font-size: var(--font-caption);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding-top: 0;
}

.ai-config__slot-name {
  font-weight: var(--weight-medium);
}

.ai-config__slot-hint {
  grid-column: 1 / -1;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
  line-height: 1.5;
}

.ai-config__override {
  margin-top: var(--space-2);
}

.ai-config__override-toggle {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) 0;
  margin-bottom: var(--space-2);
  background: transparent;
  border: none;
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: color var(--transition-fast);
}

.ai-config__override-toggle:hover {
  color: var(--text-primary);
}

.ai-config__override-chevron {
  transition: transform var(--transition-fast);
}

.ai-config__override-chevron--open {
  transform: rotate(90deg);
}

.ai-config__override-hint {
  margin: 0 0 var(--space-2) 0;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.ai-config__input {
  width: 100%;
  box-sizing: border-box;
  padding: var(--space-2) var(--space-3);
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-family: var(--font-body);
  color: var(--text-primary);
  outline: none;
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.ai-config__input:focus {
  border-color: var(--accent-primary);
}

.ai-config__switch {
  display: flex;
  align-items: center;
  justify-content: center;
}

.ai-config__checkbox {
  width: 16px;
  height: 16px;
  accent-color: var(--accent-primary);
  cursor: pointer;
}

.ai-config__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  white-space: nowrap;
}

.ai-config__test-result {
  grid-column: 1 / -1;
  font-size: var(--font-body-sm);
  word-break: break-all;
}

.ai-config__test-result--ok {
  color: var(--success);
}

.ai-config__test-result--fail {
  color: var(--error);
}

.ai-config__hint {
  margin: 0 0 var(--space-3) 0;
  font-size: var(--font-body-sm);
  color: var(--text-tertiary);
  line-height: 1.6;
}

.ai-config__btn-primary {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.ai-config__btn-primary:hover:not(:disabled) {
  transform: scale(1.02);
}

.ai-config__btn-primary:active:not(:disabled) {
  transform: scale(0.98);
}

.ai-config__btn-primary:disabled {
  opacity: 0.38;
  cursor: not-allowed;
}

.ai-config__btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: var(--btn-height-sm);
  padding: 0 var(--space-2);
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  border: 1px solid var(--btn-secondary-border);
  border-radius: var(--radius-md);
  font-size: var(--font-body-sm);
  cursor: pointer;
  white-space: nowrap;
  transition: all var(--transition-fast);
}

.ai-config__btn-secondary:hover:not(:disabled) {
  transform: scale(1.02);
}

.ai-config__btn-secondary:active:not(:disabled) {
  transform: scale(0.98);
}

.ai-config__btn-secondary:disabled {
  opacity: 0.38;
  cursor: not-allowed;
}

.ai-config__btn-danger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: var(--btn-height-sm);
  width: var(--btn-height-sm);
  background: transparent;
  border: 1px solid var(--error);
  border-radius: var(--radius-md);
  color: var(--error);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.ai-config__btn-danger:hover {
  background: var(--error-light);
}

.ai-config__spin {
  animation: ai-config-spin 1s linear infinite;
}

@keyframes ai-config-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
