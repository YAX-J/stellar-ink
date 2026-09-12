import { defineStore } from 'pinia'
import { request } from '@/api/client'

function normalizeMeteor(item) {
  return {
    ...item,
    id: Number(item.id),
    t: item.content || '',
    d: item.createdAt ? item.createdAt.replace('T', ' ').slice(0, 16) : '',
  }
}

export const useMeteorStore = defineStore('meteors', {
  state: () => ({
    items: [],
    loading: false,
    error: '',
    initialized: false,
  }),
  actions: {
    async fetchItems() {
      this.loading = true
      this.error = ''
      try {
        const data = await request('/meteors', { query: { limit: 100 } })
        this.items = (data || []).map(normalizeMeteor)
        this.initialized = true
        return this.items
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.loading = false
      }
    },

    async ensureLoaded() {
      if (this.initialized) return this.items
      return this.fetchItems()
    },

    async launch(text) {
      this.error = ''
      try {
        await request('/meteors', {
          method: 'POST',
          body: { content: text.trim() },
        })
        await this.fetchItems()
      } catch (error) {
        this.error = error.message
        throw error
      }
    },
  },
})
