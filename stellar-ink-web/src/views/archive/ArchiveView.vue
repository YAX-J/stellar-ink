<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { usePostStore } from '@/stores/posts'
import SectionHead from '@/components/common/SectionHead.vue'
import StarMapCanvas from '@/components/canvas/StarMapCanvas.vue'
import { fmt } from '@/utils/format'
import AuthorBadge from '@/components/common/AuthorBadge.vue'

const router = useRouter()
const postStore = usePostStore()

const yearAll = computed(() => [...new Set(postStore.posts.map((post) => post.year).filter(Boolean))]
  .sort((a, b) => b - a))
const activeYears = ref([])
const view = ref('map')
/* 标签筛选从光谱页并进来：标签只是文章的一个筛选维度，不值得单占一个一级页面。
   年份与标签是叠加关系（先挑年份，再挑星座）。 */
const activeTag = ref('')
const visiblePosts = computed(() => postStore.posts.filter((post) =>
  activeYears.value.includes(post.year) && (!activeTag.value || post.tags.includes(activeTag.value))))
const constellationCount = computed(() => new Set(visiblePosts.value.flatMap((post) => post.tags)).size)

/** 标签计数优先用 /tags 的全站口径，接口没接上时退化成「已加载文章里数一遍」 */
const tagCounts = computed(() => {
  if (postStore.tags.length) {
    return postStore.tags
      .map((tag) => ({ name: tag.name, count: Number(tag.count) || 0 }))
      .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
  }
  const counts = new Map()
  for (const post of postStore.posts) {
    for (const tag of post.tags) counts.set(tag, (counts.get(tag) || 0) + 1)
  }
  return [...counts.entries()]
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
})

onMounted(() => {
  postStore.ensureLoaded().catch(() => {})
  postStore.fetchTags().catch(() => {})
})

/* 标签可能因为文章被删/改而消失，选中项要跟着收敛，否则会停在「空白星座」上 */
watch(tagCounts, (list) => {
  if (activeTag.value && !list.some((tag) => tag.name === activeTag.value)) activeTag.value = ''
})

watch(yearAll, (years) => {
  const available = new Set(years)
  activeYears.value = activeYears.value.filter((year) => available.has(year))
  if (!activeYears.value.length) activeYears.value = [...years]
}, { immediate: true })

function toggleYear(y) {
  const i = activeYears.value.indexOf(y)
  if (i >= 0) {
    if (activeYears.value.length > 1) activeYears.value.splice(i, 1)
  } else {
    activeYears.value.push(y)
  }
}
function toggleTag(tag) {
  activeTag.value = activeTag.value === tag ? '' : tag
}
function resetFilter() {
  activeTag.value = ''
  activeYears.value = [...yearAll.value]
}
function openPost(p) {
  router.push(`/read/${p.id}`)
}
</script>

