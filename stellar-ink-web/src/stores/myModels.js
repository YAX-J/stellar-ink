/* 我的 AI 模型（M12）：读者/作者自己配模型。
 *
 * 与「AI 实验室 → 模型配置」的分工：
 * - 那份是**站长配的全局默认**（全站共用，一角色一行）；
 * - 这份是**我自己的**，没配的角色自动回落全局 —— 所以可以只配一个 chat，其余照旧。
 *
 * 四条口径（每一条都对应一类会被误读的界面）：
 *
 * 1. **只列 chat / fast / reasoning**：embedding / rerank 不按用户隔离 ——
 *    向量索引只有一份，换一个嵌入模型检索得到的是**错的**结果（不是差一点）。
 *    界面上必须明说，否则用户会以为「功能不全」而不是「这是刻意的」。
 * 2. **「没配」与「用全局」是两件事**：前者是状态，后者是行为。
 *    只显示空输入框，用户不知道现在到底在用谁的模型。
 * 3. **取数失败必须说出来**：这一点与阅读页的知识条目**刻意相反** ——
 *    知识条目取不到就整块不出现（那是增强），而这里用户主动来看自己的配置，
 *    失败了却显示成「你还没配」，他会以为配置丢了。
 * 4. **写成功就地生效、刷新失败不算写失败**；写**失败**则不改本地（不回滚就是在说谎）。
 *
 * 用到它的地方（两处，共用同一份状态）：
 * - `views/account/AccountView.vue`：完整的三个角色面板（M12 的正式入口）；
 * - `components/assistant/AssistantDock.vue`：右下角浮层里的「模型」一行 + 快速添加表单。
 *   那里**只处理 `chat`**（问答与深挖共用的那一个角色），并且用现成的 `GLOBAL_ONLY_NOTE`
 *   说明 embedding / rerank 为什么不在里面。
 *
 * ⚠️ 它是**全局配置 store，不是会话状态**，所以浮层可以直接用它 ——
 * 不要为它再抽一个 composable（浮层与深读页会同时存在且各自持有会话，那才是需要多实例的情形，
 * 见 `useQaStream` / `useAgent`）；抽一层只会让「谁在改这份配置」变得看不清。
 */
import { defineStore } from 'pinia'
import { request } from '@/api/client'
import { normalizePullResult } from '@/utils/providerModels'

/** 可以个人配置的角色：与后端 `ProviderUrlPolicy.isUserScoped` / Python `USER_SCOPED_ROLES` 一致。
 *
 *  ⚠️ 三处必须同时改：这里、Java 的 `isUserScoped`、Python 的 `USER_SCOPED_ROLES`。
 *  漏改一处，界面就会出现「填了保存成功但不生效」的角色。 */
export const MY_MODEL_ROLES = [
  { key: 'chat', label: '对话模型', hint: '问答、深挖用的模型' },
  { key: 'fast', label: '快速模型', hint: '润色、标题这类短任务' },
  { key: 'reasoning', label: '推理模型', hint: '复杂问题的分析' },
]

/** 为什么这两个角色不在这里：说清楚，比让用户找半天强。 */
export const GLOBAL_ONLY_NOTE =
  '嵌入模型与重排模型由站长统一配置：向量索引只有一份，换模型会让检索结果出错。'

