import { describe, expect, it } from 'vitest'
import { extractWikiSourceRefs } from '../wikiSourceRefs'

describe('extractWikiSourceRefs', () => {
  it('shouldExtractWikiLinksWithWikiPrefixStripped', () => {
    const refs = extractWikiSourceRefs(
      '参见 [[日报]](wiki/pages/daily.md) 与 [[概览]](wiki/pages/overview)'
    )
    expect(refs).toEqual([
      { title: '日报', path: 'pages/daily.md' },
      { title: '概览', path: 'pages/overview.md' }
    ])
  })

  it('shouldTolerateDoubleBracketLinks', () => {
    const refs = extractWikiSourceRefs(
      '- [[日报]]((wiki/pages/daily.md))\n- [[概览]]((wiki/pages/overview.md))'
    )
    expect(refs).toEqual([
      { title: '日报', path: 'pages/daily.md' },
      { title: '概览', path: 'pages/overview.md' }
    ])
  })

  it('shouldDeduplicateSamePath', () => {
    const refs = extractWikiSourceRefs(
      '[[日报]](wiki/pages/daily.md) 再次引用 [[日报]](wiki/pages/daily.md)'
    )
    expect(refs).toEqual([{ title: '日报', path: 'pages/daily.md' }])
  })

  it('shouldExtractPlainMarkdownPageLinks', () => {
    const refs = extractWikiSourceRefs('参考 [概览](wiki/pages/overview.md) 页面')
    expect(refs).toEqual([{ title: '概览', path: 'pages/overview.md' }])
  })

  it('shouldNormalizeAbsoluteWikiUrls', () => {
    const refs = extractWikiSourceRefs(
      '[[日报]](https://example.com/wiki/pages/daily.md)'
    )
    expect(refs).toEqual([{ title: '日报', path: 'pages/daily.md' }])
  })

  it('shouldIgnoreNonPageLinks', () => {
    expect(extractWikiSourceRefs('外部链接 [x](https://a.com/b)')).toEqual([])
    expect(extractWikiSourceRefs('')).toEqual([])
  })
})
