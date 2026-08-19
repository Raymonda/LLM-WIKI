<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Sparkles, Loader2, Check, ChevronRight, ChevronLeft,
  Code, Shield, FolderKanban, BookOpen, Target, Layers,
  FileText, Send, Newspaper, Plus, Trash2,
  X, HelpCircle, Lightbulb, AlertTriangle, Info, Zap, Eye,
} from 'lucide-vue-next'
import {
  startBootstrapV2, selectCapabilitiesV2, submitCategoryTreeV2,
  submitPageBlueprintV2, submitAutonomyV2, finalizeBootstrapV2,
  advisorCheckV2, advisorChatV2,
  type CapabilityInfo, type CategoryTreeNode, type AutonomyRule,
  type AdvisorSuggestion,
} from '@/api/harness'
import { useToastStore } from '@/stores/toast'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'done'): void }>()
const toast = useToastStore()
const { t } = useI18n()

const phase = ref<'loading' | 'capabilities' | 'tree' | 'blueprint' | 'autonomy' | 'summary' | 'saving'>('loading')
const sessionId = ref('')
const errorMsg = ref('')

// Step 1: Capabilities
const capabilities = ref<CapabilityInfo[]>([])
const selectedCapIds = ref<Set<string>>(new Set())
const capLoading = ref(false)

// Step 2: Category Tree
const tree = ref<CategoryTreeNode[]>([])
const treeNarrative = ref('')
const treeSuggestions = ref<AdvisorSuggestion[]>([])

// Step 3: Page Blueprint
const templates = ref<any[]>([])
const blueprintSuggestions = ref<AdvisorSuggestion[]>([])

// Step 4: Autonomy
const autonomyLevel = ref<'cautious' | 'balanced' | 'autonomous'>('balanced')
const autonomyRules = ref<AutonomyRule[]>([])

interface TierCapability {
  action: string
  cautious: 'auto' | 'notify' | 'confirm'
  balanced: 'auto' | 'notify' | 'confirm'
  autonomous: 'auto' | 'notify' | 'confirm'
}
const tierCapabilities = computed<TierCapability[]>(() => [
  { action: t('bootstrap.tierAction1'), cautious: 'confirm', balanced: 'auto', autonomous: 'auto' },
  { action: t('bootstrap.tierAction2'), cautious: 'confirm', balanced: 'auto', autonomous: 'auto' },
  { action: t('bootstrap.tierAction3'), cautious: 'confirm', balanced: 'confirm', autonomous: 'notify' },
  { action: t('bootstrap.tierAction4'), cautious: 'notify', balanced: 'notify', autonomous: 'auto' },
  { action: t('bootstrap.tierAction5'), cautious: 'notify', balanced: 'notify', autonomous: 'auto' },
  { action: t('bootstrap.tierAction6'), cautious: 'confirm', balanced: 'confirm', autonomous: 'notify' },
  { action: t('bootstrap.tierAction7'), cautious: 'confirm', balanced: 'confirm', autonomous: 'confirm' },
])
const tierModeLabelKey: Record<string, string> = { auto: 'bootstrap.tierModeAuto', notify: 'bootstrap.tierModeNotify', confirm: 'bootstrap.tierModeConfirm' }
const tierModeIcon: Record<string, string> = { auto: '⚡', notify: '🔔', confirm: '✋' }

// Advisor chat
const chatOpen = ref(false)
const chatLoading = ref(false)
const chatInput = ref('')
const chatHistory = ref<Array<{ role: 'user' | 'ai'; content: string }>>([])

// Summary
const summaryNarrative = ref('')
const summaryCapLabels = ref<string[]>([])
const summaryTreeStats = ref({ roots: 0, total: 0 })
const summaryTemplateCount = ref(0)
const summaryAutonomyLabel = ref('')

const stepDefs = [
  { key: 'capabilities', labelKey: 'bootstrap.capTitle', icon: Layers },
  { key: 'tree', labelKey: 'bootstrap.treeTitle', icon: FolderKanban },
  { key: 'blueprint', labelKey: 'bootstrap.blueprintTitle', icon: FileText },
  { key: 'autonomy', labelKey: 'bootstrap.autonomyTitle', icon: Shield },
  { key: 'summary', labelKey: 'bootstrap.summaryTitle', icon: Check },
] as const

const currentStepIdx = computed(() => {
  const keys = ['capabilities', 'tree', 'blueprint', 'autonomy', 'summary', 'saving']
  return keys.indexOf(phase.value)
})

const iconMap: Record<string, any> = {
  Code, Shield, FolderKanban, BookOpen, Target, Newspaper, Layers,
}

const capIconComponent = (icon: string) => iconMap[icon] || FileText

watch(() => props.open, async (opened) => {
  if (opened) {
    await reset()
    await initV2()
  }
})

async function reset() {
  phase.value = 'loading'
  sessionId.value = ''
  errorMsg.value = ''
  capabilities.value = []
  selectedCapIds.value = new Set()
  tree.value = []
  treeNarrative.value = ''
  treeSuggestions.value = []
  templates.value = []
  blueprintSuggestions.value = []
  autonomyLevel.value = 'balanced'
  autonomyRules.value = []
  chatOpen.value = false
  chatHistory.value = []
}

async function initV2() {
  try {
    const result = await startBootstrapV2()
    sessionId.value = result.sessionId
    capabilities.value = result.capabilities
    phase.value = 'capabilities'
  } catch (e: any) {
    errorMsg.value = e?.message || t('bootstrap.capStartError')
    toast.error(errorMsg.value)
  }
}

