<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSearchStore } from '@/stores/search'
import SectionHead from '@/components/common/SectionHead.vue'
import PostCard from '@/components/post/PostCard.vue'
import NoteCard from '@/components/post/NoteCard.vue'

const route = useRoute()
const router = useRouter()
const searchStore = useSearchStore()

const keyword = ref('')
const activeType = ref('all')
const total = computed(() => searchStore.postTotal + searchStore.noteTotal)
const showPosts = computed(() => activeType.value === 'all' || activeType.value === 'post')
const showNotes = computed(() => activeType.value === 'all' || activeType.value === 'note')

function submit() {
  const q = keyword.value.trim()
  router.replace({ name: 'search', query: q ? { q } : {} })
}

function clear() {
  keyword.value = ''
  router.replace({ name: 'search' })
}

function openPost(post) {
  router.push(`/read/${post.id}`)
}

function openNote(note) {
  router.push(`/note/${note.id}`)
}

watch(() => route.query.q, (value) => {
  const q = typeof value === 'string' ? value.trim() : ''
  keyword.value = q
  searchStore.search(q)
}, { immediate: true })
</script>

<template>
  <section class="page page-wide">
    <SectionHead
      title="寻星" kicker="DEEP SPACE SEARCH · 穿过标题与正文"
      :more="searchStore.searched && !searchStore.loading ? `${total} 条回声` : '文章与公开笔记'"
    />

    <form class="search-bar reveal" style="--d:.06s" @submit.prevent="submit">
      <span class="search-glyph" aria-hidden="true">⌕</span>
      <input
        v-model="keyword" type="search" aria-label="搜索关键词"
        placeholder="标题、正文、报错原文、标签"
      >
      <button v-if="keyword" class="clear-btn" type="button" title="清空" @click="clear">×</button>
      <button class="btn btn-primary" type="submit">搜索</button>
    </form>

    <div v-if="searchStore.searched" class="type-tabs reveal" style="--d:.1s">
      <button :class="{ on: activeType === 'all' }" @click="activeType = 'all'">全部 {{ total }}</button>
      <button :class="{ on: activeType === 'post' }" @click="activeType = 'post'">文章 {{ searchStore.postTotal }}</button>
      <button :class="{ on: activeType === 'note' }" @click="activeType = 'note'">笔记 {{ searchStore.noteTotal }}</button>
    </div>

    <p v-if="searchStore.loading" class="state-text">正在穿过星图…</p>
    <p v-else-if="searchStore.error && !total" class="state-text error-text">
      {{ searchStore.error }}
      <button class="state-action" @click="searchStore.search(keyword)">重新搜索</button>
    </p>
    <p v-else-if="searchStore.searched && total === 0" class="empty-state">没有找到同频内容。</p>
    <p v-else-if="!searchStore.searched" class="empty-state">等待一个关键词。</p>

    <template v-if="!searchStore.loading && searchStore.searched && (!searchStore.error || total > 0)">
      <section v-if="showPosts && searchStore.postTotal" class="result-band">
        <SectionHead title="文章" :more="`${searchStore.postTotal} 篇`" />
        <div class="post-grid">
          <PostCard
            v-for="(post, index) in searchStore.posts" :key="post.id"
            :post="post" :delay="Math.min(index * .03, .3)" @open="openPost"
          />
        </div>
        <div v-if="searchStore.postHasMore" class="load-row">
          <button class="btn btn-ghost" :disabled="!!searchStore.loadingMore" @click="searchStore.loadMore('post')">
            {{ searchStore.loadingMore === 'post' ? '正在接收…' : `更多文章 · 还有 ${searchStore.postTotal - searchStore.posts.length} 篇` }}
          </button>
        </div>
      </section>

      <section v-if="showNotes && searchStore.noteTotal" class="result-band">
        <SectionHead title="公开笔记" :more="`${searchStore.noteTotal} 条`" />
        <div class="note-grid">
          <NoteCard v-for="note in searchStore.notes" :key="note.id" :note="note" @open="openNote" />
        </div>
        <div v-if="searchStore.noteHasMore" class="load-row">
          <button class="btn btn-ghost" :disabled="!!searchStore.loadingMore" @click="searchStore.loadMore('note')">
            {{ searchStore.loadingMore === 'note' ? '正在接收…' : `更多笔记 · 还有 ${searchStore.noteTotal - searchStore.notes.length} 条` }}
          </button>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.search-bar{height:58px; display:flex; align-items:center; gap:12px; border:1px solid var(--line);
  border-radius:var(--r-md); background:var(--surface); padding:6px 7px 6px 18px}
.search-bar:focus-within{border-color:var(--primary)}
.search-glyph{font-family:var(--font-mono); color:var(--primary); font-size:22px; line-height:1}
.search-bar input{min-width:0; flex:1; border:0; outline:0; background:transparent; color:var(--ink);
  font:14px var(--font-body)}
.search-bar input::placeholder{color:var(--ink-faint)}
.search-bar .btn{height:44px; flex-shrink:0}
.clear-btn{width:36px; height:36px; border:0; background:transparent; color:var(--ink-faint);
  font-size:20px; cursor:pointer}
.clear-btn:hover{color:var(--ink)}
.type-tabs{display:flex; gap:8px; margin-top:18px; flex-wrap:wrap}
.type-tabs button{border:1px solid var(--line); border-radius:99px; background:var(--surface);
  color:var(--ink-dim); padding:8px 16px; cursor:pointer; font:12px var(--font-mono)}
.type-tabs button.on{border-color:var(--primary); background:var(--primary-soft); color:var(--primary)}
.result-band{margin-top:8px}
.post-grid,.note-grid{display:grid; grid-template-columns:repeat(auto-fill,minmax(290px,1fr)); gap:16px}
.state-text,.empty-state{margin-top:30px; color:var(--ink-faint); font-size:13px; line-height:1.9}
.empty-state{text-align:center; padding:54px 20px; border-top:1px dashed var(--line); border-bottom:1px dashed var(--line)}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.error-text{color:var(--rose)}
.load-row{display:flex; justify-content:center; margin-top:24px}
.load-row .btn:disabled{opacity:.55; cursor:wait}
@media (max-width:560px){
  .search-bar{height:auto; flex-wrap:wrap; padding:10px 12px}
  .search-bar input{min-width:calc(100% - 82px); height:38px}
  .search-bar .btn{width:100%; justify-content:center}
}
</style>
