<script setup>
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useNoteStore, NOTE_TYPES, VISIBILITY_OPTIONS, NOTE_TEMPLATE } from '@/stores/notes'
import { useAuthStore } from '@/stores/auth'
import { emit, TOAST } from '@/utils/bus'
import { countWords } from '@/utils/wordCount'

/* 编辑器懒加载：CodeMirror 6 只在本页下载，首屏与其它页面完全不受影响 */
const MarkdownEditor = defineAsyncComponent(() => import('@/components/editor/MarkdownEditor.vue'))

const route = useRoute()
const router = useRouter()
const noteStore = useNoteStore()
const auth = useAuthStore()

const canWrite = computed(() => auth.isLoggedIn && auth.isAuthorOrAbove)

const noteId = ref(null)
const title = ref('')
const body = ref('')
const tagInput = ref('')
const noteType = ref('FIX')
const visibility = ref('PRIVATE')
const published = ref(false)
const savedAt = ref(nowTime())
const saving = ref(false)
const publishing = ref(false)
const loading = ref(false)
const typeHintVisible = ref(false)
/* 每次切换/载入笔记都自增：作为编辑器的 key，强制重建实例，避免残留旧光标与撤销栈 */
const editorKey = ref(0)
const editorRef = ref(null)
let saveTimer = null

const tags = computed(() => tagInput.value
  .split(/[,，\s]+/)
  .map((t) => t.trim())
  .filter(Boolean)
  .slice(0, 10))

/* 实时字数与后端同口径（见 utils/wordCount.js），发布后列表里的数字与这里一致 */
const wordCount = computed(() => countWords(body.value))
const ownNotes = computed(() => noteStore.mine.filter((n) => n.id !== noteId.value).slice(0, 8))

const saveLabel = computed(() => {
  if (saving.value) return '正在保存…'
  if (published.value) return '已发布 · 修改需再次发布'
  return noteId.value ? `草稿已保存 · ${savedAt.value}` : '等待保存'
})

function nowTime() {
  return new Date().toTimeString().slice(0, 8)
}

/* 编辑器内容变化 → 触发自动保存；命名沿用 onInput 便于模板复用 */
function onInput() {
  scheduleSave()
}

/* ---- 快捷插入：把片段放到光标处，前后各留一个空行，避免粘在上一段后面 ---- */
function insertFragment(text) {
  const editor = editorRef.value
  if (!editor) return
  const needsLeadingBreak = body.value.length > 0 && !body.value.endsWith('\n')
  editor.insert(`${needsLeadingBreak ? '\n\n' : ''}${text}`)
}

function insertCallout() {
  insertFragment('> [!NOTE]\n> \n')
}

function insertCodeBlock() {
  insertFragment('```bash\n\n```')
}

function insertList() {
  insertFragment('- ')
}

/** Ctrl+S：立即保存一次，并给一句明确回执（Obsidian 用户的肌肉记忆） */
async function saveNow() {
  if (saveTimer) clearTimeout(saveTimer)
  if (published.value) {
    emit(TOAST, { type: 'info', message: '已发布的笔记需要点「重新发布」才会更新' })
    return
  }
  if (!title.value.trim() && !body.value.trim()) return
  await saveDraft()
  emit(TOAST, { type: 'success', message: `已保存 · ${savedAt.value}` })
}

function scheduleSave() {
  if (saveTimer) clearTimeout(saveTimer)
  /* 已发布的笔记不做后台自动保存：避免读者在编辑过程中看到半成品 */
  if (published.value) return
  if (!title.value.trim() && !body.value.trim()) return
  saveTimer = setTimeout(saveDraft, 800)
}

async function saveDraft() {
  saveTimer = null
  if (published.value || (!title.value.trim() && !body.value.trim()) || saving.value || publishing.value) return
  saving.value = true
  try {
    noteId.value = await noteStore.saveDraft({
      id: noteId.value,
      title: title.value,
      body: body.value,
      tags: tags.value,
      noteType: noteType.value,
      visibility: visibility.value,
    })
    savedAt.value = nowTime()
    await loadMine()
  } catch {
    /* 错误提示由 store + 全局 toast 承担 */
  } finally {
    saving.value = false
  }
}

async function loadMine() {
  try {
    await noteStore.fetchMine()
  } catch {
    /* 列表拉取失败不影响编辑 */
  }
}

