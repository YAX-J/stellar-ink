<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore, isAuthError } from '@/stores/auth'
import { roleLabel, ROLE_LABEL } from '@/utils/role'
import SectionHead from '@/components/common/SectionHead.vue'

const router = useRouter()
const auth = useAuthStore()

const user = computed(() => auth.user)
const avatarChar = computed(() => {
  const name = (user.value && (user.value.nickname || user.value.username)) || '星'
  return name.trim().charAt(0)
})
const roleClass = computed(() => `role-${(user.value && user.value.role) || 'READER'}`.toLowerCase())
const createdAt = computed(() => {
  const t = user.value && user.value.createdAt
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
})

/* ---- 修改密码 ---- */
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const pwLoading = ref(false)
const pwMsg = ref('')
const pwError = ref('')

async function changePassword() {
  pwMsg.value = ''
  pwError.value = ''
  if (!oldPassword.value || !newPassword.value) {
    pwError.value = '请填写原密码与新密码。'
    return
  }
  if (newPassword.value.length < 6) {
    pwError.value = '新密码至少 6 位。'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    pwError.value = '两次输入的新密码不一致。'
    return
  }
  pwLoading.value = true
  try {
    await auth.changePassword({ oldPassword: oldPassword.value, newPassword: newPassword.value })
    pwMsg.value = '密码已更新。'
    oldPassword.value = newPassword.value = confirmPassword.value = ''
  } catch (e) {
    handleError(e, pwError)
  } finally {
    pwLoading.value = false
  }
}

/* ---- 登出 ---- */
async function logout() {
  await auth.logout()
  router.replace('/')
}

/* ---- 成员管理（ADMIN） ---- */
const users = ref([])
const listLoading = ref(false)
const listError = ref('')
const roleMsgs = ref({})

async function loadUsers() {
  listLoading.value = true
  listError.value = ''
  try {
    users.value = await auth.listUsers()
  } catch (e) {
    handleError(e, listError)
  } finally {
    listLoading.value = false
  }
}

async function onRoleChange(u, role) {
  roleMsgs.value[u.id] = ''
  try {
    await auth.changeRole(u.id, role)
    roleMsgs.value[u.id] = '已更新'
    // 回填列表里的最新角色
    const item = users.value.find((x) => x.id === u.id)
    if (item) item.role = role
  } catch (e) {
    if (!handleAuthExpiry(e)) roleMsgs.value[u.id] = e.message || '更新失败'
    // 回退到原角色
    const item = users.value.find((x) => x.id === u.id)
    if (item) item.role = u.role
  }
}

/* ---- 会话失效（网关 HTTP 401）兜底：清空并回登录页，返回是否已处理 ---- */
function handleAuthExpiry(e) {
  if (isAuthError(e)) {
    auth.clearSession()
    router.replace('/login')
    return true
  }
  return false
}

function handleError(e, target) {
  if (handleAuthExpiry(e)) return
  target.value = e.message || '操作失败'
}

onMounted(async () => {
  if (!auth.isLoggedIn) return
  try {
    await auth.fetchProfile()
  } catch (e) {
    handleAuthExpiry(e)
  }
  if (auth.isAdmin) await loadUsers()
})
</script>

