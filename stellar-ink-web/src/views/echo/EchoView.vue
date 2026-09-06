<script setup>
import { ref } from 'vue'
import { useEchoStore } from '@/stores/echos'
import SectionHead from '@/components/common/SectionHead.vue'

const echoStore = useEchoStore()
const name = ref('')
const msg = ref('')

function throwBottle() {
  const m = msg.value.trim()
  if (!m) return
  echoStore.throw(name.value.trim() || '匿名旅人', m)
  msg.value = ''
  name.value = ''
}
</script>

<template>
  <section class="page">
    <div class="kicker reveal">DRIFTING ECHOES · 留言板不在墙上，在海里</div>
    <SectionHead title="回声">
      {{ echoStore.bottles.length }} 只瓶子正在海上漂
    </SectionHead>

    <div class="echo-form reveal" style="--d:.08s">
      <input v-model="name" class="in-name" placeholder="署名（可匿名）">
      <input v-model="msg" class="in-msg" placeholder="把话装进瓶子，扔进这片海…" @keydown.enter="throwBottle">
      <button class="btn btn-primary" @click="throwBottle">🫙 投入海中</button>
    </div>

    <div class="echo-sea reveal" style="--d:.14s">
      <div
        v-for="(b, i) in echoStore.bottles" :key="i" class="bottle"
        :style="{
          left: b.left + '%',
          top: b.top + '%',
          '--rot': b.rot + 'deg',
          '--fd': b.dur + 's',
        }"
      >
        <b>🫙 {{ b.n }}</b>{{ b.m }}
      </div>
    </div>
  </section>
</template>

<style scoped>
.echo-sea{position:relative; min-height:540px; border:1px solid var(--line); border-radius:var(--r-lg);
  background:linear-gradient(180deg,var(--bg-2),var(--bg)); overflow:hidden}
.bottle{position:absolute; max-width:250px; background:color-mix(in srgb,var(--bg-3) 88%,transparent);
  border:1px solid var(--teal); border-radius:14px 14px 14px 4px; padding:14px 16px;
  font-size:13px; line-height:1.8; color:var(--ink-dim);
  animation:drift var(--fd,7s) ease-in-out infinite alternate;
  box-shadow:0 6px 22px rgba(0,0,0,.18)}
.bottle b{display:block; color:var(--teal); font-size:12px; margin-bottom:5px; font-family:var(--font-mono)}
@keyframes drift{from{transform:translateY(0) rotate(var(--rot,-2deg))}
  to{transform:translateY(-14px) rotate(calc(var(--rot,-2deg)*-1))}}
</style>
