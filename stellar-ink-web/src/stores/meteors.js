import { defineStore } from 'pinia'
import { METEORS } from '@/api/mock'

export const useMeteorStore = defineStore('meteors', {
  state: () => ({ items: [...METEORS] }),
  actions: {
    launch(text) {
      this.items.unshift({ t: text, d: '刚刚' })
    },
  },
})
