import { defineStore } from 'pinia'
import { request } from '@/api/client'
import { useAuthorStore } from '@/stores/authors'

const PAGE_SIZE = 24

export function normalizePost(post, detail = false) {
  if (!post) return null
  return {
    ...post,
    id: Number(post.id),
    words: Number(post.wordCount ?? post.words ?? 0),
    tags: Array.isArray(post.tags) ? post.tags : [],
    year: Number(post.year || String(post.date || '').slice(0, 4)),
    excerpt: post.summary || post.excerpt || '',
    glow: Number(post.glow ?? 0),
    viewCount: Number(post.viewCount ?? 0),
    ...(detail ? { content: post.content || '', readMinutes: post.readMinutes, liked: !!post.liked } : {}),
  }
}

export const usePostStore = defineStore('posts', {
  state: () => ({
    posts: [],
    tags: [],
    featured: null,
    details: {},
    loading: false,
    detailLoading: false,
    error: '',
    initialized: false,
    page: 0,
    pageSize: PAGE_SIZE,
    total: 0,
    hasMore: true,
    listQuery: {},
    listSequence: 0,
  }),
  getters: {
    byId: (state) => (id) => state.details[Number(id)] ||
      state.posts.find((post) => post.id === Number(id)),
    totalWords: (state) => state.posts.reduce((total, post) => total + post.words, 0),
  },
  actions: {
    async ensureLoaded() {
      if (this.initialized && !Object.keys(this.listQuery).length) return this.posts
      return this.fetchPosts()
    },

    async fetchTags() {
      try {
        this.tags = await request('/tags')
        return this.tags
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    async fetchPosts(query = {}, { append = false } = {}) {
      if (append && (this.loading || !this.hasMore)) return this.posts
      this.loading = true
      this.error = ''
      const sequence = append ? this.listSequence : ++this.listSequence
      try {
        const { page: queryPage, size: querySize, ...filters } = query
        if (!append) this.listQuery = filters
        const pageNumber = append ? this.page + 1 : Math.max(1, Number(queryPage) || 1)
        const pageSize = Math.min(100, Math.max(1, Number(querySize) || this.pageSize))
        const page = await request('/posts', {
          query: { ...(append ? this.listQuery : filters), page: pageNumber, size: pageSize },
        })
        const records = page?.records || page?.list || []
        if (sequence !== this.listSequence) return this.posts
        const normalized = records.map((post) => normalizePost(post))
        if (append) {
          const known = new Set(this.posts.map((post) => post.id))
          this.posts.push(...normalized.filter((post) => !known.has(post.id)))
        } else {
          this.posts = normalized
        }
        this.page = Number(page?.current ?? pageNumber)
        this.pageSize = Number(page?.size ?? pageSize)
        this.total = Number(page?.total ?? this.posts.length)
        this.hasMore = this.posts.length < this.total && records.length > 0
        await useAuthorStore().ensureAuthors(normalized.map((post) => post.userId)).catch(() => {})
        this.initialized = true
        return this.posts
      } catch (error) {
        if (sequence === this.listSequence) this.error = error.message
        throw error
      } finally {
        if (sequence === this.listSequence) this.loading = false
      }
    },

    async fetchFeatured() {
      try {
        const page = await request('/posts', { query: { page: 1, size: 1, orderBy: 'hottest' }, silent: true })
        const first = (page?.records || page?.list || [])[0]
        this.featured = normalizePost(first)
        if (this.featured) {
          await useAuthorStore().ensureAuthors([this.featured.userId]).catch(() => {})
        }
        return this.featured
      } catch {
        /* 首页仍可从已加载文章中降级选取，不让推荐位影响整页读取 */
        return null
      }
    },

    async loadMore() {
      return this.fetchPosts({}, { append: true })
    },

    async fetchDetail(id) {
      const postId = Number(id)
      if (!postId) return null
      this.detailLoading = true
      this.error = ''
      try {
        const detail = normalizePost(await request(`/posts/${postId}`), true)
        await useAuthorStore().ensureAuthors([detail.userId]).catch(() => {})
        this.details[postId] = detail
        const index = this.posts.findIndex((post) => post.id === postId)
        if (detail.status === 1) {
          if (index >= 0) this.posts[index] = { ...this.posts[index], ...detail }
          else this.posts.unshift(detail)
        } else if (index >= 0) {
          this.posts.splice(index, 1)
        }
        return detail
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.detailLoading = false
      }
    },

    async fetchDrafts() {
      this.error = ''
      try {
        const page = await request('/posts/mine', { query: { status: 0, page: 1, size: 50 } })
        const drafts = (page?.records || page?.list || []).map((post) => normalizePost(post))
        await useAuthorStore().ensureAuthors(drafts.map((post) => post.userId)).catch(() => {})
        return drafts
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    async saveDraft({ id, title, body, tag }) {
      this.error = ''
      const payload = {
        title: title?.trim() || '未命名草稿',
        content: body || '',
        tags: tag ? [tag] : [],
        status: 0,
      }
      try {
        if (id) {
          await request('/posts/' + Number(id), { method: 'PUT', body: payload })
          await this.fetchDetail(id)
          return Number(id)
        }
        const data = await request('/posts', { method: 'POST', body: payload })
        const draftId = Number(data?.id)
        if (draftId) await this.fetchDetail(draftId)
        return draftId
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    async publishDraft({ id, title, body, tag }) {
      this.error = ''
      const payload = {
        title: title?.trim() || '无题的一夜',
        content: body?.trim() || '',
        tags: tag ? [tag] : [],
        status: 1,
      }
      try {
        if (id) {
          await request('/posts/' + Number(id), { method: 'PUT', body: payload })
          await this.fetchDetail(id)
          return Number(id)
        }
        return this.publish({ title, body, tag })
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    async deletePost(id) {
      this.error = ''
      try {
        await request('/posts/' + Number(id), { method: 'DELETE' })
        const postId = Number(id)
        delete this.details[postId]
        this.posts = this.posts.filter((post) => post.id !== postId)
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    async publish({ title, body, tag }) {
      this.error = ''
      try {
        const data = await request('/posts', {
          method: 'POST',
          body: {
            title: title?.trim() || '无题的一夜',
            content: body?.trim() || '',
            tags: tag ? [tag] : [],
            status: 1,
          },
        })
        const id = Number(data?.id)
        if (id) await this.fetchDetail(id)
        else await this.fetchPosts()
        return id
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    async addGlow(id) {
      this.error = ''
      try {
        const data = await request(`/posts/${Number(id)}/glow`, { method: 'POST', silent: true })
        const post = this.byId(id)
        if (post) {
          if (data?.glow !== undefined) post.glow = Number(data.glow)
          if (data?.liked !== undefined) post.liked = !!data.liked
        }
        return data
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    /** 记录一次浏览：登录用户服务端按天去重，失败静默（浏览量不值得打扰读者） */
    async recordView(id) {
      const postId = Number(id)
      if (!postId) return
      try {
        const data = await request(`/posts/${postId}/viewed`, { method: 'POST', silent: true })
        /* 只有服务端真的计入时才本地 +1，避免读者看到「刷了没变」 */
        if (data?.counted) {
          const post = this.byId(postId)
          if (post) post.viewCount = Number(post.viewCount || 0) + 1
        }
      } catch {
        /* 静默：浏览量统计失败不影响阅读 */
      }
    },
  },
})