function toggleCapability(id: string) {
  const next = new Set(selectedCapIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedCapIds.value = next
}

async function confirmCapabilities() {
  if (selectedCapIds.value.size === 0) {
    toast.error(t('bootstrap.capSelectError'))
    return
  }
  capLoading.value = true
  errorMsg.value = ''
  try {
    const result = await selectCapabilitiesV2(sessionId.value, [...selectedCapIds.value])
    const draft = JSON.parse(result.draftJson)
    if (draft.taxonomy?.roots) {
      tree.value = draft.taxonomy.roots
    }
    treeNarrative.value = draft.taxonomy?.narrative || ''
    if (draft.templates?.pageTemplates) {
      templates.value = draft.templates.pageTemplates
    }
    summaryNarrative.value = result.domainNarrative
    summaryCapLabels.value = capabilities.value
      .filter(c => selectedCapIds.value.has(c.id))
      .map(c => c.label)
    summaryTemplateCount.value = result.templateCount
    phase.value = 'tree'
    await runAdvisorCheck('category-tree')
  } catch (e: any) {
    errorMsg.value = e?.message || t('bootstrap.capConfirmError')
  } finally {
    capLoading.value = false
  }
}

// ===== Tree editing =====
function addRootNode() {
  const id = 'new_' + Date.now()
  tree.value.push({ id, label: t('bootstrap.treeNewCategory'), description: '', children: [] })
}

function addChildNode(parent: CategoryTreeNode) {
  if (!parent.children) parent.children = []
  const id = 'new_' + Date.now()
  parent.children.push({ id, label: t('bootstrap.treeNewSubCategory'), description: '' })
}

function removeNode(arr: CategoryTreeNode[], node: CategoryTreeNode) {
  const idx = arr.indexOf(node)
  if (idx >= 0) arr.splice(idx, 1)
}

function findAndRemoveNode(node: CategoryTreeNode) {
  for (const root of tree.value) {
    if (root.children) {
      const idx = root.children.indexOf(node)
      if (idx >= 0) { root.children.splice(idx, 1); return }
    }
  }
  const idx = tree.value.indexOf(node)
  if (idx >= 0) tree.value.splice(idx, 1)
}

async function confirmTree() {
  errorMsg.value = ''
  try {
    await submitCategoryTreeV2(sessionId.value, { narrative: treeNarrative.value, roots: tree.value })
    summaryTreeStats.value = { roots: tree.value.length, total: countTreeNodes(tree.value) }
    phase.value = 'blueprint'
    await runAdvisorCheck('page-blueprint')
  } catch (e: any) {
    errorMsg.value = e?.message || t('bootstrap.treeSaveError')
  }
}

function countTreeNodes(nodes: CategoryTreeNode[]): number {
  let count = 0
  for (const n of nodes) {
    count++
    if (n.children) count += countTreeNodes(n.children)
  }
  return count
}

// ===== Blueprint =====
function toggleSection(_tpl: any, section: any) {
  section.required = !section.required
}

async function confirmBlueprint() {
  errorMsg.value = ''
  try {
    await submitPageBlueprintV2(sessionId.value, { pageTemplates: templates.value })
    phase.value = 'autonomy'
    await loadAutonomyDefaults()
  } catch (e: any) {
    errorMsg.value = e?.message || t('bootstrap.blueprintSaveError')
  }
}

async function loadAutonomyDefaults() {
  try {
    const result = await submitAutonomyV2(sessionId.value, 'balanced')
    autonomyRules.value = result.rules
  } catch {
    autonomyRules.value = getDefaultRules()
  }
}

function getDefaultRules(): AutonomyRule[] {
  return [
    { id: 'auto_ingest', description: t('bootstrap.ruleAutoIngest'), enabled: true, icon: '✅' },
    { id: 'auto_crossref', description: t('bootstrap.ruleAutoCrossref'), enabled: true, icon: '✅' },
    { id: 'conflict_review', description: t('bootstrap.ruleConflictReview'), enabled: true, icon: '⚠️' },
    { id: 'gap_notify', description: t('bootstrap.ruleGapNotify'), enabled: true, icon: '⚠️' },
    { id: 'no_auto_delete', description: t('bootstrap.ruleNoAutoDelete'), enabled: true, icon: '❌' },
    { id: 'stale_notify', description: t('bootstrap.ruleStaleNotify'), enabled: true, icon: '⚠️' },
  ]
}

async function onAutonomyLevelChange() {
  try {
    const result = await submitAutonomyV2(sessionId.value, autonomyLevel.value)
    autonomyRules.value = result.rules
  } catch { /* keep current rules */ }
}

async function confirmAutonomy() {
  errorMsg.value = ''
  try {
    await submitAutonomyV2(sessionId.value, autonomyLevel.value, {
      confirmTriggers: autonomyRules.value.filter(r => r.enabled).map(r => r.description),
    })
    summaryAutonomyLabel.value = autonomyLevel.value === 'cautious' ? t('bootstrap.autonomyCautious')
      : autonomyLevel.value === 'autonomous' ? t('bootstrap.autonomyAutonomous') : t('bootstrap.autonomyBalanced')
    phase.value = 'summary'
  } catch (e: any) {
    errorMsg.value = e?.message || t('bootstrap.autonomySaveError')
  }
}

async function finalize() {
  phase.value = 'saving'
  errorMsg.value = ''
  try {
    await finalizeBootstrapV2(sessionId.value)
    toast.success(t('bootstrap.summarySaved'))
    emit('done')
  } catch (e: any) {
    errorMsg.value = e?.message || t('bootstrap.summarySaveError')
    phase.value = 'summary'
  }
}

function goBack() {
  const order = ['capabilities', 'tree', 'blueprint', 'autonomy', 'summary']
  const idx = order.indexOf(phase.value)
  if (idx > 0) {
    phase.value = order[idx - 1] as any
    errorMsg.value = ''
  }
}

// ===== Advisor =====
async function runAdvisorCheck(step: string) {
  try {
    const data = step === 'category-tree' ? JSON.stringify({ roots: tree.value })
      : step === 'page-blueprint' ? JSON.stringify({ pageTemplates: templates.value })
      : ''
    const suggestions = await advisorCheckV2(sessionId.value, step, data, [...selectedCapIds.value])
    if (step === 'category-tree') treeSuggestions.value = suggestions
    else if (step === 'page-blueprint') blueprintSuggestions.value = suggestions
  } catch { /* advisor failure is non-critical */ }
}

async function sendChat() {
  const msg = chatInput.value.trim()
  if (!msg || chatLoading.value) return
  chatLoading.value = true
  chatHistory.value.push({ role: 'user', content: msg })
  chatInput.value = ''
  try {
    const step = phase.value
    const stepData = step === 'tree' ? JSON.stringify({ roots: tree.value })
      : step === 'blueprint' ? JSON.stringify({ pageTemplates: templates.value })
      : step === 'autonomy' ? JSON.stringify({ level: autonomyLevel.value, rules: autonomyRules.value })
      : ''
    const result = await advisorChatV2(sessionId.value, step, msg, stepData, [...selectedCapIds.value])
    chatHistory.value.push({ role: 'ai', content: result.reply })
  } catch (e: any) {
    chatHistory.value.push({ role: 'ai', content: t('bootstrap.aiChatError') + (e?.message || t('bootstrap.aiUnknownError')) })
  } finally {
    chatLoading.value = false
    await nextTick()
    const panel = document.querySelector('.sb-chat__messages')
    if (panel) panel.scrollTop = panel.scrollHeight
  }
}

function dismissSuggestion(arr: AdvisorSuggestion[], idx: number) {
  arr.splice(idx, 1)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="sb-overlay" @click.self="emit('done')">
      <div class="sb-dialog">
        <!-- Header -->
        <div class="sb-header">
          <div class="sb-header__left">
            <Sparkles :size="20" class="sb-header__icon" />
            <h2 class="sb-header__title">{{ t('bootstrap.title') }}</h2>
          </div>
          <div class="sb-header__steps">
            <template v-for="(s, i) in stepDefs" :key="s.key">
              <div
                class="sb-step-dot"
                :class="{
                  'sb-step-dot--active': currentStepIdx === i,
                  'sb-step-dot--done': currentStepIdx > i,
                }"
              >
                <component :is="s.icon" :size="14" />
              </div>
              <span v-if="i < stepDefs.length - 1" class="sb-step-line"
                :class="{ 'sb-step-line--done': currentStepIdx > i }" />
            </template>
          </div>
          <button class="sb-close" @click="emit('done')">
            <X :size="18" />
          </button>
        </div>

        <!-- Error -->
        <div v-if="errorMsg" class="sb-error">
          <AlertTriangle :size="14" />
          <span>{{ errorMsg }}</span>
          <button @click="errorMsg = ''" class="sb-error__dismiss">×</button>
        </div>

        <!-- Loading -->
        <div v-if="phase === 'loading'" class="sb-body sb-body--center">
          <Loader2 :size="32" class="sb-spin" />
          <p>{{ t('bootstrap.loading') }}</p>
        </div>

        <!-- Step 1: Capabilities -->
        <div v-else-if="phase === 'capabilities'" class="sb-body">
          <div class="sb-section-header">
            <h3>{{ t('bootstrap.capTitle') }}</h3>
            <p>{{ t('bootstrap.capHint') }}</p>
          </div>
          <div class="sb-cap-grid">
            <div
              v-for="cap in capabilities"
              :key="cap.id"
              class="sb-cap-card"
              :class="{ 'sb-cap-card--selected': selectedCapIds.has(cap.id) }"
              @click="toggleCapability(cap.id)"
            >
              <div class="sb-cap-card__header">
                <component :is="capIconComponent(cap.icon)" :size="20" class="sb-cap-card__icon" />
                <span class="sb-cap-card__label">{{ cap.label }}</span>
                <Check v-if="selectedCapIds.has(cap.id)" :size="16" class="sb-cap-card__check" />
              </div>
              <p class="sb-cap-card__desc">{{ cap.description }}</p>
              <div class="sb-cap-card__docs" v-if="cap.sampleDocTypes?.length">
                <span v-for="doc in cap.sampleDocTypes.slice(0, 3)" :key="doc" class="sb-cap-card__tag">{{ doc }}</span>
              </div>
              <div class="sb-cap-card__tags" v-if="cap.tags?.length">
                <span v-for="tag in cap.tags" :key="tag" class="sb-cap-card__meta">{{ tag }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Step 2: Category Tree -->
        <div v-else-if="phase === 'tree'" class="sb-body">
          <div class="sb-section-header">
            <h3>{{ t('bootstrap.treeTitle') }}</h3>
            <p>{{ t('bootstrap.treeHint') }}</p>
          </div>
          <div class="sb-tree-layout">
            <div class="sb-tree-main">
              <div class="sb-tree-toolbar">
                <button class="sb-btn sb-btn--sm sb-btn--ghost" @click="addRootNode">
                  <Plus :size="14" /> {{ t('bootstrap.treeAddRoot') }}
                </button>
              </div>
              <div class="sb-tree-list">
                <div v-for="root in tree" :key="root.id" class="sb-tree-node">
                  <div class="sb-tree-node__row">
                    <FolderKanban :size="14" class="sb-tree-node__icon" />
                    <input v-model="root.label" class="sb-tree-node__input" :placeholder="t('bootstrap.treeRootPlaceholder')" />
                    <button class="sb-tree-node__btn" @click="addChildNode(root)" :title="t('bootstrap.treeAddChildTitle')">
                      <Plus :size="12" />
                    </button>
                    <button class="sb-tree-node__btn sb-tree-node__btn--danger" @click="findAndRemoveNode(root)" :title="t('bootstrap.treeDeleteTitle')">
                      <Trash2 :size="12" />
                    </button>
                  </div>
                  <div v-if="root.children?.length" class="sb-tree-node__children">
                    <div v-for="child in root.children" :key="child.id" class="sb-tree-node sb-tree-node--child">
                      <div class="sb-tree-node__row">
                        <FileText :size="12" class="sb-tree-node__icon sb-tree-node__icon--child" />
                        <input v-model="child.label" class="sb-tree-node__input" :placeholder="t('bootstrap.treeChildPlaceholder')" />
                        <button class="sb-tree-node__btn sb-tree-node__btn--danger" @click="removeNode(root.children!, child)">
                          <Trash2 :size="12" />
                        </button>
                      </div>
                      <div v-if="child.description" class="sb-tree-node__desc">{{ child.description }}</div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="treeSuggestions.length" class="sb-advisor-panel">
              <div class="sb-advisor-panel__title">
                <Lightbulb :size="14" /> {{ t('bootstrap.aiSuggestion') }}
              </div>
              <div v-for="(s, i) in treeSuggestions" :key="i" class="sb-advisor-item" :class="`sb-advisor-item--${s.type}`">
                <component :is="s.type === 'warning' ? AlertTriangle : s.type === 'recommendation' ? Lightbulb : Info" :size="14" />
                <span>{{ s.message }}</span>
                <button class="sb-advisor-item__dismiss" @click="dismissSuggestion(treeSuggestions, i)">×</button>
              </div>
            </div>
          </div>
        </div>

        <!-- Step 3: Page Blueprint -->
        <div v-else-if="phase === 'blueprint'" class="sb-body">
          <div class="sb-section-header">
            <h3>{{ t('bootstrap.blueprintTitle') }}</h3>
            <p>{{ t('bootstrap.blueprintHint') }}</p>
          </div>
          <div class="sb-blueprint-layout">
            <div class="sb-blueprint-main">
              <div v-for="tpl in templates" :key="tpl.type" class="sb-tpl-card">
                <div class="sb-tpl-card__header">
                  <FileText :size="14" />
                  <span>{{ tpl.label }}</span>
                </div>
                <div class="sb-tpl-card__sections">
                  <div
                    v-for="sec in tpl.sections"
                    :key="sec.id"
                    class="sb-sec-row"
                    :class="{ 'sb-sec-row--off': !sec.required }"
                  >
                    <label class="sb-switch">
                      <input type="checkbox" :checked="sec.required" @change="toggleSection(tpl, sec)" />
                      <span class="sb-switch__slider" />
                    </label>
                    <span class="sb-sec-row__label">{{ sec.label }}</span>
                    <span v-if="sec.required" class="sb-sec-row__badge">{{ t('bootstrap.blueprintRequired') }}</span>
                    <span v-else class="sb-sec-row__badge sb-sec-row__badge--off">{{ t('bootstrap.blueprintOptional') }}</span>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="blueprintSuggestions.length" class="sb-advisor-panel">
              <div class="sb-advisor-panel__title">
                <Lightbulb :size="14" /> {{ t('bootstrap.aiSuggestion') }}
              </div>
              <div v-for="(s, i) in blueprintSuggestions" :key="i" class="sb-advisor-item" :class="`sb-advisor-item--${s.type}`">
                <component :is="s.type === 'warning' ? AlertTriangle : Info" :size="14" />
                <span>{{ s.message }}</span>
                <button class="sb-advisor-item__dismiss" @click="dismissSuggestion(blueprintSuggestions, i)">×</button>
              </div>
            </div>
          </div>
        </div>

        <!-- Step 4: Autonomy -->
        <div v-else-if="phase === 'autonomy'" class="sb-body">
          <div class="sb-section-header">
            <h3>{{ t('bootstrap.autonomyTitle') }}</h3>
            <p>{{ t('bootstrap.autonomyHint') }}</p>
          </div>
          <div class="sb-autonomy">
            <div class="sb-autonomy__slider">
              <div class="sb-autonomy__labels">
                <span :class="{ 'sb-autonomy__label--active': autonomyLevel === 'cautious' }">{{ t('bootstrap.autonomyCautious') }}</span>
                <span :class="{ 'sb-autonomy__label--active': autonomyLevel === 'balanced' }">{{ t('bootstrap.autonomyBalanced') }}</span>
                <span :class="{ 'sb-autonomy__label--active': autonomyLevel === 'autonomous' }">{{ t('bootstrap.autonomyAutonomous') }}</span>
              </div>
              <input
                type="range" min="0" max="2" step="1"
                :value="autonomyLevel === 'cautious' ? 0 : autonomyLevel === 'balanced' ? 1 : 2"
                @input="autonomyLevel = (['cautious', 'balanced', 'autonomous'] as const)[Number(($event.target as HTMLInputElement).value)]; onAutonomyLevelChange()"
                class="sb-range"
              />
              <p class="sb-autonomy__level-desc">{{ autonomyLevel === 'cautious' ? t('bootstrap.autonomyCautiousDesc') : autonomyLevel === 'autonomous' ? t('bootstrap.autonomyAutonomousDesc') : t('bootstrap.autonomyBalancedDesc') }}</p>
            </div>
            <!-- Tier comparison table -->
            <div class="sb-tier-table">
              <div class="sb-tier-table__header">
                <span class="sb-tier-table__action-col">{{ t('bootstrap.autonomyActionCol') }}</span>
                <span class="sb-tier-table__tier" :class="{ 'sb-tier-table__tier--active': autonomyLevel === 'cautious' }">
                  <Eye :size="13" /> {{ t('bootstrap.autonomyCautious') }}
                </span>
                <span class="sb-tier-table__tier" :class="{ 'sb-tier-table__tier--active': autonomyLevel === 'balanced' }">
                  <Shield :size="13" /> {{ t('bootstrap.autonomyBalanced') }}
                </span>
                <span class="sb-tier-table__tier" :class="{ 'sb-tier-table__tier--active': autonomyLevel === 'autonomous' }">
                  <Zap :size="13" /> {{ t('bootstrap.autonomyAutonomous') }}
                </span>
              </div>
              <div v-for="(row, i) in tierCapabilities" :key="i" class="sb-tier-table__row">
                <span class="sb-tier-table__action">{{ row.action }}</span>
                <span class="sb-tier-table__cell" :class="{ 'sb-tier-table__cell--active': autonomyLevel === 'cautious' }">
                  <span class="sb-tier-mode" :class="`sb-tier-mode--${row.cautious}`">{{ tierModeIcon[row.cautious] }} {{ t(tierModeLabelKey[row.cautious]) }}</span>
                </span>
                <span class="sb-tier-table__cell" :class="{ 'sb-tier-table__cell--active': autonomyLevel === 'balanced' }">
                  <span class="sb-tier-mode" :class="`sb-tier-mode--${row.balanced}`">{{ tierModeIcon[row.balanced] }} {{ t(tierModeLabelKey[row.balanced]) }}</span>
                </span>
                <span class="sb-tier-table__cell" :class="{ 'sb-tier-table__cell--active': autonomyLevel === 'autonomous' }">
                  <span class="sb-tier-mode" :class="`sb-tier-mode--${row.autonomous}`">{{ tierModeIcon[row.autonomous] }} {{ t(tierModeLabelKey[row.autonomous]) }}</span>
                </span>
              </div>
            </div>
            <!-- Current rules -->
            <div class="sb-autonomy__rules">
              <div class="sb-autonomy__rules-title">{{ t('bootstrap.autonomyRulesTitle') }}</div>
              <div v-for="rule in autonomyRules" :key="rule.id" class="sb-rule-row">
                <span class="sb-rule-row__icon">{{ rule.icon }}</span>
                <span class="sb-rule-row__desc">{{ rule.description }}</span>
              </div>
            </div>
            <p class="sb-autonomy__hint">
              {{ t('bootstrap.autonomySettingsHint') }}
            </p>
          </div>
        </div>

        <!-- Summary -->
        <div v-else-if="phase === 'summary' || phase === 'saving'" class="sb-body">
          <div class="sb-section-header">
            <h3>{{ t('bootstrap.summaryTitle') }}</h3>
            <p>{{ t('bootstrap.summaryHint') }}</p>
          </div>
          <div class="sb-summary">
            <div class="sb-summary__item">
              <BookOpen :size="16" />
              <div>
                <span class="sb-summary__label">{{ t('bootstrap.summaryScope') }}</span>
                <span class="sb-summary__value">{{ summaryNarrative || summaryCapLabels.join(t('common.listSeparator')) }}</span>
              </div>
            </div>
            <div class="sb-summary__item">
              <FolderKanban :size="16" />
              <div>
                <span class="sb-summary__label">{{ t('bootstrap.summaryOrg') }}</span>
                <span class="sb-summary__value">{{ t('bootstrap.summaryOrgStats', [summaryTreeStats.roots, summaryTreeStats.total]) }}</span>
              </div>
            </div>
            <div class="sb-summary__item">
              <FileText :size="16" />
              <div>
                <span class="sb-summary__label">{{ t('bootstrap.summaryTemplate') }}</span>
                <span class="sb-summary__value">{{ t('bootstrap.summaryTemplateCount', [summaryTemplateCount]) }}</span>
              </div>
            </div>
            <div class="sb-summary__item">
              <Shield :size="16" />
              <div>
                <span class="sb-summary__label">{{ t('bootstrap.summaryAutonomy') }}</span>
                <span class="sb-summary__value">{{ summaryAutonomyLabel }}</span>
              </div>
            </div>
          </div>
          <div v-if="phase === 'saving'" class="sb-saving">
            <Loader2 :size="24" class="sb-spin" />
            <div class="sb-saving__text">
              <span>{{ t('bootstrap.summarySaving') }}</span>
              <span class="sb-saving__hint">{{ t('bootstrap.summaryPolishHint') }}</span>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="sb-footer">
          <button v-if="currentStepIdx > 0 && phase !== 'saving'" class="sb-btn sb-btn--ghost" @click="goBack">
            <ChevronLeft :size="16" /> {{ t('bootstrap.navBack') }}
          </button>
          <div class="sb-footer__spacer" />
          <button v-if="phase === 'capabilities'" class="sb-btn sb-btn--primary" :disabled="capLoading || selectedCapIds.size === 0" @click="confirmCapabilities">
            <template v-if="capLoading"><Loader2 :size="14" class="sb-spin" /> {{ t('bootstrap.navProcessing') }}</template>
            <template v-else>{{ t('bootstrap.navNext') }} <ChevronRight :size="16" /></template>
          </button>
          <button v-else-if="phase === 'tree'" class="sb-btn sb-btn--primary" @click="confirmTree">
            {{ t('bootstrap.navNext') }} <ChevronRight :size="16" />
          </button>
          <button v-else-if="phase === 'blueprint'" class="sb-btn sb-btn--primary" @click="confirmBlueprint">
            {{ t('bootstrap.navNext') }} <ChevronRight :size="16" />
          </button>
          <button v-else-if="phase === 'autonomy'" class="sb-btn sb-btn--primary" @click="confirmAutonomy">
            {{ t('bootstrap.navNext') }} <ChevronRight :size="16" />
          </button>
          <button v-else-if="phase === 'summary'" class="sb-btn sb-btn--primary sb-btn--lg" @click="finalize">
            <Check :size="16" /> {{ t('bootstrap.navStart') }}
          </button>

          <!-- Ask AI button -->
          <button
            v-if="['tree', 'blueprint', 'autonomy'].includes(phase)"
            class="sb-btn sb-btn--ai"
            @click="chatOpen = !chatOpen"
          >
            <HelpCircle :size="14" />
            {{ t('bootstrap.askAi') }}
          </button>
        </div>

        <!-- Chat Panel -->
        <Transition name="sb-slide">
          <div v-if="chatOpen && ['tree', 'blueprint', 'autonomy'].includes(phase)" class="sb-chat">
            <div class="sb-chat__header">
              <span>{{ t('bootstrap.aiAssistant') }}</span>
              <button @click="chatOpen = false"><X :size="14" /></button>
            </div>
            <div class="sb-chat__messages">
              <div v-if="chatHistory.length === 0" class="sb-chat__empty">
                {{ t('bootstrap.aiChatEmpty') }}
              </div>
              <div v-for="(msg, i) in chatHistory" :key="i" class="sb-chat__msg" :class="`sb-chat__msg--${msg.role}`">
                {{ msg.content }}
              </div>
              <div v-if="chatLoading" class="sb-chat__msg sb-chat__msg--ai">
                <Loader2 :size="12" class="sb-spin" /> {{ t('bootstrap.aiThinking') }}
              </div>
            </div>
            <div class="sb-chat__input-row">
              <input v-model="chatInput" :placeholder="t('bootstrap.aiChatPlaceholder')" @keyup.enter="sendChat" :disabled="chatLoading" />
              <button @click="sendChat" :disabled="chatLoading || !chatInput.trim()">
                <Send :size="14" />
              </button>
            </div>
          </div>
        </Transition>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.sb-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,.45);
  display: flex; align-items: center; justify-content: center;
  z-index: 1000; backdrop-filter: blur(2px);
}
.sb-dialog {
  width: min(900px, 95vw); max-height: 85vh;
  background: var(--surface-card); border: 1px solid var(--border-default);
  border-radius: var(--radius-lg); box-shadow: var(--shadow-xl);
  display: flex; flex-direction: column; overflow: hidden; position: relative;
}
.sb-header {
  display: flex; align-items: center; gap: var(--space-3);
  padding: var(--space-4) var(--space-5); border-bottom: 1px solid var(--border-subtle);
}
.sb-header__left { display: flex; align-items: center; gap: var(--space-2); }
.sb-header__icon { color: var(--accent-primary); }
.sb-header__title { margin: 0; font-size: var(--font-body-lg); font-weight: var(--weight-semibold); }
.sb-header__steps { display: flex; align-items: center; gap: 4px; margin-left: auto; }
.sb-step-dot {
  width: 28px; height: 28px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: var(--bg-tertiary); color: var(--text-tertiary); font-size: 11px;
}
.sb-step-dot--active { background: var(--accent-primary); color: var(--text-on-accent); }
.sb-step-dot--done { background: var(--success-light); color: var(--success); }
.sb-step-line { width: 20px; height: 2px; background: var(--border-subtle); }
.sb-step-line--done { background: var(--success); }
.sb-close {
  background: none; border: none; color: var(--text-tertiary); cursor: pointer;
  padding: 4px; border-radius: 4px;
}
.sb-close:hover { background: var(--bg-tertiary); }

