<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { fitCanvas } from '@/utils/canvas'

const cvs = ref(null)
let ctx = null
let meteors = []
let raf = 0

function spawn() {
  const c = cvs.value
  meteors.push({
    x: c.width * (.35 + Math.random() * .6),
    y: -10,
    vx: -(3 + Math.random() * 3),
    vy: 2 + Math.random() * 1.6,
    life: 1,
  })
}

function frame() {
  const c = cvs.value
  ctx = fitCanvas(c)
  ctx.clearRect(0, 0, c.width, c.height)
  if (Math.random() < .012 && meteors.length < 4) spawn()
  meteors = meteors.filter((m) => m.life > 0)
  for (const m of meteors) {
    m.x += m.vx * 2
    m.y += m.vy * 2
    m.life -= .012
    const tx = m.x + 90, ty = m.y - 44
    const g = ctx.createLinearGradient(m.x, m.y, tx, ty)
    g.addColorStop(0, `rgba(255,180,84,${m.life})`)
    g.addColorStop(1, 'rgba(255,180,84,0)')
    ctx.strokeStyle = g
    ctx.lineWidth = 3
    ctx.beginPath()
    ctx.moveTo(m.x, m.y)
    ctx.lineTo(tx, ty)
    ctx.stroke()
    ctx.beginPath()
    ctx.arc(m.x, m.y, 3.4, 0, 7)
    ctx.fillStyle = `rgba(255,224,170,${m.life})`
    ctx.shadowColor = '#FFB454'
    ctx.shadowBlur = 16
    ctx.fill()
    ctx.shadowBlur = 0
  }
  raf = requestAnimationFrame(frame)
}

onMounted(frame)
onUnmounted(() => cancelAnimationFrame(raf))
defineExpose({ spawn })
</script>

<template>
  <canvas ref="cvs" class="meteor-sky"></canvas>
</template>

<style scoped>
.meteor-sky{width:100%; height:150px; display:block; border:1px solid var(--line);
  border-radius:var(--r-lg); background:linear-gradient(180deg,var(--bg-2),var(--bg))}
</style>
