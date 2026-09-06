<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useLinkStore } from '@/stores/links'
import { fitCanvas } from '@/utils/canvas'

const emit = defineEmits(['select'])
const linkStore = useLinkStore()

const cvs = ref(null)
const tipData = ref(null)
const tipStyle = ref({})
let raf = 0
let pts = []
let hoverLink = -1

function frame() {
  const c = cvs.value
  const ctx = fitCanvas(c)
  const t = Date.now() / 700
  const ink = getComputedStyle(document.body).getPropertyValue('--ink').trim() || '#EDEDF7'

  pts = linkStore.friends.map((f) => ({ ...f, px: f.x * c.width, py: f.y * c.height }))
  ctx.clearRect(0, 0, c.width, c.height)
  ctx.strokeStyle = 'rgba(77,201,217,.25)'
  ctx.lineWidth = 1.5
  for (const [a, b] of linkStore.edges) {
    if (!pts[a] || !pts[b]) continue
    ctx.beginPath()
    ctx.moveTo(pts[a].px, pts[a].py)
    ctx.lineTo(pts[b].px, pts[b].py)
    ctx.stroke()
  }
  ctx.textAlign = 'center'
  pts.forEach((p, i) => {
    const tw = ((Math.sin(t + i * 2) + 1) / 2) * .4 + .6
    ctx.beginPath()
    ctx.arc(p.px, p.py, 7 * tw + 3, 0, 7)
    ctx.fillStyle = i === hoverLink ? '#FFB454' : '#4DC9D9'
    ctx.shadowColor = ctx.fillStyle
    ctx.shadowBlur = 18 * tw
    ctx.fill()
    ctx.shadowBlur = 0
    ctx.fillStyle = ink
    ctx.font = `500 ${20}px 'Noto Sans SC'`
    ctx.fillText(p.n, p.px, p.py + 34)
  })
  raf = requestAnimationFrame(frame)
}

function pick(e) {
  const c = cvs.value
  const r = c.getBoundingClientRect()
  const mx = (e.clientX - r.left) * (c.width / r.width)
  const my = (e.clientY - r.top) * (c.height / r.height)
  let best = null, bd = 1e9, bi = -1
  pts.forEach((p, i) => {
    const d = Math.hypot(p.px - mx, p.py - my)
    if (d < bd) { bd = d; best = p; bi = i }
  })
  return best && bd < 70 ? { best, bi, r } : null
}

function onMove(e) {
  const hit = pick(e)
  if (hit) {
    hoverLink = hit.bi
    tipData.value = hit.best
    tipStyle.value = {
      left: Math.min(e.clientX - hit.r.left + 16, hit.r.width - 240) + 'px',
      top: e.clientY - hit.r.top - 10 + 'px',
    }
    cvs.value.style.cursor = 'pointer'
  } else {
    hoverLink = -1
    tipData.value = null
    cvs.value.style.cursor = 'default'
  }
}

function onLeave() {
  hoverLink = -1
  tipData.value = null
}

function onClick(e) {
  const hit = pick(e)
  if (hit) emit('select', hit.bi)
}

onMounted(frame)
onUnmounted(() => cancelAnimationFrame(raf))
</script>

<template>
  <canvas ref="cvs" class="link-map" @mousemove="onMove" @mouseleave="onLeave" @click="onClick"></canvas>
  <div class="map-tip" :class="{ show: !!tipData }" :style="tipStyle">
    <template v-if="tipData">
      <h6>{{ tipData.n }}</h6>
      <p>{{ tipData.u }}<br>{{ tipData.d }}</p>
    </template>
  </div>
</template>

<style scoped>
.link-map{width:100%; height:340px; display:block; cursor:pointer}
</style>
