import { defineStore } from 'pinia'
import { request } from '@/api/client'

/**
 * 星笺 Copilot：作者的写作建议（**只给候选，不改正文**）。
 *
 * 与 `stores/qa.js` 分开：那个面向读者（问全站文章），这个面向作者（问自己正在写的草稿）。
 * 与 `stores/ai.js` 分开：那是站长的调试台（模型配置 / 评测台，全 ADMIN）。
 *
 * 红线（`development-workflow.md` §7.4）：**这里没有任何写入动作**。
 * `suggest` 只把候选拿回来；正文要等作者在差异预览里点「采纳」，
 * 并且仍然由页面调用既有的草稿 / 发布接口落库。
 */

/** 任务类型：与 Python `WritingTask`、Java `WritingTask` 逐字一致 */
export const COPILOT_TASKS = [
  { key: 'polish', label: '润色', hint: '保留原意，只改表达' },
  { key: 'continue', label: '续写', hint: '顺着往下写一两段' },
  { key: 'outline', label: '提纲', hint: '把已有内容整理成要点' },
  { key: 'title', label: '标题', hint: '不超过 20 字' },
  { key: 'tags', label: '标签', hint: '3-5 个词，逗号分隔' },
  { key: 'summary', label: '摘要', hint: '一两句话概括' },
]

/** 风格目标：与 Python `WritingTone` 一致 */
export const COPILOT_TONES = [
  { key: 'keep', label: '保持原味' },
  { key: 'restrained', label: '更克制' },
  { key: 'colloquial', label: '更口语' },
  { key: 'concise', label: '更简' },
]

/** 真模型给 5 条候选 + 长草稿可能几十秒，默认 15s 会被前端自己 abort */
export const COPILOT_TIMEOUT_MS = 90000

export const useCopilotStore = defineStore('copilot', {
  state: () => ({
    running: false,
    /** 最近一次建议结果（task / candidates / usage） */
    result: null,
    /** 本次请求对应的任务与草稿快照：候选展示的是**当时**的草稿差异 */
    requestedTask: '',
    requestedDraft: '',
    error: '',
    /* ---- 写作风格画像（E1，只读）---- */
    /** `{ authorId, evidenceSufficient, profile, notes }`；未加载时为 null */
    style: null,
    styleLoading: false,
    styleError: '',
  }),
  getters: {
    candidates: (s) => s.result?.candidates || [],
    /** 离线桩：Fake 不会真的改写，必须如实标注，别让作者以为这是模型的建议 */
    offline: (s) => s.result?.usage?.model === 'fake-copilot'
      || s.result?.usage?.model === 'fake',
    /** 样本够才有画像；不够时界面显示 `notes` 里那句人话，而不是一堆 0 */
    hasStyle: (s) => s.style?.evidenceSufficient === true && !!s.style?.profile,
  },
  actions: {
    /**
     * 请求候选。
     * @param {{ task: string, draft?: string, tone?: string, instruction?: string, candidateCount?: number }} payload
     */
    async suggest(payload) {
      const task = String(payload?.task || '').trim()
      const meta = COPILOT_TASKS.find((item) => item.key === task)
      const draft = String(payload?.draft ?? '')
      // 本地先挡一道（省一次往返）：空草稿在任何任务上只会换来空话。
      // 注意两个早退分支都要把**上一次的候选清掉** —— 留着旧结果配着新报错，
      // 看起来就像「这次的建议」其实是上一轮的。
      if (!meta) {
        this.fail('先选一个功能（润色 / 续写 / 提纲…）', task, draft)
        return null
      }
      if (!draft.trim()) {
        this.fail('编辑区还是空的：先写下一点内容，再让它给建议', task, draft)
        return null
      }

      this.running = true
      this.error = ''
      try {
        const body = {
          task,
          draft,
          tone: payload.tone || 'keep',
          candidateCount: payload.candidateCount || 3,
        }
        const instruction = String(payload.instruction || '').trim()
        if (instruction) body.instruction = instruction
        const data = await request('/ai/writing/suggest', {
          method: 'POST',
          body,
          timeout: COPILOT_TIMEOUT_MS,
        })
        this.result = data
        this.requestedTask = task
        this.requestedDraft = draft
        return data
      } catch (error) {
        // 失败时同样清掉旧候选：让人以为「这就是这次的建议」比报错更糟
        this.fail(error.message, task, draft)
        throw error
      } finally {
        this.running = false
      }
    },

    /** 统一的失败落点：记录错误与请求快照，并丢弃上一次的候选 */
    fail(message, task, draft) {
      this.result = null
      this.requestedTask = task || ''
      this.requestedDraft = draft || ''
      this.error = message
    },

    clear() {
      this.result = null
      this.requestedTask = ''
      this.requestedDraft = ''
      this.error = ''
    },

    /* ======================= 写作风格画像（E1） ======================= */

    /**
     * 读当前登录作者的写作画像。
     *
     * 画像**只读**：服务端现算、不落库，所以这里没有失效/清理的问题，重复调用无副作用。
     * 加载失败**不清空已显示的画像**：写作时看到一次网络抖动就丢掉上下文，
     * 比继续显示上一次的数据更烦人（而画像本来就不会突然变错）。
     */
    async loadStyle(maxSamples) {
      this.styleLoading = true
      this.styleError = ''
      try {
        const payload = {}
        if (maxSamples) payload.maxSamples = maxSamples
        const data = await request('/ai/writing/style', { method: 'POST', body: payload })
        this.style = data
        return data
      } catch (error) {
        this.styleError = error.message
        throw error
      } finally {
        this.styleLoading = false
      }
    },
  },
})
