<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { usePostStore } from '@/stores/posts'
import { useSettingsStore } from '@/stores/settings'
import { useCanvasAnimation } from '@/composables/useCanvasAnimation'

const postStore = usePostStore()
const settings = useSettingsStore()
const cvs = ref(null)
let primary = '', amber = ''
function palette() {
  const style = getComputedStyle(document.documentElement)
  primary = style.getPropertyValue('--primary').trim()
  amber = style.getPropertyValue('--amber').trim()
}
onMounted(palette)
watch(() => settings.theme, palette, { flush: 'post' })
const points = computed(() => postStore.posts.map((post, i) => ({
  x: .14 + ((i * 73) % 80) / 100,
  y: .12 + ((i * 41) % 76) / 100,
  r: Math.sqrt(Math.max(0, Number(post.words) || 0)) / 14 * 2,
})))
useCanvasAnimation(cvs, (ctx, w, h, time) => {
  ctx.clearRect(0, 0, w, h)
  const pts = points.value
  ctx.strokeStyle = primary
  ctx.globalAlpha = .25
  ctx.lineWidth = 1
  for (let i = 0; i < pts.length - 1; i++) {
    ctx.beginPath()
    ctx.moveTo(pts[i].x * w, pts[i].y * h)
    ctx.lineTo(pts[i + 1].x * w, pts[i + 1].y * h)
    ctx.stroke()
  }
  ctx.globalAlpha = 1
  pts.forEach((p, i) => {
    const tw = ((Math.sin(time / 700 + i) + 1) / 2) * .4 + .6
    ctx.beginPath()
    ctx.arc(p.x * w, p.y * h, p.r * tw, 0, 7)
    ctx.fillStyle = i === 0 ? amber : primary
    ctx.shadowColor = ctx.fillStyle
    ctx.shadowBlur = 18
    ctx.fill()
    ctx.shadowBlur = 0
  })
}, { sources: () => [points.value, settings.theme] })
</script>

<template>
  <canvas ref="cvs" class="mini-map" aria-hidden="true"></canvas>
</template>

<style scoped>
.mini-map{width:100%; height:100%; display:block}
</style>
