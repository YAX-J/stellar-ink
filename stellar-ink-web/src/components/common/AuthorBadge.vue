<script setup>
import { computed } from 'vue'
import { useAuthorStore } from '@/stores/authors'
import UserAvatar from '@/components/common/UserAvatar.vue'

const props = defineProps({
  userId: { type: [Number, String], default: null },
  compact: { type: Boolean, default: false },
})

const authorStore = useAuthorStore()
const author = computed(() => authorStore.find(props.userId))
</script>

<template>
  <span class="author-badge" :class="{ compact }" :title="`作者：${author.nickname}`">
    <UserAvatar
      :url="author.avatarUrl" :text="author.avatarText" :nickname="author.nickname"
      :size="compact ? 18 : 22"
    />
    <span>{{ author.nickname }}</span>
  </span>
</template>

<style scoped>
.author-badge{display:inline-flex; align-items:center; gap:7px; min-width:0; color:var(--ink-faint)}
.author-badge span{overflow:hidden; text-overflow:ellipsis; white-space:nowrap}
.author-badge.compact{gap:5px}
</style>
