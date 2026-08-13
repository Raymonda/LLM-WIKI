import { describe, it, expect } from 'vitest'
import { reduceFactBlockJson, splitSynthesisAndProspective, buildFactBlocksMarkdown, parseClarification } from '../queryStreamLogic'

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