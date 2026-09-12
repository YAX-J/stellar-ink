<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { usePostStore } from '@/stores/posts'
import { useStatsStore } from '@/stores/stats'
import { useAuthStore } from '@/stores/auth'
import SectionHead from '@/components/common/SectionHead.vue'
import PostCard from '@/components/post/PostCard.vue'
import PolarisCard from '@/components/post/PolarisCard.vue'
import MiniStarMap from '@/components/canvas/MiniStarMap.vue'

const router = useRouter()
const postStore = usePostStore()
const statsStore = useStatsStore()
const auth = useAuthStore()

onMounted(() => Promise.all([
  postStore.ensureLoaded(),
  statsStore.fetchOverview(),
]).catch(() => {}))

const tickerPosts = computed(() => postStore.posts.slice(0, 7))
const recentPosts = computed(() => postStore.posts.slice(1, 5))
/* 连续写作环：21/30 */
const streakDays = computed(() => statsStore.overview?.streakDays ?? 0)
const streakOffset = computed(() => (2 * Math.PI * 64) * (1 - Math.min(streakDays.value, 30) / 30))
const todayWords = computed(() => statsStore.overview?.todayWords ?? 0)
const totalWords = computed(() => statsStore.overview?.totalWords ?? postStore.totalWords)
const totalPosts = computed(() => statsStore.overview?.totalPosts ?? postStore.posts.length)
const nightRatio = computed(() => statsStore.overview?.nightRatio ?? 0)
const canWrite = computed(() => auth.isAuthorOrAbove)

function openPost(post) {
  router.push(`/read/${post.id}`)
}
</script>

<template>
  <section class="page">
    <div class="hero">
      <div>
        <div class="kicker reveal" style="--d:.05s">第 128 夜 · 晴 · 宜书写</div>
        <h1 class="reveal" style="--d:.12s">把思绪<br>挂成<em>星图</em></h1>
        <p class="hero-sub reveal" style="--d:.2s">
          这里不是又一个博客。每一篇文章都是一颗星，每一次书写都是一次发射。
          今晚你已经写下 <b>{{ todayWords.toLocaleString() }}</b> 字 —— 星图上又多了一点微光。
        </p>
        <div class="hero-actions reveal" style="--d:.28s">
          <RouterLink v-if="canWrite" class="btn btn-primary" to="/write">✎ 今晚写点什么</RouterLink>
          <RouterLink class="btn btn-ghost" to="/archive">✧ 逛逛星图</RouterLink>
        </div>
      </div>
      <div class="hero-canvas-wrap reveal" style="--d:.34s">
        <MiniStarMap />
        <div class="hero-canvas-tag">LIVE · 你的思想星域</div>
      </div>
    </div>

    <div class="ticker-band reveal" style="--d:.4s">
      <div class="ticker">
        <span v-for="(p, i) in [...tickerPosts, ...tickerPosts]" :key="i">{{ p.title }}</span>
      </div>
    </div>

    <SectionHead title="北极星">本周最受回望的一篇 →</SectionHead>
    <p v-if="postStore.loading && !postStore.posts.length" class="state-text">正在读取星图…</p>
    <p v-else-if="postStore.error && !postStore.posts.length" class="state-text">
      {{ postStore.error }} <button class="state-action" @click="postStore.fetchPosts()">重新读取</button>
    </p>
    <PolarisCard @open="openPost" />

    <SectionHead title="最近星尘" more="全部归档 →" @more="router.push('/archive')" />
    <div class="stardust">
      <PostCard
        v-for="(p, i) in recentPosts" :key="p.id"
        :post="p" :delay=".06 * i" @open="openPost"
      />
    </div>

    <SectionHead title="写作脉搏">数据截至今夜 23:59</SectionHead>
    <div class="pulse-grid reveal" style="--d:.06s">
      <div class="pulse-card ring-wrap">
        <svg width="150" height="150" viewBox="0 0 150 150">
          <circle cx="75" cy="75" r="64" fill="none" stroke="var(--surface-2)" stroke-width="10" />
          <circle cx="75" cy="75" r="64" fill="none" stroke="url(#gradA)" stroke-width="10"
            stroke-linecap="round" stroke-dasharray="402" :stroke-dashoffset="streakOffset" />
          <defs>
            <linearGradient id="gradA" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stop-color="#8B7CFF" />
              <stop offset="1" stop-color="#FF6B9D" />
            </linearGradient>
          </defs>
        </svg>
        <div class="ring-txt"><div><b>{{ streakDays }}</b> 天<small>连续写作</small></div></div>
      </div>
      <div class="pulse-card"><div class="num">{{ Math.round(totalWords / 1000) }}<i>K</i></div><div class="lbl">累计星尘 · 总字数</div></div>
      <div class="pulse-card"><div class="num">{{ totalPosts }}<i>篇</i></div><div class="lbl">已点亮 · 文章数</div></div>
      <div class="pulse-card"><div class="num" style="color:var(--amber)">☾ {{ nightRatio }}%</div><div class="lbl">写于夜晚 22 点后</div></div>
    </div>

    <div class="foot">
      <span>© 2026 星笺 STELLAR INK</span>
      <span>由 {{ todayWords.toLocaleString() }} 个今晚的字驱动</span>
      <span>RSS · 星链 · 关于</span>
    </div>
  </section>
