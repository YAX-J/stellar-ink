import { defineStore } from 'pinia'
import { request } from '@/api/client'

export const useStatsStore = defineStore('stats', {
  state: () => ({
    overview: null,
    loading: false,
    error: '',
  }),
  actions: {
    async fetchOverview() {
      this.loading = true
      this.error = ''
      try {
        this.overview = await request('/stats/overview')
        return this.overview
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.loading = false
      }
    },
  },
})
