export type StepType = 'python' | 'ai' | 'system' | 'user'
export type StepStatus = 'pending' | 'running' | 'completed' | 'failed' | 'paused'
export type IngestPhase = 'phase1' | 'phase2'

export interface StepMeta {
  name: string
  label: string
  tip: string
  type: StepType
  weight: number
  baselineMs: number
  phase: IngestPhase
}

export interface StepState {
  name: string
  status: StepStatus
  startedAtMs?: number
  completedAtMs?: number
  current?: number
  total?: number
  avgMsPerUnit?: number
}

export type BaselineProfile = Record<string, number>

export interface ParallelGroup {
  label: string
  stepNames: string[]
}

export const PHASE2_PARALLEL_GROUPS: ParallelGroup[] = []

export interface StepCascade {
  runningChildren?: string[]
  completedChildren?: string[]
  preCompletedOnRunning?: string[]
}

export const STEP_CASCADE: Record<string, StepCascade> = {}

function resolveBaseline(meta: StepMeta, baseline?: BaselineProfile): number {
  const override = baseline?.[meta.name]
  if (typeof override === 'number' && override > 0) return override
  return meta.baselineMs
}

const LEGACY_STEP_NAME_MAP: Record<string, string> = {
  PARSE_DOCUMENT: 'UPLOAD',
  READ_SOURCE: 'UPLOAD',
  SPLIT_CHUNKS: 'UPLOAD',
  ANALYZE_CHUNKS: 'ANALYZE',
  MERGE_RESULTS: 'ANALYZE',
  EXTRACT_METADATA: 'ANALYZE',
  PLANNING: 'WRITE',
  WRITE_SUMMARY: 'WRITE',
  WRITE_ENTITY_PAGES: 'WRITE',
  UPDATE_RELATED: 'WRITE',
  UPDATE_LINKS: 'COMPLETE',
  PROPOSE_SCHEMA_PATCH: 'COMPLETE',
}

export function normalizeStepName(name: string): string {
  return LEGACY_STEP_NAME_MAP[name] ?? name
}

export const INGEST_STEPS: StepMeta[] = [
  {
    name: 'UPLOAD',
    label: '文档解析',
    tip: '正在解析文档格式、提取可读文本、建立章节结构...',
    type: 'system',
    weight: 0.10,
    baselineMs: 8000,
    phase: 'phase1',
  },
  {
    name: 'ANALYZE',
    label: '知识分析',
    tip: 'AI 正在分析全文，提取实体、评估知识价值、构建关系图谱...',
    type: 'ai',
    weight: 0.35,
    baselineMs: 45000,
    phase: 'phase1',
  },
  {
    name: 'WRITE',
    label: '页面写入',
    tip: 'AI 正在规划并生成摘要页、实体页、更新关联页...',
    type: 'ai',
    weight: 0.40,
    baselineMs: 60000,
    phase: 'phase2',
  },
  {
    name: 'COMPLETE',
    label: '质量收尾',
    tip: '正在校验一致性、生成交叉引用、更新搜索索引...',
    type: 'system',
    weight: 0.15,
    baselineMs: 20000,
    phase: 'phase2',
  },
]

const STEP_INDEX: Record<string, StepMeta> = INGEST_STEPS.reduce((acc, s) => {
  acc[s.name] = s
  return acc
}, {} as Record<string, StepMeta>)

export function getStepMeta(name: string): StepMeta | undefined {
  return STEP_INDEX[name]
}

const RUNNING_STEP_CAP = 0.95

