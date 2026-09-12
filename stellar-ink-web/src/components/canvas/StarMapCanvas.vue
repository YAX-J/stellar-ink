<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { usePostStore } from '@/stores/posts'
import { fitCanvas } from '@/utils/canvas'
import { fmt } from '@/utils/format'
import { useAuthorStore } from '@/stores/authors'

const props = defineProps({ years: { type: Array, required: true } })
const emit = defineEmits(['open'])
const postStore = usePostStore()
const authorStore = useAuthorStore()

const cvs = ref(null)
const tipData = ref(null)
const tipStyle = ref({})
let raf = 0
let pts = []

/* 过滤带坐标的 pts（而非原始 posts），否则 x/y/r 为 undefined 会导致绘制静默失败 */
const visible = () => pts.filter((p) => props.years.includes(p.year))

function frame() {
  const c = cvs.value
  const ctx = fitCanvas(c)
  const w = c.width, h = c.height
  const t = Date.now() / 800

  pts = postStore.posts.map((p, i) => {
    const gx = (i * 97) % 88 + 6
    const gy = (i * 53 + ((i * 31) % 17)) % 80 + 10
    return { ...p, x: (gx / 100) * w, y: (gy / 100) * h, r: Math.max(4, Math.sqrt(p.words) / 7) }
  })

  ctx.clearRect(0, 0, w, h)
  const vis = visible()
  /* 连线：相同标签 */
  ctx.lineWidth = 1.5
  for (let i = 0; i < vis.length; i++) {
    for (let j = i + 1; j < vis.length; j++) {
      if (vis[i].tags.some((g) => vis[j].tags.includes(g))) {
        ctx.beginPath()
        ctx.moveTo(vis[i].x, vis[i].y)
        ctx.lineTo(vis[j].x, vis[j].y)
        ctx.strokeStyle = 'rgba(139,124,255,.22)'
        ctx.stroke()
      }
    }
  }
  vis.forEach((p, i) => {
    const tw = ((Math.sin(t + i * 1.7) + 1) / 2) * .35 + .65
    const hot = p.year === 2026
    ctx.beginPath()
    ctx.arc(p.x, p.y, p.r * tw, 0, 7)
    ctx.fillStyle = hot ? '#FFB454' : '#8B7CFF'
    ctx.shadowColor = ctx.fillStyle
    ctx.shadowBlur = 20 * tw
    ctx.fill()
    ctx.shadowBlur = 0
    ctx.beginPath()
    ctx.arc(p.x, p.y, p.r * tw + 5, 0, 7)
    ctx.strokeStyle = hot ? 'rgba(255,180,84,.35)' : 'rgba(139,124,255,.3)'
    ctx.lineWidth = 1
    ctx.stroke()
  })
  raf = requestAnimationFrame(frame)
}

function pick(e) {
  const c = cvs.value
  const r = c.getBoundingClientRect()
  const mx = (e.clientX - r.left) * (c.width / r.width)
  const my = (e.clientY - r.top) * (c.height / r.height)
  let best = null, bd = 1e9
  for (const p of visible()) {
    const d = Math.hypot(p.x - mx, p.y - my)
    if (d < bd) { bd = d; best = p }
  }
  return best && bd < best.r * 2 + 26 ? { best, r } : null
}

function onMove(e) {
  const hit = pick(e)
  if (hit) {
    const { best, r } = hit
    tipData.value = best
    tipStyle.value = {
      left: Math.min(e.clientX - r.left + 16, r.width - 240) + 'px',
      top: e.clientY - r.top - 10 + 'px',
    }
  } else {
    tipData.value = null
  }
}

function onLeave() {
  tipData.value = null
}

function onClick(e) {
  const hit = pick(e)
  if (hit) emit('open', hit.best)
}

onMounted(frame)
onUnmounted(() => cancelAnimationFrame(raf))
</script>

<template>
  <div class="star-map-wrap">
    <canvas ref="cvs" class="star-map" @mousemove="onMove" @mouseleave="onLeave" @click="onClick"></canvas>
    <div class="map-tip" :class="{ show: !!tipData }" :style="tipStyle">
      <template v-if="tipData">
        <h6>{{ tipData.title }}</h6>
        <p>{{ authorStore.find(tipData.userId).nickname }} · {{ tipData.date }} · {{ fmt(tipData.words) }} 字<br>#{{ tipData.tags.join(' #') }}</p>
      </template>
    </div>
  </div>
</template>

<style scoped>
.star-map-wrap{position:relative}
.star-map{width:100%; height:520px; display:block; cursor:crosshair}
</style>
