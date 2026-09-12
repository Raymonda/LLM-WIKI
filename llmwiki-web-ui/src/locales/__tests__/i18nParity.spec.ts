import { describe, expect, it } from 'vitest'
import zhIngest from '../zh-CN/ingest'
import enIngest from '../en/ingest'
import zhScope from '../zh-CN/scope'
import enScope from '../en/scope'
import zhWiki from '../zh-CN/wiki'
import enWiki from '../en/wiki'
import zhLint from '../zh-CN/lint'
import enLint from '../en/lint'

type LocaleTree = Record<string, unknown>

function collectKeys(tree: LocaleTree, prefix = ''): string[] {
  return Object.entries(tree).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      return collectKeys(value as LocaleTree, path)
    }
    return [path]
  })
}

const modules: Array<[string, LocaleTree, LocaleTree]> = [
  ['ingest', zhIngest, enIngest],
  ['scope', zhScope, enScope],
  ['wiki', zhWiki, enWiki],
  ['lint', zhLint, enLint],
]

describe('i18n key parity', () => {
  for (const [name, zh, en] of modules) {
    it(`shouldHaveIdenticalKeyStructureWhenModuleIs${name}`, () => {
      expect(collectKeys(en).sort()).toEqual(collectKeys(zh).sort())
    })
  }
})
