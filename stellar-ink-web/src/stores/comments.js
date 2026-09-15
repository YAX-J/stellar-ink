import { defineStore } from 'pinia'
import { request } from '@/api/client'
import { useAuthorStore } from '@/stores/authors'

function normalize(comment) {
  if (!comment) return null
  return {
    ...comment,
    id: Number(comment.id),
    postId: Number(comment.postId),
    userId: Number(comment.userId),
    content: comment.content || '',
  }
}

export const useCommentStore = defineStore('comments', {
  state: () => ({ commentsByPost: {}, loading: false, submitting: false, error: '' }),
  getters: {
    forPost: (state) => (id) => state.commentsByPost[Number(id)] || [],
  },
  actions: {
    async fetchForPost(postId) {
      const id = Number(postId)
      if (!id) return []
      this.loading = true
      this.error = ''
      try {
        const data = await request(`/posts/${id}/comments`, { silent: true })
        const list = Array.isArray(data) ? data : (data?.records || data?.list || [])
        this.commentsByPost[id] = list.map(normalize).filter(Boolean)
        await useAuthorStore().ensureAuthors(this.commentsByPost[id].map((item) => item.userId)).catch(() => {})
        return this.commentsByPost[id]
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.loading = false
      }
    },

    async add(postId, content) {
      const id = Number(postId)
      this.submitting = true
      this.error = ''
      try {
        const data = await request(`/posts/${id}/comments`, {
          method: 'POST', body: { content: String(content || '').trim() },
        })
        const created = normalize(data)
        if (created) {
          this.commentsByPost[id] = [...(this.commentsByPost[id] || []), created]
          await useAuthorStore().ensureAuthors([created.userId]).catch(() => {})
        }
        return created
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.submitting = false
      }
    },

    async remove(commentId, postId) {
      await request(`/posts/${Number(postId)}/comments/${Number(commentId)}`, { method: 'DELETE' })
      const id = Number(postId)
      if (this.commentsByPost[id]) {
        this.commentsByPost[id] = this.commentsByPost[id].filter((item) => item.id !== Number(commentId))
      }
    },
  },
})
