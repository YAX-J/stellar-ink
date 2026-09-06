import { defineStore } from 'pinia'
import { POSTS, PARA_POOL } from '@/api/mock'

let nextId = POSTS.length + 1

export const usePostStore = defineStore('posts', {
  state: () => ({
    posts: POSTS.map((p, i) => ({ ...p, id: i + 1, glow: 120 + i * 7 })),
    paraPool: PARA_POOL,
  }),
  getters: {
    byId: (s) => (id) => s.posts.find((p) => p.id === Number(id)),
    totalWords: (s) => s.posts.reduce((a, p) => a + p.words, 0),
  },
  actions: {
    /* 执笔舱发射：新文章排在星图最前面 */
    publish({ title, body, tag }) {
      const now = new Date()
      const date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
      this.posts.unshift({
        id: nextId++,
        title: title.trim() || '无题的一夜',
        date,
        words: Math.max(1, body.replace(/\s/g, '').length),
        tags: tag ? [tag] : [],
        year: now.getFullYear(),
      })
      return this.posts[0].id
    },
    addGlow(id) {
      const p = this.byId(id)
      if (p) p.glow += 1
    },
  },
})
