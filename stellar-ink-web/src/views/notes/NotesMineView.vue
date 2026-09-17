<script setup>
/* 我的笔记，一个页面两种视图：
 *   ❖ 我的笔记（原页面）  /  ✓ 结论复核（原 /notes/review 独立页，已并入）
 * 复核队列本来就是「我自己的已发布笔记」的一个筛选维度，不值得单占一个一级导航位；
 * 两个视图各自懒加载自己的数据（切到哪一档才发哪一档的请求）。 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useNoteStore, NOTE_TYPES, VISIBILITY_OPTIONS } from '@/stores/notes'
import { useAuthStore } from '@/stores/auth'
import SectionHead from '@/components/common/SectionHead.vue'
import NoteCard from '@/components/post/NoteCard.vue'
import { emit, TOAST } from '@/utils/bus'

const route = useRoute()
const router = useRouter()
const noteStore = useNoteStore()
const auth = useAuthStore()

/* 当前在哪一档进 URL（?view=review）：可刷新、可分享，也是 /notes/review 旧链接的落点 */
const view = ref(route.query.view === 'review' ? 'review' : 'mine')
const canWrite = computed(() => auth.isLoggedIn && auth.isAuthorOrAbove)

function setView(next) {
  if (view.value === next) return
  view.value = next
  router.replace({ name: 'notes-mine', query: next === 'review' ? { view: 'review' } : {} })
}
/* 浏览器前进/后退或从旧链接跳进来时，跟着 URL 回到对应视图 */
watch(() => route.query.view, (value) => {
  const next = value === 'review' ? 'review' : 'mine'
  if (next !== view.value) view.value = next
})

/* ---- 视图一：我的笔记 ---- */
const statusFilter = ref('all')
const visibilityFilter = ref('all')
const typeFilter = ref('all')

const list = computed(() => noteStore.mine)

function openNote(note) {
  /* 草稿直接进编辑台，已发布的先看效果 */
  if (note.status === 0) router.push({ name: 'note-edit', query: { id: note.id } })
  else router.push(`/note/${note.id}`)
}

async function removeNote(note) {
  if (!window.confirm(`确定删除《${note.title}》吗？`)) return
  try {
    await noteStore.remove(note.id)
    emit(TOAST, { type: 'success', message: '笔记已删除' })
  } catch {
    /* 全局 toast 已提示 */
  }
}

watch(
  () => [canWrite.value, view.value, statusFilter.value, visibilityFilter.value, typeFilter.value],
  ([allowed, current, status, visibility, noteType]) => {
    if (!allowed || current !== 'mine') return
    noteStore.fetchMine({
      status: status === 'all' ? '' : status === 'draft' ? 0 : 1,
      visibility: visibility === 'all' ? '' : visibility,
      noteType: noteType === 'all' ? '' : noteType,
    }).catch(() => {})
  },
  { immediate: true },
)

/* ---- 视图二：结论复核（180 天时效） ---- */
const reviewState = ref('DUE')
const reviewVisibility = ref('all')
const reviewType = ref('all')
const keyword = ref('')
const appliedKeyword = ref('')
const verifyingId = ref(null)

const reviewOptions = [
  { key: 'DUE', label: '待复核' },
  { key: 'UNVERIFIED', label: '未验证' },
  { key: 'EXPIRED', label: '已过期' },
  { key: 'FRESH', label: '有效期内' },
]

function loadReview() {
  if (!canWrite.value) return
  return noteStore.fetchReview({
    reviewState: reviewState.value,
    visibility: reviewVisibility.value === 'all' ? '' : reviewVisibility.value,
    noteType: reviewType.value === 'all' ? '' : reviewType.value,
    keyword: appliedKeyword.value,
  }).catch(() => {})
}

function search() {
  appliedKeyword.value = keyword.value.trim()
  loadReview()
}

function clearSearch() {
  keyword.value = ''
  appliedKeyword.value = ''
  loadReview()
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
    if (reviewState.value !== 'FRESH') await loadReview()
    emit(TOAST, { type: 'success', message: `《${note.title}》已续期 180 天` })
  } catch {
    /* 全局 toast 已提示 */
  } finally {
    verifyingId.value = null
  }
}

watch(
  () => [canWrite.value, view.value, reviewState.value, reviewVisibility.value, reviewType.value],
  ([allowed, current]) => {
    if (!allowed || current !== 'review') return
    loadReview()
  },
  { immediate: true },
)
</script>

