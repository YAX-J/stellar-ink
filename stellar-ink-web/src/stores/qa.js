import { defineStore } from 'pinia'
import { getToken, isAuthError, isRateLimited, request } from '@/api/client'
import { emit, SESSION_EXPIRED, TOAST } from '@/utils/bus'
import { readFrames } from '@/utils/sse'

/**
 * 星海问答：就全站已发布文章提问，答案必须带引用（或明确拒答）。
 *
 * 与 `stores/ai.js` 分开：那是**站长的调试台**（模型配置、评测台，全 ADMIN），
 * 这是**读者功能**。混在一起会让权限判断散落在两处，也容易让读者页面 import 到管理端的东西。
 *
 * 默认走**流式**（`POST /ai/qa/stream`）：引用由检索决定，比正文先到，所以能先渲染引用再等答案。
 * 一次性回答（`POST /ai/qa`）保留为降级路径 —— 浏览器不支持流式读取时用它，
 * 两条路复用同一套 Python 编排，不会给出不同结论。
 */

const STREAM_PATH = '/ai/qa/stream'
const ASK_PATH = '/ai/qa'

export const useQaStore = defineStore('qa', {
  state: () => ({
    asking: false,
    /** 流式进行中（正文还在增长） */
    streaming: false,
    /**
     * 最近一次问答结果，形状与非流式一致：
     * `{ answer, citations, doneReason, usage, evidenceSufficient }`。
     * 流式过程中它是**逐渐拼出来的**：引用先到、正文随后追加。
     */
    answer: null,
    question: '',
    error: '',
    /** 本次流是否已经收到 done（没收到的中断要在界面上说清，不能装作答完了） */
    completed: false,
  }),
  getters: {
    /** 证据不足（拒答）：前端要显示「文章里没有找到依据」，而不是把空答案渲染成空白 */
    refused: (s) => s.answer !== null && s.answer.evidenceSufficient === false,
    /** 离线自测：模型是 Fake 时如实提示，别让读者以为这是真实模型的回答 */
    offline: (s) => s.answer?.usage?.model === 'fake',
    /** 流断了但没收到 done：文案要与「拒答」区分开（一个是没依据，一个是中途出错） */
    interrupted: (s) => s.answer !== null && !s.completed && s.answer.evidenceSufficient === undefined,
    /** 收尾事件到了（`completed` 的只读别名：模板里读状态比读动词清楚） */
    answerDone: (s) => s.completed,
  },
  actions: {
    /** 重置状态；进行中的流一并中止（否则旧流会继续往界面上写） */
    reset() {
      this.abort()
      this.answer = null
      this.question = ''
      this.error = ''
      this.completed = false
      this.streaming = false
    },

    /** 中止当前流：用户点「停止」或换问题时调用 */
    abort() {
      if (this._controller) {
        this._controller.abort()
        this._controller = null
      }
      this.streaming = false
      this.asking = false
    },

    /**
     * 流式提问。
     * 事件顺序固定 `meta → citation* → delta* → done`，`error` 是旁路事件（之后不会有 done）。
     */
    async askStream(rawQuestion, topK = 5) {
      const question = String(rawQuestion || '').trim()
      if (!question) {
        this.error = '请先写下一个问题'
        return null
      }
      this.abort()
      const controller = new AbortController()
      this._controller = controller

      this.asking = true
      this.streaming = true
      this.error = ''
      this.completed = false
      this.question = question
      // 先落一个空壳：界面可以立刻显示「正在检索并生成」，而不是等第一帧
      this.answer = { answer: '', citations: [], doneReason: '', usage: {}, evidenceSufficient: undefined }

      const headers = { 'Content-Type': 'application/json' }
      const token = getToken()
      if (token) headers.Authorization = token

      try {
        const response = await fetch(STREAM_PATH, {
          method: 'POST',
          headers: { ...headers, Accept: 'text/event-stream' },
          body: JSON.stringify({ question, topK }),
          signal: controller.signal,
        })
        if (!response.ok || !response.body) {
          throw new Error(`流式问答不可用（HTTP ${response.status}）`)
        }
        for await (const frame of readFrames(response.body)) {
          this.applyFrame(frame)
        }
        if (!this.completed) {
          // 流结束却没收到 done：必须让用户知道答案可能不完整
          this.error = '回答中断了，内容可能不完整'
        }
        return this.answer
      } catch (error) {
        if (controller.signal.aborted) {
          // 主动中止不算失败（用户自己按的停止/清空）
          return this.answer
        }
        if (isAuthError(error)) {
          emit(SESSION_EXPIRED, { error })
        } else if (isRateLimited(error)) {
          emit(TOAST, { type: 'warn', message: '问得太频繁了，稍等一下再问' })
        }
        this.error = error.message || '提问失败'
        throw error
      } finally {
        if (this._controller === controller) {
          this._controller = null
          this.streaming = false
          this.asking = false
        }
      }
    },

    /** 处理一帧：把事件拼进 `answer`。**只在这里做状态变更**，界面不解析协议 */
    applyFrame(frame) {
      const { type, payload } = frame
      if (!this.answer) return
      if (type === 'meta') {
        // 模型标识要如实展示（Fake 时界面会挂「离线自测」）
        this.answer.usage = { ...this.answer.usage, model: payload.model }
      } else if (type === 'citation') {
        this.answer.citations = [...this.answer.citations, payload.citation]
      } else if (type === 'delta') {
        this.answer.answer += payload.text || ''
      } else if (type === 'done') {
        this.answer = {
          answer: payload.answer || this.answer.answer,
          citations: this.answer.citations,
          doneReason: payload.doneReason,
          usage: payload.usage || this.answer.usage,
          evidenceSufficient: payload.evidenceSufficient,
        }
        this.completed = true
      } else if (type === 'error') {
        this.error = payload?.message || 'AI 服务暂时不可用'
      }
    },

    /**
     * 一次性提问（降级路径）。
     * 只在流式读取不可用时使用；答案形状与流式一致，界面无需分支。
     */
    async ask(rawQuestion, topK) {
      const question = String(rawQuestion || '').trim()
      if (!question) {
        this.error = '请先写下一个问题'
        return null
      }
      this.asking = true
      this.error = ''
      try {
        const payload = { question }
        if (topK) payload.topK = topK
        // 答案要等检索 + 模型，默认 15s 太紧（尤其接上真模型后）
        const data = await request(ASK_PATH, { method: 'POST', body: payload, timeout: 60000 })
        this.answer = data
        this.question = question
        this.completed = true
        return data
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.asking = false
      }
    },
  },
})
