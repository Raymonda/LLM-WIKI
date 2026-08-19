import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/system/LoginView.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/',
    component: () => import('@/components/layout/AppLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'WikiHome',
        component: () => import('@/views/wiki/WikiListView.vue'),
        meta: { titleKey: 'nav.wikiHome' },
      },
      {
        path: 'graph',
        name: 'KnowledgeGraph',
        component: () => import('@/views/graph/GraphView.vue'),
        meta: { titleKey: 'nav.knowledgeGraph' },
      },
      {
        path: 'search',
        name: 'Search',
        component: () => import('@/views/search/SearchView.vue'),
        meta: { titleKey: 'nav.search', keepAlive: true },
      },
      {
        path: 'wiki/:id',
        name: 'WikiPage',
        component: () => import('@/views/wiki/WikiPageView.vue'),
        meta: { titleKey: 'nav.wikiPage' },
      },
      {
        path: 'wiki/p/:filePath(.*)',
        name: 'WikiPageByPath',
        component: () => import('@/views/wiki/WikiPageView.vue'),
        meta: { titleKey: 'nav.wikiPage' },
      },
      {
        path: 'ingest',
        name: 'Ingest',
        component: () => import('@/views/ingest/IngestView.vue'),
        meta: { titleKey: 'nav.ingest' },
      },
      {
        path: 'lint',
        name: 'Lint',
        component: () => import('@/views/lint/LintView.vue'),
        meta: { titleKey: 'nav.lint' },
      },
      {
        path: 'harness',
        name: 'HarnessList',
        component: () => import('@/views/harness/HarnessListView.vue'),
        meta: { titleKey: 'nav.harnessList' },
      },
      {
        path: 'harness/:id',
        name: 'HarnessDetail',
        component: () => import('@/views/harness/HarnessDetailView.vue'),
        meta: { titleKey: 'nav.harnessDetail' },
      },
      {
        path: 'token',
        name: 'TokenMonitor',
        component: () => import('@/views/dashboard/DashboardView.vue'),
        meta: { titleKey: 'nav.tokenMonitor' },
      },
      {
        path: 'system',
        name: 'System',
        component: () => import('@/views/system/SystemView.vue'),
        meta: { titleKey: 'nav.system', requireAdmin: true },
      },
      {
        path: 'settings/api-keys',
        name: 'MyApiKeys',
        component: () => import('@/views/settings/MyApiKeysView.vue'),
        meta: { titleKey: 'nav.myApiKeys' },
      },
      {
        path: 'scope/manage',
        name: 'ScopeManage',
        component: () => import('@/views/scope/ScopeManageView.vue'),
        meta: { titleKey: 'nav.scopeManage' },
      },
      {
        path: 'plaza',
        name: 'Plaza',
        component: () => import('@/views/scope/PlazaView.vue'),
        meta: { titleKey: 'nav.plaza' },
      },
      {
        path: 'trash',
        name: 'Trash',
        component: () => import('@/views/wiki/TrashView.vue'),
        meta: { titleKey: 'nav.trash' },
      },
      {
        path: 'editor',
        name: 'WikiEditorNew',
        component: () => import('@/views/editor/WikiEditorView.vue'),
        meta: { titleKey: 'nav.editorNew' },
      },
      {
        path: 'editor/draft/:draftId',
        name: 'WikiEditorDraft',
        component: () => import('@/views/editor/WikiEditorView.vue'),
        meta: { titleKey: 'nav.editorDraft' },
      },
      {
        path: 'editor/page/:pageId',
        name: 'WikiEditorEdit',
        component: () => import('@/views/editor/WikiEditorView.vue'),
        meta: { titleKey: 'nav.editorEdit' },
      },
      {
        path: 'drafts',
        name: 'WikiDrafts',
        component: () => import('@/views/editor/WikiDraftsView.vue'),
        meta: { titleKey: 'nav.drafts' },
      },
      {
        path: 'scope/audit',
        name: 'AuditLog',
        component: () => import('@/views/scope/AuditLogView.vue'),
        meta: { titleKey: 'nav.auditLog' },
      },
    ],
  },
]