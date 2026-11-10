import { defineStore } from 'pinia'
import { request } from '@/api/client'
import { normalizePost } from '@/stores/posts'
import { normalizeNote } from '@/stores/notes'
import { useAuthorStore } from '@/stores/authors'

const PAGE_SIZE = 12
const controllers = new WeakMap()

function pageData(page, normalize, fallbackPage) {
  const records = page?.records ?? page?.list
  if (!Array.isArray(records)) throw new Error('搜索结果格式不完整，请重试')
  const items = records.map((item) => normalize(item)).filter(Boolean)
  const current = Number(page?.current ?? fallbackPage)
  const total = Number(page?.total ?? items.length)
  if (!Number.isInteger(current) || current < 1 || !Number.isFinite(total) || total < 0) throw new Error('搜索分页信息无效，请重试')
  return { items, current, total, hasMore: current * PAGE_SIZE < total && items.length > 0 }
}

export const useSearchStore = defineStore('search', {
  state: () => ({
    keyword: '',
    posts: [],
    notes: [],
    postPage: 0,
    notePage: 0,
    postTotal: 0,
    noteTotal: 0,
    postHasMore: false,
    noteHasMore: false,
    loading: false,
    loadingMore: '',
    postError: '',
    noteError: '',
    postTraceId: '',
    noteTraceId: '',
    searched: false,
    sequence: 0,
  }),
  getters: {
    error: (state) => [state.postError, state.noteError].filter(Boolean).join('；'),
  },
  actions: {
    reset() {
      this.sequence++
      controllers.get(this)?.abort()
      controllers.delete(this)
      this.keyword = ''
      this.posts = []
      this.notes = []
      this.postPage = 0
      this.notePage = 0
      this.postTotal = 0
      this.noteTotal = 0
      this.postHasMore = false
      this.noteHasMore = false
      this.loading = false
      this.loadingMore = ''
      this.postError = ''
      this.noteError = ''
      this.postTraceId = ''
      this.noteTraceId = ''
      this.searched = false
    },

    async search(keyword) {
      const normalizedKeyword = String(keyword || '').trim()
      this.reset()
      const sequence = this.sequence
      if (!normalizedKeyword) return
      const controller = new AbortController()
      controllers.set(this, controller)
      this.keyword = normalizedKeyword
      this.loading = true
      this.searched = true
      try {
        const results = await Promise.allSettled([
          request('/search', { query: { keyword: normalizedKeyword, page: 1, size: PAGE_SIZE }, silent: true, signal: controller.signal }).then((page) => pageData(page, normalizePost, 1)),
          request('/search/notes', { query: { keyword: normalizedKeyword, page: 1, size: PAGE_SIZE }, silent: true, signal: controller.signal }).then((page) => pageData(page, normalizeNote, 1)),
        ])
        if (sequence !== this.sequence) return
        for (const [index, type] of ['post', 'note'].entries()) {
          const result = results[index]
          if (result.status === 'rejected') {
            this[`${type}Error`] = result.reason.message || '暂时无法读取'
            this[`${type}TraceId`] = result.reason.traceId || ''
            continue
          }
          const data = result.value
          this[type === 'post' ? 'posts' : 'notes'] = data.items
          this[`${type}Page`] = data.current
          this[`${type}Total`] = data.total
          this[`${type}HasMore`] = data.hasMore
        }
        // 作者信息是辅助数据，不让它拖住已返回的搜索结果。
        useAuthorStore().ensureAuthors([
          ...this.posts.map((post) => post.userId),
          ...this.notes.map((note) => note.userId),
        ]).catch(() => {})
      } finally {
        if (sequence === this.sequence) this.loading = false
      }
    },

    async loadMore(type) {
      if (this.loadingMore || !['post', 'note'].includes(type)) return
      const isPost = type === 'post'
      if (isPost ? !this.postHasMore : !this.noteHasMore) return
      this.loadingMore = type
      this[`${type}Error`] = ''
      this[`${type}TraceId`] = ''
      const sequence = this.sequence
      const keyword = this.keyword
      try {
        const nextPage = (isPost ? this.postPage : this.notePage) + 1
        const page = await request(isPost ? '/search' : '/search/notes', {
          query: { keyword, page: nextPage, size: PAGE_SIZE },
          silent: true,
          signal: controllers.get(this)?.signal,
        })
        if (sequence !== this.sequence || keyword !== this.keyword) return
        const data = pageData(page, isPost ? normalizePost : normalizeNote, nextPage)
        const target = isPost ? this.posts : this.notes
        const known = new Set(target.map((item) => item.id))
        target.push(...data.items.filter((item) => !known.has(item.id)))
        if (isPost) {
          this.postPage = data.current
          this.postTotal = data.total
          this.postHasMore = this.posts.length < data.total && data.items.length > 0
        } else {
          this.notePage = data.current
          this.noteTotal = data.total
          this.noteHasMore = this.notes.length < data.total && data.items.length > 0
        }
        useAuthorStore().ensureAuthors(data.items.map((item) => item.userId)).catch(() => {})
      } catch (error) {
        if (sequence === this.sequence && keyword === this.keyword) {
          this[`${type}Error`] = error.message
          this[`${type}TraceId`] = error.traceId || ''
        }
      } finally {
        if (sequence === this.sequence && keyword === this.keyword) this.loadingMore = ''
      }
    },
  },
})
