<script setup>
import { computed, onMounted, ref } from 'vue'
import { useMeteorStore } from '@/stores/meteors'
import { useAuthStore } from '@/stores/auth'
import SectionHead from '@/components/common/SectionHead.vue'
import MeteorSky from '@/components/canvas/MeteorSky.vue'

const meteorStore = useMeteorStore()
const auth = useAuthStore()
const text = ref('')
const sky = ref(null)
const launching = ref(false)
const canLaunch = computed(() => auth.isAuthorOrAbove)

onMounted(() => meteorStore.ensureLoaded().catch(() => {}))

async function launch() {
  const v = text.value.trim()
  if (!v || launching.value) return
  launching.value = true
  try {
    await meteorStore.launch(v)
    text.value = ''
    sky.value?.spawn()
  } finally {
    launching.value = false
  }
}
</script>

<template>
  <section class="page">
    <div class="kicker reveal">METEOR MEMO · 流星备忘录</div>
    <SectionHead title="流星" more="长文给星图，碎片给流星" />
    <div v-if="canLaunch" class="meteor-launch reveal" style="--d:.08s">
      <input v-model="text" placeholder="此刻划过脑海的…（回车即发射）" @keydown.enter="launch">
      <button class="btn btn-primary" :disabled="launching" @click="launch">
        {{ launching ? '发射中…' : '☄ 发射' }}
      </button>
    </div>
    <p v-else class="auth-hint">
      登录并由站长授予作者权限后，可以发射属于你的流星。
      <RouterLink to="/login">去登录</RouterLink>
    </p>
    <p v-if="meteorStore.loading && !meteorStore.items.length" class="state-text">正在读取流星…</p>
    <p v-else-if="meteorStore.error" class="state-text error-text">
      {{ meteorStore.error }} <button class="state-action" @click="meteorStore.fetchItems()">重新读取</button>
    </p>
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
.auth-hint{color:var(--ink-faint); font-size:13px; line-height:1.8; margin-bottom:22px}
.auth-hint a{color:var(--primary); text-decoration:none}
.meteor-feed{display:flex; flex-direction:column; gap:14px}
.state-text{color:var(--ink-faint); font-size:13px; line-height:1.8}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.error-text{color:var(--rose)}
.meteor-item{position:relative; border:1px solid var(--line); border-radius:var(--r-md);
  background:var(--surface); padding:18px 22px 18px 26px; overflow:hidden;
  animation:rise .5s var(--ease-standard) both}
.meteor-item::before{content:''; position:absolute; left:0; top:0; bottom:0; width:3px;
  background:linear-gradient(180deg,var(--amber),transparent)}
.meteor-item p{font-size:15px; line-height:1.9}
.meteor-item small{display:block; margin-top:8px; font-family:var(--font-mono); font-size:11px; color:var(--ink-faint)}
</style>
