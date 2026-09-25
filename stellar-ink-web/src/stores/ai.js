import { defineStore } from 'pinia'
import { request } from '@/api/client'

/**
 * 逻辑角色：与后端 `AiModelRole`、Python `providers.registry` 逐字一致。
 * 顺序即面板展示顺序 —— 对话/快速/推理都是「文本能力」，随后是嵌入与重排。
 *
 * **刻意没有「供应商预设」**：曾经这里有一份 DeepSeek / 硅基流动的端点与模型名列表，
 * 用作面板的「快速填入」。那等于把默认供应商写进代码 —— 用自建网关、公司代理或别的厂商时，
 * 它不只是没用，还会诱导人填错。现在端点与模型名**只能由使用者填**，
 * 系统里不存在「没配也能跑」的默认模型。
 */
export const AI_ROLES = [
  { key: 'chat', label: '对话模型', hint: '问答、摘要、润色' },
  { key: 'fast', label: '快速模型', hint: '打标签、意图识别等轻任务' },
  { key: 'reasoning', label: '推理模型', hint: '多步分析、深度研究' },
  { key: 'embedding', label: '嵌入模型', hint: '文章切块向量化（换模型要重建索引）' },
  { key: 'rerank', label: '重排模型', hint: '检索候选精排' },
]

/**
 * 一个模型能干什么：与后端 `AiModelCapability`、Python `ProviderCapabilities` 逐字一致。
 *
 * 模型库里每条模型都标注能力，角色的下拉框只列出**能力匹配**的那些 ——
 * 否则把一个纯对话模型选给嵌入角色，要到真正调用时才报错，而那时人已经离开配置页了。
 */
export const AI_CAPABILITIES = [
  { key: 'chat', label: '对话' },
  { key: 'embedding', label: '嵌入' },
  { key: 'rerank', label: '重排' },
]

/**
 * 角色 → 它需要的模型能力。
 *
 * 与后端 `AiModelRole.capability()`、Python `providers/registry.py::_ROLE_CAPABILITY` 三处同源：
 * 前端只是**提前过滤**（让人选不到明显不对的），真正的把关在后端绑定接口与 Python 取实例时。
 */
