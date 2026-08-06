import { useI18n } from 'vue-i18n'
import type { SupportedLocale } from '@/locales'

export function useLocale() {
  const { locale } = useI18n({ useScope: 'global' })

  function setLocale(lang: SupportedLocale) {
    locale.value = lang
    localStorage.setItem('llmwiki-language', lang)
    document.documentElement.lang = lang === 'zh-CN' ? 'zh' : 'en'
  }

  function initFromUser(language: string) {
    if (language === 'zh-CN' || language === 'en') {
      locale.value = language
      localStorage.setItem('llmwiki-language', language)
      document.documentElement.lang = language === 'zh-CN' ? 'zh' : 'en'
    }
  }

  return { locale, setLocale, initFromUser }
}
