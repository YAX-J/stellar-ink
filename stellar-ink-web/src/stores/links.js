import { defineStore } from 'pinia'
import { FRIENDS } from '@/api/mock'

export const useLinkStore = defineStore('links', {
  state: () => ({
    friends: FRIENDS.map((f) => ({ ...f })),
    edges: [[0, 1], [1, 4], [0, 2], [2, 3], [3, 5], [1, 2], [4, 5]],
  }),
  actions: {
    apply(name, url) {
      const friend = {
        n: name,
        u: url || 'unknown.space',
        d: '新来的邻居，信号确认中…',
        x: .15 + Math.random() * .7,
        y: .15 + Math.random() * .7,
        fresh: true,
      }
      this.friends.push(friend)
      return this.friends.length - 1
    },
  },
})
