<script setup>
/* 顶部横向导航（对齐原型 b12-stars-only 的顶栏）：
 *   [✦ 星笺 STELLAR INK]   [此刻 星图 笔记 流星 回声 星链]   [⌕ 执笔 ▾头像]
 * 未滚动时整条透明、直接压在星野上；滚动超过 40px 才浮出底色、模糊与分隔线。
 * 一级导航只放「内容维度」六项：执笔收成右上主色按钮（仅作者可见），
 * 寻星收成放大镜图标，光谱并入星图、复核并入我的笔记、星籍并入账号 —— 都不再占一级导航位。 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { useAuthStore } from '@/stores/auth'
import { roleLabel } from '@/utils/role'
import { emit, TOAST } from '@/utils/bus'
import UserAvatar from '@/components/common/UserAvatar.vue'

const route = useRoute()
const router = useRouter()
const settings = useSettingsStore()
const auth = useAuthStore()

const items = [
  { key: 'home', to: '/', glyph: '◉', label: '此刻' },
  { key: 'archive', to: '/archive', glyph: '✧', label: '星图' },
  { key: 'notes', to: '/notes', glyph: '❖', label: '笔记' },
  { key: 'meteor', to: '/meteor', glyph: '☄', label: '流星' },
  { key: 'echo', to: '/echo', glyph: '❞', label: '回声' },
  { key: 'links', to: '/links', glyph: '⬡', label: '星链' },
]

/* 已合并进别的页面、但仍可能留在 lastPageName 里的旧路由名，统一归到新的一级入口 */
const KEY_ALIAS = { 'notes-mine': 'notes', 'notes-review': 'notes', spectrum: 'archive', passport: 'account' }

/* 深读页与技术笔记详情不属于导航，高亮保持为进入前的页面 */
const activeKey = computed(() => {
  const name = route.name === 'read' || route.name === 'note' ? settings.lastPageName : route.name
  const key = KEY_ALIAS[name] || name
  return items.some((item) => item.key === key) ? key : ''
})

const canWrite = computed(() => auth.isLoggedIn && auth.isAuthorOrAbove)
const onSearch = computed(() => route.name === 'search')

/* ---- 滚动后才浮出底色的顶栏 ---- */
const solid = ref(false)
function onScroll() {
  solid.value = window.scrollY > 40
}
onMounted(() => {
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKeydown)
})

/* ---- 头像菜单：账号 / 我的笔记 / 外观 / 登出 ---- */
const THEMES = [
  { key: 'night', label: '永夜' },
  { key: 'dusk', label: '暮光' },
  { key: 'dawn', label: '破晓' },
]
const menuOpen = ref(false)
const userWrapEl = ref(null)

function toggleMenu() {
  menuOpen.value = !menuOpen.value
}
function closeMenu() {
  menuOpen.value = false
}
function pickTheme(key) {
  settings.setTheme(key)
}
async function doLogout() {
  closeMenu()
  await auth.logout()
  emit(TOAST, { type: 'success', message: '已登出，星图依然为你亮着' })
  router.push({ name: 'home' })
}
/* 点击外部 / Esc 关闭菜单 */
function onDocClick(event) {
  if (!menuOpen.value) return
  if (userWrapEl.value && userWrapEl.value.contains(event.target)) return
  closeMenu()
}
function onKeydown(event) {
  if (event.key === 'Escape') closeMenu()
}
onUnmounted(() => {
  window.removeEventListener('scroll', onScroll)
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKeydown)
})

/* 身份标签：已登录显角色，游客显「游客·去登录」，未登录显「登录/注册」 */
const userLabel = computed(() => {
  if (auth.isLoggedIn) return roleLabel(auth.role)
  return auth.isGuest ? '游客 · 去登录' : '登录 / 注册'
})
/* auth.isLoggedIn 只看 token；localStorage 里的 user 可能缺失或被清掉（换浏览器标签、
   手工清缓存），此时读 auth.user.nickname 会让整条导航渲染抛异常、整页白屏，
   所以这里与模板里的头像取值一样必须先判空。 */
const userDisplayName = computed(() => {
  const u = auth.user
  return (u && (u.nickname || u.username)) || ''
})
const userTip = computed(() => {
  if (auth.isLoggedIn) {
    const name = userDisplayName.value || '星客'
    return `${name} · ${roleLabel(auth.role)} · 进入账号`
  }
  if (auth.isGuest) return '当前以游客身份浏览 · 登录后可同步你的身份'
  return '登录 / 注册'
})
/* 未登录（含游客）时把「回哪儿去」写进 redirect，登录后不会被丢到账号页 */
const userTo = computed(() => {
  if (auth.isLoggedIn) return '/account'
  const name = route.name
  const fromQuery = name === 'login' || name === 'register' ? route.query.redirect : null
  const target = typeof fromQuery === 'string' ? fromQuery : route.fullPath
  return { path: '/login', query: { redirect: target } }
})
</script>

