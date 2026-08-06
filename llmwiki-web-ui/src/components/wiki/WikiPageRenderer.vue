<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import MarkdownIt from 'markdown-it'
import markdownItAnchor from 'markdown-it-anchor'
import markdownItTocDoneRight from 'markdown-it-toc-done-right'
import markdownItTaskLists from 'markdown-it-task-lists'
import markdownItFootnote from 'markdown-it-footnote'
import markdownItKatex from '@traptitech/markdown-it-katex'
import markdownItDeflist from 'markdown-it-deflist'
import markdownItSub from 'markdown-it-sub'
import markdownItSup from 'markdown-it-sup'
import {
  isShikiReady,
  highlightWithShiki,
  escapeHtml,
  waitForShikiReady,
} from '../../utils/shiki-highlighter'
import 'katex/dist/katex.min.css'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const { t } = useI18n()

const props = withDefaults(defineProps<{
  content: string
  streaming?: boolean
  linkResolution?: Record<string, number>
  sources?: Array<{ id: number; name: string; format: string }>
}>(), {
  streaming: false,
  linkResolution: () => ({}),
  sources: () => [],
})

const emit = defineEmits<{
  (e: 'toc', headings: TocItem[]): void
  (e: 'preview-source', sourceId: number, format: string): void
}>()

export interface TocItem {
  id: string
  text: string
  level: number
  children?: TocItem[]
}

const containerRef = ref<HTMLElement | null>(null)
const copiedCode = ref<string | null>(null)
const tocHeadings = ref<TocItem[]>([])
let tocCollector: TocItem[] = []
const isStreamingDiagrams = computed(() => props.streaming)
const echartsInstances = new Map<HTMLElement, { chart: any; observer: ResizeObserver }>()
const mermaidRendered = new Set<string>()
let shikiReadyTrigger = ref(0)

function stableHash(str: string): string {
  let hash = 0x811c9dc5
  for (let i = 0; i < str.length; i++) {
    hash ^= str.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(36)
}

const codeStore = new Map<string, string>()
const echartsOptionStore = new Map<string, string>()

function stripFrontmatter(content: string): string {
  const trimmed = content.trimStart()
  if (!trimmed.startsWith('---')) return content
  const afterFirst = trimmed.indexOf('\n', 3)
  if (afterFirst === -1) return content
  const closingIdx = trimmed.indexOf('\n---', afterFirst)
  if (closingIdx === -1) return content
  const between = trimmed.slice(afterFirst + 1, closingIdx)
  const hasYamlKey = between.split('\n').some(line => /^\s*[\w-]+\s*:/.test(line))
  if (!hasYamlKey) return content
  const nextNewline = trimmed.indexOf('\n', closingIdx + 4)
  return nextNewline === -1 ? '' : trimmed.slice(nextNewline + 1)
}

// ── MarkdownIt instance ───────────────────────────────────────────────
const mdInstance = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: true,
  highlight: (code, lang, info) => {
    if (isShikiReady() && lang) {
      return highlightWithShiki(code, lang, info)
    }
    return escapeHtml(code)
  },
})

mdInstance.use(markdownItAnchor, {
  slugify: (s: string) => {
    const base = s.trim().toLowerCase().replace(/[\s+]/g, '-').replace(/[^\w\u4e00-\u9fff-]/g, '')
    return base || 'heading'
  },
  callback: (token: any, info: any) => {
    tocCollector.push({ id: info.slug, text: info.title, level: Number(token.tag.slice(1)) })
  },
})

mdInstance.use(markdownItTocDoneRight, {
  containerClass: 'wiki-toc-nav', containerId: 'wiki-toc', listType: 'ul',
  slugify: (s: string) => s.trim().toLowerCase().replace(/[\s+]/g, '-').replace(/[^\w\u4e00-\u9fff-]/g, ''),
})

mdInstance.use(markdownItTaskLists, { disabled: false, lineNumber: true })
mdInstance.use(markdownItFootnote)
mdInstance.use(markdownItKatex, { throwOnError: false, errorColor: '#cc0000' })
mdInstance.use(markdownItDeflist)
mdInstance.use(markdownItSub)
mdInstance.use(markdownItSup)

if (mdInstance.linkify) {
  mdInstance.linkify.set({ fuzzyIP: false, fuzzyEmail: false })
}

