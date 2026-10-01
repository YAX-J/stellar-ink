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
    /** 实体与共现关系（E4-7）：**与 claims 分开**，因为它们可以独立失败 */
    entities: [],
    entitiesFailed: false,
    /** 本文参与的主题（E4-10）：同样独立 */
    topics: [],
    topicsFailed: false,
  }),
  getters: {
    hasClaims: (s) => s.claims.length > 0,
    hasEntities: (s) => s.entities.length > 0,
    hasTopics: (s) => s.topics.length > 0,
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
      this.entities = []
      this.entitiesFailed = false
      this.topics = []
      this.topicsFailed = false
    },

    /**
     * 取某篇文章的实体与共现关系（E4-7）。
     *
     * 与 `load` 分开而不是并成一个请求：两件东西**可以独立失败**，展示上也是分开的
     * （有主张没实体、有实体没主张都可能）。合成一个的话，任一边抖一下就会让另一边的内容
     * 也不显示 —— 那是「辅助信息损伤主流程」的另一种形态。
     *
     * @returns 实体数组（失败或没有时返回空数组，**不抛错**）
     */
    async loadEntities(rawPostId) {
      const postId = Number(rawPostId)
      if (!postId) {
        this.entities = []
        return []
      }
      // 与条目用同一个 postId 做「陈旧响应」判据：切换文章后回来的旧响应一律丢弃
      if (!this.postId) this.postId = postId
      this.entities = []
      this.entitiesFailed = false
      try {
        const data = await request(`/ai/wiki/posts/${postId}/entities`, { silent: true })
        if (this.postId !== postId) return []
        this.entities = Array.isArray(data) ? data : []
        return this.entities
      } catch {
        if (this.postId === postId) this.entitiesFailed = true
        return []
      }
    },

    /**
     * 取某篇文章**参与的主题**（E4-10）。
     *
     * 与条目、实体各自独立（三个请求、三套状态）：它们可以分别失败，展示上也分开。
     * 合成一个大请求的诱惑在于「省两个 HTTP」，代价是任一边抖一下就会让整块内容消失 ——
     * 而这三块的重要性并不一样（条目最重要），不该绑在一起。
     *
     * @returns 主题数组（失败或没有时返回空数组，**不抛错**）
     */
    async loadTopics(rawPostId) {
      const postId = Number(rawPostId)
      if (!postId) {
        this.topics = []
        return []
      }
      if (!this.postId) this.postId = postId
      this.topics = []
      this.topicsFailed = false
      try {
        const data = await request(`/ai/wiki/posts/${postId}/topics`, { silent: true })
        if (this.postId !== postId) return []
        this.topics = Array.isArray(data) ? data : []
        return this.topics
      } catch {
        if (this.postId === postId) this.topicsFailed = true
        return []
      }
    },
  },
})