<template>
  <header class="topbar" :class="{ 'is-solid': solid }">
    <div class="topbar__inner">
      <RouterLink class="brand" to="/" title="星笺 · 回到此刻">
        <span class="brand__mark" aria-hidden="true">✦</span>
        <span class="brand__name">星笺</span>
        <span class="brand__en">STELLAR INK</span>
      </RouterLink>

      <nav class="nav" aria-label="主导航">
        <RouterLink
          v-for="it in items" :key="it.key" :to="it.to"
          class="nav__link" :class="{ on: activeKey === it.key }"
          :aria-current="activeKey === it.key ? 'page' : undefined"
        >
          <span class="nav__glyph" aria-hidden="true">{{ it.glyph }}</span>{{ it.label }}
        </RouterLink>
      </nav>

      <div class="topbar__actions">
        <RouterLink
          class="icon-btn" :class="{ on: onSearch }" to="/search"
          title="寻星 · 搜索文章与笔记" aria-label="寻星：搜索文章与笔记"
        >
          <svg width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <circle cx="8.6" cy="8.6" r="5.6" stroke="currentColor" stroke-width="1.4" />
            <path d="M12.9 12.9 17 17" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          </svg>
        </RouterLink>

        <!-- 执笔：写作入口收成主色按钮，读者看不到 -->
        <RouterLink v-if="canWrite" class="write-btn" to="/write" title="执笔 · 今晚写点什么">
          <span aria-hidden="true">✎</span><span class="write-btn__label">执笔</span>
        </RouterLink>

        <div ref="userWrapEl" class="user">
          <button
            class="user__btn" type="button" :class="{ on: menuOpen }"
            :title="userTip" :aria-expanded="menuOpen ? 'true' : 'false'" aria-haspopup="menu"
            @click="toggleMenu"
          >
            <UserAvatar
              class="user__avatar"
              :url="(auth.user && auth.user.avatarUrl) || ''"
              :text="auth.isLoggedIn ? ((auth.user && auth.user.avatarText) || '') : (auth.isGuest ? '游' : '✦')"
              :nickname="userDisplayName"
              :size="26"
            />
            <span class="user__label">{{ userLabel }}</span>
            <span class="user__caret" aria-hidden="true">▼</span>
          </button>

          <div v-if="menuOpen" class="menu" role="menu">
            <RouterLink class="um-item" role="menuitem" to="/account" @click="closeMenu">账号设置</RouterLink>
            <RouterLink v-if="auth.isLoggedIn" class="um-item" role="menuitem" to="/notes/mine" @click="closeMenu">
              我的笔记
            </RouterLink>
            <p class="um-title">外观</p>
            <div class="um-themes">
              <button
                v-for="t in THEMES" :key="t.key" type="button" class="um-theme"
                :class="{ on: settings.theme === t.key }" @click="pickTheme(t.key)"
              >{{ t.label }}</button>
            </div>
            <div class="um-sep"></div>
            <button v-if="auth.isLoggedIn" class="um-item danger" type="button" role="menuitem" @click="doLogout">登出</button>
            <RouterLink v-else class="um-item" role="menuitem" :to="userTo" @click="closeMenu">登录 / 注册</RouterLink>
          </div>
        </div>
      </div>
    </div>
  </header>
</template>

<style scoped>
.topbar{
  position:sticky; top:0; z-index:50;
  background:transparent; border-bottom:1px solid transparent;
  transition:background .45s var(--ease-standard), border-color .45s var(--ease-standard);
}
/* 未滚动时导航完全透明（直接压在星野上），滚动 40px 后才浮出底色与分隔线 */
.topbar.is-solid{
  background:color-mix(in srgb, var(--bg) 84%, transparent);
  backdrop-filter:blur(16px);
  border-bottom-color:var(--line);
}
/* 内层宽度与 .page 对齐（同样的 1240px 上限与同一套内边距），品牌才会和正文左边缘对齐 */
.topbar__inner{
  max-width:1240px; margin:0 auto; height:64px; padding:0 clamp(24px,4vw,56px);
  display:flex; align-items:center; gap:24px;
}
.brand{display:flex; align-items:baseline; gap:9px; flex:none; text-decoration:none; color:var(--ink);
  font-family:var(--font-display); font-weight:700; font-size:17px; letter-spacing:.02em}
.brand__mark{color:var(--primary); font-size:15px}
.brand__en{font-family:var(--font-mono); font-size:10px; font-weight:400; letter-spacing:.2em; color:var(--ink-faint)}

