import { defineStore } from 'pinia'
import { request } from '@/api/client'

/**
 * 作者记忆（M9-4）。
 *
 * 与 Wiki store 的关键区别：**这里的失败必须说出来**。
 *
 * 知识条目是阅读页的增强，取不到就整块不出现；而记忆是用户**自己要求管理**的东西 ——
 * 他点了「查看记忆」却什么都没看到时，必须能分清是「还没有记忆」还是「服务坏了」。
 * 混起来的后果是：他以为记忆丢了，或者以为自己从没记过东西。
 *
 * 三条口径：
 * 1. **待确认（pending）与已生效（active）分开两份列表**：它们要做的事完全不同
 *    （一份是「要不要记住」，一份是「要不要关掉/删掉」）；
 * 2. **冲突单列**：确认时如果有冲突，它**没有被写进去**，界面必须显示两地正文让人选，
 *    否则用户会以为「已保存 N 条」= 候选全被接受了；
 * 3. **写成功之后就地在本地更新**（列表里改状态、删行），再 best-effort 刷新 ——
 *    刷新失败绝不能把这次写显示成失败（AGENTS §4 的那条口径）。
 */

/** 记忆类型的中文标签：与后端的 preference/fact/decision 一一对应。 */
export const MEMORY_KIND_LABELS = {
  preference: '偏好',
  fact: '事实',
  decision: '决定',
}

/** 状态的中文标签：四档分开，界面不把它们混成「有效/无效」。 */
export const MEMORY_STATUS_LABELS = {
  pending: '待确认',
  active: '生效中',
  disabled: '已禁用',
  deleted: '已删除',
}

export function kindLabel(kind) {
  return MEMORY_KIND_LABELS[kind] || kind || '未知'
}

export function statusLabel(status) {
  return MEMORY_STATUS_LABELS[status] || status || '未知'
}

/** 证据的一句话说明：区分「回到原文」与「用户确认」——前者可核对，后者只能算「你说过」。 */
export function evidenceLabel(item) {
  if (!item) return ''
  if (item.kind === 'user') return `你确认过：${item.ref}`
  return `原文：${item.ref}`
}

