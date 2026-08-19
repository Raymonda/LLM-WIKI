import { createI18n } from 'vue-i18n'
import zhCommon from './zh-CN/common'
import zhNav from './zh-CN/nav'
import zhErrors from './zh-CN/errors'
import zhAuth from './zh-CN/auth'
import zhWiki from './zh-CN/wiki'
import zhIngest from './zh-CN/ingest'
import zhSearch from './zh-CN/search'
import zhLint from './zh-CN/lint'
import zhHarness from './zh-CN/harness'
import zhSystem from './zh-CN/system'
import zhScope from './zh-CN/scope'
import zhEditor from './zh-CN/editor'
import zhDashboard from './zh-CN/dashboard'
import zhGraph from './zh-CN/graph'
import zhBootstrap from './zh-CN/bootstrap'
import zhSchemaPatch from './zh-CN/schemaPatch'
import zhApiKeys from './zh-CN/apiKeys'
import enCommon from './en/common'
import enNav from './en/nav'
import enErrors from './en/errors'
import enAuth from './en/auth'
import enWiki from './en/wiki'
import enIngest from './en/ingest'
import enSearch from './en/search'
import enLint from './en/lint'
import enHarness from './en/harness'
import enSystem from './en/system'
import enScope from './en/scope'
import enEditor from './en/editor'
import enDashboard from './en/dashboard'
import enGraph from './en/graph'
import enBootstrap from './en/bootstrap'
import enSchemaPatch from './en/schemaPatch'
import enApiKeys from './en/apiKeys'

const messages = {
  'zh-CN': {
    common: zhCommon, nav: zhNav, errors: zhErrors,
    auth: zhAuth, wiki: zhWiki, ingest: zhIngest,
    search: zhSearch, lint: zhLint, harness: zhHarness,
    system: zhSystem, scope: zhScope, editor: zhEditor,
    dashboard: zhDashboard,
    graph: zhGraph,
    bootstrap: zhBootstrap,
    schemaPatch: zhSchemaPatch,
    apiKeys: zhApiKeys,
  },
  en: {
    common: enCommon, nav: enNav, errors: enErrors,
    auth: enAuth, wiki: enWiki, ingest: enIngest,
    search: enSearch, lint: enLint, harness: enHarness,
    system: enSystem, scope: enScope, editor: enEditor,
    dashboard: enDashboard,
    graph: enGraph,
    bootstrap: enBootstrap,
    schemaPatch: enSchemaPatch,
    apiKeys: enApiKeys,
  },
}

export const i18n = createI18n({
  legacy: false,
  locale: localStorage.getItem('llmwiki-language') || 'zh-CN',
  fallbackLocale: 'en',
  messages,
})

export const SUPPORTED_LOCALES = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'en', label: 'English' },
] as const

export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number]['value']
