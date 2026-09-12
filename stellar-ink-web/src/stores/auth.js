import { defineStore } from 'pinia'
import { request, getToken, setToken, ApiError } from '@/api/client'
import { roleAtLeast } from '@/utils/role'
import { useAuthorStore } from '@/stores/authors'

const USER_KEY = 'stellar-ink-user'
const GUEST_KEY = 'stellar-ink-guest'

function loadUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY)) || null
  } catch {
    return null
  }
}

function saveUser(user) {
  if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
  else localStorage.removeItem(USER_KEY)
}

function saveGuestMode(enabled) {
  if (enabled) sessionStorage.setItem(GUEST_KEY, 'true')
  else sessionStorage.removeItem(GUEST_KEY)
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getToken() || '',
    user: loadUser(),
    guestMode: !getToken() && sessionStorage.getItem(GUEST_KEY) === 'true',
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isGuest: (s) => !s.token && s.guestMode,
    role: (s) => (s.user && s.user.role) || 'READER',
    isAdmin: (s) => s.user && s.user.role === 'ADMIN',
    isAuthorOrAbove: (s) => s.user && roleAtLeast(s.user.role, 'AUTHOR'),
  },
  actions: {
    /* 登录/注册成功后写入 token 与用户信息 */
    _applySession(data) {
      this.token = data.tokenValue
      this.user = data.user
      this.guestMode = false
      setToken(data.tokenValue)
      saveUser(data.user)
      saveGuestMode(false)
      useAuthorStore().upsertAuthor(data.user)
      return data.user
    },

    enterAsGuest() {
      if (this.token) return
      this.guestMode = true
      saveGuestMode(true)
    },

    async login({ username, password }) {
      const data = await request('/auth/login', { method: 'POST', body: { username, password } })
      return this._applySession(data)
    },

    async register({ username, password, nickname }) {
      const data = await request('/auth/register', { method: 'POST', body: { username, password, nickname } })
      return this._applySession(data)
    },

    async logout() {
      try {
        await request('/auth/logout', { method: 'POST' })
      } catch {
        /* 登出是语义收口，网络失败也照常清本地会话 */
      } finally {
        this.token = ''
        this.user = null
        this.guestMode = false
        setToken('')
        saveUser(null)
        saveGuestMode(false)
      }
    },

    async fetchProfile() {
      this.user = await request('/user/profile')
      saveUser(this.user)
      return this.user
    },

    async updateProfile(patch) {
      this.user = await request('/user/profile', { method: 'PUT', body: patch })
      saveUser(this.user)
      useAuthorStore().upsertAuthor(this.user)
      return this.user
    },

    async changePassword({ oldPassword, newPassword }) {
      await request('/user/password', { method: 'PUT', body: { oldPassword, newPassword } })
    },

    /* 以下为 ADMIN 专属：用户列表 / 改角色 */
    async listUsers() {
      return request('/user/list')
    },

    async changeRole(id, role) {
      const updated = await request(`/user/${id}/role`, { method: 'PUT', body: { role } })
      // 若改的是自己，同步本地缓存的角色
      if (this.user && Number(this.user.id) === Number(id)) {
        this.user = updated
        saveUser(updated)
      }
      return updated
    },

    /** 会话失效（token 过期/被拒）时清空本地态，供 401 兜底调用 */
    clearSession() {
      this.token = ''
      this.user = null
      this.guestMode = false
      setToken('')
      saveUser(null)
      saveGuestMode(false)
    },
  },
})

/** 导出便于在组件里判断是否为「会话失效」（网关 HTTP 401，区别于业务码 401 如「原密码不正确」） */
export function isAuthError(e) {
  return e instanceof ApiError && e.status === 401
}
