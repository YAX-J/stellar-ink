<script setup>
import { ref } from 'vue'
import { useMeteorStore } from '@/stores/meteors'
import SectionHead from '@/components/common/SectionHead.vue'
import MeteorSky from '@/components/canvas/MeteorSky.vue'

const meteorStore = useMeteorStore()
const text = ref('')
const sky = ref(null)

function launch() {
  const v = text.value.trim()
  if (!v) return
  meteorStore.launch(v)
  text.value = ''
  sky.value?.spawn()
}
</script>

<template>
  <section class="page">
    <div class="kicker reveal">METEOR MEMO · 流星备忘录</div>
    <SectionHead title="流星" more="长文给星图，碎片给流星" />
    <div class="meteor-launch reveal" style="--d:.08s">
      <input v-model="text" placeholder="此刻划过脑海的…（回车即发射）" @keydown.enter="launch">
      <button class="btn btn-primary" @click="launch">☄ 发射</button>
    </div>
    <MeteorSky ref="sky" class="reveal" style="--d:.14s" />
    <div class="meteor-feed reveal" style="--d:.2s">
      <div v-for="(m, i) in meteorStore.items" :key="i" class="meteor-item">
        <p>{{ m.t }}</p>
        <small>☄ {{ m.d }}</small>
      </div>
    </div>
  </section>
</template>

<style scoped>
.meteor-launch{display:flex; gap:12px; margin-bottom:22px}
.meteor-launch input{flex:1; height:52px; border:1px solid var(--line); border-radius:var(--r-md);
  background:var(--surface); color:var(--ink); padding:0 20px; font-size:15px; outline:none;
  font-family:var(--font-body); transition:border-color .2s}
.meteor-launch input:focus{border-color:var(--amber)}
.meteor-feed{display:flex; flex-direction:column; gap:14px}
.meteor-item{position:relative; border:1px solid var(--line); border-radius:var(--r-md);
  background:var(--surface); padding:18px 22px 18px 26px; overflow:hidden;
  animation:rise .5s var(--ease-standard) both}
.meteor-item::before{content:''; position:absolute; left:0; top:0; bottom:0; width:3px;
  background:linear-gradient(180deg,var(--amber),transparent)}
.meteor-item p{font-size:15px; line-height:1.9}
.meteor-item small{display:block; margin-top:8px; font-family:var(--font-mono); font-size:11px; color:var(--ink-faint)}
</style>
