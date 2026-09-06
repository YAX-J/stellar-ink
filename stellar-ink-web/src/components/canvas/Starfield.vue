<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'

const settings = useSettingsStore()
const cvs = ref(null)
let ctx = null
let stars = []
let raf = 0

function init() {
  const c = cvs.value
  c.width = innerWidth
  c.height = innerHeight
  stars = Array.from({ length: 130 }, () => ({
    x: Math.random() * c.width,
    y: Math.random() * c.height,
    r: Math.random() * 1.3 + .3,
    p: Math.random() * Math.PI * 2,
    s: .008 + Math.random() * .015,
  }))
}

function loop() {
  ctx.clearRect(0, 0, cvs.value.width, cvs.value.height)
  const dawn = settings.theme === 'dawn'
  for (const s of stars) {
    s.p += s.s
    const a = ((Math.sin(s.p) + 1) / 2) * .7 + .15
    ctx.beginPath()
    ctx.arc(s.x, s.y, s.r, 0, 7)
    ctx.fillStyle = dawn ? `rgba(90,75,60,${a * .5})` : `rgba(237,237,247,${a})`
    ctx.fill()
  }
  raf = requestAnimationFrame(loop)
}

onMounted(() => {
  ctx = cvs.value.getContext('2d')
  init()
  addEventListener('resize', init)
  loop()
})
onUnmounted(() => {
  cancelAnimationFrame(raf)
  removeEventListener('resize', init)
})
</script>

<template>
  <canvas ref="cvs" class="starfield"></canvas>
</template>