export function computeRawProgress(
  stepStates: StepState[],
  nowMs: number,
  includeParsePhase: boolean,
  baselineProfile?: BaselineProfile,
): number {
  const effectiveSteps = INGEST_STEPS.filter(
    s => includeParsePhase || s.name !== 'UPLOAD',
  )
  const totalWeight = effectiveSteps.reduce((sum, s) => sum + s.weight, 0) || 1

  let sum = 0
  for (const meta of effectiveSteps) {
    const state = stepStates.find(s => s.name === meta.name)
    if (!state) continue
    if (state.status === 'completed') {
      sum += meta.weight
    } else if (state.status === 'running') {
      const total = state.total ?? 0
      const current = state.current ?? 0
      let ratio: number
      if (total > 0) {
        ratio = Math.min(RUNNING_STEP_CAP, current / total)
      } else {
        const baseline = resolveBaseline(meta, baselineProfile)
        const elapsed = state.startedAtMs ? Math.max(0, nowMs - state.startedAtMs) : 0
        ratio = Math.min(RUNNING_STEP_CAP, elapsed / Math.max(1, baseline))
      }
      sum += meta.weight * ratio
    } else if (state.status === 'failed') {
      // failed 步骤不再贡献权重
    }
  }
  return Math.max(0, Math.min(1, sum / totalWeight))
}

export function monotonicProgress(prev: number, next: number): number {
  return Math.max(prev, next)
}

export function estimateRemainingMs(
  stepStates: StepState[],
  nowMs: number,
  includeParsePhase: boolean,
  baselineProfile?: BaselineProfile,
): number {
  const effectiveSteps = INGEST_STEPS.filter(
    s => includeParsePhase || s.name !== 'UPLOAD',
  )

  const parallelGroupStepNames = new Set<string>()
  for (const group of PHASE2_PARALLEL_GROUPS) {
    for (const name of group.stepNames) {
      parallelGroupStepNames.add(name)
    }
  }

  let remain = 0
  for (const group of PHASE2_PARALLEL_GROUPS) {
    const groupRemain = group.stepNames.map(name => {
      const meta = STEP_INDEX[name]
      if (!meta) return 0
      const state = stepStates.find(s => s.name === name)
      return stepRemainingMs(meta, state, nowMs, baselineProfile)
    })
    remain += Math.max(...groupRemain)
  }

  for (const meta of effectiveSteps) {
    if (parallelGroupStepNames.has(meta.name)) continue
    const state = stepStates.find(s => s.name === meta.name)
    remain += stepRemainingMs(meta, state, nowMs, baselineProfile)
  }

  return remain
}

function stepRemainingMs(
  meta: StepMeta,
  state: StepState | undefined,
  nowMs: number,
  baselineProfile?: BaselineProfile,
): number {
  const baseline = resolveBaseline(meta, baselineProfile)
  if (!state || state.status === 'pending') {
    return baseline
  } else if (state.status === 'running') {
    const total = state.total ?? 0
    const current = state.current ?? 0
    if (total > 0 && state.avgMsPerUnit && state.avgMsPerUnit > 0) {
      return Math.max(0, (total - current) * state.avgMsPerUnit)
    } else if (total > 0) {
      const ratio = Math.min(1, current / total)
      return Math.max(0, baseline * (1 - ratio))
    } else {
      const elapsed = state.startedAtMs ? Math.max(0, nowMs - state.startedAtMs) : 0
      return Math.max(0, baseline - elapsed)
    }
  }
  return 0
}

export function formatRemaining(ms: number): string {
  if (ms <= 0) return '即将完成'
  if (ms < 3000) return '即将完成'
  if (ms < 60_000) {
    const sec = Math.ceil(ms / 1000)
    return `约 ${sec} 秒`
  }
  const min = Math.floor(ms / 60_000)
  const sec = Math.ceil((ms % 60_000) / 1000)
  if (sec === 0 || sec === 60) return `约 ${min + (sec === 60 ? 1 : 0)} 分钟`
  return `约 ${min} 分 ${sec} 秒`
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  const min = Math.floor(ms / 60_000)
  const sec = Math.round((ms % 60_000) / 1000)
  return `${min}m ${sec}s`
}

export function listStepsByPhase(phase: IngestPhase, includeParse: boolean): StepMeta[] {
  return INGEST_STEPS.filter(s => s.phase === phase && (includeParse || s.name !== 'UPLOAD'))
}
