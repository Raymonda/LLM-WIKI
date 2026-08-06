import api from './index'

export interface DuplicateInfo {
  existingSourceId: number
  existingSourceName: string
  existingSourceStatus: string
  message: string
}

export interface SourceInfo {
  id: number
  name: string
  filePath: string
  format: string
  size: number
  status: string
  createdAt: string
  contentHash?: string
  duplicateInfo?: DuplicateInfo | null
}

export function uploadSource(file: File): Promise<SourceInfo> {
  const formData = new FormData()
  formData.append('file', file)
  return api.post('/source/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function listSources(): Promise<SourceInfo[]> {
  return api.get('/source/list')
}

export function countSources(): Promise<{ count: number }> {
  return api.get('/source/count')
}

export function getSource(id: number): Promise<SourceInfo> {
  return api.get(`/source/${id}`)
}

export function deleteSource(id: number): Promise<void> {
  return api.delete(`/source/${id}`)
}

export interface SourceContentInfo {
  id: number
  name: string
  format: string
  hasParsedContent: boolean
  parsedContent: string
  previewType: string
}

export function getSourceContent(id: number): Promise<SourceContentInfo> {
  return api.get(`/source/${id}/content`)
}

export function getSourcePreviewUrl(id: number): string {
  return `/api/source/${id}/preview`
}

export function getSourceDownloadUrl(id: number): string {
  return `/api/source/${id}/download`
}