<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { roleLabel, ROLE_LABEL } from '@/utils/role'
import { emit, TOAST } from '@/utils/bus'
import SectionHead from '@/components/common/SectionHead.vue'
import UserAvatar from '@/components/common/UserAvatar.vue'

const router = useRouter()
const auth = useAuthStore()

const user = computed(() => auth.user)
const roleClass = computed(() => `role-${(user.value && user.value.role) || 'READER'}`.toLowerCase())
const createdAt = computed(() => {
  const t = user.value && user.value.createdAt
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
})

/* ---- 头像：上传图片 / 恢复底字 ---- */
const AVATAR_MAX_BYTES = 1024 * 1024
const AVATAR_MAX_EDGE = 512
const AVATAR_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const fileInput = ref(null)
const avatarLoading = ref(false)
const avatarMsg = ref('')
const avatarError = ref('')
const avatarText = ref('')
const avatarTextLoading = ref(false)
const avatarTextMsg = ref('')

/** 从 FileReader 读成 dataURL，交给 <img> 解码（避免依赖 createImageBitmap 的浏览器差异） */
function readImage(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(new Error('读取图片失败'))
    reader.readAsDataURL(file)
  })
}

/** 上传前压到最长边 512 的 JPEG：手机原图动辄 3-8MB，直传必然撞上限被拒 */
function compressImage(dataUrl, maxEdge) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => {
      const scale = Math.min(1, maxEdge / Math.max(img.width, img.height))
      const width = Math.max(1, Math.round(img.width * scale))
      const height = Math.max(1, Math.round(img.height * scale))
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')
      /* 透明 PNG 转 JPEG 会变黑底，先铺一层深色底（站点是夜间基调） */
      ctx.fillStyle = getComputedStyle(document.body).backgroundColor || '#0b0b12'
      ctx.fillRect(0, 0, width, height)
      ctx.drawImage(img, 0, 0, width, height)
      resolve({ dataUrl: canvas.toDataURL('image/jpeg', 0.9), width, height })
    }
    img.onerror = () => reject(new Error('这个文件不是有效的图片'))
    img.src = dataUrl
  })
}

function pickAvatar() {
  avatarError.value = ''
  avatarMsg.value = ''
  if (fileInput.value) fileInput.value.click()
}

async function onAvatarPicked(event) {
  const file = event.target.files && event.target.files[0]
  /* 同一个文件连续选两次也要触发 change，所以先清空 input */
  event.target.value = ''
  if (!file) return
  avatarError.value = ''
  avatarMsg.value = ''
  if (!AVATAR_TYPES.includes(file.type)) {
    avatarError.value = '只支持 JPG / PNG / WebP 图片。'
    return
  }
  avatarLoading.value = true
  try {
    const raw = await readImage(file)
    const { dataUrl, width, height } = await compressImage(raw, AVATAR_MAX_EDGE)
    const blob = await (await fetch(dataUrl)).blob()
    if (blob.size > AVATAR_MAX_BYTES) {
      avatarError.value = '图片压缩后仍超过 1MB，换一张尺寸小一些的试试。'
      return
    }
    const optimized = new File([blob], 'avatar.jpg', { type: 'image/jpeg' })
    await auth.uploadAvatar(optimized)
    avatarMsg.value = `头像已更新（${width}×${height}，${Math.round(blob.size / 1024)}KB）。`
    emit(TOAST, { type: 'success', message: '头像已更新' })
  } catch (e) {
    avatarError.value = e.message || '头像上传失败，请稍后再试'
  } finally {
    avatarLoading.value = false
  }
}

async function removeAvatar() {
  avatarError.value = ''
  avatarMsg.value = ''
  avatarLoading.value = true
  try {
    await auth.deleteAvatar()
    avatarMsg.value = '已恢复为底字头像。'
  } catch (e) {
    avatarError.value = e.message || '操作失败，请稍后再试'
  } finally {
    avatarLoading.value = false
  }
}

/** 底字只有一个字：输入框里直接截断，避免用户敲了三个字提交后「只剩一个」的困惑 */
function onAvatarTextInput(event) {
  avatarText.value = [...(event.target.value || '').trim()].slice(0, 1).join('')
}

async function saveAvatarText() {
  avatarTextMsg.value = ''
  const value = avatarText.value.trim()
  if (!value) {
    avatarTextMsg.value = '请先填一个字符。'
    return
  }
  avatarTextLoading.value = true
  try {
    await auth.updateProfile({ avatarText: value })
    avatarTextMsg.value = '底字已保存。'
  } catch (e) {
    avatarTextMsg.value = e.message || '保存失败'
  } finally {
    avatarTextLoading.value = false
  }
}

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