function escapeAttr(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

const SOURCE_REF_PREFIX = '<span class="wiki-source-ref" role="button" tabindex="0" data-source-name="'
const sourceRefCounter = new Map<string, number>()

function sourceRefTextPlugin(md: MarkdownIt) {
  md.inline.ruler.before('linkify', 'source_ref_text', (state, silent) => {
    const start = state.pos
    const max = state.posMax
    const src = state.src

    const openCh = src.charCodeAt(start)
    if (openCh !== 0xFF08 && openCh !== 0x0028) return false

    if (start + 4 >= max) return false
    if (src.charCodeAt(start + 1) !== 0x6765 || src.charCodeAt(start + 2) !== 0x6E90) return false

    const colonCh = src.charCodeAt(start + 3)
    if (colonCh !== 0xFF1A && colonCh !== 0x003A) return false

    const contentStart = start + 4
    let parenClose = -1
    for (let i = contentStart; i < max; i++) {
      const ch = src.charCodeAt(i)
      if (ch === 0xFF09 || ch === 0x0029) { parenClose = i; break }
    }
    if (parenClose === -1) return false

    const filename = src.slice(contentStart, parenClose)
    if (!filename.match(/^[^（）()\n]+$/)) return false

    if (silent) return true

    const fullEnd = parenClose + 1
    const existing = sourceRefCounter.get(filename)
    const num = existing ?? (sourceRefCounter.size + 1)
    if (!existing) sourceRefCounter.set(filename, num)

    const openTag = state.push('html_inline', '', 0)
    openTag.content = `${SOURCE_REF_PREFIX}${escapeAttr(filename)}" title="${escapeAttr(filename)}"><span class="wiki-source-ref__bracket">[</span><span class="wiki-source-ref__num">${num}</span><span class="wiki-source-ref__bracket">]</span></span>`

    state.pos = fullEnd
    return true
  })
}
mdInstance.use(sourceRefTextPlugin)

// ── GitHub Admonition Plugin ──────────────────────────────────────────
const ADMONITION_TYPES = computed<Record<string, { label: string; cls: string }>>(() => ({
  NOTE: { label: t('wiki.admonitionNote'), cls: 'info' },
  TIP: { label: t('wiki.admonitionTip'), cls: 'success' },
  IMPORTANT: { label: t('wiki.admonitionImportant'), cls: 'accent' },
  WARNING: { label: t('wiki.admonitionWarning'), cls: 'warning' },
  CAUTION: { label: t('wiki.admonitionCaution'), cls: 'error' },
}))
const ADMONITION_RE = /^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*/

function admonitionPlugin(md: MarkdownIt) {
  const origRender = md.renderer.rules.blockquote_open ||
    ((tokens: any, idx: number, options: any, _env: any, self: any) => self.renderToken(tokens, idx, options))

  md.renderer.rules.blockquote_open = (tokens, idx, options, env, self) => {
    let contentStart = -1
    for (let j = idx + 1; j < tokens.length; j++) {
      if (tokens[j].type === 'blockquote_close') break
      if (tokens[j].type === 'inline' && tokens[j].content) { contentStart = j; break }
    }
    if (contentStart === -1) return origRender(tokens, idx, options, env, self)
    const match = tokens[contentStart].content.match(ADMONITION_RE)
    if (!match) return origRender(tokens, idx, options, env, self)

    const type = match[1]
    const { label, cls } = ADMONITION_TYPES.value[type]
    tokens[contentStart].content = tokens[contentStart].content.replace(ADMONITION_RE, '').trim()
    tokens[idx].tag = 'div'
    tokens[idx].attrSet('class', `wiki-admonition wiki-admonition--${cls}`)
    for (let j = idx + 1; j < tokens.length; j++) {
      if (tokens[j].type === 'blockquote_close') { tokens[j].tag = 'div'; tokens[j].meta = { isAdmonition: true }; break }
    }
    return `<div class="wiki-admonition wiki-admonition--${cls}"><div class="wiki-admonition__header"><span class="wiki-admonition__icon"></span><span class="wiki-admonition__label">${label}</span></div><div class="wiki-admonition__body">`
  }

  const origClose = md.renderer.rules.blockquote_close ||
    ((tokens: any, idx: number, options: any, _env: any, self: any) => self.renderToken(tokens, idx, options))
  md.renderer.rules.blockquote_close = (tokens, idx, options, env, self) => {
    if (tokens[idx].meta?.isAdmonition) return '</div></div>'
    return origClose(tokens, idx, options, env, self)
  }
}
mdInstance.use(admonitionPlugin)

// ── Link handling ─────────────────────────────────────────────────────
const WIKI_PAGE_HREF_PATTERN = /^(?:wiki\/)?pages\/.+\.md$/
const ABSOLUTE_WIKI_URL_PATTERN = /^https?:\/\/[^/]+\/(?:wiki\/)?pages\/(.+)$/

function normalizeWikiHref(href: string): string | null {
  if (WIKI_PAGE_HREF_PATTERN.test(href)) {
    return href.startsWith('wiki/') ? href.slice(5) : href
  }
  const absMatch = ABSOLUTE_WIKI_URL_PATTERN.exec(href)
  if (absMatch) {
    let rest = decodeURIComponent(absMatch[1])
    if (!rest.endsWith('.md')) rest += '.md'
    return `pages/${rest}`
  }
  return null
}

function isWikiPageHref(href: string): boolean {
  return normalizeWikiHref(href) !== null
}

function rewriteWikiPageHref(href: string): string {
  const normalized = normalizeWikiHref(href)
  return `/wiki/p/${normalized || href}`
}

const defaultLinkOpenRenderer = mdInstance.renderer.rules.link_open ||
  ((tokens: any, idx: number, options: any, _env: any, self: any) => self.renderToken(tokens, idx, options))

mdInstance.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const href = token.attrGet('href')
  if (href) {
    if (href.startsWith('source-ref:')) {
      const sourceName = decodeURIComponent(href.slice('source-ref:'.length))
      for (let j = idx + 1; j < tokens.length; j++) {
        if (tokens[j].type === 'link_close') {
          (tokens[j] as any).__isSourceRef = true
          break
        }
      }
      const existing = sourceRefCounter.get(sourceName)
      const num = existing ?? (sourceRefCounter.size + 1)
      if (!existing) sourceRefCounter.set(sourceName, num)
      return `${SOURCE_REF_PREFIX}${escapeAttr(sourceName)}" title="${escapeAttr(sourceName)}"><span class="wiki-source-ref__bracket">[</span><span class="wiki-source-ref__num">${num}</span><span class="wiki-source-ref__bracket">]</span></span>`
    }
    if (isWikiPageHref(href)) {
      token.attrSet('href', rewriteWikiPageHref(href))
      token.attrSet('class', 'wiki-link wiki-link--resolved')
    } else if (href.startsWith('http://') || href.startsWith('https://')) {
      token.attrSet('target', '_blank')
      token.attrSet('rel', 'noopener noreferrer')
    }
  }
  return defaultLinkOpenRenderer(tokens, idx, options, env, self)
}

