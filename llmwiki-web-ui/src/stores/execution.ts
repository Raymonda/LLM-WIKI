import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface ExecutionStep {
  id: string
  label: string
  status: 'completed' | 'running' | 'pending' | 'waiting' | 'failed'
  startedAt?: string
  completedAt?: string
  tokensUsed?: number
}

export interface Execution {
  id: string
  type: 'ingest' | 'query' | 'lint'
  status: 'running' | 'completed' | 'failed' | 'cancelled'
  sourcePath?: string
  schemaName?: string
  steps: ExecutionStep[]
  totalTokens: number
  startedAt: string
  completedAt?: string
  result?: string
  viewed?: boolean
}

export const useExecutionStore = defineStore('execution', () => {
  const executions = ref<Execution[]>([])
  const currentExecution = ref<Execution | null>(null)

  function startExecution(type: 'ingest' | 'query' | 'lint', params?: { sourcePath?: string; schemaName?: string }) {
    const execution: Execution = {
      id: `exec-${Date.now()}`,
      type,
      status: 'running',
      sourcePath: params?.sourcePath,
      schemaName: params?.schemaName,
      steps: getDefaultSteps(type),
      totalTokens: 0,
      startedAt: new Date().toISOString(),
    }
    executions.value.unshift(execution)
    currentExecution.value = execution
    return execution
  }

  function updateStep(executionId: string, stepId: string, status: ExecutionStep['status'], tokensUsed?: number) {
    const execution = executions.value.find(e => e.id === executionId)
    if (!execution) return
    const step = execution.steps.find(s => s.id === stepId)
    if (!step) return
    step.status = status
    if (tokensUsed) {
      step.tokensUsed = tokensUsed
      execution.totalTokens += tokensUsed
    }
    if (status === 'running') step.startedAt = new Date().toISOString()
    if (status === 'completed' || status === 'failed') step.completedAt = new Date().toISOString()
  }

  function completeExecution(executionId: string, result?: string) {
    const execution = executions.value.find(e => e.id === executionId)
    if (!execution) return
    execution.status = 'completed'
    execution.completedAt = new Date().toISOString()
    execution.result = result
  }

  function failExecution(executionId: string) {
    const execution = executions.value.find(e => e.id === executionId)
    if (!execution) return
    execution.status = 'failed'
    execution.completedAt = new Date().toISOString()
  }

  function cancelExecution(executionId: string) {
    const execution = executions.value.find(e => e.id === executionId)
    if (!execution) return
    execution.status = 'cancelled'
    execution.completedAt = new Date().toISOString()
  }

  function clearCurrentExecution() {
    currentExecution.value = null
  }

  function getRunningExecutions() {
    return executions.value.filter(e => e.status === 'running')
  }

  function hasRunningByType(type: 'ingest' | 'query' | 'lint') {
    return executions.value.some(e => e.type === type && e.status === 'running')
  }

  function hasCompletedResultsByType(type: 'ingest' | 'query' | 'lint') {
    return executions.value.some(e => e.type === type && e.status === 'completed' && !e.viewed)
  }

  function markViewed(executionId: string) {
    const execution = executions.value.find(e => e.id === executionId)
    if (!execution) return
    execution.viewed = true
  }

  return {
    executions,
    currentExecution,
    startExecution,
    updateStep,
    completeExecution,
    failExecution,
    cancelExecution,
    clearCurrentExecution,
    getRunningExecutions,
    hasRunningByType,
    hasCompletedResultsByType,
    markViewed,
  }
})

function getDefaultSteps(type: 'ingest' | 'query' | 'lint'): ExecutionStep[] {
  if (type === 'ingest') {
    return [
      { id: 'UPLOAD', label: '文档预处理', status: 'pending' },
      { id: 'ANALYZE', label: 'AI 全文分析', status: 'pending' },
      { id: 'WRITE', label: '页面生成', status: 'pending' },
      { id: 'COMPLETE', label: '质量收尾', status: 'pending' },
    ]
  }
  if (type === 'query') {
    return [
      { id: 'search-index', label: '搜索知识索引', status: 'pending' },
      { id: 'progressive-retrieve', label: '渐进式检索', status: 'pending' },
      { id: 'generate-answer', label: '生成回答', status: 'pending' },
    ]
  }
  return [
    { id: 'scan-pages', label: '扫描所有页面', status: 'pending' },
    { id: 'check-links', label: '检查链接完整性', status: 'pending' },
    { id: 'check-consistency', label: '检查内容一致性', status: 'pending' },
    { id: 'generate-report', label: '生成体检报告', status: 'pending' },
  ]
}