/* 当前角色能做什么：读者与作者之间缺的是站长授予，这里给出可操作的入口 */
const canWrite = computed(() => auth.isAuthorOrAbove)
/** 是否已提交待审的作者申请 */
const pendingApply = computed(() => !!(user.value && user.value.roleAppliedAt))

/* ---- 读者申请成为作者 ---- */
const applyNote = ref('')
const applying = ref(false)
const applyMsg = ref('')
const showApplyForm = ref(false)

/** 相对时间：申请时间是给站长判断先来后到用的，「几天前」比精确时间更有用 */
function daysAgo(value) {
  if (!value) return ''
  const days = Math.floor((Date.now() - new Date(value).getTime()) / 86400000)
  if (days <= 0) return '今天'
  if (days === 1) return '昨天'
  return `${days} 天前`
}

async function submitApply() {
  if (applying.value) return
  applying.value = true
  applyMsg.value = ''
  try {
    await auth.applyRole(applyNote.value)
    applyNote.value = ''
    showApplyForm.value = false
    emit(TOAST, { type: 'success', message: '申请已送达，等站长审核' })
  } catch (e) {
    applyMsg.value = e.message || '提交失败，请稍后再试'
  } finally {
    applying.value = false
  }
}

async function withdrawApply() {
  if (applying.value) return
  applying.value = true
  applyMsg.value = ''
  try {
    await auth.cancelRoleApply()
    emit(TOAST, { type: 'info', message: '已撤回申请' })
  } catch (e) {
    applyMsg.value = e.message || '撤回失败，请稍后再试'
  } finally {
    applying.value = false
  }
}

/* ---- 成员管理（ADMIN） ---- */
const users = ref([])
const listLoading = ref(false)
const listError = ref('')
const roleMsgs = ref({})
/** 待审作者申请：站长一进页面就能看到有人在等 */
const pendingApplies = computed(() => users.value.filter((u) => !!u.roleAppliedAt))

/** 审批：通过与驳回都复用既有的改角色接口，后端会一并清空待审状态 */
async function reviewApply(u, approved) {
  roleMsgs.value[u.id] = ''
  try {
    await auth.changeRole(u.id, approved ? 'AUTHOR' : 'READER')
    const item = users.value.find((x) => x.id === u.id)
    if (item) {
      item.role = approved ? 'AUTHOR' : 'READER'
      item.roleAppliedAt = null
      item.roleApplyNote = null
    }
    emit(TOAST, {
      type: 'success',
      message: approved
        ? `已通过：${u.nickname || u.username} 成为作者（需重新登录后生效）`
        : `已驳回：${u.nickname || u.username} 的申请`,
    })
  } catch (e) {
    roleMsgs.value[u.id] = e.message || '操作失败'
  }
}

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
    // 回填列表里的最新角色；后端在改角色时同时清空了待审申请，本地也要跟着清
    const item = users.value.find((x) => x.id === u.id)
    if (item) {
      item.role = role
      item.roleAppliedAt = null
      item.roleApplyNote = null
    }
  } catch (e) {
    roleMsgs.value[u.id] = e.message || '更新失败'
    // 回退到原角色
    const item = users.value.find((x) => x.id === u.id)
    if (item) item.role = u.role
  }
}

/* 局部错误只落到面板里；会话失效由 client.js → bus → main.js 统一处理并跳登录 */
function handleError(e, target) {
  target.value = e.message || '操作失败'
}

onMounted(async () => {
  if (!auth.isLoggedIn) return
  try {
    await auth.fetchProfile()
  } catch {
    /* 会话失效已由全局兜底处理 */
    return
  }
  /* 底字输入框在拿到真实资料后才填值（localStorage 里的旧缓存可能没有该字段） */
  avatarText.value = auth.user?.avatarText || ''
  if (auth.isAdmin) await loadUsers()
})
</script>

