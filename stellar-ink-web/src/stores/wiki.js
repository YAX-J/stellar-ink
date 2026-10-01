import { defineStore } from 'pinia'
import { request } from '@/api/client'

/**
 * 文章的知识条目（LLM Wiki，E4-3）。
 *
 * 与其它 store 最大的不同：**它失败时不打扰任何人**。
 *
 * 理由：这些条目是阅读页上的**增强**，不是文章本身。网关抖一下、Python 在跑批、
 * 条目还没建过 —— 任何一种都不该让读者看到一条红色提示，更不该挡住正文。
 * 所以这里的请求一律 `silent: true`，错误吞进 `failed` 里（界面只在**有内容**时才出现）。
 * 这与「写成功之后的刷新失败不得把这次写显示成失败」是同一条口径的另一面：
 * **辅助信息失败，不得损伤主流程**。
 *
 * 反过来，`failed` 也不能当作「没有条目」：界面据此决定显示什么，
 * 两者混起来会让「服务坏了」看起来像「这篇文章没有知识条目」。
 */
export const useWikiStore = defineStore('wiki', {
  state: () => ({
    /** 当前文章的知识条目（按段落序号排序，服务端已排好） */
    claims: [],
    loading: false,
    /** 取数失败：与「没有条目」是两件事，界面上也不该混 */
    failed: false,
    /** 当前对应的文章 id：用来丢弃「切换文章后才回来的旧响应」 */
    postId: null,
  }),
  getters: {
    hasClaims: (s) => s.claims.length > 0,
  },
  actions: {
    /**
     * 取某篇文章的条目。
     *
     * @returns 条目数组（失败或没有时返回空数组，**不抛错**）
     */
    async load(rawPostId) {
      const postId = Number(rawPostId)
      if (!postId) {
        this.reset()
        return []
      }
      this.postId = postId
      this.loading = true
      this.failed = false
      // 先清空：否则切换文章时会拿上一篇的条目配这一篇的正文
      this.claims = []

      try {
        const data = await request(`/ai/wiki/posts/${postId}/claims`, { silent: true })
        if (this.postId !== postId) return [] // 已经不是当前文章了，丢弃这次响应
        this.claims = Array.isArray(data) ? data : []
        return this.claims
      } catch {
        // 静默降级：阅读页不因为「知识条目取不到」而弹提示
        if (this.postId === postId) this.failed = true
        return []
      } finally {
        if (this.postId === postId) this.loading = false
      }
    },

    reset() {
      this.claims = []
      this.loading = false
      this.failed = false
      this.postId = null
    },
  },
})
