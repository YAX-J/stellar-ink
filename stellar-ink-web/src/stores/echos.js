import { defineStore } from 'pinia'
import { request } from '@/api/client'

const layout = () => ({
  left: 3 + Math.random() * 66,
  top: 5 + Math.random() * 68,
  rot: (Math.random() * 8 - 4).toFixed(1),
  dur: (6 + Math.random() * 4).toFixed(1),
})

function normalizeEcho(item) {
  return {
    ...item,
    id: Number(item.id),
    n: item.nickname || '匿名旅人',
    m: item.content || '',
    ...layout(),
  }
}

export const useEchoStore = defineStore('echos', {
  state: () => ({
    bottles: [],
    loading: false,
    error: '',
    initialized: false,
  }),
  actions: {
    async fetchBottles() {
      this.loading = true
      this.error = ''
      try {
        const data = await request('/echos')
        this.bottles = (data || []).map(normalizeEcho)
        this.initialized = true
        return this.bottles
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.loading = false
      }
    },

    async ensureLoaded() {
      if (this.initialized) return this.bottles
      return this.fetchBottles()
    },

    async throw(name, msg) {
      this.error = ''
      try {
        await request('/echos', {
          method: 'POST',
          body: { nickname: name?.trim() || '匿名旅人', content: msg.trim() },
        })
        await this.fetchBottles()
      } catch (error) {
        this.error = error.message
        throw error
      }
    },
  },
})
