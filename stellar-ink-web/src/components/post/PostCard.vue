<script setup>
import { TAG_CLASS } from '@/api/mock'
import { kWords } from '@/utils/format'

const props = defineProps({
  post: { type: Object, required: true },
  delay: { type: Number, default: 0 },
})
const emit = defineEmits(['open'])

function onMove(e) {
  const el = e.currentTarget
  const r = el.getBoundingClientRect()
  el.style.setProperty('--mx', e.clientX - r.left + 'px')
  el.style.setProperty('--my', e.clientY - r.top + 'px')
}
</script>

<template>
  <article
    class="dust reveal" :style="{ '--d': `${delay}s` }"
    @mousemove="onMove" @click="emit('open', props.post)"
  >
    <span class="wc">{{ kWords(props.post.words) }}</span>
    <div class="date">{{ props.post.date }}</div>
    <h4>{{ props.post.title }}</h4>
    <p class="ex">{{ props.post.excerpt || '这颗星还没有留下摘要。' }}</p>
    <div class="chips">
      <span v-for="t in props.post.tags" :key="t" class="chip" :class="TAG_CLASS[t] || ''">#{{ t }}</span>
    </div>
  </article>
</template>

<style scoped>
.dust{
  border:1px solid var(--line); border-radius:var(--r-md); padding:22px;
  background:var(--surface); cursor:pointer; position:relative; overflow:hidden;
  transition:transform .35s var(--ease-spring), border-color .3s, background .3s;
}
.dust::before{
  content:''; position:absolute; inset:0; opacity:0; transition:opacity .35s;
  background:radial-gradient(240px circle at var(--mx,50%) var(--my,50%),var(--primary-soft),transparent 70%);
}
.dust:hover{transform:translateY(-6px) rotate(-.4deg); border-color:var(--primary)}
.dust:hover::before{opacity:1}
.dust .date{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint); letter-spacing:.1em}
.dust h4{font-family:var(--font-serif); font-weight:600; font-size:18px; margin:12px 0 10px; line-height:1.5}
.dust .ex{font-size:13px; color:var(--ink-dim); line-height:1.9;
  display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden}
.dust .wc{position:absolute; right:18px; top:20px; font-family:var(--font-mono);
  font-size:10px; color:var(--ink-faint)}
</style>
