import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/', name: 'home', component: () => import('@/views/home/HomeView.vue'), meta: { title: '此刻' } },
  { path: '/write', name: 'write', component: () => import('@/views/write/WriteView.vue'), meta: { title: '执笔' } },
  { path: '/archive', name: 'archive', component: () => import('@/views/archive/ArchiveView.vue'), meta: { title: '星图' } },
  { path: '/meteor', name: 'meteor', component: () => import('@/views/meteor/MeteorView.vue'), meta: { title: '流星' } },
  { path: '/spectrum', name: 'spectrum', component: () => import('@/views/spectrum/SpectrumView.vue'), meta: { title: '光谱' } },
  { path: '/echo', name: 'echo', component: () => import('@/views/echo/EchoView.vue'), meta: { title: '回声' } },
  { path: '/links', name: 'links', component: () => import('@/views/links/LinksView.vue'), meta: { title: '星链' } },
  { path: '/passport', name: 'passport', component: () => import('@/views/passport/PassportView.vue'), meta: { title: '星籍' } },
  { path: '/bridge', name: 'bridge', component: () => import('@/views/bridge/BridgeView.vue'), meta: { title: '舰桥' } },
  { path: '/login', name: 'login', component: () => import('@/views/auth/AuthView.vue'), props: { mode: 'login' }, meta: { title: '登录' } },
  { path: '/register', name: 'register', component: () => import('@/views/auth/AuthView.vue'), props: { mode: 'register' }, meta: { title: '注册' } },
  { path: '/account', name: 'account', component: () => import('@/views/account/AccountView.vue'), meta: { title: '账号' } },
  { path: '/read/:id', name: 'read', component: () => import('@/views/read/ReadView.vue'), meta: { title: '深读' } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  const isAuthPage = to.name === 'login' || to.name === 'register'
  if (!isAuthPage && !auth.isLoggedIn && !auth.isGuest) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})

router.afterEach((to) => {
  document.title = to.meta.title ? `星笺 · ${to.meta.title}` : '星笺 · STELLAR INK'
})

export default router
