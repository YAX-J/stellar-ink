<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePostStore } from '@/stores/posts'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore, READ_LIMITS, READ_DEFAULTS } from '@/stores/settings'
import { fmt, readMinutes } from '@/utils/format'
import { parseMarkdown } from '@/utils/markdown'
import { emit, TOAST } from '@/utils/bus'
import AuthorBadge from '@/components/common/AuthorBadge.vue'
import MarkdownBody from '@/components/common/MarkdownBody.vue'

const route = useRoute()
const router = useRouter()
const postStore = usePostStore()
const auth = useAuthStore()
const settings = useSettingsStore()

const post = computed(() => postStore.byId(route.params.id))
const prevPost = computed(() => post.value?.prev || null)
const nextPost = computed(() => post.value?.next || null)
const canEdit = computed(() => auth.isAdmin || (
  auth.isAuthorOrAbove && Number(auth.user?.id) === Number(post.value?.userId)
))

/* 正文：Markdown 解析为块级结构交给 MarkdownBody 渲染（首段下沉由该组件判定） */
const rendered = computed(() => parseMarkdown(post.value?.content || ''))
const blocks = computed(() => rendered.value.blocks)
const toc = computed(() => rendered.value.toc)

/* ---- 阅读进度 + 位置记忆 ---- */
const glowPulse = ref(false)
const barWidth = ref(0)
const readBody = ref(null)
const restoreHint = ref(false)
const showTop = ref(false)
const showTools = ref(false)
const activeHeading = ref('')
let restoredId = null
let restoreTimer = null
let hintTimer = null

const readStyle = computed(() => ({
  '--read-fs': `${settings.read.fontSize}px`,
  '--read-lh': `${settings.read.lineHeight}`,
  '--read-w': `${settings.read.width}ch`,
}))

function onScroll() {
  const h = document.documentElement
  const ratio = h.scrollTop / (h.scrollHeight - h.clientHeight || 1)
  barWidth.value = Math.min(100, Math.max(0, ratio * 100))
  showTop.value = h.scrollTop > 900
  if (post.value) settings.rememberPosition(post.value.id, ratio)
  /* 目录高亮：取最后一个已越过的标题 */
  if (!toc.value.length) return
  const line = window.innerHeight * 0.28
  let current = toc.value[0].id
  for (const item of toc.value) {
    const el = document.getElementById(item.id)
    if (el && el.getBoundingClientRect().top <= line) current = item.id
  }
  activeHeading.value = current
}

/* 位置恢复只做一次：进入某篇文章后回到上次读到的位置 */
async function restorePosition() {
  const id = Number(route.params.id)
  if (!id || restoredId === id) return
  restoredId = id
  const ratio = settings.positionOf(id)
  if (ratio < 0.04) return
  await nextTick()
  const max = document.documentElement.scrollHeight - document.documentElement.clientHeight
  if (max <= 0) return
  window.scrollTo({ top: max * ratio, behavior: 'auto' })
  restoreHint.value = true
  clearTimeout(hintTimer)
  hintTimer = setTimeout(() => { restoreHint.value = false }, 6000)
}

