<script setup>
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const props = defineProps({ mode: { type: String, default: 'login' } })
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const isLogin = computed(() => props.mode === 'login')

const username = ref('')
const password = ref('')
const nickname = ref('')
const loading = ref(false)
const error = ref('')

/* 登录 ↔ 注册 切换时清掉上一次的报错（用户名保留，方便连续输入） */
watch(() => props.mode, () => {
  error.value = ''
})

async function submit() {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码。'
    return
  }
  if (!isLogin.value && password.value.length < 6) {
    error.value = '密码至少 6 位。'
    return
  }
  loading.value = true
  try {
    if (isLogin.value) {
      await auth.login({ username: username.value.trim(), password: password.value })
    } else {
      await auth.register({
        username: username.value.trim(),
        password: password.value,
        nickname: nickname.value.trim() || undefined,
      })
    }
    router.replace('/account')
  } catch (e) {
    error.value = e.message || '操作失败，请稍后再试。'
  } finally {
    loading.value = false
  }
}

function enterAsGuest() {
  auth.enterAsGuest()
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  router.replace(redirect)
}
</script>

<template>
  <section class="page auth-page">
    <div class="auth-card reveal">
      <!-- 顶部装饰：缓慢呼吸的星点 -->
      <div class="auth-stars" aria-hidden="true">
        <i style="--x:82%; --y:14%; --s:3px; --d:0s"></i>
        <i style="--x:91%; --y:28%; --s:2px; --d:1.2s"></i>
        <i style="--x:74%; --y:8%; --s:2px; --d:2.4s"></i>
        <i style="--x:12%; --y:88%; --s:2px; --d:.8s"></i>
      </div>

      <RouterLink class="auth-badge" to="/" title="星笺">✦</RouterLink>

      <nav class="auth-tabs">
        <RouterLink to="/login" class="auth-tab" :class="{ on: isLogin }">登录</RouterLink>
        <RouterLink to="/register" class="auth-tab" :class="{ on: !isLogin }">注册</RouterLink>
      </nav>

      <h2 class="auth-title">{{ isLogin ? '欢迎回到星笺' : '领取你的星籍' }}</h2>
      <p class="auth-sub">{{ isLogin ? '夜深了，回来继续写。' : '新来的旅人，取个名字，点亮第一颗星。' }}</p>

      <form class="auth-form" @submit.prevent="submit">
        <div class="field">
          <label>登录名</label>
          <input v-model="username" autocomplete="username" placeholder="3~50 位字母 / 数字 / 下划线" />
        </div>
        <div class="field">
          <label>密码</label>
          <input v-model="password" type="password" :autocomplete="isLogin ? 'current-password' : 'new-password'" placeholder="至少 6 位" />
        </div>
        <div v-if="!isLogin" class="field">
          <label>笔名 <span class="opt">（选填，默认同登录名）</span></label>
          <input v-model="nickname" maxlength="50" placeholder="想被怎么称呼" />
        </div>

        <p v-if="error" class="auth-error">{{ error }}</p>

        <button class="btn btn-primary auth-submit" type="submit" :disabled="loading">
          {{ loading ? '正在连线…' : (isLogin ? '登录' : '注册并登录') }}
        </button>
      </form>

      <div class="auth-divider"><span>或</span></div>
      <button class="btn btn-ghost auth-guest" type="button" @click="enterAsGuest">
        <span aria-hidden="true">✦</span>
        以游客身份进入
      </button>

      <p v-if="!isLogin" class="auth-note">
        注册后默认为<b>读者</b>：可阅读、点赞、投瓶与申请友链；创作权限需站长提升为作者。
      </p>
    </div>
  </section>
</template>

<style scoped>
.auth-page{display:grid; place-items:center; min-height:calc(100vh - 48px); padding:24px}
.auth-card{width:min(460px,100%); border:1px solid var(--line); border-radius:var(--r-lg);
  background:linear-gradient(150deg,var(--bg-2),var(--bg-3)); padding:40px 38px 34px;
  position:relative; overflow:hidden}

/* 缓慢呼吸的星点装饰 */
.auth-stars{position:absolute; inset:0; pointer-events:none}
.auth-stars i{position:absolute; left:var(--x); top:var(--y); width:var(--s); height:var(--s);
  border-radius:50%; background:var(--primary); opacity:.5;
  animation:pulse 3.6s var(--ease-standard) infinite; animation-delay:var(--d)}

.auth-badge{width:56px; height:56px; border-radius:50%; display:grid; place-items:center; margin:0 auto 22px;
  background:linear-gradient(135deg,var(--primary),var(--rose)); font-family:var(--font-mono);
  font-size:22px; color:#fff; text-decoration:none;
  box-shadow:0 0 0 6px var(--primary-soft), 0 0 28px var(--primary-soft);
  transition:transform .3s var(--ease-spring)}
.auth-badge:hover{transform:translateY(-2px) scale(1.04)}

/* 登录 / 注册 切换 */
.auth-tabs{display:flex; gap:4px; padding:4px; margin:0 auto 26px; width:fit-content;
  border:1px solid var(--line); border-radius:99px; background:var(--surface)}
.auth-tab{padding:8px 26px; border-radius:99px; font-size:13px; text-decoration:none;
  color:var(--ink-faint); transition:all .3s var(--ease-standard)}
.auth-tab:hover{color:var(--ink-dim)}
.auth-tab.on{background:var(--primary-soft); color:var(--primary)}

.auth-title{font-family:var(--font-serif); font-weight:900; font-size:26px; text-align:center}
.auth-sub{text-align:center; font-size:13px; color:var(--ink-dim); margin:8px 0 26px}
.auth-form .opt{color:var(--ink-faint); letter-spacing:0}
.auth-form .field{margin-bottom:16px}
.auth-error{font-size:12px; color:var(--rose); margin-bottom:12px; line-height:1.6}
.auth-submit{width:100%; justify-content:center}
.auth-submit:disabled{opacity:.6; cursor:not-allowed; transform:none}
.auth-divider{display:flex; align-items:center; gap:12px; margin:20px 0; color:var(--ink-faint);
  font-family:var(--font-mono); font-size:10px}
.auth-divider::before,.auth-divider::after{content:''; flex:1; height:1px; background:var(--line)}
.auth-guest{width:100%; justify-content:center}
.auth-guest span{color:var(--primary); font-family:var(--font-mono)}
.auth-note{margin-top:20px; font-size:11px; line-height:1.8; color:var(--ink-faint);
  text-align:center; letter-spacing:.02em}
.auth-note b{color:var(--ink-dim); font-weight:500}

@media (max-width:520px){
  .auth-card{padding:32px 24px 28px}
}
</style>
