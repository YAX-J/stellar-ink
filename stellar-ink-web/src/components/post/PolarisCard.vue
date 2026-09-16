<script setup>
import { computed } from 'vue'
import { usePostStore } from '@/stores/posts'
import AuthorBadge from '@/components/common/AuthorBadge.vue'

const emit = defineEmits(['open'])
const postStore = usePostStore()

/* 优先使用服务端按 hottest 选出的全站结果；请求失败时再从已加载列表降级。 */
const polaris = computed(() => {
  if (postStore.featured) return postStore.featured
  const posts = postStore.posts
  if (!posts.length) return null
  return posts.reduce((best, post) => (
    (post.glow || 0) > (best.glow || 0) ? post : best
  ), posts[0])
})
</script>

<template>
  <div v-if="polaris" class="polaris reveal" style="--d:.08s" @click="emit('open', polaris)">
    <div class="polaris-inner">
      <span class="polaris-tag">★ POLARIS · 最受回望的一篇</span>
      <h3>{{ polaris.title }}</h3>
      <p>{{ polaris.excerpt || '这颗星还没有留下摘要。' }}</p>
      <div class="polaris-meta">
        <AuthorBadge :user-id="polaris.userId" compact />
        <span>{{ polaris.date }}</span>
        <span>{{ polaris.words.toLocaleString() }} 字</span>
        <span>✦ {{ polaris.glow || 0 }} 光芒</span>
        <span v-if="polaris.viewCount > 0">◉ {{ polaris.viewCount.toLocaleString() }} 次抵达</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.polaris{
  position:relative; border-radius:var(--r-lg); padding:2px; overflow:hidden;
  background:linear-gradient(120deg,var(--primary),transparent 30%,transparent 70%,var(--amber));
  cursor:pointer;
}
.polaris-inner{
  border-radius:calc(var(--r-lg) - 2px); background:var(--bg-2);
  padding:clamp(28px,4vw,52px); position:relative; overflow:hidden;
  transition:background .6s;
}
.polaris-inner::after{
  content:''; position:absolute; right:-120px; top:-120px; width:340px; height:340px;
  border-radius:50%; border:1px solid var(--line);
  box-shadow:0 0 0 40px transparent, inset 0 0 60px var(--primary-soft);
  animation:orbit 14s linear infinite;
}
@keyframes orbit{to{transform:rotate(360deg)}}
.polaris-tag{font-family:var(--font-mono); font-size:11px; color:var(--amber); letter-spacing:.3em}
.polaris h3{font-family:var(--font-serif); font-weight:900; font-size:clamp(24px,3.4vw,42px);
  margin:16px 0 18px; line-height:1.3; max-width:20ch}
.polaris p{color:var(--ink-dim); line-height:2; max-width:62ch; font-size:15px}
.polaris-meta{margin-top:28px; display:flex; gap:26px; font-family:var(--font-mono);
  font-size:12px; color:var(--ink-faint)}
.polaris:hover .polaris-inner{background:var(--bg-3)}
</style>
