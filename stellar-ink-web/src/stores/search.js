import { defineStore } from 'pinia'
import { request } from '@/api/client'
import { normalizePost } from '@/stores/posts'
import { normalizeNote } from '@/stores/notes'
import { useAuthorStore } from '@/stores/authors'

const PAGE_SIZE = 12

function pageData(page, normalize, fallbackPage) {
  const records = page?.records || page?.list || []
  const items = records.map((item) => normalize(item))
  const current = Number(page?.current ?? fallbackPage)
  const total = Number(page?.total ?? items.length)
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
    error: '',
    searched: false,
    sequence: 0,
  }),
  actions: {
    reset() {
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
      this.error = ''
      this.searched = false
    },

    async search(keyword) {
      const normalizedKeyword = String(keyword || '').trim()
      const sequence = ++this.sequence
      if (!normalizedKeyword) {
        this.reset()
        return
      }
      this.keyword = normalizedKeyword
      this.posts = []
      this.notes = []
      this.postPage = 0
      this.notePage = 0
      this.postTotal = 0
      this.noteTotal = 0
      this.postHasMore = false
      this.noteHasMore = false
      this.loadingMore = ''
      this.loading = true
      this.error = ''
      this.searched = true
      try {
        const [postPage, notePage] = await Promise.all([
          request('/search', { query: { keyword: normalizedKeyword, page: 1, size: PAGE_SIZE }, silent: true }),
          request('/notes', { query: { keyword: normalizedKeyword, page: 1, size: PAGE_SIZE }, silent: true }),
        ])
        if (sequence !== this.sequence) return
        const postData = pageData(postPage, normalizePost, 1)
        const noteData = pageData(notePage, normalizeNote, 1)
        this.posts = postData.items
        this.notes = noteData.items
        this.postPage = postData.current
        this.notePage = noteData.current
        this.postTotal = postData.total
        this.noteTotal = noteData.total
        this.postHasMore = postData.hasMore
        this.noteHasMore = noteData.hasMore
        await useAuthorStore().ensureAuthors([
          ...this.posts.map((post) => post.userId),
          ...this.notes.map((note) => note.userId),
        ]).catch(() => {})
      } catch (error) {
        if (sequence === this.sequence) this.error = error.message
      } finally {
        if (sequence === this.sequence) this.loading = false
      }
    },

    async loadMore(type) {
      if (this.loadingMore || !['post', 'note'].includes(type)) return
      const isPost = type === 'post'
      if (isPost ? !this.postHasMore : !this.noteHasMore) return
      this.loadingMore = type
      this.error = ''
      const sequence = this.sequence
      const keyword = this.keyword
      try {
        const nextPage = (isPost ? this.postPage : this.notePage) + 1
        const page = await request(isPost ? '/search' : '/notes', {
          query: { keyword, page: nextPage, size: PAGE_SIZE },
          silent: true,
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
        await useAuthorStore().ensureAuthors(data.items.map((item) => item.userId)).catch(() => {})
      } catch (error) {
        if (sequence === this.sequence && keyword === this.keyword) this.error = error.message
      } finally {
        if (sequence === this.sequence && keyword === this.keyword) this.loadingMore = ''
      }
    },
  },
})
