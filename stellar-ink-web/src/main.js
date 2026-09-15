import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useAuthStore } from '@/stores/auth'
import { on, SESSION_EXPIRED } from '@/utils/bus'
import './styles/tokens/variables.css'
import './styles/base.css'
import './styles/components.css'

const app = createApp(App)
app.use(createPinia()).use(router)

/* 首屏懒加载或导航守卫失败时显式记录，避免只留下空的 RouterView。 */
router.onError((error, to) => {
  console.error('[router] 导航失败 ->', to && to.fullPath, error)
})

/* 会话失效统一兜底：无论从哪个页面发现 token 过期（含 GET 接口返回的 code=401），
 * 都清空本地会话并带 redirect 回到登录页，避免用户卡在「读取失败」却不知道该登录。 */
on(SESSION_EXPIRED, () => {
  const auth = useAuthStore()
  const current = router.currentRoute.value
  if (!auth.isLoggedIn) return
  auth.clearSession()
  if (current.name === 'login' || current.name === 'register') return
  router.replace({ name: 'login', query: { redirect: current.fullPath } })
})

/* 立即挂载应用。初始路由的懒加载由 RouterView 接管，不能在挂载前等待：
 * 否则路由导航较慢或挂起时，#app 会一直为空。 */
app.mount('#app')