const defaultLinkCloseRenderer = mdInstance.renderer.rules.link_close ||
  ((tokens: any, idx: number, options: any, _env: any, self: any) => self.renderToken(tokens, idx, options))

mdInstance.renderer.rules.link_close = (tokens, idx, options, env, self) => {
  if ((tokens[idx] as any).__isSourceRef) {
    return '</span>'
  }
  return defaultLinkCloseRenderer(tokens, idx, options, env, self)
}

mdInstance.inline.ruler.push('wiki_link', (state, silent) => {
  const start = state.pos
  const max = state.posMax
  if (start >= max - 2) return false
  if (state.src.charCodeAt(start) !== 0x5b) return false
  if (state.src.charCodeAt(start + 1) !== 0x5b) return false

  let end = start + 2
  let hasParenPath = false
  let parenPath = ''
  let titleEnd = -1

  while (end < max) {
    if (state.src.charCodeAt(end) === 0x5d && end + 1 < max && state.src.charCodeAt(end + 1) === 0x5d) {
      if (end + 2 < max && state.src.charCodeAt(end + 2) === 0x28) {
        hasParenPath = true
        titleEnd = end
        end += 3
        const parenStart = end
        while (end < max) {
          if (state.src.charCodeAt(end) === 0x29) break
          end++
        }
        if (end >= max) return false
        parenPath = state.src.slice(parenStart, end)
        end += 1
      }
      break
    }
    end++
  }
  if (end >= max) return false
  if (silent) return true

  const title = hasParenPath ? state.src.slice(start + 2, titleEnd) : state.src.slice(start + 2, end)
  const token = state.push('wiki_link', 'a', 0)

  if (hasParenPath && parenPath) {
    let filePath = parenPath
    if (filePath.startsWith('wiki/')) filePath = filePath.slice(5)
    token.attrSet('href', `/wiki/p/${filePath}`)
    token.attrSet('class', 'wiki-link wiki-link--resolved')
    token.meta = { hasParenPath: true }
  } else {
    const resolvedId = props.linkResolution[title]
    if (resolvedId != null) {
      token.attrSet('href', `/wiki/${resolvedId}`)
      token.attrSet('class', 'wiki-link wiki-link--resolved')
    } else {
      token.attrSet('href', `/search?query=${encodeURIComponent(title)}&mode=query`)
      token.attrSet('class', 'wiki-link wiki-link--unresolved')
    }
  }

  token.content = title
  state.pos = end + (hasParenPath ? 0 : 2)
  return true
})

