<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { usePostStore } from '@/stores/posts'
import { fitCanvas } from '@/utils/canvas'

const postStore = usePostStore()
const cvs = ref(null)
let ctx = null
let raf = 0

function draw() {
  const c = cvs.value
  ctx = fitCanvas(c)
  const w = c.width, h = c.height
  ctx.clearRect(0, 0, w, h)

  const pts = postStore.posts.map((p, i) => ({
    x: (.14 + ((i * 73) % 80) / 100) * w,
    y: (.12 + ((i * 41) % 76) / 100) * h,
    r: Math.sqrt(p.words) / 14 * 2,
  }))
  ctx.strokeStyle = 'rgba(139,124,255,.25)'
  ctx.lineWidth = 1
  for (let i = 0; i < pts.length - 1; i++) {
    ctx.beginPath()
    ctx.moveTo(pts[i].x, pts[i].y)
    ctx.lineTo(pts[i + 1].x, pts[i + 1].y)
    ctx.stroke()
  }
  const t = Date.now()
  pts.forEach((p, i) => {
    const tw = ((Math.sin(t / 700 + i) + 1) / 2) * .4 + .6
    ctx.beginPath()
    ctx.arc(p.x, p.y, p.r * tw, 0, 7)
    ctx.fillStyle = i === 0 ? '#FFB454' : '#8B7CFF'
    ctx.shadowColor = ctx.fillStyle
    ctx.shadowBlur = 18
    ctx.fill()
    ctx.shadowBlur = 0
  })
  raf = requestAnimationFrame(draw)
}

onMounted(draw)
onUnmounted(() => cancelAnimationFrame(raf))
</script>

<template>
  <canvas ref="cvs" class="mini-map"></canvas>
</template>

<style scoped>
.mini-map{width:100%; height:100%; display:block}
</style>
