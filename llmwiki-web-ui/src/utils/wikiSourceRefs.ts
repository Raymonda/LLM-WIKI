export interface WikiSourceRef {
  title: string
  path: string
}

export function extractWikiSourceRefs(text: string): WikiSourceRef[] {
  if (!text) return []
  const normalized = text.replace(/\]\]\(\(/g, ']](')
  const refs: WikiSourceRef[] = []
  const seen = new Set<string>()
  const wikiLinkRegex = /\[\[([^\]]+)\]\]\(([^)]+)\)/g
  const mdLinkRegex = /(?<!\[)\[([^\]]+)\]\(([^)]+)\)(?!\])/g

  function addMatch(title: string, rawPath: string) {
    let filePath = rawPath.replace(/^\(+/, '')
    const absUrlMatch = /^https?:\/\/[^/]+\/(?:wiki\/)?pages\/(.+)$/.exec(filePath)
    if (absUrlMatch) {
      filePath = 'pages/' + decodeURIComponent(absUrlMatch[1])
    }
    if (filePath.startsWith('wiki/')) filePath = filePath.slice(5)
    if (!filePath.startsWith('pages/')) return
    if (!filePath.endsWith('.md')) filePath += '.md'
    if (!filePath.match(/^pages\/.+\.md$/)) return
    if (!seen.has(filePath)) {
      seen.add(filePath)
      refs.push({ title, path: filePath })
    }
  }

  let match: RegExpExecArray | null
  while ((match = wikiLinkRegex.exec(normalized)) !== null) {
    addMatch(match[1], match[2])
  }
  while ((match = mdLinkRegex.exec(normalized)) !== null) {
    addMatch(match[1], match[2])
  }
  return refs
}
