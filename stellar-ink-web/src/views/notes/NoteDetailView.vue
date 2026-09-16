<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useNoteStore } from '@/stores/notes'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { fmt } from '@/utils/format'
import { parseMarkdown } from '@/utils/markdown'
import { emit, TOAST } from '@/utils/bus'
import AuthorBadge from '@/components/common/AuthorBadge.vue'
import MarkdownBody from '@/components/common/MarkdownBody.vue'

const route = useRoute()
const router = useRouter()
const noteStore = useNoteStore()
const auth = useAuthStore()
const settings = useSettingsStore()

const note = computed(() => noteStore.byId(route.params.id))
const rendered = computed(() => parseMarkdown(note.value?.content || ''))
const blocks = computed(() => rendered.value.blocks)
const toc = computed(() => rendered.value.toc)

const isOwner = computed(() => auth.isLoggedIn && Number(auth.user?.id) === Number(note.value?.userId))
/* 只有作者能改删；私有笔记对其他人根本读不到（后端 404） */
const canEdit = computed(() => isOwner.value)

const barWidth = ref(0)
const activeHeading = ref('')
const showTop = ref(false)
const verifying = ref(false)

function onScroll() {
  const h = document.documentElement
  barWidth.value = Math.min(100, (h.scrollTop / (h.scrollHeight - h.clientHeight || 1)) * 100)
  showTop.value = h.scrollTop > 900
  if (!toc.value.length) return
  const line = window.innerHeight * 0.28
  let current = toc.value[0].id
  for (const item of toc.value) {
    const el = document.getElementById(item.id)
    if (el && el.getBoundingClientRect().top <= line) current = item.id
  }
  activeHeading.value = current
}

function seekTo(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function backToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

/** 验证新鲜度：技术笔记最怕的是结论悄悄失效 */
const verifyInfo = computed(() => {
  const at = note.value?.verifiedAt
  if (note.value?.reviewState === 'UNVERIFIED' || !at) {
    return { text: '尚未验证', stale: true, days: null }
  }
  const days = Math.floor((Date.now() - new Date(at).getTime()) / 86400000)
  return {
    text: days <= 0 ? '今天刚验证过' : `结论验证于 ${days} 天前`,
    stale: note.value?.reviewState === 'EXPIRED',
    days,
  }
})

async function markVerified() {
  if (!note.value || verifying.value) return
  verifying.value = true
  try {
    await noteStore.markVerified(note.value.id)
    emit(TOAST, { type: 'success', message: '已标记为验证过，结论保持在有效期内' })
  } catch {
    /* 失败提示由全局 toast 承担 */
  } finally {
    verifying.value = false
  }
}

async function share() {
  const url = window.location.href
  const title = note.value?.title || '技术笔记'
  try {
    if (navigator.share) {
      await navigator.share({ title, url })
      return
    }
    await navigator.clipboard.writeText(url)
    emit(TOAST, { type: 'success', message: '链接已复制' })
  } catch {
    /* 用户取消或剪贴板不可用：静默 */
  }
}

async function loadNote(id) {
  try {
    await noteStore.fetchDetail(id)
    await noteStore.recordView(id)
  } catch {
    /* 错误页与全局提示已覆盖 */
  }
}

function go(target) {
  if (target) router.push(`/note/${target.id ?? target}`)
}

watch(
  () => route.params.id,
  async (id) => {
    await loadNote(id)
    await nextTick()
    onScroll()
  },
  { immediate: true },
)

onMounted(() => addEventListener('scroll', onScroll, { passive: true }))
onUnmounted(() => removeEventListener('scroll', onScroll))
</script>

<template>
<section class="page page-wide">
  <p v-if="noteStore.detailLoading && !note" class="state-text">正在读取这条笔记…</p>
  <p v-else-if="noteStore.error && !note" class="state-text error-text">
    {{ noteStore.error }}
    <button class="state-action" @click="loadNote(route.params.id)">重新读取</button>
    <RouterLink class="state-link" to="/notes"> ← 回笔记列表</RouterLink>
  </p>

  <article v-if="note">
    <div class="read-progress"><i :style="{ width: barWidth + '%' }"></i></div>

    <!-- --prose-max 下在最外层：正文、标题、元信息、操作行、上下条都从同一基线取宽；
         整页布局下取 100%，即铺满页面、右侧不留空白 -->
    <div class="read-layout" :class="{ 'has-toc': toc.length >= 3 }" :style="{
      '--read-fs': `${settings.read.fontSize}px`,
      '--read-lh': `${settings.read.lineHeight}`,
      '--prose-max': '100%',
    }">
      <aside v-if="toc.length >= 3" class="read-toc">
        <h6>目录</h6>
        <nav>
          <button
            v-for="item in toc" :key="item.id"
            class="toc-item" :class="{ on: activeHeading === item.id, sub: item.level >= 3 }"
            @click="seekTo(item.id)"
          >{{ item.text }}</button>
        </nav>
      </aside>

      <div class="read-wrap">
        <button class="read-back" @click="router.push('/notes')">← 回笔记列表</button>

        <div class="note-head">
          <span class="nh-type">{{ note.noteTypeGlyph }} {{ note.noteTypeLabel }}</span>
          <span v-if="note.visibility === 'PRIVATE'" class="nh-private" title="只有你自己能看到">🔒 私有</span>
          <span v-if="note.status === 0" class="nh-draft">草稿</span>
        </div>

        <h1 class="read-title">{{ note.title }}</h1>

        <div class="read-meta">
          <AuthorBadge :user-id="note.userId" />
          <span>{{ note.date }}</span>
          <span>{{ fmt(note.words) }} 字 · 约 {{ note.readMinutes }} 分钟</span>
          <span v-if="note.viewCount > 0">◉ {{ fmt(note.viewCount) }} 次抵达</span>
          <span v-for="t in note.tags" :key="t" class="meta-tag">{{ t }}</span>
        </div>

        <!-- 验证状态：笔记最容易失效的地方，单独一行显式呈现 -->
        <div class="verify-row" :class="{ stale: verifyInfo.stale }">
          <span class="verify-dot" aria-hidden="true"></span>
          <span class="verify-text">
            {{ verifyInfo.text }}
            <template v-if="verifyInfo.stale"> · 供参考前请先复核</template>
          </span>
          <button v-if="canEdit" class="verify-btn" :disabled="verifying" @click="markVerified">
            {{ verifying ? '标记中…' : '✔ 我今天验证过了' }}
          </button>
        </div>

        <MarkdownBody :blocks="blocks" empty-text="这条笔记还没有正文。" class="note-body" />

        <div class="read-actions">
          <button class="icon-btn" title="复制链接分享" @click="share">⧉ 分享</button>
          <button
            v-if="canEdit" class="icon-btn"
            @click="router.push({ name: 'note-edit', query: { id: note.id } })"
          >✎ 编辑</button>
          <RouterLink v-if="canEdit" class="icon-btn" to="/notes/mine">我的笔记</RouterLink>
        </div>

        <div class="read-nav">
          <div v-if="note.prev" class="read-nav-cell" @click="go(note.prev)">
            <small>← 上一条</small>
            <h5>{{ note.prev.title }}</h5>
          </div>
          <div v-if="note.next" class="read-nav-cell next" @click="go(note.next)">
            <small>下一条 →</small>
            <h5>{{ note.next.title }}</h5>
          </div>
        </div>
      </div>
    </div>

    <Transition name="fade">
      <button v-if="showTop" class="to-top" title="回到顶部" @click="backToTop">↑</button>
    </Transition>
  </article>
