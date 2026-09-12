import { defineStore } from 'pinia'
import { request } from '@/api/client'

const UNKNOWN_AUTHOR = Object.freeze({ nickname: '未知星客', avatarText: '星' })

export const useAuthorStore = defineStore('authors', {
  state: () => ({
    byId: {},
    loadedIds: [],
  }),
  getters: {
    find: (state) => (id) => state.byId[Number(id)] || UNKNOWN_AUTHOR,
  },
  actions: {
    upsertAuthor(author) {
      const id = Number(author?.id)
      if (!id) return
      this.byId[id] = {
        id,
        nickname: author.nickname || '未知星客',
        avatarText: author.avatarText || '',
      }
      if (!this.loadedIds.includes(id)) this.loadedIds.push(id)
    },

    async ensureAuthors(ids) {
      const requested = [...new Set((ids || []).map(Number).filter((id) => id > 0))]
      const loaded = new Set(this.loadedIds)
      const missing = requested.filter((id) => !loaded.has(id))
      if (!missing.length) return

      const authors = await request('/user/authors', { query: { ids: missing.join(',') } })
      const foundIds = new Set()
      for (const author of authors || []) {
        this.upsertAuthor(author)
        foundIds.add(Number(author.id))
      }
      this.loadedIds.push(...missing.filter((id) => !foundIds.has(id)))
    },
  },
})