<template>
  <section class="page">
    <div class="kicker reveal">ACCOUNT · 账号与星籍</div>
    <SectionHead title="账号" more="你的身份与权限" />

    <!-- 未登录 -->
    <div v-if="!auth.isLoggedIn" class="empty reveal">
      <div class="empty-star">✦</div>
      <p>还没有登录，无法查看账号。</p>
      <RouterLink class="btn btn-primary" to="/login">去登录</RouterLink>
    </div>

    <template v-else>
      <div class="account-grid">
        <!-- 我的星籍 -->
        <div class="panel reveal" style="--d:.06s">
          <h3>我的星籍</h3>
          <div class="id-row">
            <div class="avatar">{{ avatarChar }}</div>
            <div>
              <b class="id-name">{{ user.nickname || user.username }}</b>
              <div class="mono">@{{ user.username }}</div>
              <span class="role-badge" :class="roleClass">{{ roleLabel(user.role) }}</span>
            </div>
          </div>
          <div class="pp-rows">
            <div><span>星籍编号</span><b>NO.{{ user.id }}</b></div>
            <div><span>注册星历</span><b>{{ createdAt }}</b></div>
          </div>
        </div>

        <!-- 修改密码 -->
        <div class="panel reveal" style="--d:.12s">
          <h3>修改密码</h3>
          <form @submit.prevent="changePassword">
            <div class="field">
              <label>原密码</label>
              <input v-model="oldPassword" type="password" autocomplete="current-password" />
            </div>
            <div class="field">
              <label>新密码</label>
              <input v-model="newPassword" type="password" autocomplete="new-password" placeholder="至少 6 位" />
            </div>
            <div class="field">
              <label>确认新密码</label>
              <input v-model="confirmPassword" type="password" autocomplete="new-password" />
            </div>
            <p v-if="pwError" class="msg err">{{ pwError }}</p>
            <p v-if="pwMsg" class="msg ok">{{ pwMsg }}</p>
            <button class="btn btn-ghost" type="submit" :disabled="pwLoading">
              {{ pwLoading ? '更新中…' : '更新密码' }}
            </button>
          </form>
        </div>
      </div>

      <!-- 登出 -->
      <div class="panel reveal" style="--d:.18s">
        <h3>会话</h3>
        <div class="row-between">
          <span class="dim">退出当前登录（无状态 JWT，前端丢弃 token 即登出）。</span>
          <button class="btn btn-ghost" @click="logout">登出</button>
        </div>
      </div>

      <!-- 成员管理（仅站长） -->
      <div v-if="auth.isAdmin" class="panel reveal" style="--d:.24s">
        <h3>成员管理 · 站长</h3>
        <p v-if="listError" class="msg err">{{ listError }}</p>
        <p v-if="listLoading" class="dim">正在读取成员名单…</p>
        <div v-else class="member-list">
          <div v-for="u in users" :key="u.id" class="member-row">
            <div class="member-id">
              <b>{{ u.nickname || u.username }}</b>
              <span class="mono">@{{ u.username }}</span>
            </div>
            <div class="member-role">
              <select
                :value="u.role"
                :disabled="Number(u.id) === Number(user.id)"
                @change="onRoleChange(u, $event.target.value)"
              >
                <option v-for="(label, key) in ROLE_LABEL" :key="key" :value="key">{{ label }}</option>
              </select>
              <span v-if="Number(u.id) === Number(user.id)" class="hint">（自己不可改）</span>
              <span v-if="roleMsgs[u.id]" class="msg ok inline">{{ roleMsgs[u.id] }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.empty{text-align:center; padding:60px 20px; border:1px dashed var(--line); border-radius:var(--r-lg)}
.empty-star{font-size:34px; color:var(--primary); margin-bottom:14px}
.empty p{color:var(--ink-dim); margin-bottom:20px}
.account-grid{display:grid; grid-template-columns:1fr 1fr; gap:18px; margin-bottom:18px}
.panel{border:1px solid var(--line); border-radius:var(--r-lg); background:var(--surface); padding:clamp(22px,3vw,32px)}
.panel h3{font-family:var(--font-mono); font-size:11px; letter-spacing:.3em; color:var(--ink-faint);
  text-transform:uppercase; margin-bottom:20px; display:flex; align-items:center; gap:10px}
.panel h3::before{content:''; width:7px; height:7px; border-radius:50%; background:var(--primary)}
.id-row{display:flex; gap:16px; align-items:center; margin-bottom:20px}
.id-name{font-family:var(--font-serif); font-size:19px}
.mono{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint); margin:4px 0 8px}
.role-badge{display:inline-block; font-size:10px; padding:3px 10px; border-radius:99px; letter-spacing:.08em}
.role-reader{background:var(--primary-soft); color:var(--primary)}
.role-author{background:rgba(255,180,84,.14); color:var(--amber)}
.role-admin{background:rgba(255,107,157,.14); color:var(--rose)}
.pp-rows{display:flex; flex-direction:column; gap:10px; font-size:13px}
.pp-rows div{display:flex; justify-content:space-between; border-bottom:1px dashed var(--line); padding-bottom:8px}
.pp-rows span{color:var(--ink-faint); font-family:var(--font-mono); font-size:11px; letter-spacing:.1em}
.pp-rows b{font-weight:500}
.msg{font-size:12px; margin-bottom:12px; line-height:1.6}
.msg.err{color:var(--rose)}
.msg.ok{color:var(--teal)}
.msg.ok.inline{margin:0 0 0 8px}
.row-between{display:flex; justify-content:space-between; align-items:center; gap:14px; flex-wrap:wrap}
.dim{color:var(--ink-dim); font-size:13px}
.member-list{display:flex; flex-direction:column}
.member-row{display:flex; justify-content:space-between; align-items:center; gap:14px;
  padding:14px 0; border-bottom:1px dashed var(--line); flex-wrap:wrap}
.member-row:last-child{border:none}
.member-id b{font-size:14px; font-weight:500; margin-right:10px}
.member-role{display:flex; align-items:center; gap:8px}
.member-role select{height:36px; padding:0 12px; border:1px solid var(--line); border-radius:var(--r-sm);
  background:var(--bg-2); color:var(--ink); font-family:var(--font-body); font-size:13px; outline:none}
.member-role select:focus{border-color:var(--primary)}
.hint{font-size:11px; color:var(--ink-faint)}
@media (max-width:820px){ .account-grid{grid-template-columns:1fr} }
</style>
