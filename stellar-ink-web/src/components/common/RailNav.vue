<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { useAuthStore } from '@/stores/auth'
import { roleAtLeast, roleLabel } from '@/utils/role'
import UserAvatar from '@/components/common/UserAvatar.vue'

const route = useRoute()
const settings = useSettingsStore()
const auth = useAuthStore()
const items = [
  { key: 'home', to: '/', glyph: '◉', label: '此刻' },
  { key: 'write', to: '/write', glyph: '✎', label: '执笔', requiresRole: 'AUTHOR' },
  { key: 'archive', to: '/archive', glyph: '✧', label: '星图' },
  { key: 'search', to: '/search', glyph: '⌕', label: '寻星' },
  /* 技术笔记用 ❖（原光谱符号，笔记更需要「标本」感）；光谱改用 ▤ 以免同符号 */
  { key: 'notes', to: '/notes', glyph: '❖', label: '笔记' },
  { key: 'notes-review', to: '/notes/review', glyph: '✓', label: '复核', requiresRole: 'AUTHOR' },
  { key: 'meteor', to: '/meteor', glyph: '☄', label: '流星' },
  { key: 'spectrum', to: '/spectrum', glyph: '▤', label: '光谱' },
  { key: 'echo', to: '/echo', glyph: '❞', label: '回声' },
  { key: 'links', to: '/links', glyph: '⬡', label: '星链' },
  { key: 'passport', to: '/passport', glyph: '✪', label: '星籍' },
  { key: 'bridge', to: '/bridge', glyph: '⚙', label: '舰桥' },
]

/* 深读页与技术笔记详情不属于导航，高亮保持为进入前的页面 */
const activeKey = computed(() => (
  route.name === 'read' || route.name === 'note' ? settings.lastPageName : route.name
))
const visibleItems = computed(() => items.filter((item) =>
  !item.requiresRole || (auth.isLoggedIn && roleAtLeast(auth.role, item.requiresRole)),
))

/* 身份标签：已登录显角色，游客显「游客·去登录」，未登录显「登录/注册」 */
const userLabel = computed(() => {
  if (auth.isLoggedIn) return roleLabel(auth.role)
  return auth.isGuest ? '游客 · 去登录' : '登录 / 注册'
})
/* auth.isLoggedIn 只看 token；localStorage 里的 user 可能缺失或被清掉（换浏览器标签、
   手工清缓存），此时读 auth.user.nickname 会让整个 RailNav 渲染抛异常、整页白屏，
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
  <nav class="rail">
    <RouterLink class="logo" to="/" title="星笺">✦</RouterLink>
    <!-- 身份入口：紧跟 logo 下方，导航栏底部只留状态灯 -->
    <RouterLink
      class="rail-user"
      :class="{ on: auth.isLoggedIn, guest: auth.isGuest, anon: !auth.isLoggedIn }"
      :to="userTo" :title="userTip"
    >
      <UserAvatar
        class="ru-avatar"
        :class="{ on: auth.isLoggedIn, guest: auth.isGuest, anon: !auth.isLoggedIn }"
        :url="(auth.user && auth.user.avatarUrl) || ''"
        :text="auth.isLoggedIn ? ((auth.user && auth.user.avatarText) || '') : (auth.isGuest ? '游' : '✦')"
        :nickname="userDisplayName"
        :size="44"
      />
      <span class="ru-label">{{ userLabel }}</span>
    </RouterLink>
    <RouterLink
      v-for="it in visibleItems" :key="it.key" :to="it.to"
      class="nav-item" :class="{ active: activeKey === it.key }"
    >
      <span class="glyph">{{ it.glyph }}</span>{{ it.label }}
    </RouterLink>
    <div class="rail-foot">
      <div class="rail-dot" title="灵感在线"></div>
      <div class="rail-ver">STELLAR INK v3.0</div>
    </div>
  </nav>
</template>

<style scoped>
.rail{
  position:fixed; left:0; top:0; bottom:0; width:96px; z-index:50;
  display:flex; flex-direction:column; align-items:center; padding:28px 0;
  border-right:1px solid var(--line);
  background:color-mix(in srgb, var(--bg) 82%, transparent);
  backdrop-filter:blur(18px);
  transition:background .6s, border-color .6s, opacity .5s, transform .5s;
  overflow-y:auto; scrollbar-width:none;
}
.rail::-webkit-scrollbar{display:none}
body.focus-mode .rail{opacity:0; transform:translateX(-100%); pointer-events:none}
.logo{
  width:44px; height:44px; border-radius:14px; display:grid; place-items:center;
  background:linear-gradient(135deg,var(--primary),var(--rose));
  font-family:var(--font-mono); font-weight:600; font-size:18px; color:#fff;
  box-shadow:0 0 24px var(--primary-soft), inset 0 0 12px rgba(255,255,255,.25);
  margin-bottom:20px; cursor:pointer; text-decoration:none;
}

/* 身份入口：圆形头像 + 角色/登录 文字，与 logo 成组 */
.rail-user{display:flex; flex-direction:column; align-items:center; gap:7px; margin-bottom:28px;
  width:76px; text-align:center; text-decoration:none; color:var(--ink-faint);
  transition:color .3s var(--ease-spring)}