<template>
  <section class="page page-wide">
    <SectionHead
      :title="view === 'mine' ? '我的笔记' : '笔记复核'"
      :kicker="view === 'mine' ? 'MY NOTES · 只在你自己手里' : 'NOTE REVIEW · 结论保鲜'"
      :more="view === 'mine' ? '含私有与草稿，其他人看不到这里' : `${noteStore.reviewTotal} 条符合当前条件`"
    />

    <div v-if="!canWrite" class="empty reveal">
      <div class="empty-glyph">{{ view === 'mine' ? '❖' : '✓' }}</div>
      <p v-if="!auth.isLoggedIn">还没有登录，无法查看自己的笔记。</p>
      <p v-else>你当前是读者，写笔记需要站长把角色提升为作者。</p>
      <div class="empty-actions">
        <RouterLink v-if="!auth.isLoggedIn" class="btn btn-primary" :to="{ path: '/login', query: { redirect: '/notes/mine' } }">
          登录 / 注册
        </RouterLink>
        <RouterLink class="btn btn-ghost" to="/notes">去读公开笔记</RouterLink>
      </div>
    </div>

    <template v-else>
      <!-- 两种视图的切换：复核不再单占导航位 -->
      <div class="view-tabs reveal" role="tablist" aria-label="我的笔记视图">
        <button
          class="view-tab" :class="{ on: view === 'mine' }" role="tab" :aria-selected="view === 'mine'"
          @click="setView('mine')"
        >❖ 我的笔记</button>
        <button
          class="view-tab" :class="{ on: view === 'review' }" role="tab" :aria-selected="view === 'review'"
          @click="setView('review')"
        >✓ 结论复核</button>
      </div>

      <!-- ============ 视图一：我的笔记 ============ -->
      <template v-if="view === 'mine'">
        <div class="mine-bar reveal" style="--d:.06s">
          <div class="chip-row">
            <button class="fchip" :class="{ on: statusFilter === 'all' }" @click="statusFilter = 'all'">
              全部
            </button>
            <button class="fchip" :class="{ on: statusFilter === 'draft' }" @click="statusFilter = 'draft'">
              草稿
            </button>
            <button class="fchip" :class="{ on: statusFilter === 'published' }" @click="statusFilter = 'published'">
              已发布
            </button>
            <button class="fchip" :class="{ on: visibilityFilter === 'PRIVATE' }" @click="visibilityFilter = visibilityFilter === 'PRIVATE' ? 'all' : 'PRIVATE'">
              🔒 私有
            </button>
            <button class="fchip" :class="{ on: visibilityFilter === 'PUBLIC' }" @click="visibilityFilter = visibilityFilter === 'PUBLIC' ? 'all' : 'PUBLIC'">
              ◉ 公开
            </button>
          </div>
          <select v-model="typeFilter" class="type-select">
            <option value="all">全部类型</option>
            <option v-for="t in NOTE_TYPES" :key="t.key" :value="t.key">{{ t.glyph }} {{ t.label }}</option>
          </select>
          <div class="mine-actions">
            <RouterLink class="btn btn-primary" to="/note/edit">✎ 新建笔记</RouterLink>
          </div>
        </div>

        <p v-if="noteStore.mineLoading && !noteStore.mine.length" class="state-text">正在读取…</p>
        <p v-else-if="noteStore.error && !noteStore.mine.length" class="state-text error-text">
          {{ noteStore.error }} <button class="state-action" @click="noteStore.fetchMine()">重新读取</button>
        </p>

        <div v-else class="mine-list reveal" style="--d:.12s">
          <div v-for="n in list" :key="n.id" class="mine-item">
            <NoteCard :note="n" :show-author="false" show-state @open="openNote" />
            <div class="item-actions">
              <button class="mini-btn" @click.stop="openNote(n)">
                {{ n.status === 0 ? '继续写' : '查看' }}
              </button>
              <button class="mini-btn danger" @click.stop="removeNote(n)">删除</button>
            </div>
          </div>
          <p v-if="!list.length" class="state-text">
            这里还没有笔记。去 <RouterLink class="state-link" to="/note/edit">新建一条</RouterLink>，
            私有的草稿只有你自己能看到。
          </p>
        </div>
        <div v-if="noteStore.mineHasMore" class="load-row">
          <button class="btn btn-ghost" :disabled="noteStore.mineLoading" @click="noteStore.loadMoreMine()">
            {{ noteStore.mineLoading ? '正在翻阅…' : `继续翻阅 · 还有 ${noteStore.mineTotal - noteStore.mine.length} 条` }}
          </button>
        </div>
      </template>

      <!-- ============ 视图二：结论复核 ============ -->
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
            <select v-model="reviewVisibility" class="type-select" aria-label="可见性">
              <option value="all">全部可见性</option>
              <option v-for="option in VISIBILITY_OPTIONS" :key="option.key" :value="option.key">
                {{ option.label }}
              </option>
            </select>
            <select v-model="reviewType" class="type-select" aria-label="笔记类型">
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
          {{ noteStore.error }} <button class="state-action" @click="loadReview">重新读取</button>
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
        </div>

        <div v-if="noteStore.reviewHasMore" class="load-row">
          <button class="btn btn-ghost" :disabled="noteStore.reviewLoading" @click="noteStore.loadMoreReview()">
            {{ noteStore.reviewLoading ? '正在检查…' : `继续检查 · 还有 ${noteStore.reviewTotal - noteStore.reviewNotes.length} 条` }}
          </button>
        </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
