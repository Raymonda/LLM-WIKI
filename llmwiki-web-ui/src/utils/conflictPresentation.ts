import type { LintFindingInfo } from '@/api/lint'

export interface NormalizedConflict {
  conflictType: string
  fromTitle: string
  toTitle: string
  claimA: string
  claimB: string
  fromPageId: number | null
  toPageId: number | null
  fromPagePath: string | null
  toPagePath: string | null
  canOperate: boolean
  hasClaims: boolean
  isLegacy: boolean
}

function parseExtra(extra: string | null): Record<string, any> {
  if (!extra) return {}
  try {
    const parsed = JSON.parse(extra)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

function asText(value: unknown): string {
  if (value === null || value === undefined) return ''
  return String(value).trim()
}

function asNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  const text = asText(value)
  if (text !== '' && !Number.isNaN(Number(text))) return Number(text)
  return null
}

function pathToTitle(path: unknown): string {
  const value = asText(path)
  if (!value) return ''
  const base = value.split('/').pop() || value
  return base.replace(/\.md$/i, '')
}

function displayTitle(value: string, pathFallback: string | null): string {
  const raw = asText(value)
  if (!raw) return pathToTitle(pathFallback)
  if (raw.includes('/') || /\.md$/i.test(raw)) return pathToTitle(raw)
  return raw
}

function detailSection(detail: string | null, marker: string): string {
  if (!detail) return ''
  const match = detail.match(new RegExp(marker + '[：:][ \\t]*(.+)'))
  return match ? match[1].trim() : ''
}

function quotedTitles(title: string | null): string[] {
  return title?.match(/「([^」]+)」/g)?.map(segment => segment.slice(1, -1)) ?? []
}

export function normalizeConflictExtra(finding: LintFindingInfo): NormalizedConflict {
  const extra = parseExtra(finding.extra)
  const quoted = quotedTitles(finding.title)
  const fromPath = asText(extra.fromPagePath || finding.pagePath) || null
  const toPath = asText(extra.relatedPagePath || extra.pagePathB) || null

  const fromTitle = displayTitle(
    asText(extra.fromPageTitle) || quoted[0] || detailSection(finding.detail, '页面A'),
    fromPath
  )
  const toTitle = displayTitle(
    asText(extra.relatedPageTitle) || quoted[1] || detailSection(finding.detail, '页面B'),
    toPath
  )
  const claimA = asText(extra.claimA) || asText(extra.newClaim) || detailSection(finding.detail, '声明A')
  const claimB = asText(extra.claimB) || asText(extra.existingClaim) || detailSection(finding.detail, '声明B')

  const fromPageId = asNumber(extra.fromPageId) ?? finding.assetId ?? null
  const toPageId = asNumber(extra.relatedPageId)
  const fromResolvable = extra.fromPageId || finding.assetId || finding.pagePath || null
  const toResolvable = extra.relatedPageId || extra.relatedPagePath || extra.pagePathB || null

  return {
    conflictType: asText(extra.conflictType) || 'unknown',
    fromTitle: fromTitle || '—',
    toTitle: toTitle || '—',
    claimA,
    claimB,
    fromPageId,
    toPageId,
    fromPagePath: fromPath,
    toPagePath: toPath,
    canOperate: Boolean(fromResolvable && toResolvable),
    hasClaims: claimA !== '' || claimB !== '',
    isLegacy: !asText(extra.relatedPagePath)
  }
}