mdInstance.renderer.rules.wiki_link = (tokens, idx) => {
  const token = tokens[idx]
  const href = token.attrGet('href') || ''
  const cls = token.attrGet('class') || 'wiki-link'
  return `<a href="${escapeHtml(href)}" class="${escapeHtml(cls)}">${escapeHtml(token.content)}</a>`
}

// ── Image path rewriting ──────────────────────────────────────────────
const defaultImageRenderer = mdInstance.renderer.rules.image ||
  ((tokens: any, idx: number, options: any, _env: any, self: any) => self.renderToken(tokens, idx, options))

mdInstance.renderer.rules.image = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const src = token.attrGet('src') || ''
  if (src && !src.startsWith('http://') && !src.startsWith('https://') && !src.startsWith('data:') && !src.startsWith('/') && !src.startsWith('#')) {
    let assetUrl = `/api/wiki/assets/${src}`
    if (authStore.token) {
      assetUrl += `?token=${encodeURIComponent(authStore.token)}`
    }
    token.attrSet('src', assetUrl)
  }
  return defaultImageRenderer(tokens, idx, options, env, self)
}

// ── Code block meta parsing ──────────────────────────────────────────
interface CodeMeta { filename: string; highlightLines: string }

function parseCodeMeta(info: string): CodeMeta {
  let filename = ''
  let highlightLines = ''
  const filenameMatch = info.match(/(?:title|filename|path)=["']?([^"'\s]+)["']?/)
  if (filenameMatch) filename = filenameMatch[1]
  const hlMatch = info.match(/\{([\d,\s-]+)\}/)
  if (hlMatch) highlightLines = hlMatch[0]
  return { filename, highlightLines }
}

// ── Fence renderer override ──────────────────────────────────────────
const defaultFenceRenderer = mdInstance.renderer.rules.fence ||
  ((tokens: any, idx: number, options: any) =>
    `<pre${options.langPrefix ? ` class="${options.langPrefix}"` : ''}><code>${escapeHtml(tokens[idx].content)}</code></pre>`)

const CODE_FOLD_THRESHOLD = 30

mdInstance.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const rawInfo = (token.info || '').trim()
  const lang = rawInfo.split(/\s+/)[0].toLowerCase()

  if (lang === 'mermaid') {
    const code = token.content.trim()
    if (isStreamingDiagrams.value) {
      return `<div class="wiki-diagram-streaming-wrap">` +
        `<div class="wiki-diagram-streaming-hint">⏳ ${t('wiki.diagramGenerating')}</div>` +
        `<pre class="wiki-code-diagram-streaming"><code>${escapeHtml(code)}</code></pre></div>`
    }
    const id = `mermaid-${idx}-${stableHash(code)}`
    return `<div class="wiki-mermaid" data-mermaid-id="${id}">` +
      `<pre class="mermaid-src">${escapeHtml(code)}</pre>` +
      `<div class="mermaid-viewport" id="${id}"></div>` +
      `<div class="wiki-mermaid__controls">` +
        `<button class="wiki-diagram-btn" data-action="zoom-in" title="${t('wiki.diagramZoomIn')}">+</button>` +
        `<button class="wiki-diagram-btn" data-action="zoom-out" title="${t('wiki.diagramZoomOut')}">−</button>` +
        `<button class="wiki-diagram-btn" data-action="zoom-reset" title="${t('wiki.diagramZoomReset')}">⊙</button>` +
      `</div></div>`
  }

  if (lang === 'echarts') {
    if (isStreamingDiagrams.value) {
      return `<div class="wiki-diagram-streaming-wrap">` +
        `<div class="wiki-diagram-streaming-hint">⏳ ${t('wiki.diagramGenerating')}</div>` +
        `<pre class="wiki-code-diagram-streaming"><code>${escapeHtml(token.content.trim())}</code></pre></div>`
    }
    try {
      const optionStr = token.content.trim()
      JSON.parse(optionStr)
      const id = `echarts-${idx}-${stableHash(optionStr)}`
      echartsOptionStore.set(id, optionStr)
      return `<div class="wiki-echarts-wrap" data-echarts-wrap="${id}">` +
        `<div class="wiki-echarts" data-echarts-id="${id}"></div>` +
        `<div class="wiki-echarts__toolbar">` +
          `<button class="wiki-echarts__tool-btn" data-action="export-png" data-echarts-target="${id}" title="${t('wiki.echartsExportPng')}">📷</button>` +
          `<button class="wiki-echarts__tool-btn" data-action="fullscreen" data-echarts-target="${id}" title="${t('wiki.echartsFullscreen')}">⛶</button>` +
        `</div></div>`
    } catch {
      return `<div class="wiki-echarts wiki-echarts--error"><p>${t('wiki.echartsParseError')}</p></div>`
    }
  }

  const meta = parseCodeMeta(rawInfo)
  const result = defaultFenceRenderer(tokens, idx, options, env, self)

  const lineCount = token.content.replace(/\n$/, '').split('\n').length
  const isCollapsible = lineCount >= CODE_FOLD_THRESHOLD
  const wrapOpen = isCollapsible ? '<div class="wiki-code-collapsible">' : ''
  const wrapClose = isCollapsible
    ? `<button class="wiki-code-toggle" data-action="toggle-code">${t('wiki.codeExpandAll', [lineCount])}</button></div>`
    : ''

  const codeId = `c${idx}-${stableHash(token.content)}`
  codeStore.set(codeId, token.content)

  const headerRight = meta.filename
    ? `<span class="wiki-code-filename">${escapeHtml(meta.filename)}</span>` +
      `<button class="wiki-code-copy" data-code-id="${codeId}" title="${t('wiki.copyCode')}"><span class="wiki-code-copy-icon"></span></button>`
    : `<button class="wiki-code-copy" data-code-id="${codeId}" title="${t('wiki.copyCode')}"><span class="wiki-code-copy-icon"></span></button>`

  return `${wrapOpen}<div class="wiki-code-block">` +
    `<div class="wiki-code-header"><span class="wiki-code-lang">${lang || 'code'}</span>${headerRight}</div>` +
    `${result}</div>${wrapClose}`
}