<template>
  <section class="page">
    <div class="kicker reveal">ACCOUNT · 账号与星籍</div>
    <SectionHead title="账号" more="你的身份与权限" />

    <!-- 未登录：也覆盖「有 token 但没有用户资料」的残缺会话（localStorage 被清一半），
         否则下面的模板会读 user.nickname 直接抛异常、整页白屏 -->
    <div v-if="!auth.isLoggedIn || !user" class="empty reveal">
      <div class="empty-star">✦</div>
      <p>{{ auth.isLoggedIn ? '登录信息不完整，请重新登录。' : '还没有登录，无法查看账号。' }}</p>
      <RouterLink class="btn btn-primary" to="/login">去登录</RouterLink>
    </div>

    <template v-else>
      <div class="account-grid">
        <!-- 我的星籍 -->
        <div class="panel reveal" style="--d:.06s">
          <h3>我的星籍</h3>
          <div class="id-row">
            <UserAvatar
              :url="user.avatarUrl" :text="user.avatarText"
              :nickname="user.nickname || user.username" :size="64" shape="square"
            />
            <div>
              <b class="id-name">{{ user.nickname || user.username }}</b>
              <div class="mono">@{{ user.username }}</div>
              <span class="role-badge" :class="roleClass">{{ roleLabel(user.role) }}</span>
            </div>
          </div>

          <!-- 头像：可上传图片，也可只用底字（未上传时所有头像位都显示底字） -->
          <div class="avatar-edit">
            <div class="avatar-actions">
              <button class="apply-btn" :disabled="avatarLoading" @click="pickAvatar">
                {{ avatarLoading ? '处理中…' : (user.avatarUrl ? '更换头像' : '上传头像') }}
              </button>
              <button
                v-if="user.avatarUrl" class="apply-btn ghost"
                :disabled="avatarLoading" @click="removeAvatar"
              >恢复底字</button>
              <input
                ref="fileInput" class="avatar-file" type="file"
                accept="image/jpeg,image/png,image/webp" @change="onAvatarPicked"
              />
            </div>
            <p class="avatar-hint">支持 JPG / PNG / WebP，会自动压到 512px、1MB 以内。</p>
            <div class="avatar-text-row">
              <input
                :value="avatarText" class="avatar-text-input" maxlength="1"
                placeholder="星" @input="onAvatarTextInput"
              />
              <button class="apply-btn ghost" :disabled="avatarTextLoading" @click="saveAvatarText">
                {{ avatarTextLoading ? '保存中…' : '保存底字' }}
              </button>
              <span class="avatar-hint">底字：未上传图片时显示这一个字。</span>
            </div>
            <p v-if="avatarError" class="apply-err">{{ avatarError }}</p>
            <p v-if="avatarMsg" class="avatar-ok">{{ avatarMsg }}</p>
            <p v-if="avatarTextMsg" class="avatar-ok">{{ avatarTextMsg }}</p>
          </div>
          <div class="pp-rows">
            <div><span>星籍编号</span><b>NO.{{ user.id }}</b></div>
            <div><span>注册星历</span><b>{{ createdAt }}</b></div>
          </div>

          <!-- 权限与作者申请：读者→作者需要站长授予，这里给出可操作的入口而不是让人自己猜 -->
          <div class="perm-note">
            <template v-if="canWrite">
              <b>✦ 你已是{{ roleLabel(user.role) }}</b>
              <span>可以写文章、记录技术笔记、发射流星；进入「执笔」开始今天的星尘。</span>
            </template>

            <!-- 三态之一：审核中 -->
            <template v-else-if="pendingApply">
              <b>✎ 作者申请审核中</b>
              <span>提交于 {{ daysAgo(user.roleAppliedAt) }}，等站长处理。</span>
              <span v-if="user.roleApplyNote" class="apply-quote">“{{ user.roleApplyNote }}”</span>
              <span class="apply-tip">审核通过后需要重新登录才会生效。</span>
              <button class="apply-btn ghost" :disabled="applying" @click="withdrawApply">
                {{ applying ? '处理中…' : '撤回申请' }}
              </button>
            </template>

            <!-- 三态之二：可申请 -->
            <template v-else>
              <b>✦ 当前是读者</b>
              <span>阅读、补充光芒、投瓶与申请友链都已可用；想发布文章需要作者权限。</span>
              <button v-if="!showApplyForm" class="apply-btn" @click="showApplyForm = true">
                ✎ 申请成为作者
              </button>
              <template v-else>
                <textarea
                  v-model="applyNote" class="apply-input" rows="2" maxlength="200"
                  placeholder="想写什么？一句话说明就好（选填，站长审核时会看）"
                ></textarea>
                <div class="apply-actions">
                  <button class="apply-btn" :disabled="applying" @click="submitApply">
                    {{ applying ? '提交中…' : '提交申请' }}
                  </button>
                  <button class="apply-btn ghost" :disabled="applying" @click="showApplyForm = false">取消</button>
                </div>
              </template>
            </template>

            <p v-if="applyMsg" class="apply-err">{{ applyMsg }}</p>
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
        <h3>
          成员管理 · 站长
          <span v-if="pendingApplies.length" class="pending-badge">{{ pendingApplies.length }} 条待审</span>
        </h3>
        <p v-if="listError" class="msg err">{{ listError }}</p>
        <p v-if="listLoading" class="dim">正在读取成员名单…</p>
        <div v-else class="member-list">
          <div
            v-for="u in users" :key="u.id" class="member-row"
            :class="{ pending: !!u.roleAppliedAt }"
          >
            <div class="member-id">
              <b>{{ u.nickname || u.username }}</b>
              <span class="mono">@{{ u.username }}</span>
              <!-- 作者申请：站长在这里一眼看到谁在等、等了多久、为什么 -->
              <div v-if="u.roleAppliedAt" class="apply-row">
                <span class="apply-time">✎ 申请成为作者 · {{ daysAgo(u.roleAppliedAt) }}</span>
                <span v-if="u.roleApplyNote" class="apply-note">“{{ u.roleApplyNote }}”</span>
                <div class="apply-buttons">
                  <button class="review-btn ok" @click="reviewApply(u, true)">通过</button>
                  <button class="review-btn no" @click="reviewApply(u, false)">驳回</button>
                </div>
              </div>
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
        <p v-if="!listLoading && pendingApplies.length" class="apply-foot">
          通过后该用户需要重新登录，新角色才会生效（JWT 里带的角色是登录时签发的）。
        </p>
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
.perm-note{margin-top:18px; padding:14px 16px; border:1px dashed var(--line); border-radius:var(--r-sm);
  background:var(--bg-3); display:flex; flex-direction:column; gap:6px; align-items:flex-start}
