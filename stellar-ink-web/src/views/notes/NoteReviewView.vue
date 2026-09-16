<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useNoteStore, NOTE_TYPES, VISIBILITY_OPTIONS } from '@/stores/notes'
import { useAuthStore } from '@/stores/auth'
import { emit, TOAST } from '@/utils/bus'
import SectionHead from '@/components/common/SectionHead.vue'

const router = useRouter()
const noteStore = useNoteStore()
const auth = useAuthStore()

const reviewState = ref('DUE')
const visibility = ref('all')
const noteType = ref('all')
const keyword = ref('')
const appliedKeyword = ref('')
const verifyingId = ref(null)

const canReview = computed(() => auth.isLoggedIn && auth.isAuthorOrAbove)
const reviewOptions = [
  { key: 'DUE', label: '待复核' },
  { key: 'UNVERIFIED', label: '未验证' },
  { key: 'EXPIRED', label: '已过期' },
  { key: 'FRESH', label: '有效期内' },
]

function load() {
  if (!canReview.value) return
  return noteStore.fetchReview({
    reviewState: reviewState.value,
    visibility: visibility.value === 'all' ? '' : visibility.value,
    noteType: noteType.value === 'all' ? '' : noteType.value,
    keyword: appliedKeyword.value,
  }).catch(() => {})
}

function search() {
  appliedKeyword.value = keyword.value.trim()
  load()
}

function clearSearch() {
  keyword.value = ''
  appliedKeyword.value = ''
  load()
}

function statusLabel(note) {
  if (note.reviewState === 'UNVERIFIED') return '从未验证'
  if (note.reviewState === 'EXPIRED') return `到期于 ${formatDate(note.reviewDueAt)}`
  return `有效至 ${formatDate(note.reviewDueAt)}`
}

function formatDate(value) {
  if (!value) return '未知日期'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date(value))
}

async function markVerified(note) {
  if (verifyingId.value) return
  verifyingId.value = note.id
  try {
    await noteStore.markVerified(note.id)
    /* 待复核集合变小后从第一页重取，避免继续加载时因页码偏移漏掉一条记录。 */
    if (reviewState.value !== 'FRESH') await load()
    emit(TOAST, { type: 'success', message: `《${note.title}》已续期 180 天` })
  } catch {
    /* 全局 toast 已提示 */
  } finally {
    verifyingId.value = null
  }
}

watch(
  () => [canReview.value, reviewState.value, visibility.value, noteType.value],
  load,
  { immediate: true },
)
</script>

