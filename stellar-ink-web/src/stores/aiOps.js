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

/**
 * 审计读不出来时给的**静态**可操作提示。
 *
 * ⚠️ 为什么不靠后端那句 msg：前端 `api/client.js` 对 **5xx 会丢弃后端 msg**
 * （统一成「星笺暂时无法响应」），所以「表不存在」这类细节根本到不了界面。
 * 而这个面板最常见的失败原因就是**审计表没建**，提示必须是界面自己带的。
 * （原本我写了一条「断言 error.message 里有表名」的用例，那是错的假设 —— 已改。）
 */
export const AUDIT_HINT =
  '读不出来最常见的原因是审计表还没建：确认 deploy/sql/17_ai_retrieval_audit.sql 是否已执行。'

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

    /* ---- 检索审计（第二优先）：趋势 ---- */
    audit: null,
    auditDays: 7,
    auditLoading: false,
    auditFailed: false,
    auditError: '',

    /* ---- 链路回放（第二优先）：单次 ---- */
    trace: null,
    traceId: '',
    traceLoading: false,
    traceFailed: false,
    traceError: '',

    /* ---- 知识库维护（第三优先）：失效盘点 + 构建 ---- */
    stale: null,
    staleLoading: false,
    staleFailed: false,
    staleError: '',
    buildResult: null,
    building: false,
    buildFailed: false,
    buildError: '',
    /** 构建表单：留空 = 由服务端默认（**不**在前端编一个「全量」的承诺） */
    buildForm: { maxPosts: '', postIds: '' },
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

    /* ---- 检索审计（第二优先）---- */

    /** 拒答率与失败率**分开**：前者是语料没覆盖（该补文章），后者是链路坏了（该查服务） */
    auditRefusalRate: (state) => {
      const total = state.audit?.total || 0
      if (!total) return null
      return (state.audit.refused || 0) / total
    },

    auditFailureRate: (state) => {
      const total = state.audit?.total || 0
      if (!total) return null
      return (state.audit.failed || 0) / total
    },

    /** 命中文章 Top：**JSON 里 Map<Long,…> 的键是字符串**，这里统一成数字并保底 */
    auditTopPosts: (state) => {
      const raw = state.audit?.topPosts || {}
      return Object.entries(raw).map(([key, count]) => {
        const id = Number(key)
        return { postId: Number.isFinite(id) ? id : key, count, raw: key }
      })
    },

    /** 被反复问的问题：审计**不存问题原文**，所以只有哈希 —— 界面必须解释这一点 */
    auditRepeatQuestions: (state) => {
      const raw = state.audit?.repeatQuestions || {}
      return Object.entries(raw).map(([hash, count]) => ({ hash, count }))
    },

    /** 一条记录都没有：这**不能**推出「没人问过」——表还没建也会是这样（后端 notes 已说明） */
    auditIsEmpty: (state) => Boolean(state.audit) && (state.audit.total || 0) === 0,

    /* ---- 链路回放（第二优先）---- */

    /** 调用账（来自数据库，跨副本、持久）——「这次到底发生过什么」的权威来源 */
    traceCalls: (state) => state.trace?.calls || [],

    /** 检索回放（来自 Python 进程内缓冲）：**查不到 ≠ 没发生过** */
    traceEvents: (state) => state.trace?.events || [],

    traceHasReplay: (state) => state.trace?.pythonFound === true,

    /** 回放查不到的原因：区分「Python 不可达」与「这个副本上没有」 */
    traceMissReason: (state) => {
      if (!state.trace) return ''
      if (state.trace.pythonAvailable === false) return 'Python 进程当前不可达，回放读不到'
      // ⚠️ 措辞：不能说「这条链路不存在」—— 跨副本时它可能存在别的副本上（已知限制）
      return '这个副本上没有这条回放（回放是进程内的，多副本时可能落在别处）—— 上面的调用账仍然有效'
    },

    /* ---- 知识库维护（第三优先）---- */

    /**
     * 失效盘点的四个数。
     *
     * ⚠️ `checked` 是**这次盘点扫到的主张条数**，也是目前唯一能拿到的「全站知识条目数」——
     * 没有专门的全站计数接口（`/ai/wiki/claims/count` 需要 `postId`，是**按文章**计数）。
     * 所以界面如实写「盘点扫到 N 条」，而不是含糊地写「全站 N 条」。
     */
    wikiTotals: (state) => {
      const stale = state.stale
      if (!stale) return null
      return {
        checked: stale.checked || 0,
        current: stale.current || 0,
        stale: stale.stale || 0,
        orphan: stale.orphan || 0,
        // 截断了就不能说「全站没问题」—— 后端会带这句 note，界面上要醒目
        truncated: Boolean(stale.truncated),
      }
    },

    /** 构建结果：**落库侧的数**与**模型侧的账**分开（口径不同，混起来会误读效果） */
    buildSides: (state) => {
      const result = state.buildResult
      if (!result) return null
      return {
        stored: {
          inserted: result.inserted || 0,
          updated: result.updated || 0,
          skipped: result.skipped || 0,
          entities: result.entities || 0,
          relations: result.relations || 0,
          topics: result.topics || 0,
        },
        modelSide: {
          proposed: result.proposed || 0,
          kept: result.kept || 0,
          entityProposed: result.entityProposed || 0,
          entityKept: result.entityKept || 0,
        },
        posts: result.posts || 0,
      }
    },

    /** 被丢弃的引用按原因分解（`Map<String,Integer>` 的键同样是字符串） */
    buildDropped: (state) => {
      const raw = state.buildResult?.dropped || {}
      return Object.entries(raw)
        .map(([reason, count]) => ({ reason, count }))
        .sort((a, b) => b.count - a.count)
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

    /* ---- 检索审计（第二优先）：趋势 ---- */

    /** 最近 N 天的审计汇总。**失败与「零记录」是两件事**：前者说「读不出来」，后者照实说。 */
    async loadAudit(days) {
      if (days) this.auditDays = days
      this.auditLoading = true
      this.auditError = ''
      try {
        const data = await request(`/ai/admin/retrieval-audit/summary?days=${this.auditDays}`, {
          silent: true,
        })
        this.audit = data || null
        this.auditFailed = false
        return this.audit
      } catch (error) {
        // 最常见的原因就是**审计表还没建**（17_ai_retrieval_audit.sql 没执行）：
        // 这时必须说「读不出来」，不能显示成一张「0 次检索」的空图 —— 那会被读成「没人用过」
        this.audit = null
        this.auditFailed = true
        this.auditError = error.message
        return null
      } finally {
        this.auditLoading = false
      }
    },

    /* ---- 链路回放（第二优先）：单次 ---- */

    /** 按 traceId 取回放：调用账（数据库）+ 检索事件（Python 进程内）。 */
    async loadTrace(traceId) {
      const id = String(traceId || '').trim()
      this.traceId = id
      if (!id) {
        this.trace = null
        this.traceError = '请粘贴 traceId（在报错提示里可以复制）'
        this.traceFailed = false
        return null
      }
      this.traceLoading = true
      this.traceError = ''
      try {
        const data = await request(`/ai/admin/trace/${encodeURIComponent(id)}`, { silent: true })
        this.trace = data || null
        this.traceFailed = false
        return this.trace
      } catch (error) {
        this.trace = null
        this.traceFailed = true
        this.traceError = error.message
        return null
      } finally {
        this.traceLoading = false
      }
    },

    /* ---- 知识库维护（第三优先）---- */

    /**
     * 失效盘点：`current`（段落哈希还对得上）/ `stale`（文章改过，锚点失效）/
     * `orphan`（文章没了）。
     *
     * ⚠️ **盘点不自动重建**：它只回答「哪里过期了」，重建是下一个显式动作。
     * 把两件事合成一个按钮，会让人以为「点了盘点就等于修好了」。
     */
    async loadStale() {
      this.staleLoading = true
      this.staleError = ''
      try {
        const data = await request('/ai/admin/wiki/stale', { silent: true })
        this.stale = data || null
        this.staleFailed = false
        return this.stale
      } catch (error) {
        this.stale = null
        this.staleFailed = true
        this.staleError = error.message
        return null
      } finally {
        this.staleLoading = false
      }
    },

    /**
     * 触发一次构建（**会花模型调用**，走调用账 `scene=wiki`）。
     *
     * 表单留空表示「由服务端决定」（服务端有 maxPosts 默认值）——
     * 前端不写「全量重建」这种话：那是在承诺一件服务端不会做的事
     * （AGENTS §4：「界面不得承诺服务端不会兑现的数字」）。
     */
    async buildWiki() {
      this.building = true
      this.buildError = ''
      try {
        const payload = {}
        const maxPosts = Number(this.buildForm.maxPosts)
        if (Number.isFinite(maxPosts) && maxPosts > 0) payload.maxPosts = maxPosts
        const ids = String(this.buildForm.postIds || '')
          .split(/[\s,，]+/)
          .map((item) => Number(item))
          .filter((item) => Number.isInteger(item) && item > 0)
        if (ids.length) payload.postIds = ids
        const data = await request('/ai/admin/wiki/build', {
          method: 'POST',
          body: payload,
          silent: true,
        })
        this.buildResult = data || null
        this.buildFailed = false
        return this.buildResult
      } catch (error) {
        this.buildResult = null
        this.buildFailed = true
        this.buildError = error.message
        return null
      } finally {
        this.building = false
      }
    },
  },
})
