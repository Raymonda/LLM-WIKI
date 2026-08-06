<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Lightbulb, Sparkles, BrainCircuit, BookOpen, Atom, Globe, Puzzle, Rocket } from 'lucide-vue-next'

export interface FunFact {
  icon: string
  text: string
}

const props = defineProps<{
  active: boolean
  mode?: 'quick' | 'deep'
  facts?: FunFact[]
}>()

const { t } = useI18n()

const STATIC_FALLBACK_FACTS = computed<FunFact[]>(() => [
  { icon: 'lightbulb', text: t('wiki.funFact1') },
  { icon: 'atom', text: t('wiki.funFact2') },
  { icon: 'brain', text: t('wiki.funFact3') },
  { icon: 'globe', text: t('wiki.funFact4') },
  { icon: 'sparkles', text: t('wiki.funFact5') },
  { icon: 'puzzle', text: t('wiki.funFact6') },
  { icon: 'book', text: t('wiki.funFact7') },
  { icon: 'rocket', text: t('wiki.funFact8') },
  { icon: 'lightbulb', text: t('wiki.funFact9') },
  { icon: 'atom', text: t('wiki.funFact10') },
  { icon: 'brain', text: t('wiki.funFact11') },
  { icon: 'sparkles', text: t('wiki.funFact12') },
])

const icons: Record<string, any> = {
  lightbulb: Lightbulb,
  sparkles: Sparkles,
  brain: BrainCircuit,
  book: BookOpen,
  atom: Atom,
  globe: Globe,
  puzzle: Puzzle,
  rocket: Rocket,
}

const currentIndex = ref(0)
const isFading = ref(false)
const isPaused = ref(false)
const hasDynamicFacts = ref(false)
const shuffledFacts = ref<FunFact[]>([])
let timer: ReturnType<typeof setInterval> | null = null

const currentFacts = computed(() => {
  if (props.facts && props.facts.length > 0) {
    return props.facts
  }
  return STATIC_FALLBACK_FACTS.value
})

function shuffle<T>(arr: T[]): T[] {
  const result = [...arr]
  for (let i = result.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [result[i], result[j]] = [result[j], result[i]]
  }
  return result
}

function nextFact() {
  if (shuffledFacts.value.length <= 1) return
  isFading.value = true
  setTimeout(() => {
    currentIndex.value = (currentIndex.value + 1) % shuffledFacts.value.length
    isFading.value = false
  }, 600)
}

function startTimer() {
  stopTimer()
  if (isPaused.value) return
  timer = setInterval(nextFact, 12000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function onMouseEnter() {
  isPaused.value = true
  stopTimer()
}

function onMouseLeave() {
  isPaused.value = false
  if (props.active) startTimer()
}

function initFacts(source: FunFact[]) {
  shuffledFacts.value = shuffle(source)
  currentIndex.value = 0
}

watch(() => props.active, (active) => {
  if (active) {
    initFacts(currentFacts.value)
    isFading.value = false
    hasDynamicFacts.value = !!(props.facts && props.facts.length > 0)
    startTimer()
  } else {
    stopTimer()
    hasDynamicFacts.value = false
  }
})

watch(() => props.facts, (newFacts) => {
  if (props.active && newFacts && newFacts.length > 0 && !hasDynamicFacts.value) {
    hasDynamicFacts.value = true
    initFacts(newFacts)
  }
})

onMounted(() => {
  if (props.active) {
    initFacts(currentFacts.value)
    startTimer()
  }
})

onUnmounted(() => { stopTimer() })

const currentFact = computed(() => {
  const facts = shuffledFacts.value
  if (facts.length === 0) return { icon: 'lightbulb', text: '' }
  return facts[currentIndex.value] || { icon: 'lightbulb', text: '' }
})
</script>

<template>
  <div v-if="active" class="fun-facts" @mouseenter="onMouseEnter" @mouseleave="onMouseLeave">
    <div class="fun-facts__header">
      <span class="fun-facts__header-icon">💡</span>
      <span class="fun-facts__header-text">
        {{ mode === 'deep' ? t('wiki.funFactDeepMode') : t('wiki.funFactQuickMode') }}
      </span>
    </div>
    <div class="fun-facts__card" :class="{ 'fun-facts__card--fading': isFading }">
      <component
        :is="icons[currentFact.icon] || Lightbulb"
        :size="16"
        class="fun-facts__card-icon"
      />
      <p class="fun-facts__card-text">{{ currentFact.text }}</p>
    </div>
    <div class="fun-facts__dots">
      <span
        v-for="(_, i) in shuffledFacts"
        :key="i"
        class="fun-facts__dot"
        :class="{ 'fun-facts__dot--active': i === currentIndex }"
      ></span>
    </div>
    <div v-if="hasDynamicFacts" class="fun-facts__ai-badge">
      <Sparkles :size="10" />
      <span>{{ t('wiki.funFactAiBadge') }}</span>
    </div>
  </div>
</template>

<style scoped>
.fun-facts {
  margin-top: var(--space-4);
  animation: funFactsSlideIn 0.5s ease-out;
}

@keyframes funFactsSlideIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.fun-facts__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
}

.fun-facts__header-icon {
  font-size: 14px;
}

.fun-facts__card {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--bg-secondary);
  border: 1px solid var(--border-primary);
  border-radius: var(--radius-md);
  transition: opacity 0.6s ease, transform 0.6s ease;
  opacity: 1;
  transform: translateX(0);
}

.fun-facts__card--fading {
  opacity: 0;
  transform: translateX(-8px);
}

.fun-facts__card-icon {
  flex-shrink: 0;
  color: var(--accent-primary);
  margin-top: 2px;
  opacity: 0.7;
}

.fun-facts__card-text {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--font-body-sm);
  line-height: 1.6;
}

.fun-facts__dots {
  display: flex;
  justify-content: center;
  gap: 4px;
  margin-top: var(--space-2);
}

.fun-facts__dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--border-secondary);
  transition: all 0.3s ease;
}

.fun-facts__dot--active {
  background: var(--accent-primary);
  width: 12px;
  border-radius: 2px;
}

.fun-facts__ai-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  margin-top: var(--space-2);
  color: var(--text-quaternary);
  font-size: 11px;
}
</style>