.sb-error {
  display: flex; align-items: center; gap: var(--space-2);
  padding: var(--space-2) var(--space-5); background: var(--error-light); color: var(--error);
  font-size: var(--font-body-sm);
}
.sb-error__dismiss { background: none; border: none; color: var(--error); cursor: pointer; margin-left: auto; font-size: 16px; }

.sb-body { flex: 1; overflow-y: auto; padding: var(--space-5); }
.sb-body--center { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: var(--space-3); color: var(--text-secondary); }

.sb-section-header { margin-bottom: var(--space-4); }
.sb-section-header h3 { margin: 0 0 var(--space-1); font-size: var(--font-body-lg); font-weight: var(--weight-semibold); }
.sb-section-header p { margin: 0; color: var(--text-secondary); font-size: var(--font-body-sm); }

/* Capability cards */
.sb-cap-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: var(--space-3); }
.sb-cap-card {
  padding: var(--space-4); border: 2px solid var(--border-subtle); border-radius: var(--radius-md);
  cursor: pointer; transition: all .15s;
}
.sb-cap-card:hover { border-color: var(--accent-primary); }
.sb-cap-card--selected { border-color: var(--accent-primary); background: var(--accent-light); }
.sb-cap-card__header { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-2); }
.sb-cap-card__icon { color: var(--accent-primary); }
.sb-cap-card__label { font-weight: var(--weight-semibold); }
.sb-cap-card__check { color: var(--accent-primary); margin-left: auto; }
.sb-cap-card__desc { margin: 0 0 var(--space-2); font-size: var(--font-body-sm); color: var(--text-secondary); line-height: 1.5; }
.sb-cap-card__docs, .sb-cap-card__tags { display: flex; flex-wrap: wrap; gap: 4px; }
.sb-cap-card__tag {
  font-size: 11px; padding: 1px 6px; border-radius: 3px;
  background: var(--bg-tertiary); color: var(--text-secondary);
}
.sb-cap-card__meta {
  font-size: 11px; padding: 1px 6px; border-radius: 3px;
  background: var(--accent-light); color: var(--accent-primary);
}