<template>
  <section class="page">
    <SectionHead title="思想星图" kicker="CONSTELLATION ARCHIVE · 不是列表，是星空" more="悬停查看 · 点击阅读" />

    <div class="map-controls reveal" style="--d:.08s">
      <button
        v-for="y in yearAll" :key="y"
        class="year-chip" :class="{ on: activeYears.includes(y) }" @click="toggleYear(y)"
      >{{ y }}</button>
      <button v-if="activeTag || activeYears.length !== yearAll.length" class="reset-chip" @click="resetFilter">
        清除筛选 ×
      </button>
      <div class="view-switch">
        <button :class="{ on: view === 'map' }" @click="view = 'map'">✧ 星图</button>
        <button :class="{ on: view === 'river' }" @click="view = 'river'">☰ 长卷</button>
      </div>
    </div>

    <!-- 标签星座：原光谱页的筛选能力，宽度即文章条数（/tags 口径），与年份叠加 -->
    <div v-if="tagCounts.length" class="tag-row reveal" style="--d:.1s">
      <button
        class="tag-chip" :class="{ on: !activeTag }" title="不限标签"
        @click="activeTag = ''"
      >全部星座</button>
      <button
        v-for="tag in tagCounts" :key="tag.name"
        class="tag-chip" :class="{ on: activeTag === tag.name }"
        :title="`#${tag.name} · ${tag.count} 篇`" @click="toggleTag(tag.name)"
      >#{{ tag.name }}<small>{{ tag.count }}</small></button>
    </div>

    <p v-if="postStore.loading && !postStore.posts.length" class="state-text">正在读取星图…</p>
    <p v-else-if="postStore.error && !postStore.posts.length" class="state-text error-text">
      {{ postStore.error }} <button class="state-action" @click="postStore.fetchPosts()">重新读取</button>
    </p>

    <div class="map-stage reveal" style="--d:.14s" :class="{ river: view === 'river' }">
      <StarMapCanvas v-show="view === 'map'" :years="activeYears" :tag="activeTag" @open="openPost" />
      <div class="map-legend">
        <i style="background:var(--amber)"></i>星体大小 = 字数 &nbsp;
        <i style="background:var(--primary)"></i>连线 = 相同标签<br>
        共 {{ visiblePosts.length }} 颗星 · {{ constellationCount }} 个标签星座<template v-if="activeTag"> · 当前 #{{ activeTag }}</template>
      </div>
      <div v-if="view === 'river'" class="scroll-view">
        <div class="river-line"></div>
        <div
          v-for="(p, i) in visiblePosts" :key="p.id"
          class="river-item" :class="i % 2 ? 'even' : 'odd'" @click="openPost(p)"
        >
          <span class="d">{{ p.date }}</span>
          <h4>{{ p.title }}</h4>
          <AuthorBadge class="river-author" :user-id="p.userId" compact />
          <p>{{ fmt(p.words) }} 字 · #{{ p.tags.join(' #') }}</p>
        </div>
        <p v-if="!visiblePosts.length && !postStore.loading" class="state-text">所选年份还没有已发布的星。</p>
      </div>
    </div>
    <div v-if="postStore.hasMore" class="load-row">
      <button class="btn btn-ghost" :disabled="postStore.loading" @click="postStore.loadMore()">
        {{ postStore.loading ? '正在接收…' : `继续接收 · 还有 ${postStore.total - postStore.posts.length} 颗星` }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.map-controls{display:flex; gap:12px; align-items:center; flex-wrap:wrap; margin-bottom:22px}
.state-text{color:var(--ink-faint); font-size:13px; line-height:1.8}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.error-text{color:var(--rose)}
.load-row{display:flex; justify-content:center; margin-top:24px}
.load-row .btn:disabled{opacity:.55; cursor:wait}
.year-chip{
  border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  border-radius:99px; padding:8px 18px; font-family:var(--font-mono); font-size:13px;
  cursor:pointer; transition:all .25s var(--ease-spring);
}
.year-chip:hover{transform:translateY(-2px); color:var(--ink)}
.year-chip.on{background:var(--amber); border-color:var(--amber); color:#1a1206; font-weight:600}
.reset-chip{
  border:1px solid var(--line); background:transparent; color:var(--ink-faint); cursor:pointer;
  border-radius:99px; padding:8px 16px; font-family:var(--font-mono); font-size:11px;
  letter-spacing:.1em; transition:all .25s var(--ease-spring);
}
.reset-chip:hover{color:var(--primary); border-color:var(--primary)}
/* 标签星座（原光谱页）：一行可横向滚动的 chips，不与年份抢视觉重量 */
.tag-row{display:flex; gap:8px; flex-wrap:wrap; margin:-8px 0 22px}
.tag-chip{
  display:inline-flex; align-items:center; gap:6px; cursor:pointer;
  border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  border-radius:99px; padding:7px 15px; font-size:13px; font-family:var(--font-body);
  transition:all .25s var(--ease-spring);
}
.tag-chip small{font-family:var(--font-mono); font-size:10px; color:var(--ink-faint)}
.tag-chip:hover{transform:translateY(-2px); color:var(--ink)}
.tag-chip.on{border-color:var(--primary); background:var(--primary-soft); color:var(--primary)}
.tag-chip.on small{color:var(--primary)}
.view-switch{margin-left:auto; display:flex; border:1px solid var(--line); border-radius:99px;
  overflow:hidden}
.view-switch button{border:none; background:transparent; color:var(--ink-faint); padding:9px 20px;
  font-size:13px; cursor:pointer; transition:all .25s; font-family:var(--font-body)}
.view-switch button.on{background:var(--primary); color:var(--on-primary)}
.map-stage{
  position:relative; border:1px solid var(--line); border-radius:var(--r-lg);
  background:linear-gradient(180deg,var(--bg-2),var(--bg)); overflow:hidden;
}
.map-legend{position:absolute; left:18px; bottom:16px; font-family:var(--font-mono);
  font-size:10px; color:var(--ink-faint); letter-spacing:.12em; line-height:2; pointer-events:none}
.map-legend i{display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:6px}
.map-stage.river .map-legend{display:none}

/* 长卷视图 */
.scroll-view{display:block; padding:40px 0 20px; position:relative}
.river-line{position:absolute; left:50%; top:0; bottom:0; width:1px;
  background:linear-gradient(180deg,transparent,var(--primary),var(--amber),transparent)}
.river-item{position:relative; width:calc(50% - 40px); padding:22px 24px; margin-bottom:34px;
  border:1px solid var(--line); border-radius:var(--r-md); background:var(--surface);
  transition:transform .3s var(--ease-spring), border-color .3s; cursor:pointer}
.river-item:hover{transform:scale(1.03); border-color:var(--primary)}
.river-item.odd{margin-left:auto; margin-right:40px}
.river-item.even{margin-left:40px}
.river-item::after{content:''; position:absolute; top:30px; width:10px; height:10px;
  border-radius:50%; background:var(--amber); box-shadow:0 0 12px var(--amber)}
.river-item.odd::after{left:-46px}
.river-item.even::after{right:-46px}
.river-item .d{font-family:var(--font-mono); font-size:11px; color:var(--amber); letter-spacing:.15em}
.river-item h4{font-family:var(--font-serif); font-size:17px; margin:8px 0 6px}
.river-author{margin-bottom:8px; font-family:var(--font-mono); font-size:10px}
.river-item p{font-size:12px; color:var(--ink-faint); font-family:var(--font-mono)}

@media (max-width:720px){
  .river-item{width:auto; margin:0 0 20px 30px !important}
  .river-line{left:8px}
  .river-item::after{left:-28px !important; right:auto !important}
}
</style>