// ── Table wrapper ────────────────────────────────────────────────────
const defaultTableRenderer = mdInstance.renderer.rules.table_open || (() => '<table>')
const defaultTableCloseRenderer = mdInstance.renderer.rules.table_close || (() => '</table>')
mdInstance.renderer.rules.table_open = (tokens, idx, options, env, self) =>
  '<div class="wiki-table-wrap">' + defaultTableRenderer(tokens, idx, options, env, self)
mdInstance.renderer.rules.table_close = (tokens, idx, options, env, self) =>
  defaultTableCloseRenderer(tokens, idx, options, env, self) + '</div>'

// ── Rendered content ─────────────────────────────────────────────────
const streamCache = reactive({
  stableContent: '',
  stableHtml: '',
  stableToc: [] as TocItem[],
})

const renderedContent = computed(() => {
  if (!props.content) {
    streamCache.stableContent = ''
    streamCache.stableHtml = ''
    streamCache.stableToc = []
    return ''
  }
  tocCollector = []
  sourceRefCounter.clear()
  codeStore.clear()
  echartsOptionStore.clear()
  const cleanContent = stripFrontmatter(props.content)
  void isStreamingDiagrams.value
  void shikiReadyTrigger.value

  if (props.streaming && cleanContent.length > 500) {
    let lastBreak = cleanContent.lastIndexOf('\n\n')
    if (lastBreak > 200) {
      const candidate = cleanContent.slice(0, lastBreak)
      const fenceCount = (candidate.match(/^```/gm) || []).length
      if (fenceCount % 2 !== 0) {
        const fenceStart = candidate.lastIndexOf('\n```')
        if (fenceStart > 200) lastBreak = fenceStart
        else lastBreak = -1
      }
    }
    if (lastBreak > 200) {
      const stablePart = cleanContent.slice(0, lastBreak)
      const tailPart = cleanContent.slice(lastBreak)
      if (streamCache.stableContent !== stablePart) {
        streamCache.stableContent = stablePart
        streamCache.stableToc = []
        const savedCollector = tocCollector
        tocCollector = []
        streamCache.stableHtml = mdInstance.render(stablePart)
        streamCache.stableToc = [...tocCollector]
        tocCollector = savedCollector
      }
      tocCollector.push(...streamCache.stableToc)
      const tailHtml = mdInstance.render(tailPart)
      if (tocCollector.length > 0) {
        tocHeadings.value = [...tocCollector]
        emit('toc', tocHeadings.value)
      }
      return streamCache.stableHtml + tailHtml
    }
  } else {
    streamCache.stableContent = ''
    streamCache.stableHtml = ''
    streamCache.stableToc = []
  }

  const result = mdInstance.render(cleanContent)
  if (tocCollector.length > 0) {
    tocHeadings.value = [...tocCollector]
    emit('toc', tocHeadings.value)
  }
  return result
})

// ── Theme helper ─────────────────────────────────────────────────────
function getCurrentTheme(): 'light' | 'dark' {
  return document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light'
}

// ── Mermaid rendering with zoom/pan ─────────────────────────────────
async function renderMermaidDiagrams() {
  const mermaidEls = containerRef.value?.querySelectorAll('.wiki-mermaid:not([data-rendered])')
  if (!mermaidEls || mermaidEls.length === 0) return

  try {
    const mermaid = (await import('mermaid')).default
    const theme = getCurrentTheme()
    mermaid.initialize({
      startOnLoad: false, theme: theme === 'dark' ? 'dark' : 'default',
      securityLevel: 'strict', fontFamily: 'Inter, Noto Sans SC, sans-serif',
    })

    for (const el of Array.from(mermaidEls)) {
      const pre = el.querySelector('.mermaid-src')
      const output = el.querySelector('.mermaid-viewport')
      if (!pre || !output) continue
      const mermaidId = el.getAttribute('data-mermaid-id') || ''
      if (mermaidRendered.has(mermaidId)) continue

      const code = pre.textContent || ''
      const id = output.id || mermaidId || `mermaid-fb-${Date.now()}`
      try {
        const { svg } = await mermaid.render(id, code)
        output.innerHTML = svg
        pre.remove()
        el.setAttribute('data-rendered', 'true')
        mermaidRendered.add(mermaidId)
      } catch (e) {
        const errMsg = e instanceof Error ? e.message : t('wiki.mermaidSyntaxError')
        const shortMsg = errMsg.split('\n')[0].replace(/^Parse error on line \d+:\s*/, '').substring(0, 120)
        output.innerHTML = `<div class="wiki-mermaid-error"><p>${t('wiki.mermaidRenderFailed')}${escapeHtml(shortMsg)}</p><details><summary>${t('wiki.viewSource')}</summary><pre>${escapeHtml(code)}</pre></details></div>`
        console.warn(`[Mermaid] render failed for ${id}: ${shortMsg}`)
      }
    }
  } catch (e) {
    console.warn('Mermaid load failed:', e)
  }
}

// ── ECharts rendering ────────────────────────────────────────────────
function disposeECharts(el: HTMLElement) {
  const entry = echartsInstances.get(el)
  if (entry) { entry.observer.disconnect(); entry.chart.dispose(); echartsInstances.delete(el) }
}

async function renderEChartsDiagrams() {
  const echartsEls = containerRef.value?.querySelectorAll('.wiki-echarts:not(.wiki-echarts--error):not([data-rendered])')
  if (!echartsEls || echartsEls.length === 0) return

  try {
    const echartsModule = await import('echarts')
    const echarts = (echartsModule as any).default || echartsModule
    const theme = getCurrentTheme()

    echartsEls.forEach((el) => {
      const htmlEl = el as HTMLElement
      if (echartsInstances.has(htmlEl)) return
      const echartsId = el.getAttribute('data-echarts-id')
      if (!echartsId) return
      const optionStr = echartsOptionStore.get(echartsId)
      if (!optionStr) return
      try {
        const option = JSON.parse(optionStr)
        const chart = echarts.init(htmlEl, theme === 'dark' ? 'dark' : undefined)
        chart.setOption(option)
        htmlEl.setAttribute('data-rendered', 'true')
        const resizeObserver = new ResizeObserver(() => { if (!chart.isDisposed()) chart.resize() })
        resizeObserver.observe(htmlEl)
        echartsInstances.set(htmlEl, { chart, observer: resizeObserver })
      } catch (e) {
        el.innerHTML = `<p class="wiki-echarts-error">${t('wiki.echartsRenderFailed')}${escapeHtml(String(e instanceof Error ? e.message : t('wiki.echartsConfigError')))}</p>`
      }
    })
  } catch (e) { console.warn('ECharts load failed:', e) }
}

// ── ECharts export & fullscreen ──────────────────────────────────────
function exportEChartsPng(targetId: string) {
  for (const [el, entry] of echartsInstances) {
    if (el.getAttribute('data-echarts-id') === targetId) {
      const url = entry.chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: getCurrentTheme() === 'dark' ? '#0F0F14' : '#fff' })
      const a = document.createElement('a')
      a.href = url; a.download = `chart-${Date.now()}.png`; a.click()
      break
    }
  }
}