async function loadNote(id) {
  loading.value = true
  try {
    const detail = await noteStore.fetchDetail(id)
    noteId.value = detail.id
    title.value = detail.title === '未命名笔记' ? '' : detail.title
    body.value = detail.content || ''
    tagInput.value = detail.tags.join(', ')
    noteType.value = detail.noteType || 'FIX'
    visibility.value = detail.visibility || 'PRIVATE'
    published.value = detail.status === 1
    savedAt.value = nowTime()
    /* 换一篇笔记就重建编辑器实例：比往旧文档里灌内容更稳，不会留下错误的光标/撤销栈 */
    editorKey.value += 1
  } catch {
    /* 读不到（不存在或不是自己的）就退回新建，避免停在空白页 */
    emit(TOAST, { type: 'warn', message: '这条笔记读不到，已切换为新建' })
    router.replace({ name: 'note-edit' })
  } finally {
    loading.value = false
  }
}

/** 插入标准章节模板：正文为空时整体填充，否则放到光标处 */
function insertTemplate() {
  if (!body.value.trim()) {
    /* 走编辑器选区插入，才能保住撤销栈与光标位置 */
    editorRef.value?.insert(NOTE_TEMPLATE)
  } else {
    insertFragment(NOTE_TEMPLATE)
  }
  emit(TOAST, { type: 'info', message: '已插入标准章节，读者端会自动生成目录' })
  scheduleSave()
}

function newNote() {
  if (saveTimer) clearTimeout(saveTimer)
  noteId.value = null
  title.value = ''
  body.value = ''
  tagInput.value = ''
  noteType.value = 'FIX'
  visibility.value = 'PRIVATE'
  published.value = false
  editorKey.value += 1
}

async function openNote(note) {
  if (saveTimer) clearTimeout(saveTimer)
  await saveDraft()
  router.replace({ name: 'note-edit', query: { id: note.id } })
  await loadNote(note.id)
}

async function publish() {
  if (!body.value.trim()) {
    emit(TOAST, { type: 'warn', message: '正文还是空的，先写下结论再发布' })
    return
  }
  if (saveTimer) clearTimeout(saveTimer)
  publishing.value = true
  try {
    const id = await noteStore.publish({
      id: noteId.value,
      title: title.value,
      body: body.value,
      tags: tags.value,
      noteType: noteType.value,
      visibility: visibility.value,
    })
    noteId.value = id
    published.value = true
    await loadMine()
    emit(TOAST, {
      type: 'success',
      message: visibility.value === 'PUBLIC' ? '已发布，可在笔记列表看到' : '已发布（私有，只有你能看到）',
    })
    router.push(`/note/${id}`)
  } catch {
    /* 全局 toast 已提示 */
  } finally {
    publishing.value = false
  }
}

async function removeNote() {
  if (!noteId.value) return
  if (!window.confirm('确定删除这条笔记吗？')) return
  try {
    await noteStore.remove(noteId.value)
    newNote()
    await loadMine()
    emit(TOAST, { type: 'success', message: '笔记已删除' })
  } catch {
    /* 全局 toast 已提示 */
  }
}

onMounted(async () => {
  await loadMine()
  if (route.query.id) await loadNote(route.query.id)
})
onUnmounted(() => {
  if (saveTimer) clearTimeout(saveTimer)
})
</script>