/* 头像本体（圆形、居中、底字取字）由 UserAvatar 提供，这里只覆写三种身份态的配色。
   注意：不要在这里再写 border-radius —— UserAvatar 的 shape 是行内样式，会压过它。 */
.ru-avatar{border:1px dashed var(--line); color:var(--ink-faint);
  font-family:var(--font-mono); transition:all .3s var(--ease-spring)}
.rail-user:hover{color:var(--ink-dim)}
.rail-user:hover .ru-avatar{border-color:var(--primary); color:var(--primary)}
.ru-avatar.on{border-style:solid; border-color:transparent; color:#fff;
  background:linear-gradient(135deg,var(--primary),var(--rose));
  box-shadow:0 4px 16px var(--primary-soft)}
.ru-avatar.guest{border-style:solid; border-color:var(--primary); color:var(--primary);
  background:var(--primary-soft); box-shadow:none}
/* 未登录：实心入口，比游客更主动地引导登录 */
.ru-avatar.anon{border-style:solid; border-color:transparent; color:#fff;
  background:linear-gradient(135deg,var(--primary),var(--rose));
  box-shadow:0 4px 16px var(--primary-soft)}
/* 已登录且用了图片头像：别让身份态渐变盖住照片（图片自带底色，透明边框即可） */
.ru-avatar.img{background:none; box-shadow:none; border-color:var(--line)}
.rail-user.anon .ru-label{color:var(--ink-dim)}
.ru-label{font-size:10px; letter-spacing:.06em; line-height:1.5; max-width:76px}
.nav-item{
  width:64px; padding:12px 0 10px; margin:6px 0; border:none; border-radius:var(--r-md);
  background:transparent; color:var(--ink-faint); cursor:pointer;
  display:flex; flex-direction:column; align-items:center; gap:5px;
  font-family:var(--font-body); font-size:11px; letter-spacing:.08em;
  transition:all .3s var(--ease-spring); position:relative; text-decoration:none;
}
.nav-item .glyph{font-size:19px; line-height:1; transition:transform .3s var(--ease-spring)}
.nav-item:hover{color:var(--ink-dim); background:var(--surface)}
.nav-item:hover .glyph{transform:translateY(-2px) scale(1.12)}
.nav-item.active{color:var(--ink); background:var(--primary-soft)}
.nav-item.active::before{
  content:''; position:absolute; left:-17px; top:50%; translate:0 -50%;
  width:3px; height:26px; border-radius:2px; background:var(--primary);
  box-shadow:0 0 12px var(--primary);
}
.rail-foot{margin-top:auto; display:flex; flex-direction:column; gap:14px; align-items:center}
.rail-dot{width:8px; height:8px; border-radius:50%; background:var(--teal);
  animation:pulse 2.4s infinite; box-shadow:0 0 10px var(--teal)}
.rail-ver{font-family:var(--font-mono); font-size:9px; color:var(--ink-faint); writing-mode:vertical-rl}

/* 矮窗口自适应：默认布局需要约 890px 高，而 1366×768 笔记本的实际视口只有 ~650px，
   底部入口（星籍/舰桥）会被裁掉，且滚动条被隐藏、用户无从察觉 → 按高度逐级收紧间距。 */
@media (max-height:900px){
  .rail{padding:18px 0}
  .logo{width:40px; height:40px; font-size:16px; margin-bottom:20px}
  .nav-item{margin:3px 0; padding:9px 0 8px}
  .nav-item .glyph{font-size:17px}
  .rail-foot{gap:10px}
  .rail-ver{font-size:8px}
}
@media (max-height:780px){
  .rail{padding:12px 0}
  .logo{width:34px; height:34px; font-size:14px; border-radius:11px; margin-bottom:12px}
  .nav-item{margin:1px 0; padding:7px 0 6px; width:60px}
  .nav-item .glyph{font-size:15px}
  /* 版本号是纯装饰，矮屏下让位给导航入口 */
  .rail-foot{gap:0}
  .rail-ver{display:none}
}
@media (max-height:620px){
  .rail{padding:10px 0}
  .logo{width:30px; height:30px; font-size:13px; margin-bottom:8px}
  .nav-item{margin:0; padding:5px 0 4px; font-size:10px}
  .nav-item .glyph{font-size:14px}
}

@media (max-width:720px){
  .rail{top:auto; bottom:0; left:0; right:0; width:auto; height:72px; flex-direction:row;
    justify-content:flex-start; gap:4px; border-right:none; border-top:1px solid var(--line); padding:0 8px;
    overflow-x:auto; overflow-y:hidden}
  .logo,.rail-foot{display:none}
  .rail-user{margin:0 2px 0 0; gap:3px; width:52px; flex:0 0 52px}
  .ru-avatar{width:34px; height:34px; font-size:14px}
  .ru-label{font-size:9px; max-width:52px}
  .nav-item{margin:0; width:48px; flex:0 0 48px; font-size:10px; padding:10px 0 8px}
  .nav-item.active::before{left:50%; top:-1px; translate:-50% 0; width:26px; height:3px}
}
</style>
