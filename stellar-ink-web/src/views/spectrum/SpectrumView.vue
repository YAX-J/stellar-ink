<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { usePostStore } from '@/stores/posts'
import SectionHead from '@/components/common/SectionHead.vue'
import { fmt } from '@/utils/format'
import AuthorBadge from '@/components/common/AuthorBadge.vue'

const router = useRouter()
const postStore = usePostStore()
const filter = ref('')
const colors = ['var(--primary)', 'var(--teal)', 'var(--amber)', 'var(--rose)', 'var(--ink-dim)']

onMounted(() => {
  Promise.all([postStore.ensureLoaded(), postStore.fetchTags()]).catch(() => {})
})

const tags = computed(() => {
  const counts = new Map()
  for (const post of postStore.posts) {
    for (const tag of post.tags) counts.set(tag, (counts.get(tag) || 0) + 1)
  }
  const source = postStore.tags.length
    ? postStore.tags.map((tag) => ({ name: tag.name, count: Number(tag.count) || 0 }))
    : [...counts].map(([name, count]) => ({ name, count }))
  return source.map((tag, index) => ({ ...tag, color: colors[index % colors.length] }))
})

const rows = computed(() => {
  const tag = tags.value.find((item) => item.name === filter.value)
  return {
    color: tag?.color || 'var(--primary)',
    list: postStore.posts
      .filter((p) => p.tags.includes(filter.value)),
  }
})

watch(tags, (items) => {
  if (!items.some((item) => item.name === filter.value)) filter.value = items[0]?.name || ''
}, { immediate: true })

function openPost(p) {
  router.push(`/read/${p.id}`)
}

function reload() {
  return Promise.all([postStore.fetchPosts(), postStore.fetchTags()]).catch(() => {})
}
</script>

<template>
  <section class="page">
    <div class="kicker reveal">TAG SPECTRUM · 标签不是云，是一条光谱</div>
    <SectionHead title="光谱" more="点击任意波段，收听那个频率" />
    <p v-if="postStore.loading && !postStore.posts.length" class="state-text">正在读取光谱…</p>
    <p v-else-if="postStore.error && !postStore.posts.length" class="state-text error-text">
      {{ postStore.error }} <button class="state-action" @click="reload">重新读取</button>
    </p>

    <div v-if="tags.length" class="spectrum-bar reveal" style="--d:.08s">
      <i
        v-for="tag in tags" :key="tag.name"
        :title="`#${tag.name} · ${tag.count} 篇`"
        :style="{ background: tag.color, flexGrow: tag.count }"
        @click="filter = tag.name"
      ></i>
    </div>

    <div v-if="tags.length" class="spec-tags reveal" style="--d:.14s">
      <button
        v-for="tag in tags" :key="tag.name"
        class="spec-tag" :class="{ on: filter === tag.name }"
        :style="{ color: tag.color }" @click="filter = tag.name"
      >
        <i :style="{ background: tag.color }"></i>#{{ tag.name }}
        <small style="color:var(--ink-faint)">{{ tag.count }}</small>
      </button>
    </div>

    <div class="spec-list reveal" style="--d:.2s">
      <div v-for="p in rows.list" :key="p.id" class="spec-row" @click="openPost(p)">
        <span class="dot" :style="{ background: rows.color }"></span>
        <h4>{{ p.title }}</h4>
        <AuthorBadge class="spec-author" :user-id="p.userId" compact />
        <small>{{ p.date }} · {{ fmt(p.words) }} 字</small>
      </div>
      <div v-if="!rows.list.length" class="spec-row" @click="router.push('/write')">
        <h4 style="color:var(--ink-faint)">{{ tags.length ? '这个波段还没有星，去写一篇吧 →' : '还没有可展示的标签。' }}</h4>
      </div>
    </div>
    <div v-if="postStore.hasMore" class="load-row">
      <button class="btn btn-ghost" :disabled="postStore.loading" @click="postStore.loadMore()">
        {{ postStore.loading ? '正在接收…' : `继续接收 · 还有 ${postStore.total - postStore.posts.length} 颗星` }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.spectrum-bar{display:flex; height:14px; border-radius:99px; overflow:hidden;
  margin-bottom:34px; border:1px solid var(--line)}
.state-text{color:var(--ink-faint); font-size:13px; line-height:1.8}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.error-text{color:var(--rose)}
.load-row{display:flex; justify-content:center; margin-top:24px}
.load-row .btn:disabled{opacity:.55; cursor:wait}
.spectrum-bar i{height:100%; cursor:pointer; transition:filter .2s}
.spectrum-bar i:hover{filter:brightness(1.35)}
.spec-tags{display:flex; gap:12px; flex-wrap:wrap; margin-bottom:30px}
.spec-tag{border:1px solid var(--line); border-radius:99px; padding:10px 20px; font-size:14px;
  cursor:pointer; background:var(--surface); transition:all .3s var(--ease-spring);
  display:flex; align-items:center; gap:8px; font-family:var(--font-body)}
.spec-tag i{width:9px; height:9px; border-radius:50%}
.spec-tag:hover{transform:translateY(-3px)}
.spec-tag.on{border-color:currentColor; box-shadow:0 4px 18px rgba(0,0,0,.2)}
.spec-list{display:flex; flex-direction:column; gap:12px}
.spec-row{display:flex; align-items:center; gap:18px; border:1px solid var(--line);
  border-radius:var(--r-md); background:var(--surface); padding:16px 22px; cursor:pointer;
  transition:all .25s; animation:rise .4s both}
.spec-row:hover{border-color:var(--primary); transform:translateX(6px)}
.spec-row .dot{width:10px; height:10px; border-radius:50%; flex-shrink:0}
.spec-row h4{font-family:var(--font-serif); font-size:16px; flex:1; font-weight:600}
.spec-author{max-width:130px; font-family:var(--font-mono); font-size:10px}
.spec-row small{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint)}
</style>
