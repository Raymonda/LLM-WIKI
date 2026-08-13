export interface FactRefView {
  path: string
  title: string
}

export interface FactBlockView {
  id: string
  conclusion: string
  evidence: string
  refs: FactRefView[]
  confidence: string
  kind: string
}

const PROSPECTIVE_MARKER = '前瞻分析'

export function splitSynthesisAndProspective(full: string): [string, string] {
  const idx = full.indexOf(PROSPECTIVE_MARKER)
  if (idx < 0) return [full, '']
  const splitAt = full.lastIndexOf('\n', idx)
  const pos = splitAt >= 0 ? splitAt : idx
  return [full.slice(0, pos).replace(/\n+$/, ''), full.slice(pos).replace(/^\n+/, '')]
}

export function reduceFactBlockJson(jsonText: string, blocks: FactBlockView[]): FactBlockView[] {
  const next = [...blocks]
  try {
    const data = JSON.parse(jsonText)
    if (!data || typeof data.conclusion !== 'string') throw new Error('missing conclusion')
    next.push({
      id: data.id || `fb-${next.length + 1}`,
      conclusion: data.conclusion,
      evidence: data.evidence || '',
      refs: Array.isArray(data.refs) ? data.refs : [],
      confidence: data.confidence || 'medium',
      kind: data.kind || 'fact',
    })
  } catch {
    next.push({
      id: `fb-text-${next.length + 1}`,
      conclusion: jsonText,
      evidence: '',
      refs: [],
      confidence: 'medium',
      kind: 'text',
    })
  }
  return next
}