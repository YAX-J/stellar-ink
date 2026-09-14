import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/', name: 'home', component: () => import('@/views/home/HomeView.vue'), meta: { title: '此刻' } },
  { path: '/write', name: 'write', component: () => import('@/views/write/WriteView.vue'), meta: { title: '执笔', requiresRole: 'AUTHOR' } },
  { path: '/archive', name: 'archive', component: () => import('@/views/archive/ArchiveView.vue'), meta: { title: '星图' } },
  { path: '/notes', name: 'notes', component: () => import('@/views/notes/NotesView.vue'), meta: { title: '笔记' } },
  { path: '/notes/mine', name: 'notes-mine', component: () => import('@/views/notes/NotesMineView.vue'), meta: { title: '我的笔记', requiresAuth: true } },
  { path: '/note/edit', name: 'note-edit', component: () => import('@/views/notes/NoteEditView.vue'), meta: { title: '写笔记', requiresAuth: true } },
  { path: '/note/:id', name: 'note', component: () => import('@/views/notes/NoteDetailView.vue'), meta: { title: '笔记' } },
  { path: '/meteor', name: 'meteor', component: () => import('@/views/meteor/MeteorView.vue'), meta: { title: '流星' } },
  { path: '/spectrum', name: 'spectrum', component: () => import('@/views/spectrum/SpectrumView.vue'), meta: { title: '光谱' } },
  { path: '/echo', name: 'echo', component: () => import('@/views/echo/EchoView.vue'), meta: { title: '回声' } },
  { path: '/links', name: 'links', component: () => import('@/views/links/LinksView.vue'), meta: { title: '星链' } },
  { path: '/passport', name: 'passport', component: () => import('@/views/passport/PassportView.vue'), meta: { title: '星籍' } },
  { path: '/bridge', name: 'bridge', component: () => import('@/views/bridge/BridgeView.vue'), meta: { title: '舰桥' } },
  { path: '/login', name: 'login', component: () => import('@/views/auth/AuthView.vue'), props: { mode: 'login' }, meta: { title: '登录', layout: 'auth' } },
  { path: '/register', name: 'register', component: () => import('@/views/auth/AuthView.vue'), props: { mode: 'register' }, meta: { title: '注册', layout: 'auth' } },
  { path: '/account', name: 'account', component: () => import('@/views/account/AccountView.vue'), meta: { title: '账号', requiresAuth: true } },
  { path: '/read/:id', name: 'read', component: () => import('@/views/read/ReadView.vue'), meta: { title: '深读' } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/* 公开页面：未登录也能逛（与网关一致 —— 所有读接口本就公开）。
 * 只有真正需要身份的操作（写作、账号）才拦截，冷启动不再第一屏就是登录页。 */
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (auth.isLoggedIn) return
  /* 未登录访问需登录页：带 redirect 去登录，登录后回到原处 */
  if (to.meta.requiresAuth) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  /* 公开页但角色不足（如读者进写作舱）：留在页内用友好提示说明，不做静默重定向 */
})

router.afterEach((to) => {
  document.title = to.meta.title ? `星笺 · ${to.meta.title}` : '星笺 · STELLAR INK'
})

export default router
