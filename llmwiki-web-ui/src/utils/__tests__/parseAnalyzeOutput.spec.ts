import { describe, expect, it } from 'vitest'
import { parseAnalyzeOutput } from '../parseAnalyzeOutput'

describe('parseAnalyzeOutput', () => {
  it('shouldParseMergedAnalysisAndMetadataWhenOutputValid', () => {
    const output = JSON.stringify({
      mergedAnalysis: '## 分析\n内容',
      metadata: {
        title: '架构文档',
        summary: '摘要',
        category: '技术',
        tags: ['架构'],
        keywords: ['k8s'],
        entities: [{ name: 'Ingest', type: 'concept', description: 'd' }],
        affectedPages: [{ title: '页面A', path: 'pages/a.md', action: '更新', id: 3 }],
      },
    })

    const result = parseAnalyzeOutput(output)

    expect(result.aiAnalysis).toBe('## 分析\n内容')
    expect(result.metadata?.title).toBe('架构文档')
    expect(result.metadata?.entities[0].name).toBe('Ingest')
    expect(result.metadata?.affectedPages[0].path).toBe('pages/a.md')
  })

  it('shouldNormalizeMissingMetadataFieldsWhenOutputPartial', () => {
    const output = JSON.stringify({ metadata: { title: 'T' } })

    const result = parseAnalyzeOutput(output)

    expect(result.metadata).not.toBeNull()
    expect(result.metadata?.summary).toBe('')
    expect(result.metadata?.tags).toEqual([])
    expect(result.metadata?.entities).toEqual([])
    expect(result.metadata?.affectedPages).toEqual([])
  })

  it('shouldFallbackToRawTextWhenOutputNotJson', () => {
    const result = parseAnalyzeOutput('纯文本分析')

    expect(result.aiAnalysis).toBe('纯文本分析')
    expect(result.metadata).toBeNull()
  })

  it('shouldNormalizeEntityAndPageDefaultsWhenOptionalFieldsMissing', () => {
    const output = JSON.stringify({
      metadata: {
        entities: [{ name: 'X' }],
        affectedPages: [{ title: '页面B' }],
      },
    })

    const result = parseAnalyzeOutput(output)

    expect(result.metadata?.entities[0].type).toBe('concept')
    expect(result.metadata?.entities[0].description).toBeUndefined()
    expect(result.metadata?.affectedPages[0].path).toBe('页面B')
    expect(result.metadata?.affectedPages[0].action).toBe('更新')
    expect(result.metadata?.affectedPages[0].id).toBeUndefined()
  })
})
