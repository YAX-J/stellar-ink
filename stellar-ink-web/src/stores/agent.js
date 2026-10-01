import { defineStore } from 'pinia'
import { getToken, isAuthError, isRateLimited, request } from '@/api/client'
import { emit, SESSION_EXPIRED, TOAST } from '@/utils/bus'

/**
 * 深挖（只读 Agent）：多步检索问答（E2）。
 *
 * 与 `stores/qa.js` 分开，理由和它当初从 `stores/ai.js` 分出来一样：
 * **权限与代价都不同**。问答是「一次检索 + 一次生成」，Agent 是「多步、可能多次调模型」——
 * 它更慢也更贵，所以入口要显式（用户按按钮才跑），默认不自动触发。
 *
 * 它**不比问答多任何权限**：查的仍是站内已发布文章，门槛同样只是登录。
 * 工具全部只读且是「装不进来」而不是运行期判断（`ToolBox` 构造时就拒绝写工具）。
 *
 * 三种「没给出答案」的形态必须分开显示，它们的含义完全不同：
 * - `doneReason=length`：**预算用尽**，不是失败。`answer` 可能为空，但 `citations` 往往有值
 *   （调用账里叫「查到了这些，但没能在预算内收敛」）；
 * - `interruptedBy=caller`：用户自己停了；
 * - 请求抛错：服务真的坏了（含 429 配额）。
 */
const ASK_PATH = '/ai/agent/ask'

/**
 * 两档预算。**客户端只能收紧**：ai-service 用 `min(请求值, 服务端默认)` 夹住
 * （`AiAgentController.bounded`，默认 4 步 / 6 次调用），所以界面**不能**承诺「6 步」——
 * 传 6 也只会跑 4，那是在骗用户。想真正跑更多步得改服务端默认值。
 *
 * `standard` 传 `null` 表示**不带这个字段**，由服务端自己决定（= 默认 4 步）。
 */
export const AGENT_STEPS = { quick: 3, standard: null }

export const useAgentStore = defineStore('agent', {
  state: () => ({
    running: false,
    /** 原始响应，形状与 `docs/api/README.md` 一致：{answer, citations, doneReason, steps, toolCalls, interruptedBy, usageModel, latencyMs} */
    result: null,
    question: '',
    error: '',
    /** 用户按了停止：与服务端返回的 interruptedBy 分开记（前者是本地事实，后者是服务端的说法） */
    stopped: false,
    startedAt: 0,
  }),
  getters: {
    citations: (s) => s.result?.citations || [],
    steps: (s) => s.result?.steps || [],
    /**
     * 预算用尽（**不是失败**）：界面要显示「查到了这些，但没能在预算内收敛」。
     * `answer` 为空但 `citations` 有值时最容易误解成「服务坏了」，所以单独给一个判据。
     */
    budgetExhausted: (s) => s.result?.doneReason === 'length',
    interruptedByCaller: (s) => s.result?.interruptedBy === 'caller' || s.stopped,
    /** 离线自测：模型是 Fake 时如实说明，别让人以为这是真实模型的判断 */
    offline: (s) => s.result?.usageModel === 'fake',
    /** 有答案可显示（含「只有引用没有正文」这种预算用尽形态） */
    hasSomething: (s) => Boolean(s.result && (s.result.answer || (s.result.citations || []).length)),
  },
  actions: {
    reset() {
      this.abort()
      this.result = null
      this.question = ''
      this.error = ''
      this.stopped = false
    },

    /** 停止等待：服务端只在**步与步之间**检查中断，所以这里说的是「不再等它」，不是「已经取消」 */
    abort() {
      if (this._controller) {
        this._controller.abort()
        this._controller = null
      }
      this.running = false
    },

    async ask(rawQuestion, { depth = 'quick', maxToolCalls } = {}) {
      const question = String(rawQuestion || '').trim()
      if (!question) {
        this.error = '请先写下一个问题'
        return null
      }
      this.abort()
      const controller = new AbortController()
      this._controller = controller

      this.running = true
      this.error = ''
      this.stopped = false
      this.question = question
      this.startedAt = Date.now()
      // 先清空上一次结果：否则界面会拿旧答案配新问题
      this.result = null

      try {
        const body = { question }
        const steps = AGENT_STEPS[depth]
        // `standard` 是 null：不带这个字段，让服务端用它自己的默认值（而不是我们猜一个数）
        if (steps) body.maxSteps = steps
        if (maxToolCalls) body.maxToolCalls = maxToolCalls
        const data = await request(ASK_PATH, {
          method: 'POST',
          body,
          // 多步检索比一次问答慢得多，默认 15s 一定不够
          timeout: 120000,
          signal: controller.signal,
        })
        this.result = data
        return data
      } catch (error) {
        if (controller.signal.aborted) {
          // 主动停止不算失败：如实标成「已停止等待」，不显示成错误
          this.stopped = true
          return null
        }
        if (isAuthError(error)) {
          emit(SESSION_EXPIRED, { error })
        } else if (isRateLimited(error)) {
          emit(TOAST, { type: 'warn', message: '今天的 AI 用量到上限了，明天再来' })
        }
        this.error = error.message || '深挖失败'
        throw error
      } finally {
        if (this._controller === controller) {
          this._controller = null
          this.running = false
        }
      }
    },
  },
})
