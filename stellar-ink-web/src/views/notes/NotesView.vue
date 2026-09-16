<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useNoteStore, NOTE_TYPES } from '@/stores/notes'
import { useAuthStore } from '@/stores/auth'
import SectionHead from '@/components/common/SectionHead.vue'
import NoteCard from '@/components/post/NoteCard.vue'

const route = useRoute()
const router = useRouter()
const noteStore = useNoteStore()
const auth = useAuthStore()

const keyword = ref(typeof route.query.q === 'string' ? route.query.q : '')
const activeTag = ref(typeof route.query.tag === 'string' ? route.query.tag : '')
const activeType = ref(typeof route.query.type === 'string' ? route.query.type : '')
const view = ref('heat')

const canWrite = computed(() => auth.isLoggedIn && auth.isAuthorOrAbove)

/** 技术栈标签计数：只用公开笔记，私有笔记不参与聚合并展示 */
const tagCounts = computed(() => {
  const counts = new Map()
  for (const note of noteStore.notes) {
    for (const tag of note.tags) counts.set(tag, (counts.get(tag) || 0) + 1)
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
})

const filtered = computed(() => {
  return noteStore.notes.filter((note) => {
    if (activeTag.value && !note.tags.includes(activeTag.value)) return false
    if (activeType.value && note.noteType !== activeType.value) return false
    return true
  })
})

/** 热度视图：按技术栈分区，每个技术栈内按最新排序 */
const heatGroups = computed(() => {
  const groups = new Map()
  for (const note of filtered.value) {
    const tags = note.tags.length ? note.tags : ['未归类']
    for (const tag of tags) {
      if (!groups.has(tag)) groups.set(tag, [])
      groups.get(tag).push(note)
    }
  }
  return [...groups.entries()]
    .map(([tag, list]) => ({ tag, list }))
    .sort((a, b) => b.list.length - a.list.length || a.tag.localeCompare(b.tag))
})

const hasFilter = computed(() => !!(activeTag.value || activeType.value || keyword.value.trim()))

/* 筛选状态进 URL：可分享、可刷新、可用浏览器前进后退 */
function syncQuery() {
  const query = {}
  if (keyword.value.trim()) query.q = keyword.value.trim()
  if (activeTag.value) query.tag = activeTag.value
  if (activeType.value) query.type = activeType.value
  router.replace({ name: 'notes', query })
}

function toggleTag(tag) {
  activeTag.value = activeTag.value === tag ? '' : tag
  syncQuery()
}
function toggleType(key) {
  activeType.value = activeType.value === key ? '' : key
  syncQuery()
}
function resetFilter() {
  keyword.value = ''
  activeTag.value = ''
  activeType.value = ''
  syncQuery()
}
function openNote(note) {
  router.push(`/note/${note.id}`)
}

/* 从浏览器前进/后退或别的页面跳回来时，同步 URL 上的筛选条件 */
watch(() => route.query, (query) => {
  const q = typeof query.q === 'string' ? query.q : ''
  const tag = typeof query.tag === 'string' ? query.tag : ''
  const type = typeof query.type === 'string' ? query.type : ''
  if (q !== keyword.value) keyword.value = q
  if (tag !== activeTag.value) activeTag.value = tag
  if (type !== activeType.value) activeType.value = type
  noteStore.fetchNotes({ keyword: q, tag, noteType: type }).catch(() => {})
}, { immediate: true })
</script>

<template>
  <section class="page page-wide">
    <div class="kicker reveal">TECH NOTES · 程序员的标本册</div>
    <SectionHead title="技术笔记" more="不是文章，是能照着做的结论" />

    <div class="notes-bar reveal" style="--d:.06s">
      <input
        v-model="keyword" class="notes-search" type="search"
        placeholder="搜报错原文、类名、命令…（回车生效）"
        @keydown.enter="syncQuery()" @search="syncQuery()"
      >
      <div class="view-switch">
        <button :class="{ on: view === 'heat' }" @click="view = 'heat'">❖ 技术栈热度</button>
        <button :class="{ on: view === 'list' }" @click="view = 'list'">☰ 全部</button>
      </div>
      <RouterLink v-if="canWrite" class="btn btn-primary write-note" to="/note/edit">✎ 写一条笔记</RouterLink>
      <RouterLink v-if="auth.isLoggedIn" class="btn btn-ghost" to="/notes/mine">我的笔记</RouterLink>
    </div>

    <!-- 技术栈光谱：宽度即笔记条数，点击筛选 -->
    <div v-if="tagCounts.length" class="spec-bar reveal" style="--d:.1s">
      <i
        v-for="[tag, count] in tagCounts" :key="tag"
        :style="{ flexGrow: count, opacity: activeTag && activeTag !== tag ? .35 : 1 }"
        :title="`${tag} · ${count} 条`" @click="toggleTag(tag)"
      ></i>
    </div>

    <div class="type-row reveal" style="--d:.12s">
      <button
        v-for="t in NOTE_TYPES" :key="t.key"
        class="type-chip" :class="{ on: activeType === t.key }"
        :title="t.hint" @click="toggleType(t.key)"
      >{{ t.glyph }} {{ t.label }}</button>
      <span v-if="hasFilter" class="filter-reset" @click="resetFilter">清除筛选 ×</span>
    </div>

    <p v-if="noteStore.loading && !noteStore.notes.length" class="state-text">正在读取笔记…</p>
    <p v-else-if="noteStore.error && !noteStore.notes.length" class="state-text error-text">
      {{ noteStore.error }} <button class="state-action" @click="noteStore.fetchNotes()">重新读取</button>
    </p>

    <!-- 热度视图：每个技术栈一个分区，重叠的笔记会出现在多个分区里 -->
    <template v-else-if="view === 'heat' && !hasFilter">
      <p v-if="!heatGroups.length" class="state-text">
        还没有公开笔记。
        <template v-if="canWrite">去 <RouterLink class="state-link" to="/note/edit">写第一条</RouterLink>，把踩过的坑留下来。</template>
      </p>
      <div v-for="group in heatGroups" :key="group.tag" class="heat-group reveal">
        <SectionHead :title="group.tag" :more="`${group.list.length} 条`" />
        <div class="note-grid">
          <NoteCard v-for="n in group.list" :key="`${group.tag}-${n.id}`" :note="n" @open="openNote" />
        </div>
      </div>
    </template>

    <!-- 列表视图 / 有筛选时：直接给结果 -->
    <template v-else>
      <div class="note-grid reveal" style="--d:.14s">
        <NoteCard v-for="n in filtered" :key="n.id" :note="n" @open="openNote" />
      </div>
      <p v-if="!filtered.length && !noteStore.loading" class="state-text">
        没有匹配的笔记。
        <button class="state-action" @click="resetFilter">清除筛选</button>
      </p>
    </template>
    <div v-if="noteStore.hasMore" class="load-row">
      <button class="btn btn-ghost" :disabled="noteStore.loading" @click="noteStore.loadMore()">
        {{ noteStore.loading ? '正在翻阅…' : (activeTag ? '继续翻阅匹配标签' : `继续翻阅 · 还有 ${noteStore.total - noteStore.notes.length} 条`) }}
      </button>
    </div>
  </section>
</template>

<style scoped>
/* 整页布局：宽度由全局 .page-wide 决定（铺满导航栏右侧），此处不再各自设上限 */
.notes-bar{display:flex; gap:12px; align-items:center; flex-wrap:wrap; margin-bottom:20px}
.notes-search{flex:1; min-width:240px; height:48px; border:1px solid var(--line);
  border-radius:var(--r-md); background:var(--surface); color:var(--ink); padding:0 18px;
  font-size:14px; outline:none; font-family:var(--font-body); transition:border-color .2s}
.notes-search:focus{border-color:var(--primary)}
.view-switch{display:flex; border:1px solid var(--line); border-radius:99px; overflow:hidden}
.view-switch button{border:none; background:transparent; color:var(--ink-faint); padding:10px 18px;
  font-size:13px; cursor:pointer; transition:all .25s; font-family:var(--font-body)}
.view-switch button.on{background:var(--primary); color:#fff}
.write-note{height:48px}

.spec-bar{display:flex; height:12px; border-radius:99px; overflow:hidden; margin-bottom:22px;
  border:1px solid var(--line)}
.spec-bar i{height:100%; cursor:pointer; transition:opacity .25s, filter .2s;
  background:linear-gradient(90deg,var(--primary),var(--teal))}
.spec-bar i:hover{filter:brightness(1.3)}

.type-row{display:flex; gap:10px; flex-wrap:wrap; margin-bottom:26px; align-items:center}
.type-chip{border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  border-radius:99px; padding:8px 16px; font-size:13px; cursor:pointer;
  transition:all .25s var(--ease-spring); font-family:var(--font-body)}
.type-chip:hover{transform:translateY(-2px); color:var(--ink)}
.type-chip.on{background:var(--primary-soft); border-color:var(--primary); color:var(--primary)}
.filter-reset{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint);
  cursor:pointer; letter-spacing:.1em; margin-left:6px}
.filter-reset:hover{color:var(--primary)}

.heat-group{margin-bottom:10px}
.note-grid{display:grid; grid-template-columns:repeat(auto-fill,minmax(300px,1fr)); gap:16px}
.state-text{color:var(--ink-faint); font-size:13px; line-height:1.9}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.state-link{color:var(--primary); text-decoration:none}
.error-text{color:var(--rose)}
.load-row{display:flex; justify-content:center; margin-top:26px}
.load-row .btn:disabled{opacity:.55; cursor:wait}
</style>
