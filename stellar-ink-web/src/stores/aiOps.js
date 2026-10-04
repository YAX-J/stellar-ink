/* AI 观测面：运行时装配状态 + 用量与成本（第一优先，M12-S4）。
 *
 * 这两块回答的是两个天天会问的问题：
 * - **我配的模型到底生效了吗**（运行时装配状态）
 * - **花了多少钱、贵在哪**（用量与成本）
 *
 * 三条口径，都是为了不让面板说出它证明不了的话：
 *
 * 1. **「面板已配」不等于「真的生效」**。这里能看到的是「配置行存在 + 启用 + Python 进程可达」，
 *    而 Python 是否真的装配了这份配置，只有在**一次真实调用**里才能证明。
 *    所以文案写「已配置」，不写「运行中」—— 后者会让人以为验证过了。
 * 2. **连通性自检只测 TCP**（`scope: tcp_only`）：端点能连上不代表模型名与密钥可用。
 *    这一条在面板上必须说出来，否则「自检通过」会被当成「配置正确」。
 * 3. **成本为 0 不等于免费**：没有单价的调用按 0 计（`unpricedCalls`），
 *    没有 token 数的调用连算都算不了（`untokenizedCalls`）。这两个数不显示出来，
 *    用户会以为「AI 不花钱」—— 那是最危险的一种误读。
 */
import { defineStore } from 'pinia'
import { request } from '@/api/client'

/** 五个角色的展示顺序：生成类在前（用户日常感知最强的就是它们）。 */
export const AI_ROLE_ORDER = ['chat', 'fast', 'reasoning', 'embedding', 'rerank']

export const AI_ROLE_LABEL = {
  chat: '对话模型',
  fast: '快速模型',
  reasoning: '推理模型',
  embedding: '嵌入模型',
  rerank: '重排模型',
}

/** 每个角色「没配会怎样」：只说「未配置」等于只说了一半。 */
export const ROLE_IMPACT = {
  chat: '问答、深挖、Copilot 都会报「角色 chat 尚未配置模型」',
  fast: '短任务（润色、标题）会失败',
  reasoning: '复杂问题分析会失败',
  embedding: '检索的向量通路会报错（混合检索会退化成纯关键词）',
  rerank: '重排开关一旦打开就会失败',
}

export const RUNTIME_CAVEAT =
  '这里显示的是「面板里配了 + Python 进程可达」。它**不证明** Python 真的装配了这份配置 —— ' +
  '那要等一次真实调用；下面的「连通性自检」也只验证端点 TCP 可达，不验证模型名与密钥。'

export const useAiOpsStore = defineStore('aiOps', {
  state: () => ({
    providers: {},
    health: null,
    usage: null,
    usageDays: 7,
    loading: false,
    loaded: false,
    /** 运行时那块整体读失败 */
    failed: false,
    error: '',
    usageLoading: false,
    usageFailed: false,
    usageError: '',
    /** Python 探活失败与「面板读不到」是两件事，分开记 */
    healthFailed: false,
  }),

  getters: {
    /** 五个角色各一行：状态 + 影响 + 证据 */
    runtimeRows: (state) =>
      AI_ROLE_ORDER.map((role) => {
        const config = state.providers[role] || null
        const enabled = Boolean(config && config.enabled)
        return {
          role,
          label: AI_ROLE_LABEL[role],
          impact: ROLE_IMPACT[role],
          config,
          // ⚠️ 措辞：已配置 / 已停用 / 未配置 —— 刻意不用「运行中」
          status: !config ? 'missing' : enabled ? 'configured' : 'disabled',
          statusText: !config ? '未配置' : enabled ? '已配置' : '已停用',
          checkStatus: config?.lastCheckStatus || 'unknown',
        }
      }),

    missingRoles: (state) =>
      AI_ROLE_ORDER.filter((role) => !state.providers[role]),

    disabledRoles: (state) =>
      AI_ROLE_ORDER.filter((role) => state.providers[role] && !state.providers[role].enabled),

    /** Python 进程是否可达（探活是真去问 /health，不是恒定假信号） */
    pythonReachable: (state) => state.health?.available === true,

    pythonReason: (state) => state.health?.reason || '',

    /** 一句话结论：面板要能直接被读，而不是让人自己拼。
     *
     *  ⚠️ **两件事都要说**：缺角色与 Python 不可达是**独立**的两个问题，
     *  只报其中一个会让人修完一个以为好了（自检里当初就是这么发现判定句不够好的）。 */
    verdict() {
      if (this.failed) return '读不到配置，先看下面的错误'
      const parts = []
      if (this.missingRoles.length > 0) {
        parts.push(
          `还有 ${this.missingRoles.length} 个角色没配：${this.missingRoles
            .map((role) => AI_ROLE_LABEL[role])
            .join('、')}`,
        )
      } else {
        parts.push('五个角色都已配置')
      }
      if (this.pythonReachable) {
        parts.push('Python 进程可达')
      } else if (this.healthFailed) {
        parts.push('Python 进程不可达（探活失败）')
      } else {
        parts.push('Python 进程未确认')
      }
      return parts.join('；')
    },

    /** 失败率（0-1）；没有调用过时给 null，而不是 0 —— 「没跑过」与「一次没失败」是两件事 */
    failureRate: (state) => {
      const calls = state.usage?.calls || 0
      if (!calls) return null
      return (state.usage.failedCalls || 0) / calls
    },

    /** 成本相关的两个「解释不了的钱」，界面必须单独提 */
    costCaveats: (state) => {
      const usage = state.usage
      if (!usage) return []
      const notes = []
      if ((usage.unpricedCalls || 0) > 0) {
        notes.push(
          `${usage.unpricedCalls} 次调用**没有单价**，按 0 计入了成本 —— ` +
            '成本显示偏低不代表真的便宜，去「模型库」里给这条模型填上价格。',
        )
      }
      if ((usage.untokenizedCalls || 0) > 0) {
        notes.push(
          `${usage.untokenizedCalls} 次调用**没拿到 token 数**（上游没回 usage），` +
            '这部分连算都算不了。',
        )
      }
      return notes
    },
  },

  actions: {
    /** 运行时状态：配置列表 + Python 探活。两者都失败才算整体失败。 */
    async loadRuntime() {
      this.loading = true
      this.error = ''
      try {
        const [providers, health] = await Promise.allSettled([
          request('/ai/admin/providers', { silent: true }),
          request('/ai/health', { silent: true }),
        ])
        if (providers.status === 'fulfilled') {
          const map = {}
          ;(providers.value || []).forEach((item) => {
            if (item && item.role) map[item.role] = item
          })
          this.providers = map
          this.loaded = true
          this.failed = false
        } else {
          this.providers = {}
          this.failed = true
          this.error = providers.reason?.message || '读取模型配置失败'
        }
        if (health.status === 'fulfilled') {
          this.health = health.value || null
          this.healthFailed = false
        } else {
          // 探活失败**不**让整块失败：配置读到了就有价值，只是「Python 可达」这一项未知
          this.health = null
          this.healthFailed = true
        }
        return { providers: this.providers, health: this.health }
      } finally {
        this.loading = false
      }
    },

    async loadUsage(days) {
      if (days) this.usageDays = days
      this.usageLoading = true
      this.usageError = ''
      try {
        const data = await request(`/ai/admin/usage/summary?days=${this.usageDays}`, {
          silent: true,
        })
        this.usage = data || null
        this.usageFailed = false
        return this.usage
      } catch (error) {
        this.usage = null
        this.usageFailed = true
        this.usageError = error.message
        return null
      } finally {
        this.usageLoading = false
      }
    },
  },
})
