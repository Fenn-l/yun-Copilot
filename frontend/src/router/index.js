import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('../layout/AppLayout.vue'),
    redirect: '/chat',
    children: [
      {
        path: 'chat',
        name: 'chat',
        component: () => import('../views/ChatView.vue'),
        meta: { title: '智能对话', menu: 'chat' },
      },
      {
        path: 'tickets',
        name: 'tickets',
        component: () => import('../views/TicketsView.vue'),
        meta: { title: '工单中心', menu: 'tickets' },
      },
      {
        path: 'knowledge',
        name: 'knowledge',
        component: () => import('../views/KnowledgeView.vue'),
        meta: { title: '企业知识库', menu: 'knowledge' },
      },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('../views/DashboardView.vue'),
        meta: { title: '运营看板', menu: 'dashboard', roles: ['AGENT', 'ADMIN'] },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('../views/SettingsView.vue'),
        meta: { title: '系统设置', menu: 'settings', roles: ['ADMIN'] },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    if (auth.token) return { path: '/chat' }
    return true
  }
  if (!auth.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (!auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      auth.logout()
      return { path: '/login' }
    }
  }
  if (to.meta.roles && !to.meta.roles.includes(auth.role)) {
    return { path: '/chat' }
  }
  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '云枢 Copilot'} · 企业智能客服`
})

export default router
