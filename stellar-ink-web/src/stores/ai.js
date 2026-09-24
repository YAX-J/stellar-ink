import { defineStore } from 'pinia'
import { request } from '@/api/client'

/**
 * 逻辑角色：与后端 `AiModelRole`、Python `providers.registry` 逐字一致。
 * 顺序即面板展示顺序 —— 对话/快速/推理都是「文本能力」，随后是嵌入与重排。
 */
export const AI_ROLES = [
  { key: 'chat', label: '对话模型', hint: '问答、摘要、润色' },
  { key: 'fast', label: '快速模型', hint: '打标签、意图识别等轻任务' },
  { key: 'reasoning', label: '推理模型', hint: '多步分析、深度研究' },
  { key: 'embedding', label: '嵌入模型', hint: '文章切块向量化（换模型要重建索引）' },
  { key: 'rerank', label: '重排模型', hint: '检索候选精排' },
]

/** 常见厂商的预填建议：只填端点与模型名，**密钥永远由使用者自己填** */
export const PROVIDER_PRESETS = [
  {
    key: 'deepseek',
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com/v1',
    models: { chat: 'deepseek-chat', reasoning: 'deepseek-reasoner', fast: 'deepseek-chat' },
  },
  {
    key: 'siliconflow',
    label: '硅基流动 SiliconFlow',
    baseUrl: 'https://api.siliconflow.cn/v1',
    models: { embedding: 'BAAI/bge-m3', rerank: 'BAAI/bge-reranker-v2-m3' },
  },
  { key: 'fake', label: 'Fake（离线自测）', baseUrl: 'http://fake.local', models: {} },
]

export const useAiStore = defineStore('ai', {
  state: () => ({
    /** 已保存的角色配置，key 为角色键 */
    providers: {},
    loading: false,
    error: '',
    initialized: false,
    /** 正在提交的角色键（用于按钮 loading） */
    savingRole: '',
    /** 正在自检的角色键 */
    checkingRole: '',
    /** 角色键 → 连通性自检结论 */
    checkResults: {},
  }),
  getters: {
    list: (s) => AI_ROLES.map((role) => ({
      ...role,
      config: s.providers[role.key] || null,
      check: s.checkResults[role.key] || null,
      saving: s.savingRole === role.key,
      checking: s.checkingRole === role.key,
    })),
  },
  actions: {
    async fetchProviders() {
      this.loading = true
      this.error = ''
      try {
        const data = await request('/ai/admin/providers')
        const map = {}
        ;(data || []).forEach((item) => {
          if (item && item.role) map[item.role] = item
        })
        this.providers = map
        this.initialized = true
        return map
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.loading = false
      }
    },

    /** 保存某个角色；`apiKey` 留空表示沿用已存密钥 */
    async saveProvider(role, payload) {
      this.savingRole = role
      this.error = ''
      try {
        const saved = await request('/ai/admin/providers', {
          method: 'POST',
          body: { role, ...payload },
        })
        this.providers = { ...this.providers, [saved.role]: saved }
        // 配置变了，旧的自检结论不再可信
        const next = { ...this.checkResults }
        delete next[role]
        this.checkResults = next
        return saved
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.savingRole = ''
      }
    },

    async removeProvider(role) {
      this.error = ''
      const removed = await request(`/ai/admin/providers/${role}`, { method: 'DELETE' })
      if (removed) {
        const next = { ...this.providers }
        delete next[role]
        this.providers = next
      }
      return removed
    },

    async checkProvider(role) {
      this.checkingRole = role
      this.error = ''
      try {
        const result = await request(`/ai/admin/providers/${role}/check`, { method: 'POST' })
        this.checkResults = { ...this.checkResults, [role]: result }
        return result
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.checkingRole = ''
      }
    },
  },
})
