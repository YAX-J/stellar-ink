<script setup>
import { useRoute, useRouter } from 'vue-router'

/**
 * 引用列表：每条都能点回原文。
 *
 * 跳转逻辑放在组件里（而不是留在各个页面）：它依赖**文档标识 `kind + postId`** ——
 * 只按 postId 跳会把人送到同号的另一篇去（文章 3 与笔记 3 是不同的内容），
 * 而链接看起来完全正常。集中一处，才不会「深读页改对了、助手浮层忘了改」。
 *
 * 两档密度：
 * - 默认：标题 + 一行片段，用于深读页（那里空间够，片段能帮读者判断要不要点开）；
 * - `compact`：**单行、不带片段**，用于助手浮层的对话气泡下方 ——
 *   浮层窄，多条两行卡片会把整屏吃掉，而片段本来也只是预览，点进去才是正文。
 */
defineProps({
  /** 每项可带 `no`（原始序号）：编号必须与**答案正文里的 `[n]`** 一致，不能按位置重排 */
  citations: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
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
  <ul v-if="citations.length" class="qa-cites" :class="{ compact }">
    <li v-for="(cite, index) in citations" :key="index">
      <button class="qa-cite" type="button" @click="open(cite)">
        <b>[{{ cite.no ?? index + 1 }}] {{ cite.title }}<i
          v-if="cite.kind === 'note'" class="qa-cite-kind"
        >笔记</i></b>
        <span v-if="!compact">{{ cite.snippet }}</span>
      </button>
    </li>
  </ul>
</template>

<style scoped>
.qa-cites{list-style:none; margin:16px 0 0; display:flex; flex-direction:column; gap:8px}
.qa-cite{display:flex; flex-direction:column; gap:4px; width:100%; text-align:left; cursor:pointer;
  border:1px solid var(--line); border-radius:var(--r-sm); background:var(--bg-2);
  padding:10px 12px; font:inherit; color:var(--ink-dim);
  transition:border-color .25s var(--ease-standard)}
.qa-cite:hover{border-color:var(--primary)}
.qa-cite b{font-size:12px; font-weight:500}
/* 引用来自技术笔记时的标识：不标的话，读者点开才发现跳到了另一个栏目 */
.qa-cite-kind{font-style:normal; margin-left:6px; padding:0 5px; vertical-align:1px;
  border:1px solid var(--line); border-radius:var(--r-sm); font-size:10px; color:var(--teal)}
.qa-cite span{font-size:11px; color:var(--ink-faint); line-height:1.8}

/* 紧凑档：一行一条（编号 + 标题 + 栏目），超出省略 —— 只留"点进去"这一个动作 */
.qa-cites.compact{margin-top:0; gap:6px}
.qa-cites.compact .qa-cite{flex-direction:row; align-items:baseline; gap:6px; padding:7px 10px}
.qa-cites.compact .qa-cite b{font-size:11.5px; font-weight:400; min-width:0;
  overflow:hidden; text-overflow:ellipsis; white-space:nowrap}
</style>
