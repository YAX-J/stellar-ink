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
        await this.fetchFriends()
      } catch (error) {
        this.error = error.message
        throw error
      }
    },
  },
})
