import { computed, ref } from 'vue'
import { ApiError, getToken, isAuthError, isRateLimited, request } from '@/api/client'
import { emit, SESSION_EXPIRED, TOAST } from '@/utils/bus'
import { readFrames } from '@/utils/sse'

const STREAM_PATH = '/ai/qa/stream'
const ASK_PATH = '/ai/qa'

/**
 * 「星海问答」的流式状态机（**可多实例**）。
 *
 * 为什么是 composable 而不是继续放在 store 里：助手浮层与深读页的面板会**同时**存在，
 * 而 Pinia store 是单一全局实例 —— 两处共用一个 `answer` 时，一边的 `delta`
 * 会写进另一边的界面（表现为「浮层里的字跑到文章页去了」）。每个消费方各持一个实例，
 * 状态天然隔离。（`stores/qa.js` 现在只是本 composable 的一层薄封装，保持既有 API 不变。）
 *
 * 三个口径（与 `docs/api/README.md` 的 SSE 一节一致，不要在这里改）：
 * ① 事件顺序固定 `meta → citation* → delta* → done`，`error` 是旁路（之后不会有 done）；
 * ② **没收到 `done` 就是中断**，要如实说「回答中断了」，不能装作答完了；
 * ③ 「没给出答案」有三种形态：预算用尽 / 用户停止 / 真正失败 —— 只有最后一种进 `error`。
 *
 * @param {{ topK?: number }} [options] 默认召回条数（服务端还会再夹一道）
 */
