import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface Toast {
  id: string
  type: 'success' | 'warning' | 'error' | 'info' | 'progress'
  title: string
  message?: string
  duration?: number
  executionId?: string
}

export const useToastStore = defineStore('toast', () => {
  const toasts = ref<Toast[]>([])

  function addToast(toast: Omit<Toast, 'id'>) {
    const id = `toast-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
    const newToast: Toast = { ...toast, id }
    toasts.value.push(newToast)
    const duration = toast.duration ?? (toast.type === 'progress' ? 0 : 3000)
    if (duration > 0) {
      setTimeout(() => removeToast(id), duration)
    }
    return id
  }

  function removeToast(id: string) {
    toasts.value = toasts.value.filter(t => t.id !== id)
  }

  function success(title: string, message?: string) {
    return addToast({ type: 'success', title, message })
  }

  function warning(title: string, message?: string) {
    return addToast({ type: 'warning', title, message })
  }

  function error(title: string, message?: string) {
    return addToast({ type: 'error', title, message })
  }

  function info(title: string, message?: string) {
    return addToast({ type: 'info', title, message })
  }

  function progress(title: string, message?: string, executionId?: string) {
    return addToast({ type: 'progress', title, message, duration: 0, executionId })
  }

  function updateProgress(id: string, title: string, message?: string) {
    const toast = toasts.value.find(t => t.id === id)
    if (toast) {
      toast.title = title
      toast.message = message
    }
  }

  function completeProgress(id: string, title: string, message?: string) {
    removeToast(id)
    success(title, message)
  }

  return {
    toasts,
    addToast,
    removeToast,
    success,
    warning,
    error,
    info,
    progress,
    updateProgress,
    completeProgress,
  }
})