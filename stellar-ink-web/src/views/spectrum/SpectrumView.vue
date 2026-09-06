<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { usePostStore } from '@/stores/posts'
import { TAGS } from '@/api/mock'
import SectionHead from '@/components/common/SectionHead.vue'
import { fmt } from '@/utils/format'

const router = useRouter()
const postStore = usePostStore()
const filter = ref('沉思')

/* 与原型一致：真实计数 + 固定扰动，让光谱更饱满 */
const counts = computed(() => {
  const m = {}
  for (const t of TAGS) {
    m[t.n] = postStore.posts.filter((p) => p.tags.includes(t.n)).length +
      ((t.n.charCodeAt(0) + t.n.length) % 5) + 1
  }
  return m
})

const rows = computed(() => {
  const tag = TAGS.find((t) => t.n === filter.value)
  return {
    color: tag.c,
    list: postStore.posts
      .map((p, i) => ({ ...p, i }))
      .filter((p) => p.tags.includes(filter.value)),
  }
})

function openPost(p) {
  router.push(`/read/${p.id ?? p.i + 1}`)
}
</script>

<template>
  <section class="page">
    <div class="kicker reveal">TAG SPECTRUM · 标签不是云，是一条光谱</div>
    <SectionHead title="光谱" more="点击任意波段，收听那个频率" />

    <div class="spectrum-bar reveal" style="--d:.08s">
      <i
        v-for="t in TAGS" :key="t.n"
        :title="'#' + t.n" :style="{ background: t.c, flexGrow: counts[t.n] }"
        @click="filter = t.n"
      ></i>
    </div>

    <div class="spec-tags reveal" style="--d:.14s">
      <button
        v-for="t in TAGS" :key="t.n"
        class="spec-tag" :class="{ on: filter === t.n }"
        :style="{ color: t.c }" @click="filter = t.n"
      >
        <i :style="{ background: t.c }"></i>#{{ t.n }}
        <small style="color:var(--ink-faint)">{{ counts[t.n] }}</small>
      </button>
    </div>

    <div class="spec-list reveal" style="--d:.2s">
      <div v-for="p in rows.list" :key="p.i" class="spec-row" @click="openPost(p)">
        <span class="dot" :style="{ background: rows.color }"></span>
        <h4>{{ p.title }}</h4>
        <small>{{ p.date }} · {{ fmt(p.words) }} 字</small>
      </div>
      <div v-if="!rows.list.length" class="spec-row">
        <h4 style="color:var(--ink-faint)">这个波段还没有星，去写一篇吧 →</h4>
      </div>
    </div>
  </section>
</template>

<style scoped>
.spectrum-bar{display:flex; height:14px; border-radius:99px; overflow:hidden;
  margin-bottom:34px; border:1px solid var(--line)}
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
.spec-row small{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint)}
</style>
