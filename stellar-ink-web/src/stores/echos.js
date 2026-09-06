import { defineStore } from 'pinia'
import { ECHOS } from '@/api/mock'

/* 每只瓶子在海面上的落点只随机一次，避免重渲染时跳动 */
const layout = () => ({
  left: 3 + Math.random() * 66,
  top: 5 + Math.random() * 68,
  rot: (Math.random() * 8 - 4).toFixed(1),
  dur: (6 + Math.random() * 4).toFixed(1),
})

export const useEchoStore = defineStore('echos', {
  state: () => ({
    bottles: ECHOS.map((e) => ({ ...e, ...layout() })),
  }),
  actions: {
    throw(name, msg) {
      this.bottles.unshift({ n: name, m: msg, ...layout() })
    },
  },
})
