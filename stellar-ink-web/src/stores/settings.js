import { defineStore } from 'pinia'

const KEY = 'stellar-ink-settings'
const READ_POS_KEY = 'stellar-ink-read-positions'

/** 阅读偏好的默认值与合法范围：改动这里即可扩展 */
export const READ_DEFAULTS = { fontSize: 17, lineHeight: 2.3, width: 46 }
export const READ_LIMITS = {
  fontSize: { min: 14, max: 22, step: 1 },
  lineHeight: { min: 1.7, max: 3, step: 0.1 },
  width: { min: 34, max: 64, step: 2 },
}

function load() {
  try {
    return JSON.parse(localStorage.getItem(KEY)) || {}
  } catch {
    return {}
  }
}

function loadPositions() {
  try {
    return JSON.parse(sessionStorage.getItem(READ_POS_KEY)) || {}
  } catch {
    return {}
  }
}

function clamp(value, { min, max }, fallback) {
  const n = Number(value)
  if (!Number.isFinite(n)) return fallback
  return Math.min(max, Math.max(min, n))
}

export const useSettingsStore = defineStore('settings', {
  state: () => {
    const saved = load()
    const read = saved.read || {}
    return {
      theme: saved.theme || 'night',
      dailyGoal: saved.dailyGoal || 500,
      penName: saved.penName || '拾星人',
      signature: saved.signature || '在算法的洪流里，做一个缓慢的人。',
      /* 深读页阅读偏好（字号 / 行高 / 正文宽度，宽度单位 ch） */
      read: {
        fontSize: clamp(read.fontSize, READ_LIMITS.fontSize, READ_DEFAULTS.fontSize),
        lineHeight: clamp(read.lineHeight, READ_LIMITS.lineHeight, READ_DEFAULTS.lineHeight),
        width: clamp(read.width, READ_LIMITS.width, READ_DEFAULTS.width),
      },
      /* 深读页返回目标：记住最后一个非 read 页面 */
      lastPageName: 'home',
      lastPage: '/',
    }
  },
  actions: {
    persist() {
      const { theme, dailyGoal, penName, signature, read } = this
      localStorage.setItem(KEY, JSON.stringify({ theme, dailyGoal, penName, signature, read }))
    },
    setTheme(theme) {
      this.theme = theme
      this.persist()
    },
    rememberPage(name, path) {
      this.lastPageName = name
      this.lastPage = path
    },
    /* ---- 阅读偏好 ---- */
    setRead(patch) {
      this.read = {
        fontSize: clamp(patch.fontSize ?? this.read.fontSize, READ_LIMITS.fontSize, READ_DEFAULTS.fontSize),
        lineHeight: clamp(patch.lineHeight ?? this.read.lineHeight, READ_LIMITS.lineHeight, READ_DEFAULTS.lineHeight),
        width: clamp(patch.width ?? this.read.width, READ_LIMITS.width, READ_DEFAULTS.width),
      }
      this.persist()
    },
    resetRead() {
      this.read = { ...READ_DEFAULTS }
      this.persist()
    },
    /* ---- 阅读位置（会话级，不跨设备） ---- */
    rememberPosition(postId, ratio) {
      const id = Number(postId)
      if (!id) return
      const all = loadPositions()
      all[id] = Math.min(1, Math.max(0, Number(ratio) || 0))
      try {
        sessionStorage.setItem(READ_POS_KEY, JSON.stringify(all))
      } catch {
        /* 存储不可用时静默降级 */
      }
    },
    positionOf(postId) {
      return loadPositions()[Number(postId)] || 0
    },
  },
})
