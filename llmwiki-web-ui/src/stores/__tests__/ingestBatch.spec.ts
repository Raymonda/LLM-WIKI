import { describe, expect, it, vi } from 'vitest'
import { groupInboxItems, type InboxGroup, type InboxGroupKey } from '../ingestBatch'
import type { IngestBatchItemInfo } from '@/api/ingest'

vi.mock('@/api/ingest', () => ({
  createIngestBatch: vi.fn(),
  fetchBatchInbox: vi.fn(),
  getBatchDetail: vi.fn(),
  confirmBatchItems: vi.fn(),
  pauseBatch: vi.fn(),
  resumeBatch: vi.fn(),
  cancelBatch: vi.fn(),
  executeIngest: vi.fn(),
  reanalyzeIngest: vi.fn(),
  resumeIngest: vi.fn(),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ scopeId: 1 }),
}))

function item(executionId: number, status: string, phase1Completed: boolean | null): IngestBatchItemInfo {
  return {
    executionId,
    sourceId: null,
    sourceName: null,
    sourceFormat: null,
    status,
    totalTokens: null,
    errorMessage: null,
    analyzeOutput: null,
    guidance: null,
    startedAt: null,
    completedAt: null,
    phase1Completed,
  }
}

function idsOf(groups: InboxGroup[], key: InboxGroupKey): number[] {
  return groups.find(g => g.key === key)?.items.map(i => i.executionId) ?? []
}

describe('groupInboxItems', () => {
  it('shouldGroupRunningItemsByPhase1Completed', () => {
    const groups = groupInboxItems([item(1, 'running', true), item(2, 'running', false)])

    expect(idsOf(groups, 'writing')).toEqual([1])
    expect(idsOf(groups, 'analyzing')).toEqual([2])
  })

  it('shouldMapItemStatusesToActionGroups', () => {
    const groups = groupInboxItems([
      item(1, 'awaiting_confirmation', false),
      item(2, 'pending', false),
      item(3, 'confirmed', true),
      item(4, 'completed', true),
      item(5, 'failed', true),
      item(6, 'cancelled', true),
      item(7, 'paused', true),
    ])

    expect(idsOf(groups, 'awaiting')).toEqual([1])
    expect(idsOf(groups, 'queued')).toEqual([2, 7])
    expect(idsOf(groups, 'writing')).toEqual([3])
    expect(idsOf(groups, 'completed')).toEqual([4])
    expect(idsOf(groups, 'failed')).toEqual([5, 6])
  })
})
