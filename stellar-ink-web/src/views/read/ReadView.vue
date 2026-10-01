<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePostStore } from '@/stores/posts'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore, READ_LIMITS, READ_DEFAULTS } from '@/stores/settings'
import { useCommentStore } from '@/stores/comments'
import { useAuthorStore } from '@/stores/authors'
import { useQaStore } from '@/stores/qa'
import { useAgentStore } from '@/stores/agent'
import { useWikiStore } from '@/stores/wiki'
import { locateEvidence } from '@/utils/wiki'
import { fmt, readMinutes } from '@/utils/format'
import { parseMarkdown } from '@/utils/markdown'
import { emit, TOAST } from '@/utils/bus'
import AuthorBadge from '@/components/common/AuthorBadge.vue'
import UserAvatar from '@/components/common/UserAvatar.vue'
import MarkdownBody from '@/components/common/MarkdownBody.vue'

const route = useRoute()
const router = useRouter()
const postStore = usePostStore()
const auth = useAuthStore()
const settings = useSettingsStore()
const commentStore = useCommentStore()
const authorStore = useAuthorStore()
const qaStore = useQaStore()
const agentStore = useAgentStore()
const wikiStore = useWikiStore()

const post = computed(() => postStore.byId(route.params.id))
const prevPost = computed(() => post.value?.prev || null)
const nextPost = computed(() => post.value?.next || null)
const canEdit = computed(() => auth.isAdmin || (
  auth.isAuthorOrAbove && Number(auth.user?.id) === Number(post.value?.userId)
))
const comments = computed(() => commentStore.forPost(route.params.id))
const commentDraft = ref('')
const commentError = ref('')
const canDeleteComment = (comment) => auth.isAdmin || Number(auth.user?.id) === Number(comment.userId)
function fmtCommentTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

async function loadComments(id) {
  try { await commentStore.fetchForPost(id) } catch { /* 评论读取失败不影响正文 */ }
}

async function submitComment() {
  const content = commentDraft.value.trim()
  if (!content) { commentError.value = '写下一句再发射吧' ; return }
  if (content.length > 1000) { commentError.value = '评论最多 1000 个字' ; return }
  commentError.value = ''
  try {
    await commentStore.add(route.params.id, content)
    commentDraft.value = ''
  } catch (error) { commentError.value = error.message }
}

async function deleteComment(comment) {
  if (!window.confirm('确定要收回这条回声吗？')) return
  try { await commentStore.remove(comment.id, route.params.id) } catch { /* 全局 toast 已提示 */ }
}

/* ---- 问星笺（问答入口）----
 * 问的是**全站已发布文章**，不是只看当前这篇：这样「这篇文章提到的书在哪篇写过」也能问到。
 * 答案与引用一律来自服务端，前端只负责展示与把引用做成可跳转的链接。
 *
 * 默认走**流式**：引用由检索决定、比正文先到，所以先渲染引用再等答案 ——
 * 读者不用盯着转圈等到整段生成完。中止（停止按钮 / 清空 / 离开页面）会关掉这条流，
 * 服务端随之取消下游模型调用。
 */
const qaDraft = ref('')

/** 引用可点回原文：跳到那篇文章（当前这篇就不跳，只提示） */
function openCitation(citation) {
  if (!citation || Number(citation.postId) === Number(route.params.id)) return
  router.push({ name: 'read', params: { id: citation.postId } })
}

/* ---- 深挖（只读 Agent，E2）----
 * 与「问星笺」共用一块面板，因为用户的心智是同一件事：都在这篇文章旁边问站内文章。
 * 但它**更慢也更贵**（多步检索、可能多次调模型），所以：
 * ① 只在用户明确切到「深挖」时才可用（不自动跑）；
 * ② 结果里显示**步骤**与预算状态 —— 否则「转了很久、答案很短」看起来就像坏了；
 * ③ `doneReason=length`（预算用尽）不是失败：能把查到的引用给出来，就是有用的结果。
 */
const qaMode = ref('ask') // 'ask' 一次问答 | 'dig'
const digDepth = ref('quick')

async function askStar() {
  const question = qaDraft.value.trim()
  if (!question) {
    qaStore.error = '请先写下一个问题'
    return
  }
  if (qaMode.value === 'dig') {
    try {
      const result = await agentStore.ask(question, { depth: digDepth.value })
      if (result) qaDraft.value = ''
    } catch { /* 错误已进 store.error 并有局部/全局提示 */ }
    return
  }
  try {
    const answer = await qaStore.askStream(question, 5)
    if (answer) qaDraft.value = ''
  } catch { /* 错误已进 store.error 并有局部/全局提示 */ }
}