function toggleEChartsFullscreen(targetId: string) {
  const wrap = containerRef.value?.querySelector(`[data-echarts-wrap="${targetId}"]`) as HTMLElement
  if (!wrap) return
  if (document.fullscreenElement === wrap) {
    document.exitFullscreen()
  } else {
    wrap.requestFullscreen().then(() => {
      const el = wrap.querySelector('.wiki-echarts') as HTMLElement
      const entry = echartsInstances.get(el)
      if (entry) setTimeout(() => entry.chart.resize(), 100)
    })
  }
}

// ── Mermaid zoom/pan ─────────────────────────────────────────────────
function handleMermaidZoom(el: HTMLElement, action: string) {
  const mermaidEl = el.closest('.wiki-mermaid')
  if (!mermaidEl) return
  const viewport = mermaidEl.querySelector('.mermaid-viewport') as HTMLElement
  if (!viewport) return

  let scale = parseFloat(viewport.dataset.scale || '1')
  let tx = parseFloat(viewport.dataset.tx || '0')
  let ty = parseFloat(viewport.dataset.ty || '0')

  if (action === 'zoom-in') scale = Math.min(scale * 1.25, 5)
  else if (action === 'zoom-out') scale = Math.max(scale / 1.25, 0.25)
  else { scale = 1; tx = 0; ty = 0 }

  viewport.dataset.scale = String(scale)
  viewport.dataset.tx = String(tx)
  viewport.dataset.ty = String(ty)
  viewport.style.transform = `translate(${tx}px, ${ty}px) scale(${scale})`
  viewport.style.transformOrigin = 'center center'
}

