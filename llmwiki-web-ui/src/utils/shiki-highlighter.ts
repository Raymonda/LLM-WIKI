import { createHighlighter, type Highlighter, type ShikiTransformer } from 'shiki'
import {
  transformerMetaHighlight,
  transformerNotationDiff,
  transformerNotationHighlight,
} from '@shikijs/transformers'

let highlighterInstance: Highlighter | null = null
let initPromise: Promise<void> | null = null
let ready = false

const SHIKI_LANGS = [
  'javascript', 'typescript', 'python', 'java', 'go', 'rust', 'c', 'cpp',
  'csharp', 'kotlin', 'swift', 'scala',
  'bash', 'shell', 'sql', 'json', 'yaml', 'xml', 'html', 'css', 'scss',
  'markdown', 'dockerfile', 'nginx', 'protobuf', 'graphql', 'toml',
  'diff', 'plaintext',
  'php', 'ruby', 'lua', 'haskell', 'r', 'dart', 'elixir', 'clojure',
  'vue', 'vue-html', 'tsx', 'jsx',
]

const SHIKI_THEMES = {
  light: 'github-light',
  dark: 'github-dark',
}

export function getShikiThemes() {
  return SHIKI_THEMES
}

export function isShikiReady(): boolean {
  return ready
}

let resolveReady: (() => void) | null = null
const shikiReadyPromise = new Promise<void>((resolve) => { resolveReady = resolve })

export function waitForShikiReady(): Promise<void> {
  if (ready) return Promise.resolve()
  return shikiReadyPromise
}

export async function initShikiHighlighter(): Promise<void> {
  if (ready && highlighterInstance) return
  if (initPromise) return initPromise

  initPromise = (async () => {
    try {
      highlighterInstance = await createHighlighter({
        themes: [SHIKI_THEMES.light, SHIKI_THEMES.dark],
        langs: SHIKI_LANGS,
      })
      ready = true
      resolveReady?.()
    } catch (e) {
      console.warn('Shiki highlighter init failed:', e)
      ready = false
      initPromise = null
    }
  })()

  return initPromise
}

const SHIKI_TRANSFORMERS: ShikiTransformer[] = [
  transformerNotationHighlight(),
  transformerNotationDiff(),
  transformerMetaHighlight(),
]

export function highlightWithShiki(code: string, lang: string, meta?: string): string {
  if (!highlighterInstance) return escapeHtml(code)

  const validLangs = highlighterInstance.getLoadedLanguages()
  const resolvedLang = validLangs.includes(lang) ? lang : 'plaintext'

  try {
    return highlighterInstance.codeToHtml(code, {
      lang: resolvedLang,
      themes: {
        light: SHIKI_THEMES.light,
        dark: SHIKI_THEMES.dark,
      },
      meta: { __raw: meta || '' },
      transformers: SHIKI_TRANSFORMERS,
    })
  } catch {
    return escapeHtml(code)
  }
}

export function getShikiHighlighter(): Highlighter | null {
  return highlighterInstance
}

export function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

initShikiHighlighter()
