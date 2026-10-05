import { computed, ref } from 'vue'
import { isAuthError, isRateLimited, request } from '@/api/client'
import { emit, SESSION_EXPIRED, TOAST } from '@/utils/bus'

/**
 * 深挖（只读 Agent）的可多实例状态机。
 *
 * 为什么不直接用 `stores/agent.js`：深读页的深挖面板与右下角助手浮层会**同时存在**，
 * 共用一个 store 时一边的结果会写进另一边的界面 —— 与 `useQaStream` 当初从
 * `stores/qa.js` 抽出来是同一个理由。`stores/agent.js` 仍是深读页在用的那份，暂不动它。
 *
 * 三种「没给出答案」的形态必须分开显示（含义完全不同）：
 * - `doneReason=length`：**预算用尽**，不是失败。`answer` 可能为空但 `citations` 往往有值
 *   （调用账里叫「查到了这些，但没能在预算内收敛」）；
 * - `interruptedBy=caller`：用户自己停了；
 * - 请求抛错：服务真的坏了（含 429 配额）。
 */
const ASK_PATH = '/ai/agent/ask'

/**
 * 两档预算。**客户端只能收紧**：服务端用 `min(请求值, 默认)` 夹住（默认 4 步 / 6 次调用），
 * 所以界面**不能**承诺「6 步」——传 6 也只跑 4，那是在骗用户。
 * `standard` 传 `null` 表示不带这个字段，由服务端决定。
 */
export const AGENT_STEPS = { quick: 3, standard: null }

export function useAgent() {
  const running = ref(false)
  /** 原始响应：`{agent, answer, citations, doneReason, steps, toolCalls, interruptedBy, usageModel, latencyMs}` */
  const result = ref(null)
  const question = ref('')
  const error = ref('')
  /** 用户按了停止：与服务端返回的 `interruptedBy` 分开记（前者是本地事实，后者是服务端的说法） */
  const stopped = ref(false)

  let controller = null

  const agent = computed(() => result.value?.agent || '')
  const citations = computed(() => result.value?.citations || [])
  const steps = computed(() => result.value?.steps || [])
  const budgetExhausted = computed(() => result.value?.doneReason === 'length')
  const interruptedByCaller = computed(
    () => result.value?.interruptedBy === 'caller' || stopped.value,
  )
  /** 离线自测：模型是 Fake 时如实说明（字段名与 RAG 那条链不同：这里是 `usageModel`） */
  const offline = computed(() => result.value?.usageModel === 'fake')
  /** 有东西可显示（含"只有引用没有正文"这种预算用尽形态） */
  const hasSomething = computed(() =>
    Boolean(result.value && (result.value.answer || (result.value.citations || []).length)),
  )

  /** 停止等待：服务端只在**步与步之间**检查中断，所以这里说的是「不再等它」，不是「已经取消」 */
  function abort() {
    if (controller) {
      controller.abort()
      controller = null
    }
    running.value = false
  }

  function reset() {
    abort()
    result.value = null
    question.value = ''
    error.value = ''
    stopped.value = false
  }

  /**
   * @param agentName 司职名（`answerer` / `searcher` / `verifier`）；留空由服务端用它自己的默认司职
   */
  async function ask(rawQuestion, { agent: agentName = '', depth = 'quick', maxToolCalls } = {}) {
    const text = String(rawQuestion || '').trim()
    if (!text) {
      error.value = '请先写下一个问题'
      return null
    }
    abort()
    const local = new AbortController()
    controller = local

    running.value = true
    error.value = ''
    stopped.value = false
    question.value = text
    // 先清空上一次结果：否则界面会拿旧答案配新问题
    result.value = null

    try {
      const body = { question: text }
      if (agentName) body.agent = agentName
      const steps = AGENT_STEPS[depth]
      // `standard` 是 null：不带这个字段，让服务端用它自己的默认值（而不是我们猜一个数）
      if (steps) body.maxSteps = steps
      if (maxToolCalls) body.maxToolCalls = maxToolCalls
      const data = await request(ASK_PATH, {
        method: 'POST',
        body,
        // 多步检索比一次问答慢得多，默认 15s 一定不够
        timeout: 120000,
        signal: local.signal,
      })
      result.value = data
      return data
    } catch (err) {
      if (local.signal.aborted) {
        // 主动停止不算失败：如实标成「已停止等待」，不显示成错误
        stopped.value = true
        return null
      }
      if (isAuthError(err)) {
        emit(SESSION_EXPIRED, { error: err })
      } else if (isRateLimited(err)) {
        emit(TOAST, { type: 'warn', message: '今天的 AI 用量到上限了，明天再来' })
      }
      error.value = err.message || '深挖失败'
      throw err
    } finally {
      if (controller === local) {
        controller = null
        running.value = false
      }
    }
  }

  return {
    running,
    result,
    question,
    error,
    stopped,
    agent,
    citations,
    steps,
    budgetExhausted,
    interruptedByCaller,
    offline,
    hasSomething,
    ask,
    abort,
    reset,
  }
}
