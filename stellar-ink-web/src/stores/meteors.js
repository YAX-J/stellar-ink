import { defineStore } from 'pinia'
import { request } from '@/api/client'
import { useAuthorStore } from '@/stores/authors'

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
        await useAuthorStore().ensureAuthors(this.items.map((item) => item.userId)).catch(() => {})
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

    async remove(id) {
      this.error = ''
      try {
        await request(`/meteors/${Number(id)}`, { method: 'DELETE' })
        this.items = this.items.filter((item) => item.id !== Number(id))
      } catch (error) {
        this.error = error.message
        throw error
      }
    },
  },
})