.nav{display:flex; gap:2px; margin:0 auto}
.nav__link{
  position:relative; display:flex; align-items:center; gap:6px; padding:9px 13px;
  border-radius:var(--r-sm); text-decoration:none; white-space:nowrap;
  font-size:14px; color:var(--ink-dim);
  transition:color .25s var(--ease-standard), background .25s var(--ease-standard);
}
.nav__glyph{font-size:13px; color:var(--ink-faint); transition:color .25s var(--ease-standard)}
.nav__link:hover{color:var(--ink); background:var(--surface)}
.nav__link:hover .nav__glyph{color:var(--ink-dim)}
.nav__link.on{color:var(--ink)}
.nav__link.on .nav__glyph{color:var(--primary)}
.nav__link.on::after{
  content:''; position:absolute; left:13px; right:13px; bottom:1px; height:1px; background:var(--primary);
}

.topbar__actions{display:flex; align-items:center; gap:10px; flex:none; margin-left:auto}
.icon-btn{
  width:36px; height:36px; border-radius:50%; border:1px solid transparent;
  background:transparent; color:var(--ink-dim); display:grid; place-items:center; cursor:pointer;
  text-decoration:none;
  transition:color .25s var(--ease-standard), background .25s var(--ease-standard), border-color .25s var(--ease-standard);
}
.icon-btn:hover,.icon-btn.on{color:var(--ink); background:var(--surface); border-color:var(--line)}
.write-btn{
  display:flex; align-items:center; gap:7px; height:36px; padding:0 16px;
  border-radius:999px; border:1px solid transparent; background:var(--primary); color:var(--on-primary);
  font-size:13.5px; font-weight:500; text-decoration:none;
  transition:transform .3s var(--ease-standard), background .3s var(--ease-standard);
}
.write-btn:hover{transform:translateY(-1px)}

.user{position:relative}
.user__btn{
  display:flex; align-items:center; gap:9px; padding:4px 11px 4px 5px; cursor:pointer;
  border:1px solid var(--line); border-radius:999px; background:transparent;
  color:var(--ink-dim); font:inherit; font-size:13px; font-weight:300;
  transition:color .25s var(--ease-standard), background .25s var(--ease-standard), border-color .25s var(--ease-standard);
}
.user__btn:hover,.user__btn.on{color:var(--ink); background:var(--surface); border-color:var(--primary)}
.user__label{white-space:nowrap}
.user__caret{font-size:9px; color:var(--ink-faint); transition:transform .3s var(--ease-standard)}
.user__btn.on .user__caret{transform:rotate(180deg)}

.menu{
  position:absolute; right:0; top:calc(100% + 12px); width:214px; z-index:60;
  border:1px solid var(--line); border-radius:var(--r-md); padding:8px;
  background:color-mix(in srgb, var(--bg-3) 94%, transparent);
  backdrop-filter:blur(16px); box-shadow:0 18px 44px rgba(0,0,0,.34);
  display:flex; flex-direction:column; gap:2px;
}
.um-item{
  display:block; width:100%; text-align:left; text-decoration:none; cursor:pointer;
  border:0; background:transparent; font:inherit; font-size:13px; color:var(--ink-dim);
  padding:9px 12px; border-radius:var(--r-sm); transition:background .2s, color .2s;
}
.um-item:hover{background:var(--surface-2); color:var(--ink)}
.um-item.danger{color:var(--rose)}
.um-title{
  margin:8px 0 4px; padding:0 12px; font-family:var(--font-mono); font-size:10px;
  letter-spacing:.24em; color:var(--ink-faint); text-transform:uppercase;
}
.um-themes{display:flex; gap:6px; padding:0 8px 4px}
.um-theme{
  flex:1; border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  font:inherit; font-size:12px; padding:7px 0; border-radius:var(--r-sm); cursor:pointer;
  transition:all .2s var(--ease-spring);
}
.um-theme:hover{border-color:var(--primary); color:var(--ink)}
.um-theme.on{background:var(--primary-soft); border-color:var(--primary); color:var(--primary)}
.um-sep{height:1px; background:var(--line); margin:8px 4px}

/* 窄屏：导航换到第二行并横向滚动，顶栏不再挤压成一团 */
@media (max-width:1080px){
  .topbar__inner{flex-wrap:wrap; height:auto; gap:8px 16px; padding:8px clamp(16px,4vw,32px) 0}
  .brand{order:1}
  .topbar__actions{order:2}
  .nav{order:3; flex:1 0 100%; margin:0; overflow-x:auto; scrollbar-width:none;
    padding-bottom:6px}
  .nav::-webkit-scrollbar{display:none}
  .nav__link{padding:7px 11px; font-size:13px}
  .nav__link.on::after{bottom:-1px}
}
@media (max-width:560px){
  .brand__en{display:none}
  .write-btn{padding:0 12px}
  .write-btn__label{display:none}
  .user__label{display:none}
  .user__btn{padding:4px 6px}
}
</style>