/* Tree */
.sb-tree-layout { display: flex; gap: var(--space-4); }
.sb-tree-main { flex: 1; min-width: 0; }
.sb-tree-toolbar { display: flex; gap: var(--space-2); margin-bottom: var(--space-3); }
.sb-tree-list { display: flex; flex-direction: column; gap: var(--space-2); }
.sb-tree-node { border: 1px solid var(--border-subtle); border-radius: var(--radius-sm); overflow: hidden; }
.sb-tree-node__row {
  display: flex; align-items: center; gap: var(--space-2);
  padding: var(--space-2) var(--space-3); background: var(--bg-tertiary);
}
.sb-tree-node__icon { color: var(--text-tertiary); flex-shrink: 0; }
.sb-tree-node__icon--child { color: var(--text-tertiary); opacity: .7; }
.sb-tree-node__input {
  flex: 1; background: none; border: none; font-size: var(--font-body-sm);
  color: var(--text-primary); outline: none; padding: 2px 0;
}
.sb-tree-node__input:focus { border-bottom: 1px solid var(--accent-primary); }
.sb-tree-node__btn {
  background: none; border: none; cursor: pointer; color: var(--text-tertiary);
  padding: 2px; border-radius: 3px; display: flex; align-items: center;
}
.sb-tree-node__btn:hover { background: var(--bg-secondary); }
.sb-tree-node__btn--danger:hover { color: var(--error); }
.sb-tree-node__children { padding-left: var(--space-5); display: flex; flex-direction: column; gap: 1px; }
.sb-tree-node--child { border: none; }
.sb-tree-node--child .sb-tree-node__row { background: transparent; padding-left: var(--space-3); }
.sb-tree-node__desc { font-size: 11px; color: var(--text-tertiary); padding: 0 var(--space-3) var(--space-1) calc(var(--space-3) + 18px); }