<template>
  <section class="page page-wide">
    <div class="kicker reveal">NOTE STUDIO · 标本工作台</div>

    <div v-if="!canWrite" class="gate reveal" style="--d:.08s">
      <div class="gate-glyph" aria-hidden="true">❖</div>
      <h2>记录笔记需要作者权限</h2>
      <p v-if="!auth.isLoggedIn">
        你还没有登录。登录后即为读者，可以阅读全部公开笔记；
        写笔记需要站长把角色提升为作者。
      </p>
      <p v-else>
        你当前是读者，可以阅读全部公开笔记；写笔记需要站长把角色提升为作者。
      </p>
      <div class="gate-actions">
        <RouterLink v-if="!auth.isLoggedIn" class="btn btn-primary" :to="{ path: '/login', query: { redirect: '/note/edit' } }">
          登录 / 注册
        </RouterLink>
        <RouterLink class="btn btn-ghost" to="/notes">先去读笔记</RouterLink>
      </div>
    </div>

    <div v-else class="studio">
      <div class="desk reveal" style="--d:.1s">
        <div class="desk-top">
          <div class="type-row">
            <button
              v-for="t in NOTE_TYPES" :key="t.key"
              class="type-chip" :class="{ on: noteType === t.key }"
              :title="t.hint" @click="noteType = t.key; scheduleSave()"
            >{{ t.glyph }} {{ t.label }}</button>
          </div>
          <div class="vis-row">
            <button
              v-for="v in VISIBILITY_OPTIONS" :key="v.key"
              class="vis-chip" :class="{ on: visibility === v.key, priv: v.key === 'PRIVATE' }"
              :title="v.hint" @click="visibility = v.key; scheduleSave()"
            >{{ v.key === 'PRIVATE' ? '🔒' : '◉' }} {{ v.label }}</button>
          </div>
        </div>

        <input v-model="title" class="title-input" placeholder="标题：建议直接写报错原文或一句话症状" @input="onInput">
        <div class="quill-line"></div>

        <div class="tag-row">
          <input
            v-model="tagInput" class="tag-input"
            placeholder="技术栈标签，逗号分隔：java, spring-boot, mysql" @input="onInput"
          >
          <span class="tag-hint">{{ tags.length }}/10</span>
        </div>

        <!-- 编辑工具栏：Obsidian 式编辑的几个常用入口 -->
        <div class="edit-tools">
          <button class="tool" title="插入标准章节（现象/环境/排查/结论/参考）" @click="insertTemplate">✧ 标准章节</button>
          <button class="tool" title="插入提示卡，读者端会渲染成高亮块" @click="insertCallout">❝ 提示卡</button>
          <button class="tool" title="插入代码块" @click="insertCodeBlock">▤ 代码块</button>
          <button class="tool" title="插入列表项" @click="insertList">• 列表</button>
          <span class="tool-tip">Ctrl+B 粗体 · Ctrl+I 斜体 · Ctrl+K 链接 · Ctrl+S 保存</span>
        </div>

        <!-- 编辑器：CodeMirror 6 懒加载，行内渲染非光标行的 Markdown -->
        <MarkdownEditor
          ref="editorRef"
          :key="editorKey"
          v-model="body"
          :placeholder-text="'从这里开始。非光标行会直接渲染成结果，# 就是标题，**就是加粗。'"
          @update:model-value="onInput"
          @save="saveNow"
        />

        <div class="desk-foot">
          <span class="save-dot"><i></i>{{ saveLabel }}</span>
          <span>{{ wordCount }} 字</span>
          <span v-if="published" class="pub-flag">已发布</span>
          <button class="btn btn-ghost mini" @click="newNote">＋ 新建</button>
          <button v-if="noteId" class="btn btn-ghost mini danger" @click="removeNote">删除</button>
          <button class="btn btn-primary grow" :disabled="publishing || saving" @click="publish">
            {{ publishing ? '正在发布…' : (published ? '✎ 重新发布' : '❖ 发布这条笔记') }}
          </button>
        </div>
        <p v-if="noteStore.error" class="publish-error">{{ noteStore.error }}</p>
      </div>

      <aside class="side">
        <div class="side-card reveal" style="--d:.16s">
          <h5>当前类型说明</h5>
          <p class="type-hint">{{ NOTE_TYPES.find((t) => t.key === noteType)?.hint }}</p>
        </div>

        <div class="side-card reveal" style="--d:.22s">
          <h5>我的笔记 · 最近</h5>
          <div
            v-for="n in ownNotes" :key="n.id" class="mini-note"
            :class="{ on: n.id === noteId }" @click="openNote(n)"
          >
            <span class="mn-title">{{ n.title }}</span>
            <span class="mn-meta">{{ n.visibility === 'PUBLIC' ? '公开' : '私有' }}<template v-if="n.status === 0"> · 草稿</template></span>
          </div>
          <p v-if="!ownNotes.length" class="side-empty">还没有其它笔记。</p>
          <RouterLink class="side-link" to="/notes/mine">管理全部 →</RouterLink>
        </div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
/* 写作台：整页布局，但写作面本身给出上限——标题/正文行过长会明显降低写作与校对体验。
 * 这是「行长」约束而非页面收窄，页宽更大时只是余量更多（其余笔记页不限宽）。 */
.page-wide{--editor-max:1400px}

.gate{border:1px dashed var(--line); border-radius:var(--r-lg); background:var(--surface);
  padding:clamp(30px,5vw,64px); text-align:center; display:flex; flex-direction:column;
  align-items:center; gap:14px}
.gate-glyph{width:64px; height:64px; border-radius:50%; display:grid; place-items:center; font-size:24px;
  color:var(--teal); background:var(--surface-2); box-shadow:0 0 26px var(--primary-soft)}
.gate h2{font-family:var(--font-serif); font-weight:900; font-size:clamp(22px,2.8vw,30px)}
.gate p{font-size:14px; line-height:2; color:var(--ink-dim); max-width:52ch}
.gate-actions{display:flex; gap:12px; flex-wrap:wrap; justify-content:center; margin-top:8px}

/* 正文列 minmax(0,1fr)：CodeMirror 的超长行只在自己内部滚动，不撑破栅格 */
.studio{display:grid; grid-template-columns:minmax(0,1fr) 292px; gap:26px;
  max-width:var(--editor-max,1400px)}
.desk{border:1px solid var(--line); border-radius:var(--r-lg);
  background:linear-gradient(180deg,var(--bg-2),var(--bg)); padding:clamp(24px,3.2vw,42px);
  display:flex; flex-direction:column; min-height:70vh}
