import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import { i18n } from '@/locales'

function interpolate(template: string, args?: unknown[]): string {
  if (!args || args.length === 0) return template
  return template.replace(/\{(\d+)\}/g, (placeholder, index) => {
    const value = args[Number(index)]
    return value === null || value === undefined ? placeholder : String(value)
  })
}

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

api.interceptors.request.use((config) => {
  const authStore = useAuthStore()
  if (authStore.token) {
    config.headers.Authorization = `Bearer ${authStore.token}`
  }
  if (authStore.scopeId && authStore.scopeId > 0) {
    config.headers['X-Scope-Id'] = String(authStore.scopeId)
  }
  config.headers['Accept-Language'] = i18n.global.locale.value === 'en' ? 'en' : 'zh-CN'
  return config
})

api.interceptors.response.use(
  (response) => {
    const data = response.data
    if (data.success) {
      return data.data
    }
    const { t, te } = i18n.global
    const i18nKey = `errors.${data.code}`
    const localizedMsg = te(i18nKey)
      ? t(i18nKey, data.args || [])
      : interpolate(data.msg || 'Request failed', data.args)
    const error = new Error(localizedMsg) as any
    error.code = data.code
    error.extra = data.extra
    return Promise.reject(error)
  },
  (error) => {
    if (error.response?.status === 401) {
      const authStore = useAuthStore()
      authStore.clearAuth()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  },
)

export default api