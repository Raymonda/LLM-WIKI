import api from './index'

export interface AiProviderView {
  name: string
  baseUrl: string
  enabled: boolean
  apiKeyConfigured: boolean
  apiKeyMasked: string
}

export interface AiSlotView {
  slot: string
  provider: string
  model: string
  multimodal: boolean
}

export interface AiRuntimeConfigView {
  providers: AiProviderView[]
  slots: AiSlotView[]
}

export interface AiProviderInput {
  name: string
  baseUrl: string
  apiKey: string
  enabled: boolean
}

export interface AiSlotInput {
  slot: string
  provider: string
  model: string
  multimodal?: boolean
}

export interface AiConnectionTestResult {
  ok: boolean
  latencyMs: number
  message: string
}

export function getAiRuntimeConfig(): Promise<AiRuntimeConfigView> {
  return api.get('/system/ai-config')
}

export function saveAiRuntimeConfig(payload: {
  providers: AiProviderInput[]
  slots: AiSlotInput[]
}): Promise<void> {
  return api.put('/system/ai-config', payload)
}

export function testAiConnection(payload: {
  baseUrl: string
  apiKey: string
  providerName: string
  model: string
}): Promise<AiConnectionTestResult> {
  return api.post('/system/ai-config/test', payload)
}
