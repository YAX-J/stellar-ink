import { defineStore } from 'pinia'
import { request, ApiError } from '@/api/client'
import { useAuthorStore } from '@/stores/authors'

/* 笔记类型：与后端 NoteType 枚举一一对应（symbol 与后端 glyph 保持一致） */
export const NOTE_TYPES = [
  { key: 'FIX', label: '问题解决', glyph: '❖', hint: '现象 → 结论，最常用的一类' },
  { key: 'PITFALL', label: '踩坑记录', glyph: '⚠', hint: '做错了什么、代价是什么、以后怎么避' },
  { key: 'TIL', label: '学习笔记', glyph: '✦', hint: '某个机制或原理的理解与推导' },
  { key: 'SCRAP', label: '碎片', glyph: '☄', hint: '还没有结论的观察与线索' },
]

export const NOTE_TYPE_MAP = Object.fromEntries(NOTE_TYPES.map((t) => [t.key, t]))

export const VISIBILITY_OPTIONS = [
  { key: 'PRIVATE', label: '私有', hint: '只有你自己能看到' },
  { key: 'PUBLIC', label: '公开', hint: '出现在笔记列表与技术栈热度里' },
]

/** 新建笔记的默认模板：把「结构」固化成习惯，同时让目录自动生成 */
export const NOTE_TEMPLATE = [
  '## 现象',
  '',
  '（完整贴上报错原文或复现步骤，越原始越好，方便日后搜索命中）',
  '',
  '## 环境',
  '',
  '- 版本 / 依赖：',
  '- 运行环境：',
  '',
  '## 排查',
  '',
  '（走过哪些弯路也记下来 —— 弯路本身能防止二次踩坑）',
  '',
  '## 结论',
  '',
  '（一句话说清怎么解决的，这一段会被当作列表摘要）',
  '',
  '```bash',
  '',
  '```',
  '',
  '## 参考',
  '',
  '- ',
].join('\n')

function normalizeNote(note, detail = false) {
  if (!note) return null
  return {
    ...note,
    id: Number(note.id),
    userId: Number(note.userId),
    words: Number(note.wordCount ?? 0),
    tags: Array.isArray(note.tags) ? note.tags : [],
    viewCount: Number(note.viewCount ?? 0),
    noteType: note.noteType || 'FIX',
    noteTypeLabel: note.noteTypeLabel || NOTE_TYPE_MAP[note.noteType]?.label || '问题解决',
    noteTypeGlyph: note.noteTypeGlyph || NOTE_TYPE_MAP[note.noteType]?.glyph || '❖',
    visibility: note.visibility || 'PRIVATE',
    excerpt: note.summary || '',
    verifiedAt: note.verifiedAt || null,
    ...(detail ? { content: note.content || '', readMinutes: note.readMinutes } : {}),
  }
}