/** 切换模式时把另一边的结果清掉：两套结果的形状不同，混着显示会张冠李戴 */
function switchMode(mode) {
  if (qaMode.value === mode) return
  qaMode.value = mode
  if (mode === 'dig') qaStore.reset()
  else agentStore.reset()
}

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

/* 整页布局：正文铺满页面，不再设置行宽上限（右侧不留空白）。
 * 字号 / 行距仍按阅读偏好下发。 */
const readStyle = computed(() => ({
  '--read-fs': `${settings.read.fontSize}px`,
  '--read-lh': `${settings.read.lineHeight}`,
  '--prose-max': '100%',
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

/* ---- 知识条目（LLM Wiki，E4-3）----
 * 它们是**文章的增强**，不是文章本身：取不到时整块不出现，阅读不受影响（store 已静默降级）。
 * 每条都带原文片段 —— 这是 Wiki 与「模型写一段摘要」的区别，也是读者唯一能自行核对的东西。
 */
const locateMissId = ref(null)

/** 实体类型的展示名：界面上说人话，别把 kind 原样丢给读者 */
const KIND_LABELS = {
  person: '人物',
  concept: '概念',
  tool: '工具',
  org: '组织',
  place: '地点',
  other: '其他',
}

function kindLabel(kind) {
  return KIND_LABELS[kind] || KIND_LABELS.other
}

function locate(claim) {
  locateMissId.value = null
  const result = locateEvidence(readBody.value, claim.quote)
  if (result === 'missing') {
    // 如实说：条目带的是**抽取时**的版本，文章改过之后片段可能已经不在正文里了
    locateMissId.value = claim.id
  }
}

async function loadPost(id) {
  try {
    await postStore.fetchDetail(id)
    /* 详情读取成功后再单独记一次浏览：后端对登录用户按天去重，草稿不计数 */
    await postStore.recordView(id)
  } catch {
    /* 会话/网络错误由全局兜底提示，页面显示重试入口 */
  }
  // 知识条目与实体**各取各的**：任一边失败都不影响另一边，也都不参与正文的成败
  wikiStore.load(id)
  wikiStore.loadEntities(id)
}

watch(
  () => route.params.id,
  async (id) => {
    restoredId = null
    restoreHint.value = false
    await loadPost(id)
    loadComments(id)
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
  // 离开页面主动收尾：不中止的话这条流会读到组件卸载之后，而下游模型会继续生成
  qaStore.abort()
})
</script>

<template>
<section class="page page-wide read-page">
  <p v-if="postStore.detailLoading && !post" class="state-text">正在读取这颗星…</p>
  <p v-else-if="postStore.error && !post" class="state-text error-text">
    {{ postStore.error }} <button class="state-action" @click="loadPost(route.params.id)">重新读取</button>
  </p>

  <article v-if="post">
    <div class="read-progress"><i :style="{ width: barWidth + '%' }"></i></div>

    <!-- --prose-max 下在最外层：顶部工具条、标题、元信息、正文、操作行、上下条、回声面板
         都从同一个基线取宽度，左右边界始终对齐 -->
    <div class="read-layout" :class="{ 'has-toc': toc.length >= 3 }" :style="readStyle">
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

      <div class="read-wrap">
        <!-- 顶部工具条：返回与阅读设置同一行（此前一个靠左、一个靠右各占一行，
             中间是一整片空白，进页面先看到两个孤零零的按钮） -->
        <div class="read-bar reveal">
          <button class="read-back" @click="router.push(settings.lastPage)">← 返回星域</button>

          <!-- 阅读设置：字号 / 行距，写入本地偏好；面板绝对定位，展开时不推动正文 -->
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
              <button class="tool-reset" @click="settings.resetRead()">
                恢复默认（{{ READ_DEFAULTS.fontSize }}px / {{ READ_DEFAULTS.lineHeight }}）
              </button>
            </div>
          </div>
        </div>

        <div class="title-row reveal" style="--d:.06s">
          <h1 class="read-title">{{ post.title }}</h1>
          <span class="kicker">DEEP READING · 深读舱</span>
        </div>
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

        <section class="qa-panel reveal" style="--d:.33s" aria-label="问星笺">
          <div class="qa-head">
            <div class="title-row">
              <h3>{{ qaMode === 'dig' ? '深挖' : '问星笺' }}</h3>
              <span class="kicker">{{ qaMode === 'dig' ? 'DIG · 多步检索（更慢更贵）' : 'ASK · 就全站文章提问' }}</span>
            </div>
            <button
              v-if="qaMode === 'dig' ? agentStore.result : qaStore.answer"
              class="qa-reset" type="button"
              @click="qaMode === 'dig' ? agentStore.reset() : qaStore.reset()"
            >清空</button>
          </div>

          <div class="qa-modes">
            <button
              class="qa-mode" :class="{ on: qaMode === 'ask' }" type="button"
              @click="switchMode('ask')"
            >一次问答</button>
            <button
              class="qa-mode" :class="{ on: qaMode === 'dig' }" type="button"
              @click="switchMode('dig')"
            >深挖（多步）</button>
          </div>

          <p v-if="!auth.isLoggedIn" class="qa-hint">
            {{ qaMode === 'dig' ? '深挖' : '提问' }}需要登录（答案要花算力，也要能按人计费）。
            <RouterLink class="qa-link" :to="{ name: 'login', query: { redirect: route.fullPath } }">去登录</RouterLink>
          </p>

          <template v-else>
            <div class="qa-compose">
              <input
                v-model="qaDraft" maxlength="500" type="text"
                :placeholder="qaMode === 'dig' ? '问一个需要翻几篇才能答的问题…' : '问点什么，比如：作者为什么坚持写博客？'"
                @keydown.enter="askStar"
              >
              <button
                class="btn btn-primary"
                :disabled="qaMode === 'dig' ? agentStore.running : qaStore.asking"
                @click="askStar"
              >
                {{ qaMode === 'dig' ? (agentStore.running ? '检索中…' : '深挖') : (qaStore.asking ? '生成中…' : '提问') }}
              </button>
              <button
                v-if="qaMode === 'dig' ? agentStore.running : qaStore.streaming"
                class="btn btn-ghost qa-stop" type="button"
                @click="qaMode === 'dig' ? agentStore.abort() : qaStore.reset()"
              >停止</button>
            </div>

            <p v-if="qaMode === 'dig'" class="qa-hint">
              深挖会自己决定查几次、一次查什么（最多
              <b>{{ digDepth === 'quick' ? 3 : 4 }}</b> 步），所以比一次问答慢，也更费额度。
              预算只能收紧，想跑更多步要改服务端配置。
              <select v-model="digDepth" class="qa-depth" :disabled="agentStore.running">
                <option value="quick">快一点（3 步）</option>
                <option value="standard">标准（服务端 4 步）</option>
              </select>
              <span v-if="agentStore.error"> · <b class="qa-err">{{ agentStore.error }}</b></span>
            </p>
            <p v-else class="qa-hint">
              答案只依据站内文章，并给出引用；找不到依据时会直说「没有找到」，不会编。
              <span v-if="qaStore.error"> · <b class="qa-err">{{ qaStore.error }}</b></span>
            </p>

            <!-- ---------- 深挖结果（多步检索） ---------- -->
            <div v-if="qaMode === 'dig' && (agentStore.result || agentStore.running || agentStore.stopped)" class="qa-answer">
              <p v-if="agentStore.question" class="qa-question">问：{{ agentStore.question }}</p>
              <p v-if="agentStore.offline" class="qa-offline">离线自测：当前用的是 Fake 模型，回答仅用于验证链路。</p>

              <p v-if="agentStore.running" class="qa-waiting">正在多步检索…（每步之间可以点「停止」）</p>
              <!-- 预算用尽**不是失败**：它常常带回引用，只是没在预算内收敛 -->
              <p v-else-if="agentStore.budgetExhausted" class="qa-interrupted">
                步数用尽了，没能在预算内收敛。下面是这几步查到的东西。
              </p>
              <p v-else-if="agentStore.stopped" class="qa-interrupted">
                已停止等待。服务端会在当前这一步结束后收尾，已经查到的引用仍会记在调用账里。
              </p>

              <p v-if="agentStore.result?.answer" class="qa-text">{{ agentStore.result.answer }}</p>

              <ol v-if="agentStore.steps.length" class="qa-steps">
                <li v-for="step in agentStore.steps" :key="step.index">
                  <span class="qa-step-tool">{{ step.tool || '思考' }}</span>
                  <span class="qa-step-label" :class="{ bad: !!step.error }">{{ step.error || step.label }}</span>
                </li>
              </ol>

              <ul v-if="agentStore.citations.length" class="qa-cites">
                <li v-for="(cite, index) in agentStore.citations" :key="index">
                  <button class="qa-cite" type="button" @click="openCitation(cite)">
                    <b>[{{ index + 1 }}] {{ cite.title }}</b>
                    <span>{{ cite.snippet }}</span>
                  </button>
                </li>
              </ul>
              <p v-else-if="!agentStore.running && !agentStore.result?.answer" class="qa-hint">
                这次既没有答案也没有引用：文章里确实没有能支撑回答的段落。
              </p>

              <p v-if="agentStore.result" class="qa-hint">
                共 {{ agentStore.result.toolCalls || 0 }} 次工具调用 · 模型 {{ agentStore.result.usageModel || '未知' }}
                · {{ agentStore.result.latencyMs || 0 }}ms
              </p>
            </div>

            <!-- ---------- 一次问答结果（流式） ---------- -->
            <div v-else-if="qaMode === 'ask' && qaStore.answer" class="qa-answer">
              <p v-if="qaStore.question" class="qa-question">问：{{ qaStore.question }}</p>
              <p v-if="qaStore.offline" class="qa-offline">离线自测：当前用的是 Fake 模型，回答仅用于验证链路。</p>
              <p v-if="qaStore.answerDone && qaStore.interrupted" class="qa-interrupted">
                回答中断了，下面是已经生成的部分。
              </p>
              <p v-if="qaStore.streaming && !qaStore.answer.answer" class="qa-waiting">正在检索文章…</p>
              <p class="qa-text" :class="{ refused: qaStore.refused }">{{ qaStore.answer.answer }}<span
                v-if="qaStore.streaming && qaStore.answer.answer" class="qa-caret" aria-hidden="true"
              >▍</span></p>

              <ul v-if="qaStore.answer.citations?.length" class="qa-cites">
                <li v-for="(cite, index) in qaStore.answer.citations" :key="index">
                  <button class="qa-cite" type="button" @click="openCitation(cite)">
                    <b>[{{ index + 1 }}] {{ cite.title }}</b>
                    <span>{{ cite.snippet }}</span>
                  </button>
                </li>
              </ul>
              <p v-else-if="qaStore.refused" class="qa-hint">这次没有引用可给：文章里确实没有相关段落。</p>
            </div>
          </template>
        </section>

        <!-- 知识条目：**没有条目也没有实体时整块不出现**（不留空壳，也不显示加载占位） -->
        <section
          v-if="wikiStore.hasClaims || wikiStore.hasEntities"
          class="wiki-panel reveal" style="--d:.36s" aria-label="本文的知识条目"
        >
          <template v-if="wikiStore.hasClaims">
            <div class="title-row">
              <h3>知识条目</h3>
              <span class="kicker">WIKI · 每条都附原文片段</span>
            </div>
            <p class="wiki-hint">
              这些条目由模型从本文抽取，且**必须能在原文里找到依据**才会留下；
              下面是它们的原文片段，可自行核对。
            </p>
            <ol class="wiki-list">
              <li v-for="claim in wikiStore.claims" :key="claim.id">
                <p class="wiki-text">{{ claim.text }}</p>
                <blockquote class="wiki-quote">{{ claim.quote }}</blockquote>
                <div class="wiki-meta">
                  <span v-if="claim.headingPath" class="wiki-path">{{ claim.headingPath }}</span>
                  <button class="wiki-locate" type="button" @click="locate(claim)">在正文中定位</button>
                  <span v-if="locateMissId === claim.id" class="wiki-miss">
                    正文里找不到这段文字 —— 文章可能在抽取之后改过。
                  </span>
                </div>
              </li>
            </ol>
          </template>

          <!-- 实体（E4-7）：它们同样有证据 —— 每条都能回到一句具体主张。
               这里如实写「共现」：两个实体被一起谈论，不等于它们之间有什么关系。 -->
          <template v-if="wikiStore.hasEntities">
            <div class="title-row" :class="{ 'wiki-sub-gap': wikiStore.hasClaims }">
              <h3>本文提到的实体</h3>
              <span class="kicker">CONCEPTS · 共现，不是因果</span>
            </div>
            <ul class="wiki-entities">
              <li v-for="entity in wikiStore.entities" :key="entity.id">
                <div class="wiki-entity-head">
                  <span class="wiki-entity-name">{{ entity.name }}</span>
                  <span class="wiki-entity-kind">{{ kindLabel(entity.kind) }}</span>
                  <span class="wiki-entity-count">
                    全站 {{ entity.mentionCount }} 次 · {{ entity.postCount }} 篇
                  </span>
                </div>
                <p class="wiki-entity-claim">{{ entity.mentions[0] && entity.mentions[0].claimText }}</p>
                <div v-if="entity.relations && entity.relations.length" class="wiki-entity-rel">
                  <span class="wiki-rel-label">与谁一起被谈论：</span>
                  <span v-for="rel in entity.relations" :key="rel.entityId" class="wiki-rel-chip">
                    {{ rel.name }}（{{ rel.weight }} 次）
                  </span>
                </div>
              </li>
            </ul>
          </template>
        </section>

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

        <section class="comments-panel reveal" style="--d:.36s" aria-label="文章评论">
          <div class="comments-head">
            <div class="title-row"><h3>留下一句回声</h3><span class="kicker">ECHOES · 回声</span></div>
            <span class="comments-count">{{ comments.length }} 条</span>
          </div>
          <div v-if="auth.isLoggedIn" class="comment-compose">
            <textarea v-model="commentDraft" maxlength="1000" rows="3" placeholder="写下你的想法，和作者在星海相遇…" @keydown.ctrl.enter="submitComment" />
            <div class="comment-compose-foot">
              <span class="comment-hint">Ctrl + Enter 发送<span v-if="commentError"> · {{ commentError }}</span></span>
              <button class="btn btn-primary comment-submit" :disabled="commentStore.submitting" @click="submitComment">{{ commentStore.submitting ? '发射中…' : '发射回声' }}</button>
            </div>
          </div>
          <p v-else class="comment-login-hint">登录后即可留下回声。</p>
          <p v-if="commentStore.loading && !comments.length" class="comments-empty">正在收听回声…</p>
          <p v-else-if="!comments.length" class="comments-empty">还没有回声，成为第一个回应这颗星的人吧。</p>
          <ul v-else class="comment-list">
            <li v-for="comment in comments" :key="comment.id" class="comment-item">
              <UserAvatar
                :url="authorStore.find(comment.userId).avatarUrl"
                :text="authorStore.find(comment.userId).avatarText"
                :nickname="authorStore.find(comment.userId).nickname"
                :size="34"
              />
              <div class="comment-main"><div class="comment-meta"><b>{{ authorStore.find(comment.userId).nickname }}</b><time>{{ fmtCommentTime(comment.createdAt) }}</time><button v-if="canDeleteComment(comment)" class="comment-delete" @click="deleteComment(comment)">收回</button></div><p>{{ comment.content }}</p></div>
            </li>
          </ul>
        </section>
      </div>
    </div>

    <Transition name="fade">
      <button v-if="showTop" class="to-top" title="回到顶部" @click="backToTop">↑</button>
    </Transition>
  </article>
</section>
</template>

<style scoped>
/* 阅读进度条：贴在视口最上沿、压在顶栏之上（z-index 高于顶栏的 50，
 * 否则滚动后顶栏的毛玻璃与底色会把这条线糊掉） */
.read-progress{position:fixed; top:0; left:0; right:0; height:3px; z-index:52}
.state-text{color:var(--ink-faint); font-size:13px; line-height:1.8}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.error-text{color:var(--rose)}
.read-progress i{display:block; height:100%; width:0;
  background:linear-gradient(90deg,var(--primary),var(--rose),var(--amber))}

/* 深读：整页布局——页面铺满整幅视口宽度，不再有居中或收窄的容器。
 * 正文与标题/元信息/操作行/上下条/回声面板统一由 --prose-max 限宽（阅读偏好换算），
 * 右边界因此对齐，宽屏下只是行尾余量变多，不会出现「窄正文 + 长横杠」。 */
.read-layout{display:grid; grid-template-columns:minmax(0,1fr); gap:36px; align-items:start;
  width:100%; margin:0}
.read-toc{display:none}
@media (min-width:1180px){
  /* 目录列与间距弹性伸缩，正文列吸收全部余量 */
  .read-layout.has-toc{grid-template-columns:clamp(132px,16vw,190px) minmax(0,1fr);
    gap:clamp(20px,3vw,36px)}
  .read-layout.has-toc .read-toc{display:block; position:sticky; top:80px}
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

/* 顶部工具条：返回 + 阅读设置同排，贴在一起，不再各占一行留出大片空白 */
.read-bar{display:flex; align-items:center; justify-content:space-between; gap:16px;
  flex-wrap:wrap; margin-bottom:18px; max-width:var(--prose-max,100%)}
.read-back{border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  border-radius:99px; padding:8px 18px; font-size:13px; cursor:pointer;
  transition:all .25s; font-family:var(--font-body)}
.read-back:hover{color:var(--primary); border-color:var(--primary); transform:translateX(-3px)}

/* 阅读设置入口：挂在工具条右端，面板绝对定位，展开时不影响正文位置 */
.read-tools{position:relative; display:flex; align-items:center; gap:12px; flex-wrap:wrap; z-index:5}
.restore-hint{margin:0; font-size:12px; color:var(--amber); letter-spacing:.04em;
  font-family:var(--font-mono)}
.tool-btn{border:1px solid var(--line); background:var(--surface); color:var(--ink-faint);
  border-radius:99px; padding:8px 16px; font-size:12px; cursor:pointer; font-family:var(--font-body);
  transition:all .25s}
.tool-btn:hover,.tool-btn.on{color:var(--primary); border-color:var(--primary)}
.tool-panel{position:absolute; right:0; top:calc(100% + 10px); z-index:20; width:min(300px,78vw);
  border:1px solid var(--line); border-radius:var(--r-md);
  background:var(--bg-3); padding:16px 18px; display:flex; flex-direction:column; gap:12px;
  box-shadow:0 12px 30px rgba(0,0,0,.22)}
.tool-panel label{display:flex; flex-direction:column; gap:6px; font-size:11px; color:var(--ink-faint)}
.tool-panel label span{font-family:var(--font-mono); letter-spacing:.1em}
.tool-panel label b{color:var(--primary); margin-left:4px}
.tool-reset{border:1px dashed var(--line); background:transparent; color:var(--ink-faint);
  border-radius:var(--r-sm); padding:8px; font-size:11px; cursor:pointer; font-family:var(--font-body);
  transition:all .25s}
.tool-reset:hover{color:var(--ink-dim); border-color:var(--primary)}

.read-wrap{max-width:100%; min-width:0}
.read-title{font-family:var(--font-serif); font-weight:900;
  font-size:clamp(30px,4.4vw,52px); line-height:1.3; margin:12px 0 20px;
  max-width:var(--prose-max,100%)}
/* 元信息行的下边框必须与正文同宽，否则整页布局下会拉出一条横贯页面的长线 */
.read-meta{display:flex; gap:20px; flex-wrap:wrap; font-family:var(--font-mono); font-size:12px;
  color:var(--ink-faint); padding-bottom:26px; border-bottom:1px solid var(--line); margin-bottom:38px;
  max-width:var(--prose-max,100%)}

/* 正文排版已抽到 components/common/MarkdownBody.vue（与笔记详情共用），
 * 行宽由其 --prose-max 统一控制，这里不再二次收窄 */
.read-body{min-width:0}

/* 问星笺：与正文同宽，暖色只用在「离线自测 / 拒答」这两处提醒上 */
.qa-panel{border:1px solid var(--line); border-radius:var(--r-md); background:var(--surface);
  padding:20px; margin:6px 0 26px; max-width:var(--prose-max,100%)}
.qa-head{display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:12px}
.qa-head h3{font-size:16px; font-weight:500}
.qa-reset{border:0; background:transparent; color:var(--ink-faint); font:inherit; font-size:12px;
  cursor:pointer}
.qa-reset:hover{color:var(--ink-dim)}
.qa-compose{display:flex; gap:10px; flex-wrap:wrap}
.qa-compose input{flex:1 1 260px; min-width:0; background:var(--bg-2); border:1px solid var(--line);
  border-radius:var(--r-sm); color:var(--ink); padding:10px 14px; font:inherit; font-size:13px;
  transition:border-color .3s var(--ease-soft)}
.qa-compose input:focus{outline:none; border-color:var(--primary)}
.qa-compose .btn{white-space:nowrap}
.qa-stop{height:auto; padding:10px 16px; font-size:13px}
.qa-hint{font-size:11px; color:var(--ink-faint); line-height:1.9; margin-top:10px}
.qa-link{color:var(--primary); margin-left:4px}
.qa-err{color:var(--rose); font-weight:400}
.qa-answer{margin-top:16px; padding-top:16px; border-top:1px solid var(--line)}
.qa-question{font-size:12px; color:var(--ink-faint); margin-bottom:8px}
.qa-offline{font-size:11px; color:var(--amber); margin-bottom:8px}
/* 中断提示用暖色：它是「内容可能不完整」的提醒，不是错误（错误走 .qa-err） */
.qa-interrupted{font-size:11px; color:var(--amber); line-height:1.9; margin-bottom:8px}
.qa-waiting{font-size:13px; color:var(--ink-faint); animation:qa-pulse 1.6s ease-in-out infinite}
/* 光标：用一个字宽的下划线块，比动画省略号更能表达「还在写」 */
.qa-caret{display:inline-block; margin-left:2px; color:var(--primary);
  animation:qa-pulse 1.1s step-end infinite}
@keyframes qa-pulse{0%,100%{opacity:1}50%{opacity:.25}}
.qa-text{font-size:14px; line-height:1.95; color:var(--ink-dim); white-space:pre-wrap}
.qa-text.refused{border-left:2px solid var(--amber); padding-left:12px; color:var(--ink-faint)}
.qa-cites{list-style:none; margin:16px 0 0; display:flex; flex-direction:column; gap:8px}
.qa-cite{display:flex; flex-direction:column; gap:4px; width:100%; text-align:left; cursor:pointer;
  border:1px solid var(--line); border-radius:var(--r-sm); background:var(--bg-2);
  padding:10px 12px; font:inherit; color:var(--ink-dim); transition:border-color .25s var(--ease-soft)}
.qa-cite:hover{border-color:var(--primary)}
.qa-cite b{font-size:12px; font-weight:500}
.qa-cite span{font-size:11px; color:var(--ink-faint); line-height:1.8}
/* 模式切换：默认「一次问答」，深挖要用户自己切过去 —— 它更慢也更贵，不能默认选中 */
.qa-modes{display:flex; gap:6px; margin-bottom:12px}
.qa-mode{border:1px solid var(--line); background:transparent; color:var(--ink-faint); font:inherit;
  font-size:12px; padding:5px 12px; border-radius:999px; cursor:pointer; transition:all .25s}
.qa-mode:hover{color:var(--ink-dim); border-color:var(--primary)}
.qa-mode.on{color:var(--ink); border-color:var(--primary); background:var(--bg-2)}
.qa-depth{margin-left:8px; background:var(--bg-2); border:1px solid var(--line); color:var(--ink-dim);
  font:inherit; font-size:11px; border-radius:var(--r-sm); padding:2px 6px}
.qa-depth:disabled{opacity:.5}
/* 步骤表：深挖的「过程」要看得见，否则「转很久、答案很短」看起来就像坏了 */
.qa-steps{list-style:none; margin:14px 0 0; padding:0; display:flex; flex-direction:column; gap:6px}
.qa-steps li{display:flex; gap:10px; align-items:baseline; font-size:11px; line-height:1.8}
.qa-step-tool{font-family:var(--font-mono); color:var(--ink-faint); min-width:88px}
.qa-step-label{color:var(--ink-dim)}
.qa-step-label.bad{color:var(--rose)}
/* 知识条目：主张与证据**并排成对**出现，证据用引用块而不是小字 ——
   它是读者唯一能自行核对的东西，不该被降级成脚注 */
.wiki-panel{margin-top:22px; border:1px solid var(--line); border-radius:var(--r-md);
  background:var(--surface); padding:22px}
.wiki-hint{font-size:11px; color:var(--ink-faint); line-height:1.9; margin:10px 0 0}
.wiki-list{list-style:none; margin:16px 0 0; padding:0; display:flex; flex-direction:column; gap:16px}
.wiki-list li{border-top:1px solid var(--line); padding-top:14px}
.wiki-list li:first-child{border-top:0; padding-top:0}
.wiki-text{font-size:14px; line-height:1.9; color:var(--ink-dim)}
.wiki-quote{margin:8px 0 0; padding:8px 12px; border-left:2px solid var(--primary);
  background:var(--bg-2); border-radius:0 var(--r-sm) var(--r-sm) 0;
  font-size:13px; line-height:1.9; color:var(--ink-faint)}
.wiki-meta{display:flex; align-items:center; gap:10px; flex-wrap:wrap; margin-top:10px}
.wiki-path{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint)}
.wiki-locate{border:1px solid var(--line); background:transparent; color:var(--ink-faint);
  font:inherit; font-size:11px; padding:4px 10px; border-radius:999px; cursor:pointer;
  transition:all .25s}
.wiki-locate:hover{color:var(--ink-dim); border-color:var(--primary)}
.wiki-miss{font-size:11px; color:var(--amber)}
/* 实体：与主张同一个面板，但**明显是另一类东西** —— 主张是「文章说了什么」，
   实体是「文章提到了什么」，混在一起会让人把「提到」读成「主张」 */
.wiki-sub-gap{margin-top:26px}
.wiki-entities{list-style:none; margin:14px 0 0; padding:0; display:flex; flex-direction:column; gap:14px}
.wiki-entities li{border-top:1px solid var(--line); padding-top:12px}
.wiki-entities li:first-child{border-top:0; padding-top:0}
.wiki-entity-head{display:flex; align-items:center; gap:8px; flex-wrap:wrap}
.wiki-entity-name{font-size:14px; color:var(--ink)}
.wiki-entity-kind{font-size:10px; letter-spacing:.1em; color:var(--ink-faint);
  border:1px solid var(--line); border-radius:999px; padding:2px 8px}
.wiki-entity-count{font-family:var(--font-mono); font-size:10px; color:var(--ink-faint)}
.wiki-entity-claim{margin:8px 0 0; padding-left:12px; border-left:2px solid var(--line);
  font-size:12.5px; line-height:1.9; color:var(--ink-faint)}
.wiki-entity-rel{margin-top:8px; display:flex; align-items:center; gap:8px; flex-wrap:wrap}
.wiki-rel-label{font-size:11px; color:var(--ink-faint)}
.wiki-rel-chip{font-size:11px; color:var(--ink-dim); background:var(--surface-2);
  border-radius:999px; padding:3px 9px}

/* 操作行/上下条/回声面板与正文同一条右边界；间距收了一档，短文页不再显得空荡 */
.read-actions{display:flex; align-items:center; gap:12px; margin:46px 0 24px; flex-wrap:wrap;
  max-width:var(--prose-max,100%)}
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
.read-nav{display:grid; grid-template-columns:1fr 1fr; gap:16px; margin-top:30px;
  max-width:var(--prose-max,100%)}
.read-nav-cell{border:1px solid var(--line); border-radius:var(--r-md); padding:18px 20px;
  background:var(--surface); cursor:pointer; transition:all .3s}
.read-nav-cell:hover{border-color:var(--primary); transform:translateY(-3px)}
.read-nav-cell small{font-family:var(--font-mono); font-size:10px; color:var(--ink-faint); letter-spacing:.2em}
.read-nav-cell h5{font-family:var(--font-serif); font-size:15px; margin-top:8px; line-height:1.6; font-weight:600}
.read-nav-cell.next{text-align:right}

.comments-panel{margin-top:56px; border-top:1px solid var(--line); padding-top:30px;
  max-width:var(--prose-max,100%)}
.comments-head{display:flex; align-items:flex-end; justify-content:space-between; gap:16px; margin-bottom:24px}
.comments-head .kicker{margin:0}
.comments-head h3{font-family:var(--font-serif); font-size:26px; font-weight:900}
.comments-count{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint)}
.comment-compose{border:1px solid var(--line); background:var(--surface); padding:16px; margin-bottom:26px}
.comment-compose textarea{width:100%; border:0; outline:none; resize:vertical; min-height:72px; background:transparent; color:var(--ink); font:inherit; line-height:1.8}
.comment-compose-foot{display:flex; justify-content:space-between; align-items:center; gap:12px; margin-top:8px}
.comment-hint{font-size:11px; color:var(--ink-faint)}
.comment-hint span{color:var(--rose)}
.comment-submit{height:36px; padding:0 16px; border-radius:var(--r-sm); font-size:12px}
.comment-submit:disabled{opacity:.6; cursor:wait}
.comment-login-hint,.comments-empty{font-size:13px; color:var(--ink-faint); padding:14px 0}
.comment-list{list-style:none; margin:0; padding:0}
.comment-item{display:flex; gap:12px; padding:18px 0; border-bottom:1px solid var(--line)}
.comment-main{min-width:0; flex:1}
.comment-meta{display:flex; align-items:center; gap:10px; margin-bottom:6px}
.comment-meta b{font-size:13px; color:var(--ink-dim)}
.comment-meta time{font-family:var(--font-mono); color:var(--ink-faint); font-size:10px}
.comment-main p{white-space:pre-wrap; margin:0; line-height:1.8; font-size:14px; color:var(--ink)}
.comment-delete{margin-left:auto; border:0; background:transparent; color:var(--ink-faint); font-size:11px; cursor:pointer}
.comment-delete:hover{color:var(--rose)}

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
  /* 窄屏工具条折两行：返回在上、阅读设置在右下 */
  .read-bar{margin-bottom:16px}
  .tool-panel{right:auto; left:0; width:min(300px,86vw)}
}
</style>