</section>
</template>

<style scoped>
.read-progress{position:fixed; top:0; left:96px; right:0; height:3px; z-index:40}
.read-progress i{display:block; height:100%; width:0;
  background:linear-gradient(90deg,var(--teal),var(--primary),var(--amber))}
.state-text{color:var(--ink-faint); font-size:13px; line-height:1.9}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.state-link{color:var(--primary); text-decoration:none; margin-left:8px}
.error-text{color:var(--rose)}

/* 笔记详情：整页布局——页面铺满导航栏右侧，不再有居中或收窄的容器。
 * 正文与标题/元信息/操作行/上下条统一由 --prose-max 限宽（阅读偏好换算），
 * 右边界因此是对齐的，宽屏下只是行尾余量变多，不会出现「窄正文 + 长横杠」。 */
.read-layout{display:grid; grid-template-columns:minmax(0,1fr); gap:36px; align-items:start;
  width:100%; margin:0}
.read-toc{display:none}
@media (min-width:1180px){
  /* 长笔记（≥3 个小节）才铺开成「左目录 + 正文」双列；目录列与间距弹性伸缩 */
  .read-layout.has-toc{grid-template-columns:clamp(132px,16vw,190px) minmax(0,1fr);
    gap:clamp(20px,3vw,36px)}
  .read-layout.has-toc .read-toc{display:block; position:sticky; top:64px}
}
.read-toc h6{font-family:var(--font-mono); font-size:10px; letter-spacing:.3em; color:var(--ink-faint);
  text-transform:uppercase; margin-bottom:14px}
.read-toc nav{display:flex; flex-direction:column; gap:2px; max-height:70vh; overflow-y:auto;
  border-left:1px solid var(--line); padding-left:2px; scrollbar-width:thin}
.toc-item{border:0; background:transparent; text-align:left; cursor:pointer; font:inherit;
  font-size:12px; line-height:1.7; color:var(--ink-faint); padding:6px 10px; border-radius:0 6px 6px 0;
  transition:color .2s, background .2s; overflow:hidden; text-overflow:ellipsis; white-space:nowrap}
.toc-item.sub{padding-left:22px; font-size:11px}
.toc-item:hover{color:var(--ink-dim); background:var(--surface)}
.toc-item.on{color:var(--teal); background:var(--surface-2); box-shadow:inset 2px 0 0 var(--teal)}