</template>

<style scoped>
.hero{display:grid; grid-template-columns:1.35fr .9fr; gap:40px; align-items:center;
  min-height:64vh; position:relative}
.hero h1{
  font-family:var(--font-serif); font-weight:900;
  font-size:clamp(44px,6.2vw,84px); line-height:1.14; letter-spacing:.02em;
}
.hero h1 em{
  font-style:normal;
  background:linear-gradient(100deg,var(--primary) 10%,var(--rose) 55%,var(--amber) 95%);
  -webkit-background-clip:text; background-clip:text; -webkit-text-fill-color:transparent;
}
.hero-sub{margin-top:22px; color:var(--ink-dim); font-size:15px; line-height:2; max-width:44ch}
.hero-sub b{color:var(--amber); font-weight:500}
.hero-actions{margin-top:34px; display:flex; gap:14px; flex-wrap:wrap}
.hero-canvas-wrap{
  position:relative; height:420px; border-radius:var(--r-lg); overflow:hidden;
  border:1px solid var(--line); background:linear-gradient(180deg,var(--bg-2),var(--bg-3));
}
.hero-canvas-tag{
  position:absolute; left:16px; bottom:14px; font-family:var(--font-mono);
  font-size:10px; letter-spacing:.25em; color:var(--ink-faint);
}

/* 思绪走马灯 */
.ticker-band{
  margin:10px -999px 0; padding:14px 999px; border-top:1px solid var(--line);
  border-bottom:1px solid var(--line); overflow:hidden; white-space:nowrap;
  background:var(--surface);
}
.ticker{display:inline-block; animation:scroll 36s linear infinite}
.ticker span{font-family:var(--font-serif); font-size:14px; color:var(--ink-dim); margin:0 34px}
.ticker span::before{content:'✦ '; color:var(--amber)}
@keyframes scroll{to{transform:translateX(-50%)}}

/* 星尘卡片流 */
.stardust{display:grid; grid-template-columns:repeat(auto-fill,minmax(258px,1fr)); gap:18px}
.state-text{color:var(--ink-faint); font-size:13px; line-height:1.8}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}

/* 写作脉搏 */
.pulse-grid{display:grid; grid-template-columns:280px 1fr 1fr 1fr; gap:18px}
.pulse-card{border:1px solid var(--line); border-radius:var(--r-md); background:var(--surface);
  padding:26px; display:flex; flex-direction:column; justify-content:center; gap:6px;
  transition:transform .3s var(--ease-spring)}
.pulse-card:hover{transform:translateY(-4px)}
.pulse-card .num{font-family:var(--font-display); font-weight:700; font-size:40px; letter-spacing:-.02em}
.pulse-card .num i{font-style:normal; font-size:15px; color:var(--ink-faint); margin-left:4px}
.pulse-card .lbl{font-size:12px; color:var(--ink-faint); letter-spacing:.15em}
.ring-wrap{align-items:center; text-align:center; position:relative}
.ring-wrap svg{transform:rotate(-90deg)}
.ring-txt{position:absolute; inset:0; display:grid; place-items:center}
.ring-txt b{font-family:var(--font-display); font-size:34px; font-weight:700}
.ring-txt small{display:block; font-size:11px; color:var(--ink-faint); letter-spacing:.2em; margin-top:2px}

@media (max-width:980px){
  .hero{grid-template-columns:1fr}
  .pulse-grid{grid-template-columns:1fr 1fr}
}
</style>
