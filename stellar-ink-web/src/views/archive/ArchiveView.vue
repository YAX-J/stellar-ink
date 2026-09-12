<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { usePostStore } from '@/stores/posts'
import SectionHead from '@/components/common/SectionHead.vue'
import StarMapCanvas from '@/components/canvas/StarMapCanvas.vue'
import { fmt } from '@/utils/format'

const router = useRouter()
const postStore = usePostStore()

const yearAll = [2024, 2025, 2026]
const activeYears = ref([2025, 2026])
const view = ref('map')

onMounted(() => postStore.ensureLoaded().catch(() => {}))

function toggleYear(y) {
  const i = activeYears.value.indexOf(y)
  if (i >= 0) {
    if (activeYears.value.length > 1) activeYears.value.splice(i, 1)
  } else {
    activeYears.value.push(y)
  }
}
function openPost(p) {
  router.push(`/read/${p.id}`)
}
</script>

<template>
  <section class="page">
    <div class="kicker reveal">CONSTELLATION ARCHIVE · 不是列表，是星空</div>
    <SectionHead title="思想星图" more="悬停查看 · 点击阅读" />

    <div class="map-controls reveal" style="--d:.08s">
      <button
        v-for="y in yearAll" :key="y"
        class="year-chip" :class="{ on: activeYears.includes(y) }" @click="toggleYear(y)"
      >{{ y }}</button>
      <div class="view-switch">
        <button :class="{ on: view === 'map' }" @click="view = 'map'">✧ 星图</button>
        <button :class="{ on: view === 'river' }" @click="view = 'river'">☰ 长卷</button>
      </div>
    </div>
    <p v-if="postStore.loading && !postStore.posts.length" class="state-text">正在读取星图…</p>
    <p v-else-if="postStore.error && !postStore.posts.length" class="state-text error-text">
      {{ postStore.error }} <button class="state-action" @click="postStore.fetchPosts()">重新读取</button>
    </p>

    <div class="map-stage reveal" style="--d:.14s" :class="{ river: view === 'river' }">
      <StarMapCanvas v-show="view === 'map'" :years="activeYears" @open="openPost" />
      <div class="map-legend">
        <i style="background:var(--amber)"></i>星体大小 = 字数 &nbsp;
        <i style="background:var(--primary)"></i>连线 = 相同标签<br>
        共 {{ postStore.posts.length }} 颗星 · 已连成 9 个星座
      </div>
      <div v-if="view === 'river'" class="scroll-view">
        <div class="river-line"></div>
        <div
          v-for="(p, i) in postStore.posts" :key="p.id"
          class="river-item" :class="i % 2 ? 'even' : 'odd'" @click="openPost(p)"
        >
          <span class="d">{{ p.date }}</span>
          <h4>{{ p.title }}</h4>
          <p>{{ fmt(p.words) }} 字 · #{{ p.tags.join(' #') }}</p>
        </div>
        <p v-if="!postStore.posts.length && !postStore.loading" class="state-text">还没有已发布的星。</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.map-controls{display:flex; gap:12px; align-items:center; flex-wrap:wrap; margin-bottom:22px}
.state-text{color:var(--ink-faint); font-size:13px; line-height:1.8}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.error-text{color:var(--rose)}
.year-chip{
  border:1px solid var(--line); background:var(--surface); color:var(--ink-dim);
  border-radius:99px; padding:8px 18px; font-family:var(--font-mono); font-size:13px;
  cursor:pointer; transition:all .25s var(--ease-spring);
}
.year-chip:hover{transform:translateY(-2px); color:var(--ink)}
.year-chip.on{background:var(--amber); border-color:var(--amber); color:#1a1206; font-weight:600}
.view-switch{margin-left:auto; display:flex; border:1px solid var(--line); border-radius:99px;
  overflow:hidden}
.view-switch button{border:none; background:transparent; color:var(--ink-faint); padding:9px 20px;
  font-size:13px; cursor:pointer; transition:all .25s; font-family:var(--font-body)}
.view-switch button.on{background:var(--primary); color:#fff}
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
.river-item p{font-size:12px; color:var(--ink-faint); font-family:var(--font-mono)}

@media (max-width:720px){
  .river-item{width:auto; margin:0 0 20px 30px !important}
  .river-line{left:8px}
  .river-item::after{left:-28px !important; right:auto !important}
}
</style>
