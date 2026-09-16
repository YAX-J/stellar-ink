import { defineStore } from 'pinia'
import { request } from '@/api/client'

const UNKNOWN_AUTHOR = Object.freeze({ nickname: '未知星客', avatarText: '星', avatarUrl: '' })

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
        avatarUrl: author.avatarUrl || '',
      }
      if (!this.loadedIds.includes(id)) this.loadedIds.push(id)
    },

    async ensureAuthors(ids) {
      const requested = [...new Set((ids || []).map(Number).filter((id) => id > 0))]
      const loaded = new Set(this.loadedIds)
      const missing = requested.filter((id) => !loaded.has(id))
      if (!missing.length) return

      for (let start = 0; start < missing.length; start += 100) {
        const batch = missing.slice(start, start + 100)
        const authors = await request('/user/authors', { query: { ids: batch.join(',') } })
        const foundIds = new Set()
        for (const author of authors || []) {
          this.upsertAuthor(author)
          foundIds.add(Number(author.id))
        }
        this.loadedIds.push(...batch.filter((id) => !foundIds.has(id)))
      }
    },

    /** 作者自己换了头像/笔名后，让缓存里的这条摘要失效并立刻重取。
     * 否则「我的头像变了，但文章署名还是旧图」——缓存命中时 ensureAuthors 根本不会发请求。 */
    async refreshAuthor(id) {
      const numId = Number(id)
      if (!numId) return
      this.loadedIds = this.loadedIds.filter((item) => Number(item) !== numId)
      delete this.byId[numId]
      await this.ensureAuthors([numId])
    },
  },
})