export const useMyModelsStore = defineStore('myModels', {
  state: () => ({
    /** role → 我的配置（没有该键 = 这个角色用全局那份） */
    items: {},
    loaded: false,
    loading: false,
    /** 取数失败：界面据此说「服务坏了」，而不是「你还没配」 */
    failed: false,
    error: '',
    savingRole: '',
    /** 表单内的错误（留在按钮上方，不只靠会消失的全局 toast） */
    formError: '',
    checkResults: {},
  }),

  getters: {
    /** 三个角色各一行：带上「我是自己配的，还是在用全局」 */
    rows: (state) =>
      MY_MODEL_ROLES.map((role) => ({
        ...role,
        config: state.items[role.key] || null,
        personal: Boolean(state.items[role.key]),
      })),
    personalCount: (state) => Object.keys(state.items).length,
    hasPersonal: (state) => Object.keys(state.items).length > 0,
    /** 一个角色有没有配密钥：没配密钥就保存不了（首次），界面要提前说 */
    keyConfigured: (state) => (role) =>
      Boolean(state.items[role] && state.items[role].apiKeyConfigured),
  },

  actions: {
    async load() {
      this.loading = true
      this.error = ''
      try {
        const data = await request('/ai/me/providers', { silent: true })
        const map = {}
        ;(data || []).forEach((item) => {
          if (item && item.role) map[item.role] = item
        })
        this.items = map
        this.loaded = true
        this.failed = false
        return map
      } catch (error) {
        // ⚠️ 失败**不清成「没配」**：那会让「服务坏了」看起来像「我从没配过」。
        // 标记 failed 并清空列表 —— 清空是为了不展示可能已经过期的端点与掩码
        this.failed = true
        this.items = {}
        this.error = error.message
        return {}
      } finally {
        this.loading = false
      }
    },

    /** 保存某个角色；`apiKey` 留空表示沿用已存密钥 */
    async save(role, payload) {
      this.savingRole = role
      this.formError = ''
      try {
        const saved = await request('/ai/me/providers', {
          method: 'POST',
          body: { role, ...payload },
          silent: true,
        })
        // 就地更新：POST 已经 200，说明落库了；随后的刷新只是为了让页面好看
        this.items = { ...this.items, [saved.role]: saved }
        const next = { ...this.checkResults }
        delete next[role]
        this.checkResults = next
        this.loaded = true
        this.failed = false
        return saved
      } catch (error) {
        // 写失败：**不改本地**。乐观更新在这里是错的 —— 用户会以为保存成功了
        this.formError = error.message
        throw error
      } finally {
        this.savingRole = ''
      }
    },

    /** 删除我的配置：该角色回落到全局那份 */
    async remove(role) {
      this.formError = ''
      const removed = await request(`/ai/me/providers/${role}`, { method: 'DELETE', silent: true })
      if (removed) {
        const next = { ...this.items }
        delete next[role]
        this.items = next
        const checks = { ...this.checkResults }
        delete checks[role]
        this.checkResults = checks
      }
      return removed
    },

    /**
     * 拉取供应商的模型列表（`POST /ai/me/providers/models`）—— 浮层那张表单的**可选**入口。
     *
     * 三条口径（与上面那四条并列，同样都是会被误读的地方）：
     * 1. **结果不进 state、不落任何存储**：只 `return` 给调用方，用完即弃。
     *    供应商那边随时会加/删模型，缓存（哪怕是内存里的 `state`）只会让用户对着一个
     *    已经不存在的模型名填半天 —— 这是本轮明确不要的东西。
     * 2. **apiKey 只在这一次请求体里出现**：不写 localStorage / sessionStorage，也不进 state。
     *    留空（空串）时**干脆不传这个字段**，让后端按「用已保存的密钥」处理 ——
     *    传空串要靠后端判 blank，少一层约定。
     * 3. **失败不改动任何已保存配置**：这里只把错误抛给调用方，不碰 `items` / `loaded` /
     *    `failed` / `formError`。特别是 `failed` —— 它的含义是「我的配置没读到」，
     *    拉列表没成也标上，模型那一行就会显示成「没读到配置」，两件事被混成一件。
     *    `ownErrors: true`：供应商的 401/403 不是本站会话失效，绝不能让拉列表把用户踢下线。
     */
    async fetchProviderModels({ provider, baseUrl, apiKey = '' }) {
      const body = { provider, baseUrl, role: 'chat' }
      if (apiKey) body.apiKey = apiKey
      const data = await request('/ai/me/providers/models', {
        method: 'POST',
        body,
        // 供应商侧发现模型可能要几秒（有的要拉一整份目录），比默认 15s 稍宽一点
        timeout: 20000,
        silent: true,
        ownErrors: true,
      })
      return normalizePullResult(data)
    },

    async check(role) {
      this.savingRole = role
      this.formError = ''
      try {
        const result = await request(`/ai/me/providers/${role}/check`, {
          method: 'POST',
          silent: true,
        })
        this.checkResults = { ...this.checkResults, [role]: result }
        return result
      } catch (error) {
        this.formError = error.message
        throw error
      } finally {
        this.savingRole = ''
      }
    },
  },
})