.read-wrap{max-width:100%; min-width:0}
.read-back{border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  border-radius:99px; padding:9px 20px; font-size:13px; cursor:pointer; margin-bottom:30px;
  transition:all .25s; font-family:var(--font-body)}
.read-back:hover{color:var(--primary); border-color:var(--primary); transform:translateX(-3px)}

.note-head{display:flex; align-items:center; gap:12px; flex-wrap:wrap; margin-bottom:10px}
.nh-type{font-family:var(--font-mono); font-size:11px; letter-spacing:.2em; color:var(--teal)}
.nh-private{font-size:11px; color:var(--amber); border:1px dashed var(--amber);
  border-radius:99px; padding:2px 10px}
.nh-draft{font-size:11px; color:var(--ink-faint); border:1px dashed var(--line);
  border-radius:99px; padding:2px 10px}
.read-title{font-family:var(--font-serif); font-weight:900;
  font-size:clamp(26px,3.6vw,42px); line-height:1.35; margin:0 0 18px;
  max-width:var(--prose-max,100%)}
/* 元信息行的下边框必须与正文同宽，否则整页布局下会拉出一条横贯页面的长线 */
.read-meta{display:flex; gap:18px; flex-wrap:wrap; align-items:center; font-family:var(--font-mono);
  font-size:12px; color:var(--ink-faint); padding-bottom:18px; border-bottom:1px solid var(--line);
  max-width:var(--prose-max,100%)}
.meta-tag{font-family:var(--font-mono); font-size:10px; padding:3px 9px; border-radius:99px;
  background:var(--primary-soft); color:var(--primary)}

.verify-row{display:flex; align-items:center; gap:10px; flex-wrap:wrap; margin:16px 0 34px;
  padding:10px 14px; border:1px dashed var(--line); border-radius:var(--r-sm);
  font-size:12px; color:var(--ink-dim); background:var(--surface);
  max-width:var(--prose-max,100%)}
.verify-row.stale{border-color:var(--amber)}
.verify-dot{width:7px; height:7px; border-radius:50%; background:var(--teal); flex:0 0 auto;
  box-shadow:0 0 8px var(--teal)}
.verify-row.stale .verify-dot{background:var(--amber); box-shadow:0 0 8px var(--amber)}
.verify-text{line-height:1.7}
.verify-btn{margin-left:auto; border:1px solid var(--line); background:transparent; color:var(--teal);
  border-radius:99px; padding:6px 14px; font-size:12px; cursor:pointer; font-family:var(--font-body);
  transition:all .25s}
.verify-btn:hover{border-color:var(--teal); background:var(--surface-2)}
.verify-btn:disabled{opacity:.6; cursor:wait}

.note-body{margin-bottom:44px}
/* 操作行与上/下条跟正文同一条右边界，避免「窄正文 + 长横杠」的断裂感 */
.read-actions{display:flex; align-items:center; gap:12px; margin:20px 0 30px; flex-wrap:wrap;
  max-width:var(--prose-max,100%)}
.icon-btn{height:44px; padding:0 18px; border:1px solid var(--line); border-radius:var(--r-md);
  background:var(--surface); color:var(--ink-dim); cursor:pointer; font-family:var(--font-body);
  font-size:13px; text-decoration:none; display:inline-flex; align-items:center;
  transition:all .25s var(--ease-spring)}
.icon-btn:hover{color:var(--primary); border-color:var(--primary); transform:translateY(-2px)}
.read-nav{display:grid; grid-template-columns:1fr 1fr; gap:16px; margin-top:24px;
  max-width:var(--prose-max,100%)}
.read-nav-cell{border:1px solid var(--line); border-radius:var(--r-md); padding:18px 20px;
  background:var(--surface); cursor:pointer; transition:all .3s}
.read-nav-cell:hover{border-color:var(--teal); transform:translateY(-3px)}
.read-nav-cell small{font-family:var(--font-mono); font-size:10px; color:var(--ink-faint); letter-spacing:.2em}
.read-nav-cell h5{font-family:var(--font-serif); font-size:15px; margin-top:8px; line-height:1.6; font-weight:600}
.read-nav-cell.next{text-align:right}

.to-top{position:fixed; right:26px; bottom:26px; z-index:45; width:44px; height:44px;
  border-radius:50%; border:1px solid var(--line); background:color-mix(in srgb, var(--bg-3) 90%, transparent);
  backdrop-filter:blur(10px); color:var(--ink-dim); font-size:16px; cursor:pointer;
  transition:all .25s var(--ease-spring)}
.to-top:hover{color:var(--primary); border-color:var(--primary); transform:translateY(-3px)}
.fade-enter-active,.fade-leave-active{transition:opacity .3s}
.fade-enter-from,.fade-leave-to{opacity:0}

@media (max-width:720px){
  .page-wide{padding-left:20px; padding-right:20px}
  .read-nav{grid-template-columns:1fr}
  .read-progress{left:0}
}
</style>
