import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createIngestSSE, getIngestProgress, listActiveIngest, type ExecutionInfo } from '@/api/ingest'
import { useIngestProgressStore } from '../ingestProgress'

vi.mock('@/api/ingest', () => ({
  analyzeIngest: vi.fn(),
  createIngestSSE: vi.fn(),
  cancelIngest: vi.fn(),
  deleteIngest: vi.fn(),
  executeIngest: vi.fn(),
  getIngestProgress: vi.fn(),
  reanalyzeIngest: vi.fn(),
  pauseIngest: vi.fn(),
  resumeIngest: vi.fn(),
  listActiveIngest: vi.fn(),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ scopeId: 1, token: 'test-token' }),
}))

vi.mock('@/api/wiki', () => ({
  listPages: vi.fn().mockResolvedValue([]),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

class FakeEventSource {
  private listeners = new Map<string, Array<(e: MessageEvent) => void>>()
  closed = false

  addEventListener(type: string, cb: (e: MessageEvent) => void) {
    const list = this.listeners.get(type) ?? []
    list.push(cb)
    this.listeners.set(type, list)
  }

  close() {
    this.closed = true
  }

  emit(type: string, payload: unknown) {
    for (const cb of this.listeners.get(type) ?? []) {
      cb({ data: JSON.stringify(payload) } as MessageEvent)
    }
  }
}

function executionInfo(executionId: number, status: string): ExecutionInfo {
  return { executionId, status } as unknown as ExecutionInfo
}

function stepPayload(stepName: string, status: string) {
  return { stepId: 1, stepName, status, outputData: '' }
}

describe('ingestProgress recovery stage mapping', () => {
  let fakeStream: FakeEventSource

  beforeEach(() => {
    vi.stubGlobal('window', {
      setInterval: (fn: () => void, ms: number) => globalThis.setInterval(fn, ms),
      clearInterval: (id: unknown) => globalThis.clearInterval(id as ReturnType<typeof setInterval>),
      setTimeout: (fn: () => void, ms: number) => globalThis.setTimeout(fn, ms),
      clearTimeout: (id: unknown) => globalThis.clearTimeout(id as ReturnType<typeof setTimeout>),
    })
    setActivePinia(createPinia())
    vi.clearAllMocks()
    fakeStream = new FakeEventSource()
    vi.mocked(createIngestSSE).mockReturnValue(fakeStream as unknown as EventSource)
  })

  it('shouldPromoteRunningRecoveryToWritingWhenPhase2StepsReplay', async () => {
    vi.mocked(listActiveIngest).mockResolvedValue([executionInfo(33, 'running')])
    const store = useIngestProgressStore()

    await store.recoverActiveTasks(1)
    expect(store.currentStep).toBe('analyzing')

    fakeStream.emit('step', stepPayload('UPLOAD', 'completed'))
    fakeStream.emit('step', stepPayload('ANALYZE', 'completed'))
    expect(store.currentStep).toBe('analyzing')

    fakeStream.emit('step', stepPayload('WRITE', 'running'))
    expect(store.currentStep).toBe('executing')
  })

  it('shouldPromoteRunningRecoveryToWritingWhenWriteAlreadyCompleted', async () => {
    vi.mocked(listActiveIngest).mockResolvedValue([executionInfo(33, 'running')])
    const store = useIngestProgressStore()

    await store.recoverActiveTasks(1)

    fakeStream.emit('step', stepPayload('UPLOAD', 'completed'))
    fakeStream.emit('step', stepPayload('ANALYZE', 'completed'))
    fakeStream.emit('step', stepPayload('WRITE', 'completed'))
    fakeStream.emit('step', stepPayload('COMPLETE', 'running'))
    expect(store.currentStep).toBe('executing')
  })

  it('shouldMapConfirmedRecoveryToWritingStage', async () => {
    vi.mocked(listActiveIngest).mockResolvedValue([executionInfo(34, 'confirmed')])
    const store = useIngestProgressStore()

    await store.recoverActiveTasks(1)

    expect(store.currentStep).toBe('executing')
    expect(store.executionStatus).toBe('confirmed')
    expect(createIngestSSE).toHaveBeenCalledWith(34)
  })

  it('shouldMapPendingRecoveryToAnalyzingStage', async () => {
    vi.mocked(listActiveIngest).mockResolvedValue([executionInfo(36, 'pending')])
    const store = useIngestProgressStore()

    await store.recoverActiveTasks(1)

    expect(store.currentStep).toBe('analyzing')
    expect(createIngestSSE).toHaveBeenCalledWith(36)
  })

  it('shouldConnectSSEForPausedRecoveryToRebuildStepStates', async () => {
    vi.mocked(listActiveIngest).mockResolvedValue([executionInfo(37, 'paused')])
    const store = useIngestProgressStore()

    await store.recoverActiveTasks(1)

    expect(store.currentStep).toBe('paused')
    expect(createIngestSSE).toHaveBeenCalledWith(37)

    fakeStream.emit('step', stepPayload('UPLOAD', 'completed'))
    fakeStream.emit('step', stepPayload('ANALYZE', 'completed'))
    expect(store.stepStates.find(s => s.name === 'ANALYZE')?.status).toBe('completed')
  })

  it('shouldHydrateTaskFromServerWhenOpeningUnknownTask', async () => {
    vi.mocked(getIngestProgress).mockResolvedValue(executionInfo(40, 'confirmed'))
    const store = useIngestProgressStore()

    await store.openTask(40)

    expect(getIngestProgress).toHaveBeenCalledWith(40)
    expect(store.activeTaskId).toBe(40)
    expect(store.currentStep).toBe('executing')
    expect(createIngestSSE).toHaveBeenCalledWith(40)
  })

  it('shouldExposeBatchIdInTaskSummariesWhenRecovered', async () => {
    vi.mocked(listActiveIngest).mockResolvedValue([
      { executionId: 50, status: 'completed', batchId: 60 } as unknown as ExecutionInfo,
    ])
    const store = useIngestProgressStore()

    await store.recoverActiveTasks(1)

    const summary = store.allTaskSummaries.find(t => t.executionId === 50)
    expect(summary?.batchId).toBe(60)
  })

  it('shouldSkipServerFetchWhenOpeningHydratedTask', async () => {
    vi.mocked(listActiveIngest).mockResolvedValue([executionInfo(41, 'running')])
    const store = useIngestProgressStore()

    await store.recoverActiveTasks(1)
    vi.mocked(getIngestProgress).mockClear()

    await store.openTask(41)

    expect(getIngestProgress).not.toHaveBeenCalled()
    expect(store.activeTaskId).toBe(41)
  })
})
