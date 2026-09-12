import { describe, it, expect } from 'vitest'
import { normalizeConflictExtra } from '../conflictPresentation'
import type { LintFindingInfo } from '@/api/lint'

function makeFinding(partial: Partial<LintFindingInfo>): LintFindingInfo {
  return {
    id: 31,
    scopeId: 1,
    pagePath: null,
    assetId: null,
    findingType: 'conflict',
    priority: 'high',
    title: '',
    detail: null,
    extra: null,
    rulingBriefJson: null,
    handlingMethod: null,
    riskScore: null,
    autoResolvedAt: null,
    repairExecutionId: null,
    status: 'open',
    executionId: 7,
    createdAt: null,
    updatedAt: null,
    userFeedback: null,
    feedbackCount: null,
    archivedAt: null,
    orphanDiagnosis: null,
    ...partial
  }
}

describe('normalizeConflictExtra', () => {
  it('maps canonical extra fields', () => {
    const finding = makeFinding({
      title: '「拜登」与「特朗普」存在矛盾',
      extra: JSON.stringify({
        fromPageId: 11, fromPagePath: 'entities/biden.md', fromPageTitle: '拜登',
        relatedPageId: 12, relatedPagePath: 'entities/trump.md', relatedPageTitle: '特朗普',
        conflictType: 'fact_conflict', claimA: '拜登是第46任总统', claimB: '特朗普是第46任总统',
        source: 'lint_probe'
      })
    })

    const result = normalizeConflictExtra(finding)

    expect(result.conflictType).toBe('fact_conflict')
    expect(result.fromTitle).toBe('拜登')
    expect(result.toTitle).toBe('特朗普')
    expect(result.claimA).toBe('拜登是第46任总统')
    expect(result.claimB).toBe('特朗普是第46任总统')
    expect(result.fromPageId).toBe(11)
    expect(result.toPageId).toBe(12)
    expect(result.canOperate).toBe(true)
    expect(result.hasClaims).toBe(true)
    expect(result.isLegacy).toBe(false)
  })

  it('falls back to quoted title and pagePathB for legacy extra', () => {
    const finding = makeFinding({
      title: '「页面A」与「页面B」存在事实矛盾',
      pagePath: 'entities/a.md',
      assetId: 11,
      extra: JSON.stringify({ pagePathB: 'entities/b.md', claimA: 'A 主张', claimB: 'B 主张' })
    })

    const result = normalizeConflictExtra(finding)

    expect(result.fromTitle).toBe('页面A')
    expect(result.toTitle).toBe('页面B')
    expect(result.fromPagePath).toBe('entities/a.md')
    expect(result.toPagePath).toBe('entities/b.md')
    expect(result.fromPageId).toBe(11)
    expect(result.toPageId).toBeNull()
    expect(result.canOperate).toBe(true)
    expect(result.isLegacy).toBe(true)
  })

  it('keeps empty claims for content duplication findings', () => {
    const finding = makeFinding({
      title: '「Alpha」与「Alpha 副本」内容高度重复',
      extra: JSON.stringify({
        fromPageId: 11, fromPagePath: 'pages/alpha.md', fromPageTitle: 'Alpha',
        relatedPageId: 12, relatedPagePath: 'pages/alpha-copy.md', relatedPageTitle: 'Alpha 副本',
        conflictType: 'content_duplication', claimA: '', claimB: '',
        source: 'content_duplicate_detector'
      })
    })

    const result = normalizeConflictExtra(finding)

    expect(result.conflictType).toBe('content_duplication')
    expect(result.hasClaims).toBe(false)
    expect(result.canOperate).toBe(true)
    expect(result.isLegacy).toBe(false)
  })

  it('parses titles and claims from detail when extra is unusable', () => {
    const finding = makeFinding({
      title: '检测到矛盾',
      detail: '页面A: entities/biden.md\n声明A: 拜登是总统\n页面B: entities/trump.md\n声明B: 特朗普是总统',
      pagePath: 'entities/biden.md',
      assetId: 11
    })

    const result = normalizeConflictExtra(finding)

    expect(result.fromTitle).toBe('biden')
    expect(result.toTitle).toBe('trump')
    expect(result.claimA).toBe('拜登是总统')
    expect(result.claimB).toBe('特朗普是总统')
    expect(result.canOperate).toBe(false)
    expect(result.isLegacy).toBe(true)
  })
})