.perm-note b{font-size:12px; color:var(--primary); letter-spacing:.06em}
.perm-note span{font-size:12px; line-height:1.9; color:var(--ink-dim)}
.apply-quote{font-family:var(--font-serif) !important; color:var(--ink) !important; font-size:13px !important;
  border-left:2px solid var(--amber); padding-left:10px}
.apply-tip{color:var(--ink-faint) !important; font-size:11px !important}
.apply-btn{margin-top:4px; border:1px solid var(--primary); background:var(--primary-soft);
  color:var(--primary); border-radius:99px; padding:7px 18px; font-size:12px; cursor:pointer;
  font-family:var(--font-body); transition:all .25s var(--ease-spring)}
.apply-btn:hover{transform:translateY(-2px)}
.apply-btn:disabled{opacity:.6; cursor:wait; transform:none}
.apply-btn.ghost{background:transparent; border-color:var(--line); color:var(--ink-faint)}
.apply-btn.ghost:hover{border-color:var(--ink-dim); color:var(--ink-dim)}
.apply-input{width:100%; border:1px solid var(--line); border-radius:var(--r-sm); background:var(--bg-2);
  color:var(--ink); padding:10px 12px; font-size:13px; line-height:1.8; outline:none; resize:vertical;
  font-family:var(--font-body); margin-top:4px; transition:border-color .2s}
.apply-input:focus{border-color:var(--primary)}
.apply-actions{display:flex; gap:8px}
.apply-err{font-size:12px; color:var(--rose); line-height:1.6}

/* 头像编辑区 */
.avatar-edit{margin:-6px 0 20px; padding:14px 16px; border:1px dashed var(--line);
  border-radius:var(--r-sm); background:var(--bg-3); display:flex; flex-direction:column; gap:10px}
.avatar-actions{display:flex; gap:8px; flex-wrap:wrap}
/* 原生 file input 无法用主题变量美化，统一隐藏、由按钮触发 */
.avatar-file{display:none}
.avatar-hint{font-size:11px; color:var(--ink-faint); line-height:1.7}
.avatar-text-row{display:flex; align-items:center; gap:8px; flex-wrap:wrap}
.avatar-text-input{width:52px; height:34px; text-align:center; border:1px solid var(--line);
  border-radius:var(--r-sm); background:var(--bg-2); color:var(--ink);
  font-family:var(--font-serif); font-size:16px; outline:none; transition:border-color .2s}
.avatar-text-input:focus{border-color:var(--primary)}
.avatar-ok{font-size:12px; color:var(--teal); line-height:1.6}

/* 审核队列 */
.pending-badge{margin-left:8px; font-family:var(--font-mono); font-size:10px; letter-spacing:.1em;
  color:var(--amber); border:1px solid var(--amber); border-radius:99px; padding:2px 9px}
.member-row.pending{border-left:2px solid var(--amber); padding-left:14px}
.apply-row{display:flex; flex-direction:column; gap:6px; margin-top:8px}
.apply-time{font-family:var(--font-mono); font-size:10px; letter-spacing:.06em; color:var(--amber)}
.apply-note{font-size:12px; line-height:1.8; color:var(--ink-dim); max-width:46ch}
.apply-buttons{display:flex; gap:8px}
.review-btn{border-radius:99px; padding:5px 16px; font-size:12px; cursor:pointer;
  font-family:var(--font-body); transition:all .25s; border:1px solid var(--line); background:transparent}
.review-btn.ok{color:var(--teal); border-color:var(--teal)}
.review-btn.ok:hover{background:var(--surface-2)}
.review-btn.no{color:var(--ink-faint)}
.review-btn.no:hover{color:var(--rose); border-color:var(--rose)}
.apply-foot{margin-top:14px; font-size:11px; line-height:1.8; color:var(--ink-faint)}
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
