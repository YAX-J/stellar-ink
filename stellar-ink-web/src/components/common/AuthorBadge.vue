<script setup>
import { computed } from 'vue'
import { useAuthorStore } from '@/stores/authors'

const props = defineProps({
  userId: { type: [Number, String], default: null },
  compact: { type: Boolean, default: false },
})

const authorStore = useAuthorStore()
const author = computed(() => authorStore.find(props.userId))
const avatar = computed(() => author.value.avatarText?.trim()?.slice(0, 1) || author.value.nickname?.slice(0, 1) || '星')
</script>

<template>
  <span class="author-badge" :class="{ compact }" :title="`作者：${author.nickname}`">
    <i>{{ avatar }}</i>
    <span>{{ author.nickname }}</span>
  </span>
</template>

<style scoped>
.author-badge{display:inline-flex; align-items:center; gap:7px; min-width:0; color:var(--ink-faint)}
.author-badge i{width:22px; height:22px; flex:0 0 22px; border:1px solid var(--line); border-radius:50%;
  display:grid; place-items:center; background:var(--primary-soft); color:var(--primary);
  font-family:var(--font-serif); font-size:11px; font-style:normal}
.author-badge span{overflow:hidden; text-overflow:ellipsis; white-space:nowrap}
.author-badge.compact{gap:5px}
.author-badge.compact i{width:18px; height:18px; flex-basis:18px; font-size:9px}
</style>
