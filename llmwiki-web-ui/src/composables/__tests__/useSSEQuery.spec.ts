import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useSSEQuery } from '../useSSEQuery'

const { createQuerySSE } = vi.hoisted(() => ({
  createQuerySSE: vi.fn(),
}))

vi.mock('@/api/query', () => ({
  createQuerySSE,
}))

class FakeEventSource {
  closed = false
  private handlers: Record<string, ((e: MessageEvent) => void)[]> = {}
  addEventListener(name: string, cb: (e: MessageEvent) => void) {
    ;(this.handlers[name] ||= []).push(cb)
  }
  close() {
    this.closed = true
  }
  emit(name: string, data: string) {
    for (const cb of this.handlers[name] || []) cb({ data } as MessageEvent)
  }
}

describe('useSSEQuery session id', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    createQuerySSE.mockReset()
  })

  it('should reuse session id when clarification retries after 60s', () => {
    const sse = useSSEQuery()
    const es = new FakeEventSource()
    createQuerySSE.mockReturnValue(es)

    sse.startQuery('原始问题', 'quick')
    expect(createQuerySSE).toHaveBeenCalledTimes(1)
    expect(createQuerySSE.mock.calls[0][1]).toBeUndefined()

    es.emit('start', JSON.stringify({ sessionId: 'sess-42' }))
    es.emit('clarification', JSON.stringify({ question: '追问', reason: 'x', options: ['意图A'] }))

    vi.advanceTimersByTime(60000)

    expect(createQuerySSE).toHaveBeenCalledTimes(2)
    expect(createQuerySSE.mock.calls[1][1]).toBe('sess-42')
  })

  it('should parse narrative flag from start event', () => {
    const es = new FakeEventSource()
    createQuerySSE.mockReturnValue(es as any)
    const sse = useSSEQuery()
    sse.startQuery('问题')
    es.emit('start', JSON.stringify({ sessionId: 's-1', narrative: true }))
    expect(sse.narrativeEnabled.value).toBe(true)
  })

  it('should default narrative to false when flag missing', () => {
    const es = new FakeEventSource()
    createQuerySSE.mockReturnValue(es as any)
    const sse = useSSEQuery()
    sse.startQuery('问题')
    es.emit('start', JSON.stringify({ sessionId: 's-1' }))
    expect(sse.narrativeEnabled.value).toBe(false)
  })
})