<template>
  <section class="page page-wide">
    <div class="kicker reveal">NOTE REVIEW · 结论保鲜</div>
    <SectionHead title="笔记复核" :more="`${noteStore.reviewTotal} 条符合当前条件`" />

    <div v-if="!canReview" class="empty reveal">
      <div class="empty-glyph">✓</div>
      <p>复核中心只对作者开放。</p>
      <RouterLink class="btn btn-ghost" to="/notes">返回公开笔记</RouterLink>
    </div>

    <template v-else>
      <div class="review-tools reveal" style="--d:.06s">
        <div class="state-tabs" aria-label="复核状态">
          <button
            v-for="option in reviewOptions" :key="option.key"
            class="state-tab" :class="{ on: reviewState === option.key }"
            @click="reviewState = option.key"
          >{{ option.label }}</button>
        </div>

        <form class="review-search" @submit.prevent="search">
          <input v-model="keyword" type="search" maxlength="100" placeholder="搜索标题或正文" aria-label="搜索笔记">
          <button v-if="appliedKeyword" class="clear-btn" type="button" title="清除搜索" @click="clearSearch">×</button>
          <button class="btn btn-ghost" type="submit">搜索</button>
        </form>

        <div class="filter-row">
          <select v-model="visibility" class="filter-select" aria-label="可见性">
            <option value="all">全部可见性</option>
            <option v-for="option in VISIBILITY_OPTIONS" :key="option.key" :value="option.key">
              {{ option.label }}
            </option>
          </select>
          <select v-model="noteType" class="filter-select" aria-label="笔记类型">
            <option value="all">全部类型</option>
            <option v-for="option in NOTE_TYPES" :key="option.key" :value="option.key">
              {{ option.glyph }} {{ option.label }}
            </option>
          </select>
          <RouterLink class="btn btn-primary" to="/note/edit">✎ 新建笔记</RouterLink>
        </div>
      </div>

      <p v-if="noteStore.reviewLoading && !noteStore.reviewNotes.length" class="state-text">正在检查结论时效…</p>
      <p v-else-if="noteStore.error && !noteStore.reviewNotes.length" class="state-text error-text">
        {{ noteStore.error }} <button class="state-action" @click="load">重新读取</button>
      </p>

      <div v-else-if="noteStore.reviewNotes.length" class="review-list reveal" style="--d:.12s">
        <article v-for="note in noteStore.reviewNotes" :key="note.id" class="review-item">
          <div class="review-main">
            <div class="review-meta">
              <span class="note-type">{{ note.noteTypeGlyph }} {{ note.noteTypeLabel }}</span>
              <span class="visibility">{{ note.visibility === 'PUBLIC' ? '公开' : '私有' }}</span>
              <span class="review-status" :class="note.reviewState.toLowerCase()">
                {{ statusLabel(note) }}
              </span>
            </div>
            <button class="title-btn" @click="router.push(`/note/${note.id}`)">{{ note.title }}</button>
            <p>{{ note.excerpt || '这条笔记还没有留下结论。' }}</p>
            <div class="tag-row">
              <span v-for="tag in note.tags" :key="tag">{{ tag }}</span>
            </div>
          </div>
          <div class="review-actions">
            <button class="btn btn-primary" :disabled="verifyingId === note.id" @click="markVerified(note)">
              {{ verifyingId === note.id ? '确认中…' : '✓ 仍然有效' }}
            </button>
            <button class="btn btn-ghost" @click="router.push({ name: 'note-edit', query: { id: note.id } })">✎ 编辑</button>
            <button class="icon-open" title="打开笔记" aria-label="打开笔记" @click="router.push(`/note/${note.id}`)">→</button>
          </div>
        </article>
      </div>

      <div v-else class="empty reveal">
        <div class="empty-glyph">✓</div>
        <p>{{ reviewState === 'DUE' ? '没有等待复核的结论。' : '当前筛选下没有笔记。' }}</p>
        <button v-if="appliedKeyword" class="btn btn-ghost" @click="clearSearch">清除搜索</button>
        <RouterLink v-else class="btn btn-ghost" to="/notes/mine">查看我的笔记</RouterLink>
      </div>

      <div v-if="noteStore.reviewHasMore" class="load-row">
        <button class="btn btn-ghost" :disabled="noteStore.reviewLoading" @click="noteStore.loadMoreReview()">
          {{ noteStore.reviewLoading ? '正在检查…' : `继续检查 · 还有 ${noteStore.reviewTotal - noteStore.reviewNotes.length} 条` }}
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.review-tools{display:grid; grid-template-columns:minmax(0,1fr) auto; gap:14px 20px;
  align-items:center; margin-bottom:24px; padding-bottom:20px; border-bottom:1px solid var(--line)}
.state-tabs{display:flex; gap:6px; flex-wrap:wrap}
.state-tab{height:38px; padding:0 15px; border:1px solid var(--line); border-radius:var(--r-sm);
  background:transparent; color:var(--ink-dim); font-family:var(--font-body); cursor:pointer;
  transition:all .25s var(--ease-standard)}
.state-tab:hover{color:var(--ink); border-color:var(--ink-faint)}
.state-tab.on{color:var(--primary); border-color:var(--primary); background:var(--primary-soft)}
.review-search{display:flex; align-items:center; min-width:min(100%,360px); height:40px;
  border:1px solid var(--line); border-radius:var(--r-sm); background:var(--surface)}
