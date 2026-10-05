<script setup>
import { useRoute, useRouter } from 'vue-router'

/**
 * 引用列表：每条都能点回原文。
 *
 * 跳转逻辑放在组件里（而不是留在各个页面）：它依赖**文档标识 `kind + postId`** ——
 * 只按 postId 跳会把人送到同号的另一篇去（文章 3 与笔记 3 是不同的内容），
 * 而链接看起来完全正常。集中一处，才不会「深读页改对了、助手浮层忘了改」。
 */
defineProps({
  citations: { type: Array, default: () => [] },
})
const emit = defineEmits(['open'])

const route = useRoute()
const router = useRouter()

function open(citation) {
  if (!citation) return
  const isNote = citation.kind === 'note'
  // 已经在目标页面上就不跳（深读页问的常是当前这篇）
  if (!isNote && Number(citation.postId) === Number(route.params.id)) {
    emit('open', citation)
    return
  }
  router.push({ name: isNote ? 'note' : 'read', params: { id: citation.postId } })
  emit('open', citation)
}
</script>

<template>
  <ul v-if="citations.length" class="qa-cites">
    <li v-for="(cite, index) in citations" :key="index">
      <button class="qa-cite" type="button" @click="open(cite)">
        <b>[{{ index + 1 }}] {{ cite.title }}<i
          v-if="cite.kind === 'note'" class="qa-cite-kind"
        >笔记</i></b>
        <span>{{ cite.snippet }}</span>
      </button>
    </li>
  </ul>
</template>

<style scoped>
.qa-cites{list-style:none; margin:16px 0 0; display:flex; flex-direction:column; gap:8px}
.qa-cite{display:flex; flex-direction:column; gap:4px; width:100%; text-align:left; cursor:pointer;
  border:1px solid var(--line); border-radius:var(--r-sm); background:var(--bg-2);
  padding:10px 12px; font:inherit; color:var(--ink-dim); transition:border-color .25s var(--ease-soft)}
.qa-cite:hover{border-color:var(--primary)}
.qa-cite b{font-size:12px; font-weight:500}
/* 引用来自技术笔记时的标识：不标的话，读者点开才发现跳到了另一个栏目 */
.qa-cite-kind{font-style:normal; margin-left:6px; padding:0 5px; vertical-align:1px;
  border:1px solid var(--line); border-radius:var(--r-sm); font-size:10px; color:var(--teal)}
.qa-cite span{font-size:11px; color:var(--ink-faint); line-height:1.8}
</style>
