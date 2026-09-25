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

/**
 * 对比表的列与顺序：**指标名由 Python 侧决定**（`evaluate_strategy` 的键），
 * 这里只声明「面板想按什么顺序展示、哪些列缺了就跳过」。
 * 加新指标时改这里就能显示，不必动后端。
 */
export const EVAL_METRIC_COLUMNS = [
  { key: 'recall@1', label: 'Recall@1' },
  { key: 'recall@3', label: 'Recall@3' },
  { key: 'recall@5', label: 'Recall@5' },
  { key: 'precision@5', label: 'P@5' },
  { key: 'ndcg@5', label: 'NDCG@5' },
  { key: 'mrr', label: 'MRR' },
  { key: 'refusalRate', label: '拒答率' },
  { key: 'falseRefusalRate', label: '误拒率' },
  { key: 'citationAccuracy', label: '引用准确率' },
  { key: 'latencyP50', label: 'P50(ms)' },
]

/** 评测接口最长等多久：真模型跑 30 题可能几十秒，默认 15s 会被 abort */
export const EVAL_TIMEOUT_MS = 120000

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
    /* ---- 评测台（C 阶段）---- */
    /** 可选数据集（来自 GET /ai/admin/eval/datasets） */
    evalDatasets: [],
    /** 标准策略组（来自 GET /ai/admin/eval/strategies） */
    evalStrategies: [],
    /** 用户勾选的策略 key */
    evalSelected: [],
    evalMetaLoading: false,
    evalRunning: false,
    evalError: '',
    /** 上一次运行结果（对比表 + 逐题明细 + notes） */
    evalResult: null,
    /** 逐题明细当前查看的策略 */
    evalCaseStrategy: '',
  }),
  getters: {
    list: (s) => AI_ROLES.map((role) => ({
      ...role,
      config: s.providers[role.key] || null,
      check: s.checkResults[role.key] || null,
      saving: s.savingRole === role.key,
      checking: s.checkingRole === role.key,
    })),
    /** 对比表要渲染的列：只保留结果里真的有的指标（缺列不显示占位空列） */
    evalColumns: (s) => {
      const per = (s.evalResult && s.evalResult.perStrategy) || {}
      const keys = new Set()
      Object.values(per).forEach((metrics) => Object.keys(metrics || {}).forEach((k) => keys.add(k)))
      return EVAL_METRIC_COLUMNS.filter((column) => keys.has(column.key))
    },
    /** 逐题明细里当前策略的行；未指定时用第一组 */
    evalCases: (s) => {
      if (!s.evalResult || !Array.isArray(s.evalResult.cases)) return []
      const strategy = s.evalCaseStrategy
        || (s.evalResult.strategies?.[0]?.key ?? '')
      return strategy ? s.evalResult.cases.filter((row) => row.strategy === strategy) : []
    },
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

    /* ============================ 评测台 ============================ */

    /** 读数据集与标准策略组：面板一进「评测台」页签就调，两者缺一都跑不了 */
    async loadEvalMeta() {
      this.evalMetaLoading = true
      this.evalError = ''
      try {
        const [datasets, strategies] = await Promise.all([
          request('/ai/admin/eval/datasets'),
          request('/ai/admin/eval/strategies'),
        ])
        this.evalDatasets = datasets || []
        this.evalStrategies = strategies || []
        // 默认全选：用户点一下就能跑出对照表，而不是先勾五下
        this.evalSelected = this.evalStrategies.map((item) => item.key)
        return { datasets: this.evalDatasets, strategies: this.evalStrategies }
      } catch (error) {
        this.evalError = error.message
        throw error
      } finally {
        this.evalMetaLoading = false
      }
    },

    /**
     * 跑一轮评测。
     * `strategies` 为空表示「交给 Python 用标准五组」——面板在没有元数据时也能跑。
     * 超时单独放宽：真模型跑 30 题可能几十秒，默认 15s 会被前端自己 abort。
     */
    async runEval({ dataset, strategies = [], maxCases = null } = {}) {
      this.evalRunning = true
      this.evalError = ''
      try {
        const payload = { dataset }
        if (strategies.length) payload.strategies = strategies
        if (maxCases) payload.maxCases = maxCases
        const result = await request('/ai/admin/eval/run', {
          method: 'POST',
          body: payload,
          timeout: EVAL_TIMEOUT_MS,
        })
        this.evalResult = result
        this.evalCaseStrategy = result?.strategies?.[0]?.key || ''
        return result
      } catch (error) {
        this.evalError = error.message
        throw error
      } finally {
        this.evalRunning = false
      }
    },

    /** 按当前勾选拼出请求里的策略列表（保留 Python 给的字段，避免前端漏字段导致口径不同） */
    selectedStrategyPayload() {
      const chosen = new Set(this.evalSelected)
      return this.evalStrategies.filter((item) => chosen.has(item.key))
    },
  },
})
