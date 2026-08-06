import { reactive } from 'vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'

export interface ConfirmOptions {
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  type?: 'warning' | 'danger' | 'info'
  confirmVariant?: 'danger' | 'primary'
}

export function useConfirmDialog() {
  const state = reactive({
    open: false,
    title: '',
    message: '',
    confirmText: '确认',
    cancelText: '取消',
    type: 'warning' as 'warning' | 'danger' | 'info',
    confirmVariant: 'primary' as 'danger' | 'primary',
    resolve: null as ((v: boolean) => void) | null,
  })

  function showConfirm(options: ConfirmOptions): Promise<boolean> {
    return new Promise(resolve => {
      state.title = options.title
      state.message = options.message
      state.confirmText = options.confirmText || '确认'
      state.cancelText = options.cancelText || '取消'
      state.type = options.type || 'warning'
      state.confirmVariant = options.confirmVariant || 'primary'
      state.resolve = resolve
      state.open = true
    })
  }

  function onConfirm() {
    state.open = false
    state.resolve?.(true)
    state.resolve = null
  }

  function onCancel() {
    state.open = false
    state.resolve?.(false)
    state.resolve = null
  }

  return { state, showConfirm, onConfirm, onCancel, ConfirmDialog }
}