/* Blueprint */
.sb-blueprint-layout { display: flex; gap: var(--space-4); }
.sb-blueprint-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: var(--space-3); }
.sb-tpl-card { border: 1px solid var(--border-subtle); border-radius: var(--radius-sm); overflow: hidden; }
.sb-tpl-card__header {
  display: flex; align-items: center; gap: var(--space-2);
  padding: var(--space-2) var(--space-3); background: var(--bg-tertiary);
  font-weight: var(--weight-semibold); font-size: var(--font-body-sm);
}
.sb-tpl-card__sections { padding: var(--space-2) var(--space-3); display: flex; flex-direction: column; gap: 4px; }
.sb-sec-row {
  display: flex; align-items: center; gap: var(--space-2); padding: 4px 0;
}
.sb-sec-row--off { opacity: .5; }
.sb-sec-row__label { flex: 1; font-size: var(--font-body-sm); }
.sb-sec-row__badge {
  font-size: 10px; padding: 1px 5px; border-radius: 3px;
  background: var(--success-light); color: var(--success);
}
.sb-sec-row__badge--off { background: var(--bg-tertiary); color: var(--text-tertiary); }

/* Switch */
.sb-switch { position: relative; display: inline-block; width: 32px; height: 18px; flex-shrink: 0; }
.sb-switch input { opacity: 0; width: 0; height: 0; }
.sb-switch__slider {
  position: absolute; inset: 0; background: var(--border-subtle); border-radius: 9px;
  cursor: pointer; transition: .2s;
}
.sb-switch__slider::before {
  content: ''; position: absolute; width: 14px; height: 14px; left: 2px; bottom: 2px;
  background: var(--text-on-accent); border-radius: 50%; transition: .2s;
}
.sb-switch input:checked + .sb-switch__slider { background: var(--accent-primary); }
.sb-switch input:checked + .sb-switch__slider::before { transform: translateX(14px); }