export function useQaStream(options = {}) {
  const defaultTopK = options.topK ?? 5

  const asking = ref(false)
  const streaming = ref(false)
  /** 形状与非流式一致：`{ answer, citations, doneReason, usage, evidenceSufficient }` */
  const answer = ref(null)
  const question = ref('')
  const error = ref('')
  /** 本次流是否已经收到 `done`（没收到的中断要在界面上说清） */
  const completed = ref(false)
  let controller = null

  /** 证据不足（拒答）：要显示「没有找到依据」，而不是把空答案渲染成空白 */
  const refused = computed(
    () => answer.value !== null && answer.value.evidenceSufficient === false,
  )
  /** 离线自测：模型是 Fake 时如实提示，别让读者以为这是真实模型的回答 */
  const offline = computed(() => answer.value?.usage?.model === 'fake')
  /** 流断了但没收到 done：文案要与「拒答」区分开（一个是没依据，一个是中途出错） */
  const interrupted = computed(
    () => answer.value !== null && !completed.value && answer.value.evidenceSufficient === undefined,
  )
  const answerDone = computed(() => completed.value)

  /** 中止当前流：用户点「停止」、换问题或离开页面时调用 */
  function abort() {
    if (controller) {
      controller.abort()
      controller = null
    }
    streaming.value = false
    asking.value = false
  }

  /** 重置状态；进行中的流一并中止（否则旧流会继续往界面上写） */
  function reset() {
    abort()
    answer.value = null
    question.value = ''
    error.value = ''
    completed.value = false
    streaming.value = false
  }

  /** 处理一帧：把事件拼进 `answer`。**只在这里做状态变更**，界面不解析协议 */
  function applyFrame(frame) {
    const { type, payload } = frame
    if (!answer.value) return
    if (type === 'meta') {
      // 模型标识要如实展示（Fake 时界面会挂「离线自测」）
      answer.value.usage = { ...answer.value.usage, model: payload.model }
    } else if (type === 'citation') {
      answer.value.citations = [...answer.value.citations, payload.citation]
    } else if (type === 'delta') {
      answer.value.answer += payload.text || ''
    } else if (type === 'done') {
      answer.value = {
        answer: payload.answer || answer.value.answer,
        citations: answer.value.citations,
        doneReason: payload.doneReason,
        usage: payload.usage || answer.value.usage,
        evidenceSufficient: payload.evidenceSufficient,
      }
      completed.value = true
    } else if (type === 'error') {
      error.value = payload?.message || 'AI 服务暂时不可用'
    }
  }

  /**
   * 流式提问。
   *
   * @param {string} rawQuestion 这次的问题
   * @param {{ topK?: number, history?: Array<{question: string, answer: string}> }} [opts]
   *   `history` 是**多轮会话的前几轮**（助手浮层传，深读页不传）。
   *   它只是语境的参考：服务端提示词明确要求不得当事实陈述、不得据它编号引用。
   */
  async function askStream(rawQuestion, opts = {}) {
    // 兼容早先的 `askStream(question, 5)`：**数字参数被当成 opts 时不会报错**，
    // 只会静默回退默认 topK —— 那正是最难发现的一类分叉。显式认一下。
    const options = typeof opts === 'number' ? { topK: opts } : opts
    const text = String(rawQuestion || '').trim()
    if (!text) {
      error.value = '请先写下一个问题'
      return null
    }
    abort()
    const current = new AbortController()
    controller = current

    asking.value = true
    streaming.value = true
    error.value = ''
    completed.value = false
    question.value = text
    // 先落一个空壳：界面可以立刻显示「正在检索并生成」，而不是等第一帧
    answer.value = {
      answer: '',
      citations: [],
      doneReason: '',
      usage: {},
      evidenceSufficient: undefined,
    }

    const headers = { 'Content-Type': 'application/json' }
    const token = getToken()
    if (token) headers.Authorization = token

    const body = { question: text, topK: options.topK ?? defaultTopK }
    if (options.history?.length) body.history = options.history

    try {
      const response = await fetch(STREAM_PATH, {
        method: 'POST',
        headers: { ...headers, Accept: 'text/event-stream' },
        body: JSON.stringify(body),
        signal: current.signal,
      })
      if (!response.ok || !response.body) {
        // 必须抛 ApiError：`isAuthError()` / `isRateLimited()` 要求
        // `error instanceof ApiError`，抛普通 Error 会让流式路径的 401/429 **静默失效** ——
        // 表现是「登录过期只显示一句『流式问答不可用』」与「配额触顶不弹提示」，
        // 而同一个用户走非流式（request 那条路）却是正常的。
        throw new ApiError(
          response.status,
          `流式问答不可用（HTTP ${response.status}）`,
          response.status,
        )
      }
      for await (const frame of readFrames(response.body)) {
        applyFrame(frame)
      }
      if (!completed.value && !error.value) {
        // 流结束却没收到 done：必须让用户知道答案可能不完整。
        // ⚠️ 但**服务端明确发过 error 帧**时，那句话才是真因（例如内部验签 401、
        // 上游 429），不能被这句泛化文案覆盖 —— 覆盖之后用户永远看不到「为什么」，
        // 只能看到一个看起来像网络抖动的「中断」。（真实踩过：整条链路的 401
        // 被显示成「回答中断了，内容可能不完整」。）
        error.value = '回答中断了，内容可能不完整'
      }
      return answer.value
    } catch (err) {
      if (current.signal.aborted) {
        // 主动中止不算失败（用户自己按的停止/清空）
        return answer.value
      }
      if (isAuthError(err)) {
        emit(SESSION_EXPIRED, { error: err })
      } else if (isRateLimited(err)) {
        emit(TOAST, { type: 'warn', message: '问得太频繁了，稍等一下再问' })
      }
      error.value = err.message || '提问失败'
      throw err
    } finally {
      if (controller === current) {
        controller = null
        streaming.value = false
        asking.value = false
      }
    }
  }

  /**
   * 一次性提问（降级路径）：只在流式读取不可用时使用。
   * 答案形状与流式一致，界面无需分支。
   */
  async function ask(rawQuestion, topK) {
    const text = String(rawQuestion || '').trim()
    if (!text) {
      error.value = '请先写下一个问题'
      return null
    }
    asking.value = true
    error.value = ''
    try {
      const payload = { question: text }
      if (topK) payload.topK = topK
      // 答案要等检索 + 模型，默认 15s 太紧（尤其接上真模型后）
      const data = await request(ASK_PATH, { method: 'POST', body: payload, timeout: 60000 })
      answer.value = data
      question.value = text
      completed.value = true
      return data
    } catch (err) {
      error.value = err.message
      throw err
    } finally {
      asking.value = false
    }
  }

  return {
    asking,
    streaming,
    answer,
    question,
    error,
    completed,
    refused,
    offline,
    interrupted,
    answerDone,
    askStream,
    ask,
    abort,
    reset,
    applyFrame,
  }
}
