import { ref, onUnmounted } from 'vue'
import { createQuerySSE, type QueryAnalysisMode } from '@/api/query'
import { reduceFactBlockJson, splitSynthesisAndProspective, type FactBlockView } from './queryStreamLogic'

export type QueryMode = 'tool-calling' | 'fallback' | 'rate-limited' | 'no-ai' | ''

interface ProgressStep {
  id: string
  label: string
  status: 'pending' | 'running' | 'done'
}

export interface FunFact {
  icon: string
  text: string
}

const DEFAULT_PROGRESS_STEPS: ProgressStep[] = [
  { id: 'search-index', label: '搜索知识索引', status: 'pending' },
  { id: 'progressive-retrieve', label: '渐进式检索', status: 'pending' },
  { id: 'generate-answer', label: '生成回答', status: 'pending' },
]

export function useSSEQuery() {
  const factAnswer = ref('')
  const synthesisStreamContent = ref('')
  const synthesisDisplayContent = ref('')
  const prospectiveAnswer = ref('')
  const aiAnswer = ref('')
  const isStreaming = ref(false)
  const queryError = ref('')
  const queryMode = ref<QueryMode>('')
  const isLoading = ref(false)
  const progressSteps = ref<ProgressStep[]>(DEFAULT_PROGRESS_STEPS.map(s => ({ ...s })))
  const isSynthesizing = ref(false)
  const funFacts = ref<FunFact[]>([])
  const factBlocks = ref<FactBlockView[]>([])

  let eventSource: EventSource | null = null
  let progressTimers: ReturnType<typeof setTimeout>[] = []

  function closeEventSource() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  function clearProgressTimers() {
    progressTimers.forEach(clearTimeout)
    progressTimers = []
  }

  function resetProgress() {
    clearProgressTimers()
    progressSteps.value = DEFAULT_PROGRESS_STEPS.map(s => ({ ...s, status: 'pending' as const }))
  }

  function startProgress() {
    resetProgress()
    progressSteps.value[0].status = 'running'
  }

  function completeProgress() {
    clearProgressTimers()
    progressSteps.value.forEach(s => { s.status = 'done' })
  }

  function attachSSEListeners(es: EventSource, onComplete?: () => void) {
    es.addEventListener('start', () => {
      isLoading.value = true
      isStreaming.value = true
    })

    es.addEventListener('mode', (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data)
        if (data.mode) queryMode.value = data.mode
      } catch {
        queryMode.value = ''
      }
    })

    es.addEventListener('step', (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data)
        const stepName = data.step
        if (stepName === 'retrieving') {
          progressSteps.value[0].status = 'done'
          progressSteps.value[1].status = 'running'
        } else if (stepName === 'generating') {
          progressSteps.value[1].status = 'done'
          progressSteps.value[2].status = 'running'
        } else if (stepName === 'synthesizing') {
          isSynthesizing.value = true
        }
      } catch {}
    })

    es.addEventListener('fun-facts', (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data)
        if (Array.isArray(data.facts)) {
          funFacts.value = data.facts.map((f: any) => ({
            icon: f.icon || 'lightbulb',
            text: f.text || ''
          })).filter((f: FunFact) => f.text)
        }
      } catch {}
    })

    es.addEventListener('answer-chunk', (e: MessageEvent) => {
      if (progressSteps.value[2].status === 'running') completeProgress()
      isLoading.value = false
      let chunk: string
      try {
        const data = JSON.parse(e.data)
        chunk = data.content || e.data
      } catch {
        chunk = e.data
      }
      if (isSynthesizing.value) {
        synthesisStreamContent.value += chunk
      } else {
        factAnswer.value += chunk
      }
      aiAnswer.value += chunk
    })

    es.addEventListener('fact-block', (e: MessageEvent) => {
      if (progressSteps.value[2].status === 'running') completeProgress()
      isLoading.value = false
      factBlocks.value = reduceFactBlockJson(e.data, factBlocks.value)
    })

    es.addEventListener('answer-complete', () => {
      ;[synthesisDisplayContent.value, prospectiveAnswer.value] = splitSynthesisAndProspective(synthesisStreamContent.value)
      isLoading.value = false
      isStreaming.value = false
      isSynthesizing.value = false
      completeProgress()
      closeEventSource()
      onComplete?.()
    })

    es.addEventListener('error', (e: MessageEvent) => {
      if (synthesisStreamContent.value) {
        ;[synthesisDisplayContent.value, prospectiveAnswer.value] = splitSynthesisAndProspective(synthesisStreamContent.value)
      }
      isLoading.value = false
      isStreaming.value = false
      isSynthesizing.value = false
      clearProgressTimers()
      try {
        const data = JSON.parse(e.data)
        queryError.value = data.message || 'AI 问答暂时不可用'
      } catch {
        queryError.value = 'AI 问答暂时不可用'
      }
      closeEventSource()
      onComplete?.()
    })

    es.onerror = () => {
      if (synthesisStreamContent.value) {
        ;[synthesisDisplayContent.value, prospectiveAnswer.value] = splitSynthesisAndProspective(synthesisStreamContent.value)
      }
      if (isLoading.value) {
        isLoading.value = false
        queryError.value = 'SSE 连接中断'
      }
      isStreaming.value = false
      isSynthesizing.value = false
      clearProgressTimers()
      closeEventSource()
    }
  }

  function startQuery(question: string, mode: QueryAnalysisMode = 'quick', onComplete?: () => void) {
    if (!question || isStreaming.value) return

    factAnswer.value = ''
    synthesisStreamContent.value = ''
    synthesisDisplayContent.value = ''
    prospectiveAnswer.value = ''
    aiAnswer.value = ''
    queryError.value = ''
    queryMode.value = ''
    isLoading.value = true
    isStreaming.value = true
    isSynthesizing.value = false
    funFacts.value = []
    factBlocks.value = []
    closeEventSource()

    startProgress()

    eventSource = createQuerySSE(question, undefined, mode)
    attachSSEListeners(eventSource, onComplete)
  }

  function reset() {
    closeEventSource()
    clearProgressTimers()
    resetProgress()
    factAnswer.value = ''
    synthesisStreamContent.value = ''
    synthesisDisplayContent.value = ''
    prospectiveAnswer.value = ''
    aiAnswer.value = ''
    queryError.value = ''
    queryMode.value = ''
    isStreaming.value = false
    isLoading.value = false
    isSynthesizing.value = false
    funFacts.value = []
    factBlocks.value = []
  }

  onUnmounted(() => {
    closeEventSource()
    clearProgressTimers()
  })

  return {
    factAnswer,
    synthesisStreamContent,
    synthesisDisplayContent,
    prospectiveAnswer,
    aiAnswer,
    isStreaming,
    queryError,
    queryMode,
    isLoading,
    progressSteps,
    isSynthesizing,
    funFacts,
    factBlocks,
    startQuery,
    reset,
    completeProgress,
    closeEventSource,
  }
}
