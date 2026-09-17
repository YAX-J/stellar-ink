<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <section class="page">
    <div class="lost reveal" style="--d:.08s">
      <div class="lost-glyph" aria-hidden="true">✦</div>
      <div class="title-row lost-title">
        <h1>这颗星不在星图上</h1>
        <span class="kicker">LOST ORBIT · 这条星轨不存在</span>
      </div>
      <p>
        链接可能已经失效，或者这篇文章被作者收回了。
        你可以回到此刻，或者去星图里慢慢找。
      </p>
      <div class="lost-actions">
        <RouterLink class="btn btn-primary" to="/">回到此刻</RouterLink>
        <RouterLink class="btn btn-ghost" to="/archive">逛逛星图</RouterLink>
        <RouterLink class="btn btn-ghost" to="/search">寻星</RouterLink>
      </div>
    </div>
  </section>
</template>

<style scoped>
.lost{
  border:1px dashed var(--line); border-radius:var(--r-lg); background:var(--surface);
  padding:clamp(40px,7vw,88px) clamp(24px,5vw,64px); text-align:center;
  display:flex; flex-direction:column; align-items:center; gap:16px;
}
.lost-glyph{font-size:38px; color:var(--primary); opacity:.7}
.lost-title{justify-content:center}
.lost h1{font-family:var(--font-serif); font-weight:900; font-size:clamp(26px,3.4vw,38px); letter-spacing:.02em}
.lost p{color:var(--ink-dim); font-size:14px; line-height:2; max-width:52ch}
.lost-actions{display:flex; gap:12px; flex-wrap:wrap; justify-content:center; margin-top:6px}
</style>