// ── Click handlers ───────────────────────────────────────────────────
function handleCopyClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  const btn = target.closest('.wiki-code-copy') as HTMLElement
  if (!btn) return
  const codeId = btn.getAttribute('data-code-id')
  if (!codeId) return
  const code = codeStore.get(codeId)
  if (!code) return
  navigator.clipboard.writeText(code).then(() => {
    copiedCode.value = btn.closest('.wiki-code-block')?.querySelector('code')?.textContent || ''
    btn.classList.add('wiki-code-copy--copied')
    setTimeout(() => { btn.classList.remove('wiki-code-copy--copied'); copiedCode.value = null }, 2000)
  })
}

function handleCodeToggle(btn: HTMLElement) {
  const wrapper = btn.closest('.wiki-code-collapsible')
  if (!wrapper) return
  const isExpanded = wrapper.classList.toggle('wiki-code-collapsible--expanded')
  const lineCount = wrapper.querySelector('.wiki-code-block pre code')?.textContent?.replace(/\n$/, '').split('\n').length || 0
  btn.textContent = isExpanded ? t('wiki.codeCollapse') : t('wiki.codeExpandAll', [lineCount])
}

function handleContentClick(e: MouseEvent) {
  const target = e.target as HTMLElement

  const sourceRef = target.closest('.wiki-source-ref') as HTMLElement
  if (sourceRef) {
    const name = sourceRef.getAttribute('data-source-name') || ''
    const source = props.sources.find(s =>
      s.name.toLowerCase() === name.toLowerCase() ||
      s.name.toLowerCase().endsWith('/' + name.toLowerCase()) ||
      s.name.toLowerCase().endsWith('\\' + name.toLowerCase())
    )
    if (source) emit('preview-source', source.id, source.format)
    return
  }

  if (target.closest('.wiki-code-copy')) { handleCopyClick(e); return }

  const toggleBtn = target.closest('[data-action="toggle-code"]') as HTMLElement
  if (toggleBtn) { handleCodeToggle(toggleBtn); return }

  const diagramBtn = target.closest('.wiki-diagram-btn') as HTMLElement
  if (diagramBtn) { handleMermaidZoom(diagramBtn, diagramBtn.dataset.action || ''); return }

  const echartsBtn = target.closest('.wiki-echarts__tool-btn') as HTMLElement
  if (echartsBtn) {
    const action = echartsBtn.dataset.action
    const tid = echartsBtn.dataset.echartsTarget || ''
    if (action === 'export-png') exportEChartsPng(tid)
    else if (action === 'fullscreen') toggleEChartsFullscreen(tid)
    return
  }

  const anchor = target.closest('a') as HTMLAnchorElement
  if (anchor) {
    const href = anchor.getAttribute('href')
    if (!href) return
    if (href.startsWith('#')) {
      e.preventDefault()
      document.getElementById(href.slice(1))?.scrollIntoView({ behavior: 'smooth' })
      return
    }
    if (href.startsWith('/') && !href.startsWith('//')) { e.preventDefault(); router.push(href); return }
  }
}