export const ROLE_CAPABILITY = {
  chat: 'chat',
  fast: 'chat',
  reasoning: 'chat',
  embedding: 'embedding',
  rerank: 'rerank',
}

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
    /* ---- 模型库（可下拉选择的模型池）---- */
    /** 模型库列表（来自 GET /ai/admin/models，密钥只有掩码） */
    models: [],
    modelsLoading: false,
    /** 模型库读不出来时的原因：最常见的是「还没执行 11_ai_model_library.sql」 */
    modelsError: '',
    modelsLoaded: false,
    /** 正在保存/自检的模型 id（按钮 loading） */
    savingModel: false,
    checkingModel: null,
    /** 正在把模型应用到哪个角色 */
    bindingRole: '',
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
      binding: s.bindingRole === role.key,
      capability: ROLE_CAPABILITY[role.key],
      /** 该角色当前生效的配置来自库里哪一条（手填时为 null） */
      boundModel: (s.providers[role.key] && s.providers[role.key].modelId)
        ? s.models.find((item) => item.id === s.providers[role.key].modelId) || null
        : null,
      /** 下拉框可选项：能力匹配的模型，停用的排在最后并标注 */
      options: s.models
        .filter((item) => (item.capabilities || []).includes(ROLE_CAPABILITY[role.key]))
        .slice()
        .sort((a, b) => Number(b.enabled) - Number(a.enabled) || a.id - b.id),
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

    /* ============================ 模型库 ============================ */

    /**
     * 读模型库。
     *
     * 读不到时**不抛**：这个接口依赖 `deploy/sql/11_ai_model_library.sql`，
     * 还没执行的库上会直接报「表不存在」。那种情况下页面应该照常能用（角色卡片还在），
     * 只是顶部给一句「模型库不可用，先执行那条 SQL」—— 而不是整页白屏。
     */
    async loadModels() {
      this.modelsLoading = true
      this.modelsError = ''
      try {
        const data = await request('/ai/admin/models')
        this.models = data || []
        this.modelsLoaded = true
        return this.models
      } catch (error) {
        this.modelsError = error.message
        throw error
      } finally {
        this.modelsLoading = false
      }
    },

    /**
     * 新增或修改一条模型；`apiKey` 留空表示沿用已存密钥。
     *
     * 两条刻意的处理（踩过一次「保存按钮是假的」）：
     * 1. **先就地更新列表**：保存成功这件事，不该被「随后的刷新失败」抹掉；
     * 2. 刷新走 {@link refreshAfterWrite}，**失败只记状态、不抛** ——
     *    否则一次 503 会把「已经建好了」显示成「保存失败」，用户再点一次还会看到
     *    「已经有同名模型」，而列表里一条都没有。
     */
    async saveModel(payload) {
      this.savingModel = true
      this.error = ''
      try {
        const saved = await request('/ai/admin/models', { method: 'POST', body: payload })
        const without = this.models.filter((item) => item.id !== saved.id)
        this.models = [...without, saved].sort((a, b) => a.id - b.id)
        this.modelsLoaded = true
        await this.refreshAfterWrite()
        return saved
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.savingModel = false
      }
    },

    /**
     * 写操作之后的刷新：模型库与角色配置各刷一次，**失败只记状态、不抛**。
     *
     * 为什么必须这样：这些刷新紧跟在一个**已经成功**的写操作之后。让它抛错，
     * 上层就会把「保存成功」显示成「保存失败」—— 而数据其实已经落库了。
     * 这正是「保存按钮是假的」的来源（一次 503 就够）。
     */
    async refreshAfterWrite() {
      try {
        await this.loadModels()
      } catch {
        /* 原因已记进 modelsError，列表保留乐观更新的结果 */
      }
      try {
        await this.fetchProviders()
      } catch {
        /* 原因已记进 error */
      }
    },

    /**
     * 删掉库里一条模型。
     *
     * 与 {@link saveModel} 同口径：删除成功就**立刻**从列表里拿掉，随后的刷新失败只记状态。
     * `refreshAfterWrite` 里的 `loadModels` 出错会 `throw`，本方法捕获后不再冒泡。
     */
    async removeModel(id, force = false) {
      this.error = ''
      const removed = await request(`/ai/admin/models/${id}${force ? '?force=true' : ''}`, {
        method: 'DELETE',
      })
      if (removed) {
        const next = this.models.filter((item) => item.id !== id)
        // 解绑（force）会把角色行上的 model_id 清空，删掉模型即该角色不再指向库里任何模型
        const providers = { ...this.providers }
        Object.keys(providers).forEach((role) => {
          const config = providers[role]
          if (config && config.modelId === id) {
            providers[role] = { ...config, modelId: null }
          }
        })
        this.models = next
        this.providers = providers
        this.modelsLoaded = true
        await this.refreshAfterWrite()
      }
      return removed
    },

    async checkModel(id) {
      this.checkingModel = id
      this.error = ''
      try {
        const result = await request(`/ai/admin/models/${id}/check`, { method: 'POST' })
        // 自检结果后端已落库（last_check_*），这里先就地更新，随后刷新只为校准时间戳；
        // 刷不出来不该让「自检成功」显示成「自检失败」
        this.models = this.models.map((item) => (item.id === id
          ? {
            ...item,
            lastCheckStatus: result.ok ? 'ok' : 'failed',
            lastCheckMessage: result.message,
            lastCheckedAt: new Date().toISOString(),
          }
          : item))
        await this.refreshAfterWrite()
        return result
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.checkingModel = null
      }
    },

    /** 把库里的某条模型应用到某个角色（后端会校验能力匹配，并把字段复制成该角色当前生效的配置） */
    async bindModel(role, modelId) {
      this.bindingRole = role
      this.error = ''
      try {
        const bound = await request(`/ai/admin/providers/${role}/model`, {
          method: 'PUT',
          body: { modelId },
        })
        this.providers = { ...this.providers, [role]: bound }
        const next = { ...this.checkResults }
        delete next[role]
        this.checkResults = next
        // 绑定关系写在角色行上，库列表里的「正被谁使用」也会变 —— 先就地改，
        // 再 best-effort 刷新（同 saveModel：刷新失败不能把「已应用」显示成「应用失败」）
        this.models = this.models.map((item) => {
          const used = (item.boundRoles || []).filter((key) => key !== role)
          return item.id === modelId ? { ...item, boundRoles: [...used, role] } : { ...item, boundRoles: used }
        })
        await this.refreshAfterWrite()
        return bound
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.bindingRole = ''
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
