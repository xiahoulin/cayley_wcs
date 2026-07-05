import { createRouter, createWebHashHistory } from 'vue-router'
import { auth } from '../store/auth'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', component: () => import('../views/LoginView.vue') },
    {
      path: '/',
      component: () => import('../layout/MainLayout.vue'),
      children: [
        { path: 'dashboard', component: () => import('../views/DashboardView.vue') },
        { path: 'task', component: () => import('../views/TaskView.vue') },
        { path: 'alarm', component: () => import('../views/AlarmView.vue') },
        { path: 'stats', component: () => import('../views/StatsView.vue') },
        { path: 'protocol', component: () => import('../views/ProtocolView.vue') },
        { path: 'fault-code', component: () => import('../views/FaultCodeView.vue') },
        { path: 'application', component: () => import('../views/ApplicationView.vue') },
        { path: 'binding-grant', component: () => import('../views/BindingGrantView.vue') },
        { path: 'binding-detail', component: () => import('../views/BindingDetailView.vue') },
        { path: 'connection', component: () => import('../views/ConnectionView.vue') },
        { path: 'webhook', component: () => import('../views/WebhookView.vue') },
        { path: 'dict', component: () => import('../views/DictView.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !auth.isAuthed) {
    return '/login'
  }
  return true
})

export default router
