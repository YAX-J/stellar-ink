<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useNoteStore, NOTE_TYPES } from '@/stores/notes'
import { useAuthStore } from '@/stores/auth'
import SectionHead from '@/components/common/SectionHead.vue'
import NoteCard from '@/components/post/NoteCard.vue'
import { emit, TOAST } from '@/utils/bus'

const router = useRouter()
const noteStore = useNoteStore()
const auth = useAuthStore()

const statusFilter = ref('all')
const visibilityFilter = ref('all')
const typeFilter = ref('all')

const canWrite = computed(() => auth.isLoggedIn && auth.isAuthorOrAbove)

const list = computed(() => noteStore.mine.filter((note) => {
  if (statusFilter.value === 'draft' && note.status !== 0) return false
  if (statusFilter.value === 'published' && note.status !== 1) return false
  if (visibilityFilter.value !== 'all' && note.visibility !== visibilityFilter.value) return false
  if (typeFilter.value !== 'all' && note.noteType !== typeFilter.value) return false
  return true
}))

const counts = computed(() => ({
  all: noteStore.mine.length,
  draft: noteStore.mine.filter((n) => n.status === 0).length,
  priv: noteStore.mine.filter((n) => n.visibility === 'PRIVATE').length,
  pub: noteStore.mine.filter((n) => n.visibility === 'PUBLIC').length,
}))

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

onMounted(() => {
  if (auth.isLoggedIn) noteStore.fetchMine().catch(() => {})
})
</script>

<template>
  <section class="page page-wide">
    <div class="kicker reveal">MY NOTES · 只在你自己手里</div>
    <SectionHead title="我的笔记" more="含私有与草稿，其他人看不到这里" />

    <div v-if="!canWrite" class="empty reveal">
      <div class="empty-glyph">❖</div>
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
      <div class="mine-bar reveal" style="--d:.06s">
        <div class="chip-row">
          <button class="fchip" :class="{ on: statusFilter === 'all' }" @click="statusFilter = 'all'">
            全部 <b>{{ counts.all }}</b>
          </button>
          <button class="fchip" :class="{ on: statusFilter === 'draft' }" @click="statusFilter = 'draft'">
            草稿 <b>{{ counts.draft }}</b>
          </button>
          <button class="fchip" :class="{ on: visibilityFilter === 'PRIVATE' }" @click="visibilityFilter = visibilityFilter === 'PRIVATE' ? 'all' : 'PRIVATE'">
            🔒 私有 <b>{{ counts.priv }}</b>
          </button>
          <button class="fchip" :class="{ on: visibilityFilter === 'PUBLIC' }" @click="visibilityFilter = visibilityFilter === 'PUBLIC' ? 'all' : 'PUBLIC'">
            ◉ 公开 <b>{{ counts.pub }}</b>
          </button>
        </div>
        <select v-model="typeFilter" class="type-select">
          <option value="all">全部类型</option>
          <option v-for="t in NOTE_TYPES" :key="t.key" :value="t.key">{{ t.glyph }} {{ t.label }}</option>
        </select>
        <RouterLink class="btn btn-primary" to="/note/edit">✎ 新建笔记</RouterLink>
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
.mine-bar .btn{margin-left:auto; height:40px}

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
</style>
