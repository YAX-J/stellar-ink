<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePostStore } from '@/stores/posts'
import { useSettingsStore } from '@/stores/settings'
import { fmt, readMinutes } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const postStore = usePostStore()
const settings = useSettingsStore()

const post = computed(() => postStore.byId(route.params.id))
const idx = computed(() => postStore.posts.findIndex((p) => p.id === Number(route.params.id)))

/* 正文：演示段落池循环取用 */
const pa = (n) => postStore.paraPool[(idx.value + n) % postStore.paraPool.length]

const prevPost = computed(() => {
  const len = postStore.posts.length
  return postStore.posts[(idx.value - 1 + len) % len]
})
const nextPost = computed(() => postStore.posts[(idx.value + 1) % postStore.posts.length])

const glowPulse = ref(false)
const barWidth = ref(0)

function addGlow() {
  if (!post.value) return
  postStore.addGlow(post.value.id)
  glowPulse.value = true
  setTimeout(() => (glowPulse.value = false), 180)
}
function onScroll() {
  const h = document.documentElement
  barWidth.value = (h.scrollTop / (h.scrollHeight - h.clientHeight || 1)) * 100
}
function go(id) {
  router.push(`/read/${id.id ?? id}`)
}

watch(
  () => route.params.id,
  () => {
    if (!postStore.byId(route.params.id)) router.replace('/')
    onScroll()
  },
  { immediate: true },
)
onMounted(() => addEventListener('scroll', onScroll, { passive: true }))
onUnmounted(() => removeEventListener('scroll', onScroll))
</script>

<template>
  <section v-if="post" class="page">
    <div class="read-progress"><i :style="{ width: barWidth + '%' }"></i></div>
    <article class="read-wrap">
      <button class="read-back" @click="router.push(settings.lastPage)">← 返回星域</button>
      <div class="kicker reveal">DEEP READING · 深读舱</div>
      <h1 class="read-title reveal" style="--d:.06s">{{ post.title }}</h1>
      <div class="read-meta reveal" style="--d:.12s">
        <span>{{ post.date }}</span>
        <span>{{ fmt(post.words) }} 字 · 约 {{ readMinutes(post.words) }} 分钟</span>
        <span>#{{ post.tags.join(' #') }}</span>
        <span>☾ 写于深夜</span>
      </div>
      <div class="read-body reveal" style="--d:.18s">
        <p class="dropcap">{{ pa(0) }}</p>
        <p>{{ pa(1) }}</p>
        <blockquote>“犹豫的地方，往往就是光进来的地方。”</blockquote>
        <p>{{ pa(2) }}</p>
        <div class="marg-note"><b>✎ 批注</b>这一段写于第三次修改，原稿有八百字，删剩两句。</div>
        <p>{{ pa(3) }}</p>
        <h3>夜的第二部分</h3>
        <p>{{ pa(4) }}</p>
        <p>{{ pa(5) }}</p>
      </div>
      <div class="read-actions reveal" style="--d:.24s">
        <button class="glow-btn" :style="glowPulse ? 'transform:scale(.94)' : ''" @click="addGlow">
          ✦ 为这颗星补充光芒 <b>{{ post.glow }}</b>
        </button>
      </div>
      <div class="read-nav reveal" style="--d:.3s">
        <div class="read-nav-cell" @click="go(prevPost)">
          <small>← 上一颗星</small>
          <h5>{{ prevPost.title }}</h5>
        </div>
        <div class="read-nav-cell next" @click="go(nextPost)">
          <small>下一颗星 →</small>
          <h5>{{ nextPost.title }}</h5>
        </div>
      </div>
    </article>
  </section>
</template>

<style scoped>
.read-progress{position:fixed; top:0; left:96px; right:0; height:3px; z-index:40}
.read-progress i{display:block; height:100%; width:0;
  background:linear-gradient(90deg,var(--primary),var(--rose),var(--amber))}
.read-back{border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  border-radius:99px; padding:9px 20px; font-size:13px; cursor:pointer; margin-bottom:34px;
  transition:all .25s; font-family:var(--font-body)}
.read-back:hover{color:var(--primary); border-color:var(--primary); transform:translateX(-3px)}
.read-wrap{max-width:720px; margin:0 auto}
.read-title{font-family:var(--font-serif); font-weight:900;
  font-size:clamp(30px,4.4vw,52px); line-height:1.3; margin:12px 0 20px}
.read-meta{display:flex; gap:20px; flex-wrap:wrap; font-family:var(--font-mono); font-size:12px;
  color:var(--ink-faint); padding-bottom:26px; border-bottom:1px solid var(--line); margin-bottom:38px}
.read-body p{font-size:17px; line-height:2.3; color:var(--ink-dim); margin-bottom:30px}
.read-body p.dropcap::first-letter{font-family:var(--font-serif); font-weight:900; font-size:56px;
  float:left; line-height:1; margin:6px 12px 0 0; color:var(--primary)}
.read-body blockquote{font-family:var(--font-serif); font-size:clamp(20px,2.6vw,27px); font-weight:600;
  line-height:1.8; color:var(--ink); border-left:3px solid var(--amber); padding:6px 0 6px 24px; margin:40px 0}
.marg-note{background:var(--primary-soft); border:1px dashed var(--primary); border-radius:var(--r-sm);
  padding:14px 18px; font-size:13px; line-height:1.9; color:var(--ink-dim); margin:-8px 0 30px}
.marg-note b{color:var(--primary); margin-right:8px; font-size:12px}
.read-body h3{font-family:var(--font-serif); font-weight:900; font-size:24px; margin:44px 0 18px;
  display:flex; align-items:center; gap:14px}
.read-body h3::before{content:'✦'; color:var(--amber); font-size:16px}
.read-actions{text-align:center; margin:56px 0 30px}
.glow-btn{border:1px solid var(--amber); background:rgba(255,180,84,.1); color:var(--amber);
  border-radius:99px; padding:14px 34px; font-size:15px; cursor:pointer;
  transition:all .3s var(--ease-spring); font-family:var(--font-body)}
.glow-btn:hover{transform:scale(1.05); box-shadow:0 0 30px rgba(255,180,84,.25)}
.glow-btn b{font-family:var(--font-mono); margin-left:6px}
.read-nav{display:grid; grid-template-columns:1fr 1fr; gap:16px; margin-top:30px}
.read-nav-cell{border:1px solid var(--line); border-radius:var(--r-md); padding:18px 20px;
  background:var(--surface); cursor:pointer; transition:all .3s}
.read-nav-cell:hover{border-color:var(--primary); transform:translateY(-3px)}
.read-nav-cell small{font-family:var(--font-mono); font-size:10px; color:var(--ink-faint); letter-spacing:.2em}
.read-nav-cell h5{font-family:var(--font-serif); font-size:15px; margin-top:8px; line-height:1.6; font-weight:600}
.read-nav-cell.next{text-align:right}

@media (max-width:720px){
  .read-nav{grid-template-columns:1fr}
  .read-progress{left:0}
}
</style>
