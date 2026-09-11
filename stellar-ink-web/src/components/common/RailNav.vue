<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { useAuthStore } from '@/stores/auth'
import { roleLabel } from '@/utils/role'

const route = useRoute()
const settings = useSettingsStore()
const auth = useAuthStore()

const items = [
  { key: 'home', to: '/', glyph: '◉', label: '此刻' },
  { key: 'write', to: '/write', glyph: '✎', label: '执笔' },
  { key: 'archive', to: '/archive', glyph: '✧', label: '星图' },
  { key: 'meteor', to: '/meteor', glyph: '☄', label: '流星' },
  { key: 'spectrum', to: '/spectrum', glyph: '❖', label: '光谱' },
  { key: 'echo', to: '/echo', glyph: '❞', label: '回声' },
  { key: 'links', to: '/links', glyph: '⬡', label: '星链' },
  { key: 'passport', to: '/passport', glyph: '✪', label: '星籍' },
  { key: 'bridge', to: '/bridge', glyph: '⚙', label: '舰桥' },
]

/* 深读页不属于导航，高亮保持为进入前的页面 */
const activeKey = computed(() => (route.name === 'read' ? settings.lastPageName : route.name))

const avatarChar = computed(() => {
  if (!auth.isLoggedIn) return '✦'
  const n = auth.user.nickname || auth.user.username || '星'
  return n.trim().charAt(0)
})
/* 未登录显示「登录」，已登录显示角色（读者 / 作者 / 站长） */
const userLabel = computed(() => (auth.isLoggedIn ? roleLabel(auth.role) : '登录'))
const userTip = computed(() =>
  auth.isLoggedIn ? `${auth.user.nickname || auth.user.username} · ${roleLabel(auth.role)}` : '登录 / 注册',
)
</script>

<template>
  <nav class="rail">
    <RouterLink class="logo" to="/" title="星笺">✦</RouterLink>
    <!-- 身份入口：紧跟 logo 下方，导航栏底部只留状态灯 -->
    <RouterLink
      class="rail-user" :class="{ on: auth.isLoggedIn }"
      :to="auth.isLoggedIn ? '/account' : '/login'" :title="userTip"
    >
      <span class="ru-avatar" :class="{ on: auth.isLoggedIn }">{{ avatarChar }}</span>
      <span class="ru-label">{{ userLabel }}</span>
    </RouterLink>
    <RouterLink
      v-for="it in items" :key="it.key" :to="it.to"
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
  text-decoration:none; color:var(--ink-faint); transition:color .3s var(--ease-spring)}
.ru-avatar{width:44px; height:44px; border-radius:50%; display:grid; place-items:center;
  border:1px dashed var(--line); color:var(--ink-faint);
  font-family:var(--font-mono); font-size:16px; transition:all .3s var(--ease-spring)}
.rail-user:hover{color:var(--ink-dim)}
.rail-user:hover .ru-avatar{border-color:var(--primary); color:var(--primary)}
.ru-avatar.on{border-style:solid; border-color:transparent; color:#fff;
  background:linear-gradient(135deg,var(--primary),var(--rose));
  box-shadow:0 4px 16px var(--primary-soft)}
.ru-label{font-size:11px; letter-spacing:.08em}
.rail-user.on .ru-label{color:var(--ink-dim)}
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

@media (max-width:720px){
  .rail{top:auto; bottom:0; left:0; right:0; width:auto; height:72px; flex-direction:row;
    justify-content:center; gap:4px; border-right:none; border-top:1px solid var(--line); padding:0 8px}
  .logo,.rail-foot{display:none}
  .rail-user{margin:0 2px 0 0; gap:3px}
  .ru-avatar{width:34px; height:34px; font-size:14px}
  .ru-label{font-size:10px}
  .nav-item{margin:0; width:48px; font-size:10px; padding:10px 0 8px}
  .nav-item.active::before{left:50%; top:-1px; translate:-50% 0; width:26px; height:3px}
}
</style>