function seekTo(id) {
  const el = document.getElementById(id)
  if (!el) return
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function backToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function addGlow() {
  if (!post.value) return
  try {
    await postStore.addGlow(post.value.id)
    glowPulse.value = true
    setTimeout(() => (glowPulse.value = false), 180)
  } catch {
    /* 失败提示由全局 toast 承担 */
  }
}

/* 分享：优先原生分享面板，否则复制链接 */
async function share() {
  const url = window.location.href
  const title = post.value?.title || '星笺'
  try {
    if (navigator.share) {
      await navigator.share({ title, url })
      return
    }
    await navigator.clipboard.writeText(url)
    emit(TOAST, { type: 'success', message: '链接已复制，可以送给别人了' })
  } catch {
    /* 用户取消分享或剪贴板不可用：静默 */
  }
}

function go(target) {
  if (target) router.push(`/read/${target.id ?? target}`)
}

async function loadPost(id) {
  try {
    await postStore.fetchDetail(id)
    /* 详情读取成功后再单独记一次浏览：后端对登录用户按天去重，草稿不计数 */
    await postStore.recordView(id)
  } catch {
    /* 会话/网络错误由全局兜底提示，页面显示重试入口 */
  }
}

watch(
  () => route.params.id,
  async (id) => {
    restoredId = null
    restoreHint.value = false
    await loadPost(id)
    onScroll()
    restorePosition()
  },
  { immediate: true },
)

onMounted(() => {
  addEventListener('scroll', onScroll, { passive: true })
  restorePosition()
})
onUnmounted(() => {
  removeEventListener('scroll', onScroll)
  clearTimeout(restoreTimer)
  clearTimeout(hintTimer)
})
</script>

<template>
<section class="page read-page">
  <p v-if="postStore.detailLoading && !post" class="state-text">正在读取这颗星…</p>
  <p v-else-if="postStore.error && !post" class="state-text error-text">
    {{ postStore.error }} <button class="state-action" @click="loadPost(route.params.id)">重新读取</button>
  </p>

  <article v-if="post">
    <div class="read-progress"><i :style="{ width: barWidth + '%' }"></i></div>

    <!-- 阅读设置：字号 / 行高 / 正文宽度，写入本地偏好 -->
    <div class="read-tools">
      <Transition name="fade">
        <p v-if="restoreHint" class="restore-hint">✦ 已回到你上次读到的位置</p>
      </Transition>
      <button class="tool-btn" :class="{ on: showTools }" title="阅读设置" @click="showTools = !showTools">
        ⚙ 阅读设置
      </button>
      <div v-if="showTools" class="tool-panel">
        <label>
          <span>字号 <b>{{ settings.read.fontSize }}</b></span>
          <input
            :value="settings.read.fontSize" type="range"
            :min="READ_LIMITS.fontSize.min" :max="READ_LIMITS.fontSize.max" :step="READ_LIMITS.fontSize.step"
            @input="settings.setRead({ fontSize: Number($event.target.value) })"
          >
        </label>
        <label>
          <span>行距 <b>{{ settings.read.lineHeight.toFixed(1) }}</b></span>
          <input
            :value="settings.read.lineHeight" type="range"
            :min="READ_LIMITS.lineHeight.min" :max="READ_LIMITS.lineHeight.max" :step="READ_LIMITS.lineHeight.step"
            @input="settings.setRead({ lineHeight: Number($event.target.value) })"
          >
        </label>
        <label>
          <span>行宽 <b>{{ settings.read.width }}</b></span>
          <input
            :value="settings.read.width" type="range"
            :min="READ_LIMITS.width.min" :max="READ_LIMITS.width.max" :step="READ_LIMITS.width.step"
            @input="settings.setRead({ width: Number($event.target.value) })"
          >
        </label>
        <button class="tool-reset" @click="settings.resetRead()">
          恢复默认（{{ READ_DEFAULTS.fontSize }}px / {{ READ_DEFAULTS.lineHeight }} / {{ READ_DEFAULTS.width }}）
        </button>
      </div>
    </div>

    <div class="read-layout">
      <!-- 目录：仅长文（≥3 个小节）出现，滚动时高亮当前小节 -->
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

      <div class="read-wrap" :style="readStyle">
        <button class="read-back" @click="router.push(settings.lastPage)">← 返回星域</button>
        <div class="kicker reveal">DEEP READING · 深读舱</div>
        <h1 class="read-title reveal" style="--d:.06s">{{ post.title }}</h1>
        <div class="read-meta reveal" style="--d:.12s">
          <AuthorBadge :user-id="post.userId" />
          <span>{{ post.date }}</span>
          <span>{{ fmt(post.words) }} 字 · 约 {{ post.readMinutes || readMinutes(post.words) }} 分钟</span>
          <span v-if="post.viewCount > 0">◉ {{ fmt(post.viewCount) }} 次抵达</span>
          <span v-if="post.tags.length">#{{ post.tags.join(' #') }}</span>
        </div>

        <div ref="readBody" class="read-body reveal" style="--d:.18s">
          <MarkdownBody :blocks="blocks" empty-text="这颗星还没有留下正文。" />
        </div>

        <div class="read-actions reveal" style="--d:.24s">
          <button
            class="glow-btn" :class="{ liked: post.liked }"
            :style="glowPulse ? 'transform:scale(.94)' : ''"
            :title="post.liked ? '你已经为它补充过光芒' : '为这颗星补充一点光'"
            @click="addGlow"
          >
            ✦ {{ post.liked ? '已补充光芒' : '为这颗星补充光芒' }} <b>{{ post.glow }}</b>
          </button>
          <button class="icon-btn" title="复制链接分享" @click="share">⧉ 分享</button>
          <button
            v-if="canEdit" class="icon-btn" title="编辑文章"
            @click="router.push({ name: 'write', query: { post: post.id } })"
          >✎ 编辑</button>
        </div>

        <div class="read-nav reveal" style="--d:.3s">
          <div v-if="prevPost" class="read-nav-cell" @click="go(prevPost)">
            <small>← 上一颗星</small>
            <h5>{{ prevPost.title }}</h5>
          </div>
          <div v-if="nextPost" class="read-nav-cell next" @click="go(nextPost)">
            <small>下一颗星 →</small>
            <h5>{{ nextPost.title }}</h5>
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
.state-text{color:var(--ink-faint); font-size:13px; line-height:1.8}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.error-text{color:var(--rose)}
.empty-body{color:var(--ink-faint) !important}
.read-progress i{display:block; height:100%; width:0;
  background:linear-gradient(90deg,var(--primary),var(--rose),var(--amber))}

/* 宽屏：目录在左，正文在右；窄屏退回单列并把目录挪到正文上方 */
.read-layout{display:grid; grid-template-columns:minmax(0,1fr); gap:40px; align-items:start}
.read-toc{display:none}
@media (min-width:1180px){
  .read-layout{grid-template-columns:190px minmax(0,1fr); max-width:1000px; margin:0 auto}
  .read-toc{display:block; position:sticky; top:64px}
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
.toc-item.on{color:var(--primary); background:var(--primary-soft); box-shadow:inset 2px 0 0 var(--primary)}

/* 阅读设置 */
.read-tools{display:flex; align-items:flex-start; justify-content:flex-end; gap:12px;
  flex-wrap:wrap; margin-bottom:6px; position:relative; z-index:5}
.restore-hint{margin:0 auto 0 0; font-size:12px; color:var(--amber); letter-spacing:.04em;
  font-family:var(--font-mono); align-self:center}
.tool-btn{border:1px solid var(--line); background:var(--surface); color:var(--ink-faint);
  border-radius:99px; padding:8px 16px; font-size:12px; cursor:pointer; font-family:var(--font-body);
  transition:all .25s}
.tool-btn:hover,.tool-btn.on{color:var(--primary); border-color:var(--primary)}
.tool-panel{width:min(320px,100%); border:1px solid var(--line); border-radius:var(--r-md);
  background:var(--bg-3); padding:16px 18px; display:flex; flex-direction:column; gap:12px;
  box-shadow:0 12px 30px rgba(0,0,0,.22)}
.tool-panel label{display:flex; flex-direction:column; gap:6px; font-size:11px; color:var(--ink-faint)}
.tool-panel label span{font-family:var(--font-mono); letter-spacing:.1em}
.tool-panel label b{color:var(--primary); margin-left:4px}
.tool-reset{border:1px dashed var(--line); background:transparent; color:var(--ink-faint);
  border-radius:var(--r-sm); padding:8px; font-size:11px; cursor:pointer; font-family:var(--font-body);
  transition:all .25s}
.tool-reset:hover{color:var(--ink-dim); border-color:var(--primary)}

.read-back{border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  border-radius:99px; padding:9px 20px; font-size:13px; cursor:pointer; margin-bottom:34px;
  transition:all .25s; font-family:var(--font-body)}
.read-back:hover{color:var(--primary); border-color:var(--primary); transform:translateX(-3px)}
.read-wrap{max-width:100%; margin:0 auto; min-width:0}
.read-title{font-family:var(--font-serif); font-weight:900;
  font-size:clamp(30px,4.4vw,52px); line-height:1.3; margin:12px 0 20px}
.read-meta{display:flex; gap:20px; flex-wrap:wrap; font-family:var(--font-mono); font-size:12px;
  color:var(--ink-faint); padding-bottom:26px; border-bottom:1px solid var(--line); margin-bottom:38px}

/* 正文排版已抽到 components/common/MarkdownBody.vue（与笔记详情共用），
 * 这里只保留正文容器的宽度约束：字号/行距/宽度由阅读设置经 CSS 变量下发 */
.read-body{max-width:var(--read-w,46ch)}

.read-actions{display:flex; justify-content:center; align-items:center; gap:12px; margin:56px 0 30px; flex-wrap:wrap}
.glow-btn{border:1px solid var(--amber); background:rgba(255,180,84,.1); color:var(--amber);
  border-radius:99px; padding:14px 34px; font-size:15px; cursor:pointer;
  transition:all .3s var(--ease-spring); font-family:var(--font-body)}
.glow-btn:hover{transform:scale(1.05); box-shadow:0 0 30px rgba(255,180,84,.25)}
.glow-btn b{font-family:var(--font-mono); margin-left:6px}
.glow-btn.liked{border-style:dashed; background:transparent; opacity:.85}
.icon-btn{height:46px; padding:0 18px; border:1px solid var(--line); border-radius:var(--r-md);
  background:var(--surface); color:var(--ink-dim); cursor:pointer; font-family:var(--font-body);
  font-size:13px; transition:all .25s var(--ease-spring)}
.icon-btn:hover{color:var(--primary); border-color:var(--primary); transform:translateY(-2px)}
.read-nav{display:grid; grid-template-columns:1fr 1fr; gap:16px; margin-top:30px}
.read-nav-cell{border:1px solid var(--line); border-radius:var(--r-md); padding:18px 20px;
  background:var(--surface); cursor:pointer; transition:all .3s}
.read-nav-cell:hover{border-color:var(--primary); transform:translateY(-3px)}
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
  .read-nav{grid-template-columns:1fr}
  .read-progress{left:0}
  .read-tools{margin-bottom:14px}
  .read-back{margin-bottom:24px}
}
</style>