/* Autonomy */
.sb-autonomy { max-width: 720px; margin: 0 auto; }
.sb-autonomy__slider { margin-bottom: var(--space-4); }
.sb-autonomy__labels { display: flex; justify-content: space-between; margin-bottom: var(--space-2); font-size: var(--font-body-sm); color: var(--text-tertiary); }
.sb-autonomy__label--active { color: var(--accent-primary); font-weight: var(--weight-semibold); }
.sb-range { width: 100%; accent-color: var(--accent-primary); }
.sb-autonomy__level-desc { margin: var(--space-2) 0 0; font-size: var(--font-body-sm); color: var(--text-secondary); text-align: center; line-height: 1.5; }

/* Tier comparison table */
.sb-tier-table {
  border: 1px solid var(--border-subtle); border-radius: var(--radius-md);
  overflow: hidden; margin-bottom: var(--space-4); font-size: var(--font-body-sm);
}
.sb-tier-table__header {
  display: grid; grid-template-columns: 1.6fr 1fr 1fr 1fr;
  background: var(--bg-tertiary); border-bottom: 1px solid var(--border-subtle);
  font-weight: var(--weight-semibold); font-size: 12px; color: var(--text-secondary);
}
.sb-tier-table__action-col { padding: var(--space-2) var(--space-3); }
.sb-tier-table__tier {
  display: flex; align-items: center; gap: 4px; justify-content: center;
  padding: var(--space-2) var(--space-1); color: var(--text-tertiary); transition: all .15s;
}
.sb-tier-table__tier--active { color: var(--accent-primary); font-weight: var(--weight-bold); }
.sb-tier-table__row {
  display: grid; grid-template-columns: 1.6fr 1fr 1fr 1fr;
  border-bottom: 1px solid var(--border-subtle); transition: background .1s;
}
.sb-tier-table__row:last-child { border-bottom: none; }
.sb-tier-table__action { padding: var(--space-2) var(--space-3); color: var(--text-primary); line-height: 1.4; }
.sb-tier-table__cell {
  display: flex; align-items: center; justify-content: center;
  padding: var(--space-2) var(--space-1); transition: background .15s;
}
.sb-tier-table__cell--active { background: var(--accent-light); }
.sb-tier-mode {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: 11px; padding: 2px 6px; border-radius: 4px; white-space: nowrap; line-height: 1.4;
}
.sb-tier-mode--auto { background: var(--success-light, #ecfdf5); color: var(--success, #059669); }
.sb-tier-mode--notify { background: var(--warning-light, #fffbeb); color: var(--warning, #d97706); }
.sb-tier-mode--confirm { background: var(--accent-light, #eff6ff); color: var(--accent-primary, #2563eb); }

.sb-autonomy__rules { margin-bottom: var(--space-2); display: flex; flex-direction: column; gap: var(--space-2); }
.sb-autonomy__rules-title { font-size: var(--font-body-sm); font-weight: var(--weight-semibold); color: var(--text-secondary); margin-bottom: var(--space-2); }
.sb-rule-row { display: flex; align-items: flex-start; gap: var(--space-2); font-size: var(--font-body-sm); }
.sb-rule-row__icon { flex-shrink: 0; }
.sb-rule-row__desc { color: var(--text-primary); line-height: 1.5; }
.sb-autonomy__hint { margin-top: var(--space-4); font-size: var(--font-body-sm); color: var(--text-tertiary); text-align: center; }

/* Summary */
.sb-summary { display: flex; flex-direction: column; gap: var(--space-3); max-width: 500px; margin: 0 auto; }
.sb-summary__item { display: flex; align-items: flex-start; gap: var(--space-3); }
.sb-summary__item > svg { flex-shrink: 0; color: var(--accent-primary); margin-top: 2px; }
.sb-summary__label { display: block; font-size: var(--font-body-sm); color: var(--text-secondary); margin-bottom: 2px; }
.sb-summary__value { font-size: var(--font-body); color: var(--text-primary); }
.sb-saving { display: flex; align-items: center; justify-content: center; gap: var(--space-2); margin-top: var(--space-4); color: var(--text-secondary); }
.sb-saving__text { display: flex; flex-direction: column; gap: 2px; }
.sb-saving__hint { font-size: var(--font-body-sm); color: var(--text-tertiary); }

/* Advisor panel */
.sb-advisor-panel {
  width: 240px; flex-shrink: 0;
  border: 1px solid var(--border-subtle); border-radius: var(--radius-sm);
  padding: var(--space-3); max-height: 400px; overflow-y: auto;
}
.sb-advisor-panel__title { display: flex; align-items: center; gap: var(--space-1); font-size: var(--font-body-sm); font-weight: var(--weight-semibold); margin-bottom: var(--space-2); color: var(--accent-primary); }
.sb-advisor-item {
  display: flex; align-items: flex-start; gap: var(--space-1); font-size: 12px;
  padding: var(--space-2); border-radius: 4px; margin-bottom: 4px; line-height: 1.4;
}
.sb-advisor-item--info { background: var(--accent-light); color: var(--accent-primary); }
.sb-advisor-item--warning { background: var(--warning-light); color: var(--warning); }
.sb-advisor-item--recommendation { background: var(--success-light); color: var(--success); }
.sb-advisor-item__dismiss { background: none; border: none; cursor: pointer; font-size: 14px; margin-left: auto; opacity: .6; flex-shrink: 0; }
.sb-advisor-item__dismiss:hover { opacity: 1; }

/* Chat */
.sb-chat {
  position: absolute; right: 0; top: 0; bottom: 0; width: 320px;
  background: var(--bg-primary); border-left: 1px solid var(--border-subtle);
  display: flex; flex-direction: column; z-index: 10;
}
.sb-chat__header {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--space-3); border-bottom: 1px solid var(--border-subtle);
  font-weight: var(--weight-semibold); font-size: var(--font-body-sm);
}
.sb-chat__header button { background: none; border: none; cursor: pointer; color: var(--text-tertiary); }
.sb-chat__messages { flex: 1; overflow-y: auto; padding: var(--space-3); display: flex; flex-direction: column; gap: var(--space-2); }
.sb-chat__empty { color: var(--text-tertiary); font-size: var(--font-body-sm); text-align: center; padding: var(--space-4); }
.sb-chat__msg { padding: var(--space-2) var(--space-3); border-radius: var(--radius-sm); font-size: var(--font-body-sm); line-height: 1.5; max-width: 90%; }
.sb-chat__msg--user { background: var(--accent-primary); color: var(--text-on-accent); align-self: flex-end; }
.sb-chat__msg--ai { background: var(--bg-tertiary); color: var(--text-primary); align-self: flex-start; }
.sb-chat__input-row {
  display: flex; gap: var(--space-2); padding: var(--space-2) var(--space-3);
  border-top: 1px solid var(--border-subtle);
}
.sb-chat__input-row input {
  flex: 1; border: 1px solid var(--border-subtle); border-radius: 4px;
  padding: var(--space-2); font-size: var(--font-body-sm); background: var(--bg-primary); color: var(--text-primary);
}
.sb-chat__input-row button {
  background: var(--accent-primary); color: var(--text-on-accent); border: none; border-radius: 4px;
  padding: var(--space-2); cursor: pointer; display: flex; align-items: center;
}
.sb-chat__input-row button:disabled { opacity: .4; cursor: not-allowed; }

/* Footer */
.sb-footer {
  display: flex; align-items: center; gap: var(--space-2);
  padding: var(--space-3) var(--space-5); border-top: 1px solid var(--border-subtle);
}
.sb-footer__spacer { flex: 1; }

/* Buttons */
.sb-btn {
  display: inline-flex; align-items: center; gap: var(--space-1);
  padding: var(--space-2) var(--space-4); border-radius: var(--radius-sm);
  font-size: var(--font-body-sm); font-weight: var(--weight-medium);
  cursor: pointer; border: 1px solid transparent; transition: all .15s;
}
.sb-btn--primary { background: var(--accent-primary); color: var(--text-on-accent); }
.sb-btn--primary:hover { opacity: .9; }
.sb-btn--primary:disabled { opacity: .4; cursor: not-allowed; }
.sb-btn--ghost { background: none; color: var(--text-secondary); border-color: var(--border-subtle); }
.sb-btn--ghost:hover { background: var(--bg-tertiary); }
.sb-btn--sm { padding: var(--space-1) var(--space-2); font-size: 12px; }
.sb-btn--lg { padding: var(--space-2) var(--space-5); }
.sb-btn--ai {
  background: var(--accent-light); color: var(--accent-primary); border: none;
  padding: var(--space-1) var(--space-3); font-size: 12px; border-radius: var(--radius-sm);
  cursor: pointer; display: flex; align-items: center; gap: 4px;
}
.sb-btn--ai:hover { opacity: .85; }

.sb-spin { animation: sb-spin 1s linear infinite; }
@keyframes sb-spin { to { transform: rotate(360deg); } }

.sb-slide-enter-active, .sb-slide-leave-active { transition: transform .2s ease; }
.sb-slide-enter-from, .sb-slide-leave-to { transform: translateX(100%); }
</style>
