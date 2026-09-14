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

/* 路由错误必须显式暴露：Vue Router 在导航失败/被中止时会保留 pending promise，
 * isReady() 可能长时间不 resolve，表现为「资源全部 200、控制台无异常、页面整片空白」，
 * 排查成本极高 —— 所以这类错误不能静默。 */
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

/* 首屏导航：等 router.isReady()，但加安全兜底。
 *
 * 为什么不能只写 await router.isReady()：一旦它不 resolve，app.mount() 永远不会执行，
 * 而生产构建里这种失败**没有任何报错**（资源全部 200、控制台干净、页面整片空白），
 * 排查成本极高（本次 App.vue 多根节点就是这么暴露出来的）。
 * 兜底后即使等待超时也照常挂载界面，最坏情况是首屏晚几百毫秒，而不是黑屏。 */
const READY_TIMEOUT_MS = 3000
const ready = await Promise.race([
  router.isReady().then(() => true),
  new Promise((resolve) => setTimeout(() => resolve(false), READY_TIMEOUT_MS)),
])
if (!ready) {
  console.warn(`[router] isReady() 超过 ${READY_TIMEOUT_MS}ms 未完成，先挂载界面（当前路由 ${router.currentRoute.value.fullPath}）`)
}

app.mount('#app')