.desk-top{display:flex; justify-content:space-between; gap:16px; flex-wrap:wrap; margin-bottom:22px}
.type-row,.vis-row{display:flex; gap:8px; flex-wrap:wrap}
.type-chip,.vis-chip{border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  border-radius:99px; padding:7px 14px; font-size:12px; cursor:pointer;
  transition:all .25s var(--ease-spring); font-family:var(--font-body)}
.type-chip:hover,.vis-chip:hover{transform:translateY(-2px); color:var(--ink)}
.type-chip.on{background:var(--surface-2); border-color:var(--teal); color:var(--teal)}
.vis-chip.on{background:var(--primary-soft); border-color:var(--primary); color:var(--primary)}
.vis-chip.on.priv{background:rgba(255,180,84,.12); border-color:var(--amber); color:var(--amber)}

.title-input{width:100%; border:none; background:transparent; outline:none; color:var(--ink);
  font-family:var(--font-serif); font-weight:900; font-size:clamp(22px,2.6vw,32px); line-height:1.4}
.title-input::placeholder{color:var(--ink-faint); font-weight:600}
.quill-line{width:64px; height:3px; border-radius:2px; margin:14px 0 20px;
  background:linear-gradient(90deg,var(--teal),var(--primary))}

.tag-row{display:flex; align-items:center; gap:10px; margin-bottom:16px}
.tag-input{flex:1; height:42px; border:1px solid var(--line); border-radius:var(--r-sm);
  background:var(--bg-2); color:var(--ink); padding:0 14px; font-size:13px; outline:none;
  font-family:var(--font-mono); transition:border-color .2s}
.tag-input:focus{border-color:var(--primary)}
.tag-hint{font-family:var(--font-mono); font-size:10px; color:var(--ink-faint)}

/* 编辑工具栏：CodeMirror 接管正文后，原来的 textarea 样式已移除 */
.edit-tools{display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin-bottom:10px}
.tool{border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  border-radius:99px; padding:6px 13px; font-size:12px; cursor:pointer; font-family:var(--font-body);
  transition:all .25s var(--ease-spring)}
.tool:hover{color:var(--primary); border-color:var(--primary); transform:translateY(-2px)}
.tool-tip{font-family:var(--font-mono); font-size:10px; color:var(--ink-faint); letter-spacing:.04em;
  margin-left:auto}

.desk-foot{display:flex; align-items:center; gap:16px; flex-wrap:wrap; margin-top:22px;
  padding-top:16px; border-top:1px dashed var(--line);
  font-family:var(--font-mono); font-size:11px; color:var(--ink-faint)}
.ghost-mini{border:1px dashed var(--line); background:transparent; color:var(--ink-dim);
  border-radius:99px; padding:7px 14px; font-size:12px; cursor:pointer; font-family:var(--font-body);
  transition:all .25s}
.ghost-mini:hover{color:var(--teal); border-color:var(--teal)}
.save-dot{display:inline-flex; align-items:center; gap:7px}
.save-dot i{width:7px; height:7px; border-radius:50%; background:var(--teal); animation:pulse 2.2s infinite}
.pub-flag{color:var(--teal)}
.desk-foot .grow{margin-left:auto}
.desk-foot .grow:disabled{opacity:.6; cursor:wait}
.btn.mini{height:36px; padding:0 14px; font-size:12px}
.btn.danger{color:var(--rose)}
.publish-error{margin-top:10px; color:var(--rose); font-size:12px}

.side{display:flex; flex-direction:column; gap:16px}
.type-hint{font-size:12px; line-height:1.9; color:var(--ink-dim)}
.mini-note{display:flex; justify-content:space-between; gap:10px; align-items:center;
  padding:10px 2px; border-bottom:1px dashed var(--line); cursor:pointer; font-size:13px;
  color:var(--ink-dim); transition:color .2s}
.mini-note:last-of-type{border-bottom:none}
.mini-note:hover{color:var(--ink)}
.mini-note.on{color:var(--primary)}
.mn-title{overflow:hidden; text-overflow:ellipsis; white-space:nowrap}
.mn-meta{font-family:var(--font-mono); font-size:10px; color:var(--ink-faint); flex:0 0 auto}
.side-empty{font-size:12px; color:var(--ink-faint); line-height:1.8}
.side-link{display:inline-block; margin-top:12px; font-family:var(--font-mono); font-size:11px;
  color:var(--primary); text-decoration:none; letter-spacing:.1em}

@media (max-width:980px){
  .studio{grid-template-columns:1fr}
}
@media (max-width:720px){
  .page-wide{padding-left:20px; padding-right:20px}
}
</style>
