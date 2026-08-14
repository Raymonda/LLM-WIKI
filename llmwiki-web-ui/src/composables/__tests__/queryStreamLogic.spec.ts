import { describe, it, expect } from 'vitest'
import { reduceFactBlockJson, splitSynthesisAndProspective, buildFactBlocksMarkdown, parseClarification, injectFactBadges, type FactBlockView } from '../queryStreamLogic'

describe('reduceFactBlockJson', () => {
  it('should append a parsed fact block', () => {
    const blocks = reduceFactBlockJson('{"id":"fb-1","conclusion":"结论","evidence":"依据","confidence":"high","kind":"fact"}', [])
    expect(blocks).toHaveLength(1)
    expect(blocks[0]).toMatchObject({ id: 'fb-1', confidence: 'high' })
  })

  it('should demote invalid json to text block', () => {
    const blocks = reduceFactBlockJson('not json', [])
    expect(blocks).toHaveLength(1)
    expect(blocks[0].kind).toBe('text')
  })
})

describe('splitSynthesisAndProspective', () => {
  it('should split at prospective marker', () => {
    const [synthesis, prospective] = splitSynthesisAndProspective('分析内容\n⚡ **前瞻分析（AI 推演，仅供参考）**\n推演内容')
    expect(synthesis).toContain('分析内容')
    expect(prospective).toContain('推演内容')
  })

  it('should return full content when no marker', () => {
    const [synthesis, prospective] = splitSynthesisAndProspective('无前瞻段落')
    expect(synthesis).toBe('无前瞻段落')
    expect(prospective).toBe('')
  })
})

describe('buildFactBlocksMarkdown', () => {
  it('should render markdown list with confidence and refs', () => {
    const md = buildFactBlocksMarkdown([
      { id: '1', conclusion: '结论A', evidence: '依据A', refs: [{ path: 'wiki/pages/a.md', title: '页面A' }], confidence: 'high', kind: 'fact' },
    ])
    expect(md).toContain('结论A')
    expect(md).toContain('页面A')
    expect(md).toContain('wiki/pages/a.md')
  })
})

describe('parseClarification', () => {
  it('should parse question payload', () => {
    const view = parseClarification('{"question":"您想了解的是？","reason":"指代不明"}')
    expect(view.question).toBe('您想了解的是？')
    expect(view.options).toEqual([])
    expect(view.assumedIntentId).toBeNull()
  })

  it('should fall back to raw text on invalid json', () => {
    const view = parseClarification('not json')
    expect(view.question).toBe('not json')
  })

  it('should coerce non-string options and assumedIntentId to safe values', () => {
    const view = parseClarification('{"question":"q","options":[1, null, "ok"],"assumedIntentId":123}')
    expect(view.options).toEqual(['ok'])
    expect(view.assumedIntentId).toBeNull()
  })
})

const blocks: FactBlockView[] = [
  { id: 'fb-1', conclusion: 'c1', evidence: 'e1', refs: [], confidence: 'high', kind: 'fact' },
  { id: 'fb-2', conclusion: 'c2', evidence: 'e2', refs: [], confidence: 'low', kind: 'fact' },
]

describe('injectFactBadges', () => {
  it('should inject badges for in-range references', () => {
    const out = injectFactBadges('结论 [1] 和 [2]。', blocks)
    expect(out).toContain('data-fact-index="0"')
    expect(out).toContain('data-fact-index="1"')
    expect(out).toContain('fact-ref-badge--high')
    expect(out).toContain('fact-ref-badge--low')
  })

  it('should keep out-of-range references as plain text', () => {
    const out = injectFactBadges('结论 [5]。', blocks)
    expect(out).not.toContain('data-fact-index')
    expect(out).toContain('[5]')
  })

  it('should skip code blocks', () => {
    const out = injectFactBadges('```\ncode [1]\n```\n正文 [1]', blocks)
    expect(out).toContain('code [1]')
    expect(out).toContain('data-fact-index="0"')
  })

  it('should return content unchanged when blocks is null or empty', () => {
    expect(injectFactBadges('正文 [1]', null)).toBe('正文 [1]')
    expect(injectFactBadges('正文 [1]', [])).toBe('正文 [1]')
  })
})
