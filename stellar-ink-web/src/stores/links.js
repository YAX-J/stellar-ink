import { defineStore } from 'pinia'
import { request } from '@/api/client'

const positions = [
  [.16, .3], [.38, .62], [.56, .22], [.74, .5], [.3, .82], [.85, .78],
]

function normalizeLink(item, index) {
  const position = positions[index % positions.length]
  return {
    ...item,
    id: Number(item.id),
    n: item.name || '',
    u: item.url || '',
    d: item.description || '新来的邻居，信号确认中…',
    x: item.x ?? position[0],
    y: item.y ?? position[1],
  }
}

export const useLinkStore = defineStore('links', {
  state: () => ({
    friends: [],
    edges: [[0, 1], [1, 4], [0, 2], [2, 3], [3, 5], [1, 2], [4, 5]],
    loading: false,
    error: '',
    initialized: false,
    pending: [],
    loadingPending: false,
    pendingError: '',
  }),
  actions: {
    async fetchFriends() {
      this.loading = true
      this.error = ''
      try {
        const data = await request('/links')
        this.friends = (data || []).map(normalizeLink)
        this.initialized = true
        return this.friends
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.loading = false
      }
    },

    async ensureLoaded() {
      if (this.initialized) return this.friends
      return this.fetchFriends()
    },

    async apply(name, url) {
      this.error = ''
      try {
        await request('/links', {
          method: 'POST',
          body: { name: name.trim(), url: url.trim() },
        })
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    async fetchPending() {
      this.loadingPending = true
      this.pendingError = ''
      try {
        const data = await request('/links/pending', { silent: true })
        this.pending = (data || []).map(normalizeLink)
        return this.pending
      } catch (error) {
        this.pendingError = error.message
        throw error
      } finally {
        this.loadingPending = false
      }
    },

    async review(id, approved) {
      this.pendingError = ''
      try {
        const reviewed = this.pending.find((item) => Number(item.id) === Number(id))
        await request(`/links/${id}/status?status=${approved ? 1 : 2}`, {
          method: 'PUT',
          silent: true,
        })
        this.pending = this.pending.filter((item) => Number(item.id) !== Number(id))
        if (approved && reviewed && this.initialized) {
          this.friends.push(normalizeLink({ ...reviewed, status: 1 }, this.friends.length))
        }
      } catch (error) {
        this.pendingError = error.message
        throw error
      }
    },
  },
})
