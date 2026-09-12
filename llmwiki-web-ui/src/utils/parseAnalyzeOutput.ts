import type { AnalysisMetadata } from '@/stores/ingestProgress'

export interface ParsedAnalyzeOutput {
  aiAnalysis: string
  metadata: AnalysisMetadata | null
}

export function parseAnalyzeOutput(outputData: string | null | undefined): ParsedAnalyzeOutput {
  const result: ParsedAnalyzeOutput = { aiAnalysis: '', metadata: null }
  if (!outputData) return result
  try {
    const parsed = JSON.parse(outputData)
    if (parsed.mergedAnalysis) {
      result.aiAnalysis = parsed.mergedAnalysis
    }
    if (parsed.metadata) {
      const metadata = parsed.metadata
      result.metadata = {
        title: metadata.title || '',
        summary: metadata.summary || '',
        category: metadata.category || '',
        tags: Array.isArray(metadata.tags) ? metadata.tags : [],
        keywords: Array.isArray(metadata.keywords) ? metadata.keywords : [],
        entities: Array.isArray(metadata.entities)
          ? metadata.entities.map((e: any) => ({
              name: e.name || '',
              type: e.type || 'concept',
              description: e.description || undefined,
            }))
          : [],
        affectedPages: Array.isArray(metadata.affectedPages)
          ? metadata.affectedPages.map((p: any) => ({
              title: p.title,
              path: p.path || p.title,
              action: p.action || '更新',
              id: p.id ?? undefined,
            }))
          : [],
      }
    }
  } catch {
    result.aiAnalysis = outputData
  }
  return result
}