export const useMemoryStore = defineStore('memory', {
  state: () => ({
    /** 已生效 + 已禁用（后端默认不返回已删除） */
    memories: [],
    /** 待确认的候选（同一个接口按 status 过滤，分开存是为了界面各管一块） */
    pending: [],
    loading: false,
    /** 取数失败：与「没有记忆」是两件事，界面必须分开说 */
    failed: false,
    /** 上一次操作的提示（成功/冲突说明），显示在面板里而不是一次性 toast */
    notice: '',
    /** 需要用户决定的冲突（确认接口返回），**没有被写进去** */
    conflicts: [],
    /** 风格画像（M9-3b）：null = 还没生成过 */
    styleProfile: null,
    styleFailed: false,
  }),
  getters: {
    hasMemories: (s) => s.memories.length > 0,
    hasPending: (s) => s.pending.length > 0,
    hasConflicts: (s) => s.conflicts.length > 0,
  },
  actions: {
    /**
     * 拉两份列表（生效中/已禁用 与 待确认）。
     *
     * 两个请求而不是一个：它们可以独立失败，而且成功一个就能显示一半 ——
     * 合成一个的话，「待确认取不到」会让「我的记忆」也整块空掉。
     */
    async load() {
      this.loading = true
      this.failed = false
      try {
        const [memories, pending] = await Promise.all([
          request('/ai/memory/list', { silent: true }),
          request('/ai/memory/list?status=pending', { silent: true }),
        ])
        this.memories = Array.isArray(memories) ? memories : []
        this.pending = Array.isArray(pending) ? pending : []
      } catch {
        // 这里**不静默**：用户主动来看记忆，取不到要说出来
        this.failed = true
        this.memories = []
        this.pending = []
      } finally {
        this.loading = false
      }
    },

    /**
     * 从一段对话里抽候选（落成待确认）。
     *
     * @returns `{kept, proposed, dropped, notes}`；失败返回 null（由界面提示）
     */
    async extract(conversation, maxCandidates = 5) {
      try {
        const data = await request('/ai/memory/extract', {
          method: 'POST',
          body: { conversation, maxCandidates },
        })
        this.notice = describeExtract(data)
        await this.load()
        return data
      } catch (error) {
        this.notice = error?.message ? `抽取失败：${error.message}` : '抽取失败'
        return null
      }
    },

    /**
     * 确认待确认的记忆。
     *
     * 冲突**不会**被写进去：它们回到 `conflicts` 里由用户决定 ——
     * 界面若只显示「已保存 N 条」，用户会以为候选全被接受了。
     */
    async confirm(memoryIds = []) {
      try {
        const data = await request('/ai/memory/confirm', {
          method: 'POST',
          body: { memoryIds },
        })
        this.conflicts = Array.isArray(data?.conflicts) ? data.conflicts : []
        const added = data?.added || 0
        const merged = data?.merged || 0
        this.notice = this.conflicts.length
          ? `已保存 ${added} 条、合并 ${merged} 条；另有 ${this.conflicts.length} 条与已有记忆冲突，需要你选一个。`
          : `已保存 ${added} 条、合并 ${merged} 条。`
        await this.load()
        return data
      } catch (error) {
        this.notice = error?.message ? `确认失败：${error.message}` : '确认失败'
        return null
      }
    },

    /** 启用/禁用：先就地改本地那条，再 best-effort 刷新（刷新失败不算写失败）。 */
    async setStatus(memoryId, status) {
      const target = this.memories.find((item) => item.id === memoryId)
      const previous = target?.status
      if (target) target.status = status
      try {
        await request(`/ai/memory/${memoryId}/status?status=${encodeURIComponent(status)}`, {
          method: 'PUT',
          silent: true,
        })
        await this.load()
        return true
      } catch (error) {
        if (target) target.status = previous // 写失败要回滚本地，否则界面在说谎
        this.notice = error?.message ? `操作失败：${error.message}` : '操作失败'
        return false
      }
    },

    /** 删除：先从列表里拿掉那条（写成功即生效），失败则放回去。 */
    async remove(memoryId) {
      const index = this.memories.findIndex((item) => item.id === memoryId)
      const removed = index >= 0 ? this.memories.splice(index, 1)[0] : null
      try {
        await request(`/ai/memory/${memoryId}`, { method: 'DELETE', silent: true })
        this.notice = '这条记忆已删除（连同它的证据与派生画像）。'
        await this.load()
        return true
      } catch (error) {
        if (removed) this.memories.splice(index, 0, removed)
        this.notice = error?.message ? `删除失败：${error.message}` : '删除失败'
        return false
      }
    },

    /** 全部清除：**不可撤销**，所以由界面先确认再调用。 */
    async clearAll() {
      try {
        const data = await request('/ai/memory/clear', { method: 'POST', silent: true })
        this.notice = `已清除 ${data?.removed || 0} 条记忆，派生画像也一并清掉了。`
        this.conflicts = []
        await this.load()
        return true
      } catch (error) {
        this.notice = error?.message ? `清除失败：${error.message}` : '清除失败'
        return false
      }
    },

    /**
     * 取最新一版风格画像（M9-3b）。
     *
     * `null` 与「失败」分开：前者是「还没生成过」（可以点刷新），后者是服务坏了。
     */
    async loadStyleProfile() {
      this.styleFailed = false
      try {
        this.styleProfile = await request('/ai/memory/style-profile', { silent: true })
      } catch {
        this.styleProfile = null
        this.styleFailed = true
      }
    },

    async refreshStyleProfile() {
      try {
        this.styleProfile = await request('/ai/memory/style-profile/refresh', {
          method: 'POST',
          silent: true,
        })
        this.styleFailed = false
        this.notice = `风格画像已更新（第 ${this.styleProfile?.version ?? '?'} 版）。`
        return true
      } catch (error) {
        // 「样本不足」是可操作提示，必须原样透出（不是「生成失败，请稍后重试」）
        this.notice = error?.message ? `刷新失败：${error.message}` : '刷新失败'
        return false
      }
    },

    reset() {
      this.memories = []
      this.pending = []
      this.loading = false
      this.failed = false
      this.notice = ''
      this.conflicts = []
      this.styleProfile = null
      this.styleFailed = false
    },
  },
})

/**
 * 抽取结果的说明。
 *
 * `dropped` 必须显示出来：它区分「模型没提出」与「提出了但出处对不上」——
 * 后者的处置是改提示词或换模型，与前者的「再聊聊看」完全不同。
 */
export function describeExtract(data) {
  if (!data) return ''
  const kept = data.kept || 0
  const proposed = data.proposed || 0
  const dropped = data.dropped || {}
  const reasons = Object.keys(dropped)
  if (!reasons.length) {
    return `提出 ${proposed} 条，留下 ${kept} 条待确认。`
  }
  return `提出 ${proposed} 条，留下 ${kept} 条；丢弃 ${reasons
    .map((key) => `${key} ${dropped[key]} 条`)
    .join('、')}。`
}
