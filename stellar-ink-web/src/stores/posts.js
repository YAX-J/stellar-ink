import { defineStore } from 'pinia'
import { request } from '@/api/client'

function normalizePost(post, detail = false) {
  if (!post) return null
  return {
    ...post,
    id: Number(post.id),
    words: Number(post.wordCount ?? post.words ?? 0),
    tags: Array.isArray(post.tags) ? post.tags : [],
    year: Number(post.year || String(post.date || '').slice(0, 4)),
    excerpt: post.summary || post.excerpt || '',
    ...(detail ? { content: post.content || '', readMinutes: post.readMinutes } : {}),
  }
}

export const usePostStore = defineStore('posts', {
  state: () => ({
    posts: [],
    tags: [],
    details: {},
    loading: false,
    detailLoading: false,
    error: '',
    initialized: false,
  }),
  getters: {
    byId: (state) => (id) => state.details[Number(id)] ||
      state.posts.find((post) => post.id === Number(id)),
    totalWords: (state) => state.posts.reduce((total, post) => total + post.words, 0),
  },
  actions: {
    async ensureLoaded() {
      if (this.initialized) return this.posts
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

    async fetchPosts(query = {}) {
      this.loading = true
      this.error = ''
      try {
        const page = await request('/posts', {
          query: { page: 1, size: 100, ...query },
        })
        const records = page?.records || page?.list || []
        this.posts = records.map((post) => normalizePost(post))
        this.initialized = true
        return this.posts
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.loading = false
      }
    },

    async fetchDetail(id) {
      const postId = Number(id)
      if (!postId) return null
      this.detailLoading = true
      this.error = ''
      try {
        const detail = normalizePost(await request(`/posts/${postId}`), true)
        this.details[postId] = detail
        const index = this.posts.findIndex((post) => post.id === postId)
        if (index >= 0) this.posts[index] = { ...this.posts[index], ...detail }
        else this.posts.push(detail)
        return detail
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.detailLoading = false
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
        const data = await request(`/posts/${Number(id)}/glow`, { method: 'POST' })
        const post = this.byId(id)
        if (post && data?.glow !== undefined) post.glow = data.glow
        return data?.glow
      } catch (error) {
        this.error = error.message
        throw error
      }
    },
  },
})
