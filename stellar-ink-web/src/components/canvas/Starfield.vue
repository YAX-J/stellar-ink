<script setup>
import { onMounted, ref, watch } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { useCanvasAnimation } from '@/composables/useCanvasAnimation'

const settings = useSettingsStore()
const cvs = ref(null)
let ink = ''
const stars = Array.from({ length: 130 }, () => ({
  x: Math.random(), y: Math.random(), r: Math.random() * 1.3 + .3,
  p: Math.random() * Math.PI * 2, s: .008 + Math.random() * .015,
}))
function palette() { ink = getComputedStyle(document.documentElement).getPropertyValue('--ink').trim() }
onMounted(palette)
watch(() => settings.theme, palette, { flush: 'post' })
useCanvasAnimation(cvs, (ctx, width, height, time) => {
  ctx.clearRect(0, 0, width, height)
  const dawn = settings.theme === 'dawn'
  ctx.fillStyle = ink
  for (const s of stars) {
    const a = ((Math.sin(s.p + time * s.s * .06) + 1) / 2) * .7 + .15
    ctx.globalAlpha = a * (dawn ? .5 : 1)
    ctx.beginPath()
    ctx.arc(s.x * width, s.y * height, s.r * 2, 0, 7)
    ctx.fill()
  }
  ctx.globalAlpha = 1
}, { sources: () => settings.theme })
</script>

<template>
  <canvas ref="cvs" class="starfield" aria-hidden="true"></canvas>
</template>

<style scoped>
/* 明确 CSS 尺寸，避免 HiDPI 位图反过来改变替换元素的固有尺寸。 */
.starfield{width:100%; height:100%}
</style>
