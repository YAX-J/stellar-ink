<script setup>
import { ref } from 'vue'
import { useLinkStore } from '@/stores/links'
import SectionHead from '@/components/common/SectionHead.vue'
import LinkSky from '@/components/canvas/LinkSky.vue'

const linkStore = useLinkStore()
const name = ref('')
const url = ref('')
const flashId = ref(null)

function flash(i) {
  flashId.value = i
  setTimeout(() => (flashId.value = null), 1400)
}

function onSelect(i) {
  const el = document.getElementById(`link-card-${i}`)
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  flash(i)
}

function apply() {
  const n = name.value.trim()
  if (!n) return
  const i = linkStore.apply(n, url.value.trim())
  name.value = ''
  url.value = ''
  const el = document.getElementById(`link-card-${i}`)
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  flash(i)
}
</script>

<template>
  <section class="page">
    <div class="kicker reveal">NEIGHBOR CONSTELLATION · 友链即友邻星座</div>
    <SectionHead title="星链" more="悬停认识他们 · 点击降落" />

    <div class="link-stage reveal" style="--d:.08s">
      <LinkSky @select="onSelect" />
    </div>

    <div class="link-grid reveal" style="--d:.14s">
      <div
        v-for="(f, i) in linkStore.friends" :id="`link-card-${i}`" :key="i"
        class="link-card" :class="{ flash: i === flashId }"
      >
        <b>{{ f.n }}</b>
        <span class="u">{{ f.u }}</span>
        <p>{{ f.d }}</p>
      </div>
    </div>

    <div class="side-card reveal" style="--d:.2s">
      <h5>申请接入星链 · 交换友链</h5>
      <div class="echo-form" style="margin-bottom:0">
        <input v-model="name" class="in-name" placeholder="你的站点名">
        <input v-model="url" class="in-msg" placeholder="站点地址，如 myblog.com" @keydown.enter="apply">
        <button class="btn btn-ghost" @click="apply">⬡ 发送信号</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.link-stage{position:relative; border:1px solid var(--line); border-radius:var(--r-lg);
  background:linear-gradient(180deg,var(--bg-2),var(--bg)); overflow:hidden; margin-bottom:22px}
.link-grid{display:grid; grid-template-columns:repeat(auto-fill,minmax(230px,1fr)); gap:14px; margin-bottom:26px}
.link-card{border:1px solid var(--line); border-radius:var(--r-md); background:var(--surface);
  padding:20px; transition:all .3s var(--ease-spring)}
.link-card:hover,.link-card.flash{transform:translateY(-4px); border-color:var(--teal);
  box-shadow:0 8px 26px rgba(77,201,217,.12)}
.link-card b{font-size:15px; font-weight:500}
.link-card .u{font-family:var(--font-mono); font-size:11px; color:var(--teal); margin:6px 0 8px; display:block}
.link-card p{font-size:12px; color:var(--ink-faint); line-height:1.8}
</style>
