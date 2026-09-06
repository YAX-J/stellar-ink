import { defineStore } from 'pinia'

const KEY = 'stellar-ink-settings'

function load() {
  try {
    return JSON.parse(localStorage.getItem(KEY)) || {}
  } catch {
    return {}
  }
}

export const useSettingsStore = defineStore('settings', {
  state: () => {
    const saved = load()
    return {
      theme: saved.theme || 'night',
      dailyGoal: saved.dailyGoal || 500,
      penName: saved.penName || '拾星人',
      signature: saved.signature || '在算法的洪流里，做一个缓慢的人。',
      /* 深读页返回目标：记住最后一个非 read 页面 */
      lastPageName: 'home',
      lastPage: '/',
    }
  },
  actions: {
    persist() {
      const { theme, dailyGoal, penName, signature } = this
      localStorage.setItem(KEY, JSON.stringify({ theme, dailyGoal, penName, signature }))
    },
    setTheme(theme) {
      this.theme = theme
      this.persist()
    },
    rememberPage(name, path) {
      this.lastPageName = name
      this.lastPage = path
    },
  },
})
