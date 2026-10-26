import { defineStore } from 'pinia'
import { request } from '@/api/client'

/* 系列 / 专栏：只负责取数与新建。
 * 管理（改名、删系列）在系列详情页做；篇目挂载不在这里 —— 它属于文章/笔记的保存（seriesId）。 */

function normalizeSeries(raw) {
  if (!raw) return null
  return {
    ...raw,
    id: Number(raw.id),
    userId: Number(raw.userId),
    description: raw.description || '',
    postCount: Number(raw.postCount ?? 0),
    noteCount: Number(raw.noteCount ?? 0),
    itemCount: Number(raw.itemCount ?? 0),
  }
}

function normalizeItem(raw) {
  return {
    ...raw,
    id: Number(raw.id),
    kind: raw.kind === 'note' ? 'note' : 'post',
    order: raw.order == null ? null : Number(raw.order),
    wordCount: Number(raw.wordCount ?? 0),
  }
}

export const useSeriesStore = defineStore('series', {
  state: () => ({
    list: [],
    loading: false,
    error: '',
    initialized: false,
    details: {},
  }),
  getters: {
    /** 只列有公开篇目的系列：空系列放进列表只会让人点进去看到空白 */
    visibleList: (state) => state.list.filter((item) => item.itemCount > 0),
    byId: (state) => (id) => state.details[Number(id)] || null,
  },
  actions: {
    async fetchList({ force = false } = {}) {
      if (this.initialized && !force) return this.list
      this.loading = true
      this.error = ''
      try {
        const page = await request('/series', { query: { page: 1, size: 50 } })
        const records = page?.records || page?.list || []
        this.list = records.map(normalizeSeries).filter(Boolean)
        this.initialized = true
        return this.list
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.loading = false
      }
    },

    async fetchDetail(id) {
      const seriesId = Number(id)
      this.error = ''
      try {
        const raw = await request(`/series/${seriesId}`)
        const detail = normalizeSeries(raw)
        if (!detail) return null
        detail.items = (raw.items || []).map(normalizeItem)
        detail.total = Number(raw.total ?? detail.items.length)
        this.details[seriesId] = detail
        return detail
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    async create({ title, description }) {
      const data = await request('/series', {
        method: 'POST',
        body: { title: title?.trim(), description: description?.trim() || '' },
      })
      this.initialized = false
      await this.fetchList({ force: true }).catch(() => {})
      return Number(data?.id)
    },

    async rename(id, { title, description }) {
      await request(`/series/${Number(id)}`, {
        method: 'PUT',
        body: { title: title?.trim(), description: description?.trim() ?? '' },
      })
      this.initialized = false
      await this.fetchList({ force: true }).catch(() => {})
      await this.fetchDetail(id).catch(() => {})
    },

    async remove(id) {
      await request(`/series/${Number(id)}`, { method: 'DELETE' })
      const seriesId = Number(id)
      delete this.details[seriesId]
      this.list = this.list.filter((item) => item.id !== seriesId)
    },
  },
})
