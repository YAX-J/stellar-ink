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
    page: 0,
    pageSize: 24,
    total: 0,
    hasMore: true,
  }),
  actions: {
    async fetchItems({ append = false } = {}) {
      if (append && (this.loading || !this.hasMore)) return this.items
      this.loading = true
      this.error = ''
      try {
        const pageNumber = append ? this.page + 1 : 1
        const data = await request('/meteors', { query: { page: pageNumber, size: this.pageSize } })
        const records = data?.records || data?.list || []
        const normalized = records.map(normalizeMeteor)
        if (append) {
          const known = new Set(this.items.map((item) => item.id))
          this.items.push(...normalized.filter((item) => !known.has(item.id)))
        } else {
          this.items = normalized
        }
        this.page = Number(data?.current ?? pageNumber)
        this.total = Number(data?.total ?? this.items.length)
        this.hasMore = this.items.length < this.total && records.length > 0
        await useAuthorStore().ensureAuthors(normalized.map((item) => item.userId)).catch(() => {})
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

    async loadMore() {
      return this.fetchItems({ append: true })
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
        this.total = Math.max(0, this.total - 1)
      } catch (error) {
        this.error = error.message
        throw error
      }
    },
  },
})
