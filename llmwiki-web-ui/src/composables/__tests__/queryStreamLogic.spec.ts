import { describe, it, expect } from 'vitest'
import { reduceFactBlockJson, splitSynthesisAndProspective } from '../queryStreamLogic'

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