.review-search:focus-within{border-color:var(--primary)}
.review-search input{flex:1; min-width:0; height:100%; padding:0 12px; border:0; outline:0;
  color:var(--ink); background:transparent; font-family:var(--font-body)}
.review-search .btn{height:32px; margin-right:3px; padding:0 13px}
.clear-btn{width:30px; height:30px; border:0; background:transparent; color:var(--ink-faint);
  font-size:18px; cursor:pointer}
.filter-row{grid-column:1 / -1; display:flex; gap:10px; align-items:center; flex-wrap:wrap}
.filter-select{height:40px; padding:0 12px; border:1px solid var(--line); border-radius:var(--r-sm);
  background:var(--bg-2); color:var(--ink); font-family:var(--font-body); outline:none}
.filter-select:focus{border-color:var(--primary)}
.filter-row .btn{margin-left:auto; height:40px}

.review-list{display:flex; flex-direction:column; gap:12px}
.review-item{display:grid; grid-template-columns:minmax(0,1fr) auto; gap:24px; align-items:center;
  min-height:150px; padding:20px 22px; border:1px solid var(--line); border-radius:var(--r-md);
  background:var(--surface); transition:border-color .25s var(--ease-standard)}
.review-item:hover{border-color:var(--teal)}
.review-main{min-width:0}
.review-meta{display:flex; gap:9px; align-items:center; flex-wrap:wrap; margin-bottom:9px;
  font-family:var(--font-mono); font-size:10px; letter-spacing:.06em}
.note-type{color:var(--teal)}
.visibility{color:var(--ink-faint); padding:2px 7px; border:1px dashed var(--line); border-radius:var(--r-sm)}
.review-status{color:var(--teal)}
.review-status.unverified,.review-status.expired{color:var(--amber)}
.title-btn{display:block; max-width:100%; padding:0; border:0; background:transparent; color:var(--ink);
  font-family:var(--font-serif); font-size:18px; font-weight:600; line-height:1.55; text-align:left;
  cursor:pointer; overflow-wrap:anywhere}
.title-btn:hover{color:var(--primary)}
.review-main p{margin-top:7px; color:var(--ink-dim); font-size:13px; line-height:1.8;
  display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; overflow:hidden}
.tag-row{display:flex; gap:6px; flex-wrap:wrap; margin-top:10px}
.tag-row span{padding:3px 8px; border-radius:var(--r-sm); background:var(--primary-soft);
  color:var(--primary); font-family:var(--font-mono); font-size:10px}
.review-actions{display:grid; grid-template-columns:auto auto 38px; gap:8px; align-items:center}
.review-actions .btn{height:38px; white-space:nowrap}
.review-actions .btn:disabled{opacity:.55; cursor:wait}
.icon-open{width:38px; height:38px; border:1px solid var(--line); border-radius:var(--r-sm);
  background:transparent; color:var(--ink-dim); font-size:17px; cursor:pointer}
.icon-open:hover{color:var(--primary); border-color:var(--primary)}

.empty{text-align:center; padding:56px 20px; border:1px dashed var(--line); border-radius:var(--r-md);
  display:flex; flex-direction:column; align-items:center; gap:14px}
.empty-glyph{width:56px; height:56px; border-radius:50%; display:grid; place-items:center;
  color:var(--teal); background:var(--surface-2); font-size:20px}
.empty p,.state-text{color:var(--ink-dim); font-size:13px; line-height:1.9}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.error-text{color:var(--rose)}
.load-row{display:flex; justify-content:center; margin-top:24px}
.load-row .btn:disabled{opacity:.55; cursor:wait}

@media (max-width:860px){
  .review-tools{grid-template-columns:1fr}
  .review-search,.filter-row{grid-column:1}
  .review-item{grid-template-columns:1fr}
  .review-actions{display:flex; flex-wrap:wrap}
  .filter-row .btn{margin-left:0}
}
@media (max-width:520px){
  .review-search{min-width:0; width:100%}
  .filter-select{flex:1; min-width:0}
  .review-actions .btn{flex:1}
}
</style>
