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
        { path: 'protocol', component: () => import('../views/ProtocolView.vue') },
        { path: 'application', component: () => import('../views/ApplicationView.vue') },
        { path: 'binding-grant', component: () => import('../views/BindingGrantView.vue') },
        { path: 'binding-detail', component: () => import('../views/BindingDetailView.vue') },
        { path: 'connection', component: () => import('../views/ConnectionView.vue') },
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
