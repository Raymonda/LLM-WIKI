import type { Component } from 'vue'
import { AlertTriangle, CheckCircle, ClipboardCheck, FileText, Loader2, PauseCircle, XCircle } from 'lucide-vue-next'

export type QueueTone = 'running' | 'attention' | 'paused' | 'failed' | 'done' | 'cancelled' | 'neutral'

export interface QueueTaskDisplay {
  tone: QueueTone
  icon: Component
  labelKey: string
}

export interface QueueBatchDisplay {
  tone: QueueTone
  labelKey: string
}

export interface QueueTaskLike {
  executionId: number
  sourceName: string
  currentStep: string
  status: string
  isPhaseRunning: boolean
  progress: number
  batchId?: number | null
}

export interface QueueBatchLike {
  batchId: number
  status: string
  totalCount: number
  awaitingCount: number
  failedCount: number
  completedCount: number
}

const TERMINAL_TASK_STATUSES = ['completed', 'failed', 'cancelled', 'budget_exhausted']
const OPEN_BATCH_STATUSES = ['active', 'paused']

const RUNNING_STEP_LABEL_KEYS: Record<string, string> = {
  analyzing: 'ingest.stageAnalyzing',
  review: 'ingest.stageReview',
  executing: 'ingest.stageExecuting',
}

export function isTerminalTaskStatus(status: string): boolean {
  return TERMINAL_TASK_STATUSES.includes(status)
}

export function isOpenBatchStatus(status: string): boolean {
  return OPEN_BATCH_STATUSES.includes(status)
}

export function taskQueueState(task: QueueTaskLike): QueueTaskDisplay {
  if (task.status === 'failed') {
    return { tone: 'failed', icon: AlertTriangle, labelKey: 'ingest.statusFailed' }
  }
  if (task.status === 'budget_exhausted') {
    return { tone: 'failed', icon: AlertTriangle, labelKey: 'ingest.statusBudgetExhausted' }
  }
  if (task.status === 'cancelled') {
    return { tone: 'cancelled', icon: XCircle, labelKey: 'ingest.statusCancelledItem' }
  }
  if (task.currentStep === 'done') {
    return { tone: 'done', icon: CheckCircle, labelKey: 'ingest.statusDone' }
  }
  if (task.status === 'paused' || task.currentStep === 'paused') {
    return { tone: 'paused', icon: PauseCircle, labelKey: 'ingest.statusPaused' }
  }
  if (task.isPhaseRunning) {
    return { tone: 'running', icon: Loader2, labelKey: RUNNING_STEP_LABEL_KEYS[task.currentStep] ?? 'ingest.floatingPhaseDefault' }
  }
  if (task.currentStep === 'review') {
    return { tone: 'attention', icon: ClipboardCheck, labelKey: 'ingest.taskStateReview' }
  }
  return { tone: 'neutral', icon: FileText, labelKey: RUNNING_STEP_LABEL_KEYS[task.currentStep] ?? 'ingest.floatingPhaseDefault' }
}

export function batchQueueState(batch: QueueBatchLike): QueueBatchDisplay {
  if (batch.status === 'cancelled') {
    return { tone: 'cancelled', labelKey: 'ingest.batchStatusCancelled' }
  }
  if (batch.status === 'completed') {
    return { tone: 'done', labelKey: 'ingest.batchStatusCompleted' }
  }
  if (batch.status === 'paused') {
    return { tone: 'paused', labelKey: 'ingest.batchStatusPaused' }
  }
  return { tone: batch.awaitingCount > 0 ? 'attention' : 'running', labelKey: 'ingest.batchStatusActive' }
}