function handleContentKeydown(e: KeyboardEvent) {
  if (e.key !== 'Enter' && e.key !== ' ') return
  const target = e.target as HTMLElement
  const sourceRef = target.closest('.wiki-source-ref') as HTMLElement
  if (!sourceRef) return
  e.preventDefault()
  const name = sourceRef.getAttribute('data-source-name') || ''
  const source = props.sources.find(s =>
    s.name.toLowerCase() === name.toLowerCase() ||
    s.name.toLowerCase().endsWith('/' + name.toLowerCase()) ||
    s.name.toLowerCase().endsWith('\\' + name.toLowerCase())
  )
  if (source) emit('preview-source', source.id, source.format)
}

// ── Mermaid pan support ──────────────────────────────────────────────
let isPanning = false
let panStart = { x: 0, y: 0 }
let panTarget: HTMLElement | null = null

function handleMouseDown(e: MouseEvent) {
  const viewport = (e.target as HTMLElement).closest('.mermaid-viewport') as HTMLElement
  if (!viewport) return
  isPanning = true
  panTarget = viewport
  panStart = { x: e.clientX - parseFloat(viewport.dataset.tx || '0'), y: e.clientY - parseFloat(viewport.dataset.ty || '0') }
  viewport.style.cursor = 'grabbing'
  e.preventDefault()
}

function handleMouseMove(e: MouseEvent) {
  if (!isPanning || !panTarget) return
  const tx = e.clientX - panStart.x
  const ty = e.clientY - panStart.y
  panTarget.dataset.tx = String(tx)
  panTarget.dataset.ty = String(ty)
  const scale = panTarget.dataset.scale || '1'
  panTarget.style.transform = `translate(${tx}px, ${ty}px) scale(${scale})`
}

function handleMouseUp() {
  if (panTarget) panTarget.style.cursor = 'grab'
  isPanning = false
  panTarget = null
}

// ── Lifecycle ────────────────────────────────────────────────────────
let diagramRenderScheduled = false

function scheduleDiagramRender() {
  if (diagramRenderScheduled) return
  if (props.streaming) return
  diagramRenderScheduled = true
  requestAnimationFrame(async () => {
    diagramRenderScheduled = false
    await nextTick()
    renderMermaidDiagrams()
    renderEChartsDiagrams()
  })
}

onMounted(async () => {
  await nextTick()
  scheduleDiagramRender()
  if (!isShikiReady()) {
    await waitForShikiReady()
    shikiReadyTrigger.value++
    scheduleDiagramRender()
  }
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  document.addEventListener('mousemove', throttledMouseMove)
  document.addEventListener('mouseup', handleMouseUp)
})

watch(() => props.content, () => { scheduleDiagramRender() })

watch(() => props.streaming, (isStreaming, wasStreaming) => {
  if (wasStreaming && !isStreaming) scheduleDiagramRender()
})

watch(isStreamingDiagrams, (canRender, wasBlocking) => {
  if (wasBlocking && !canRender) scheduleDiagramRender()
})

const themeObserver = new MutationObserver(() => {
  nextTick(async () => {
    for (const [el] of echartsInstances) disposeECharts(el)
    containerRef.value?.querySelectorAll('.wiki-mermaid[data-rendered]').forEach((el) => el.removeAttribute('data-rendered'))
    mermaidRendered.clear()
    scheduleDiagramRender()
  })
})

let mouseMoveRafId = 0
function throttledMouseMove(e: MouseEvent) {
  if (!isPanning) return
  if (mouseMoveRafId) return
  mouseMoveRafId = requestAnimationFrame(() => {
    mouseMoveRafId = 0
    handleMouseMove(e)
  })
}

onBeforeUnmount(() => {
  themeObserver.disconnect()
  for (const [el] of echartsInstances) disposeECharts(el)
  mermaidRendered.clear()
  codeStore.clear()
  echartsOptionStore.clear()
  document.removeEventListener('mousemove', throttledMouseMove)
  document.removeEventListener('mouseup', handleMouseUp)
  if (mouseMoveRafId) cancelAnimationFrame(mouseMoveRafId)
})
</script>

<template>
  <div
    ref="containerRef"
    class="wiki-content wiki-content--enhanced"
    :class="{ 'wiki-content--streaming': streaming }"
    v-html="renderedContent"
    @click="handleContentClick"
    @keydown="handleContentKeydown"
    @mousedown="handleMouseDown"
  ></div>
</template>

<style scoped>
.wiki-content--streaming > *:last-child::after {
  content: '';
  display: inline-block;
  width: 2px;
  height: 1em;
  background: var(--accent-primary);
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 1s step-end infinite;
}
@keyframes blink {
  50% { opacity: 0; }
}
</style>