export const useNoteStore = defineStore('notes', {
  state: () => ({
    /* 公开列表 */
    notes: [],
    loading: false,
    /* 我的笔记（含私有与草稿） */
    mine: [],
    mineLoading: false,
    details: {},
    detailLoading: false,
    error: '',
    initialized: false,
    mineInitialized: false,
  }),
  getters: {
    byId: (state) => (id) => state.details[Number(id)] ||
      state.notes.find((note) => note.id === Number(id)) ||
      state.mine.find((note) => note.id === Number(id)),
    /** 技术栈热度：公开笔记按标签聚合，用于列表页分区 */
    byTag: (state) => {
      const map = new Map()
      for (const note of state.notes) {
        for (const tag of note.tags) {
          if (!map.has(tag)) map.set(tag, [])
          map.get(tag).push(note)
        }
      }
      return [...map.entries()]
        .map(([tag, list]) => ({ tag, list }))
        .sort((a, b) => b.list.length - a.list.length || a.tag.localeCompare(b.tag))
    },
  },
  actions: {
    async fetchNotes(query = {}) {
      this.loading = true
      this.error = ''
      try {
        const page = await request('/notes', { query: { page: 1, size: 100, orderBy: 'latest', ...query } })
        const records = page?.records || page?.list || []
        this.notes = records.map((note) => normalizeNote(note))
        await useAuthorStore().ensureAuthors(this.notes.map((note) => note.userId)).catch(() => {})
        this.initialized = true
        return this.notes
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.loading = false
      }
    },

    async ensureLoaded() {
      if (this.initialized) return this.notes
      return this.fetchNotes()
    },

    async fetchMine(query = {}) {
      this.mineLoading = true
      this.error = ''
      try {
        const page = await request('/notes/mine', { query: { page: 1, size: 100, ...query } })
        const records = page?.records || page?.list || []
        this.mine = records.map((note) => normalizeNote(note))
        await useAuthorStore().ensureAuthors(this.mine.map((note) => note.userId)).catch(() => {})
        this.mineInitialized = true
        return this.mine
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.mineLoading = false
      }
    },

    async fetchDetail(id) {
      const noteId = Number(id)
      if (!noteId) return null
      this.detailLoading = true
      this.error = ''
      try {
        const raw = await request(`/notes/${noteId}`)
        const detail = normalizeNote(raw, true)
        /* 接口返回空（例如被 dev 代理回退成 HTML）时不要继续往下走：
         * 之前直接取 detail.userId 会抛 TypeError，把真实原因盖成一句看不懂的报错 */
        if (!detail) {
          throw new ApiError(0, '这条笔记没有返回内容，请稍后重试', 0)
        }
        await useAuthorStore().ensureAuthors([detail.userId]).catch(() => {})
        this.details[noteId] = detail
        return detail
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.detailLoading = false
      }
    },

    /** 保存草稿（自动保存走这里）：返回笔记 id */
    async saveDraft({ id, title, body, tags, noteType, visibility }) {
      this.error = ''
      const payload = {
        title: title?.trim() || '未命名笔记',
        content: body || '',
        tags: tags || [],
        noteType: noteType || 'FIX',
        /* 草稿阶段就记住可见性选择，但状态固定为草稿 */
        visibility: visibility || 'PRIVATE',
        status: 0,
      }
      try {
        if (id) {
          await request(`/notes/${Number(id)}`, { method: 'PUT', body: payload })
          await this.fetchDetail(id)
          return Number(id)
        }
        const data = await request('/notes', { method: 'POST', body: payload })
        const noteId = Number(data?.id)
        if (noteId) await this.fetchDetail(noteId)
        return noteId
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    /** 发布（自动保存只在草稿态发生，发布必须由作者显式触发） */
    async publish({ id, title, body, tags, noteType, visibility }) {
      this.error = ''
      const payload = {
        title: title?.trim() || '未命名笔记',
        content: body?.trim() || '',
        tags: tags || [],
        noteType: noteType || 'FIX',
        visibility: visibility || 'PRIVATE',
        status: 1,
      }
      try {
        if (id) {
          await request(`/notes/${Number(id)}`, { method: 'PUT', body: payload })
          await this.fetchDetail(id)
          return Number(id)
        }
        const data = await request('/notes', { method: 'POST', body: payload })
        const noteId = Number(data?.id)
        if (noteId) await this.fetchDetail(noteId)
        return noteId
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    async remove(id) {
      this.error = ''
      try {
        await request(`/notes/${Number(id)}`, { method: 'DELETE' })
        const noteId = Number(id)
        delete this.details[noteId]
        this.notes = this.notes.filter((note) => note.id !== noteId)
        this.mine = this.mine.filter((note) => note.id !== noteId)
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    /** 标记「结论仍然有效」 */
    async markVerified(id) {
      this.error = ''
      try {
        const data = await request(`/notes/${Number(id)}/verify`, { method: 'PUT' })
        const noteId = Number(id)
        const verifiedAt = data?.verifiedAt || new Date().toISOString()
        if (this.details[noteId]) this.details[noteId].verifiedAt = verifiedAt
        for (const list of [this.notes, this.mine]) {
          const item = list.find((note) => note.id === noteId)
          if (item) item.verifiedAt = verifiedAt
        }
        return verifiedAt
      } catch (error) {
        this.error = error.message
        throw error
      }
    },

    /** 记录一次浏览：登录用户按天去重、作者本人不计，失败静默 */
    async recordView(id) {
      const noteId = Number(id)
      if (!noteId) return
      try {
        const data = await request(`/notes/${noteId}/viewed`, { method: 'POST', silent: true })
        if (data?.counted) {
          const note = this.byId(noteId)
          if (note) note.viewCount = Number(note.viewCount || 0) + 1
        }
      } catch {
        /* 静默：浏览量统计失败不影响阅读 */
      }
    },
  },
})