/* 整页布局：宽度由全局 .page-wide 决定，卡片栅格自然铺满 */
.empty{text-align:center; padding:56px 20px; border:1px dashed var(--line); border-radius:var(--r-lg);
  display:flex; flex-direction:column; align-items:center; gap:14px}
.empty-glyph{width:60px; height:60px; border-radius:50%; display:grid; place-items:center;
  font-size:22px; color:var(--teal); background:var(--surface-2)}
.empty p{color:var(--ink-dim); font-size:14px; line-height:1.9}
.empty-actions{display:flex; gap:12px; flex-wrap:wrap; justify-content:center}

/* 视图切换（我的笔记 / 结论复核） */
.view-tabs{display:flex; gap:6px; margin-bottom:22px; padding:4px; width:fit-content;
  border:1px solid var(--line); border-radius:99px; background:var(--surface)}
.view-tab{border:0; background:transparent; color:var(--ink-faint); cursor:pointer;
  border-radius:99px; padding:8px 18px; font-size:13px; font-family:var(--font-body);
  transition:color .25s var(--ease-standard), background .25s var(--ease-standard)}
.view-tab:hover{color:var(--ink)}
.view-tab.on{background:var(--primary-soft); color:var(--primary)}

.mine-bar{display:flex; gap:14px; align-items:center; flex-wrap:wrap; margin-bottom:24px}
.chip-row{display:flex; gap:8px; flex-wrap:wrap}
.fchip{border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  border-radius:99px; padding:8px 16px; font-size:13px; cursor:pointer;
  transition:all .25s var(--ease-spring); font-family:var(--font-body)}
.fchip:hover{transform:translateY(-2px); color:var(--ink)}
.fchip.on{background:var(--primary-soft); border-color:var(--primary); color:var(--primary)}
.fchip b{font-family:var(--font-mono); font-size:11px; margin-left:4px; opacity:.8}
.type-select{height:40px; padding:0 12px; border:1px solid var(--line); border-radius:var(--r-sm);
  background:var(--bg-2); color:var(--ink); font-family:var(--font-body); font-size:13px; outline:none}
.type-select:focus{border-color:var(--primary)}
.mine-actions{display:flex; gap:10px; margin-left:auto; flex-wrap:wrap}
.mine-actions .btn{height:40px}

.mine-list{display:grid; grid-template-columns:repeat(auto-fill,minmax(310px,1fr)); gap:16px}
.mine-item{position:relative; display:flex; flex-direction:column}
.item-actions{display:flex; gap:8px; margin-top:8px}
.mini-btn{flex:1; height:34px; border:1px solid var(--line); border-radius:var(--r-sm);
  background:transparent; color:var(--ink-dim); font-size:12px; cursor:pointer;
  font-family:var(--font-body); transition:all .25s}
.mini-btn:hover{color:var(--primary); border-color:var(--primary)}
.mini-btn.danger:hover{color:var(--rose); border-color:var(--rose)}
.state-text{color:var(--ink-faint); font-size:13px; line-height:1.9}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.state-link{color:var(--primary); text-decoration:none}
.error-text{color:var(--rose)}
.load-row{display:flex; justify-content:center; margin-top:26px}
.load-row .btn:disabled{opacity:.55; cursor:wait}

/* 复核视图 */
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
/* 复核卡片里的技术栈标签：小标记，不是可点的筛选项 */
.review-main .tag-row{display:flex; gap:6px; flex-wrap:wrap; margin-top:10px}
.review-main .tag-row span{padding:3px 8px; border-radius:var(--r-sm); background:var(--primary-soft);
  color:var(--primary); font-family:var(--font-mono); font-size:10px}
.review-actions{display:grid; grid-template-columns:auto auto 38px; gap:8px; align-items:center}
.review-actions .btn{height:38px; white-space:nowrap}
.review-actions .btn:disabled{opacity:.55; cursor:wait}
.icon-open{width:38px; height:38px; border:1px solid var(--line); border-radius:var(--r-sm);
  background:transparent; color:var(--ink-dim); font-size:17px; cursor:pointer}
.icon-open:hover{color:var(--primary); border-color:var(--primary)}

@media (max-width:860px){
  .review-tools{grid-template-columns:1fr}
  .review-search,.filter-row{grid-column:1}
  .review-item{grid-template-columns:1fr}
  .review-actions{display:flex; flex-wrap:wrap}
  .filter-row .btn{margin-left:0}
}
@media (max-width:520px){
  .review-search{min-width:0; width:100%}
  .type-select{flex:1; min-width:0}
  .review-actions .btn{flex:1}
}
</style>
