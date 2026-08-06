import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'bytemd/dist/index.css'
import 'highlight.js/styles/github.css'
import './styles/bytemd-theme.css'
import './styles/global.css'
import App from './App.vue'
import { routes } from './router'
import { useAuthStore } from './stores/auth'
import { i18n } from './locales'

const pinia = createPinia()
const router = createRouter({
  history: createWebHistory(),
  routes,
})

document.documentElement.lang = i18n.global.locale.value === 'en' ? 'en' : 'zh'

router.beforeEach(async (to, _from, next) => {
  const authStore = useAuthStore(pinia)
  if (authStore.isAuthenticated() && !authStore.initialized) {
    await authStore.initFromApi()
  }
  if (to.meta.requiresAuth && !authStore.isAuthenticated()) {
    next({ name: 'Login' })
  } else if (to.name === 'Login' && authStore.isAuthenticated()) {
    next({ path: '/' })
  } else if (to.meta.requireAdmin && !authStore.isSystemAdmin) {
    next({ path: '/' })
  } else {
    next()
  }
})

router.afterEach((to) => {
  const titleKey = to.meta.titleKey as string | undefined
  if (titleKey) {
    document.title = `${i18n.global.t(titleKey)} - ${i18n.global.t('common.brandName')}`
  } else {
    document.title = i18n.global.t('common.brandName')
  }
})

const app = createApp(App)
app.use(pinia)
app.use(router)
app.use(i18n)
app.use(ElementPlus)
app.mount('#app')