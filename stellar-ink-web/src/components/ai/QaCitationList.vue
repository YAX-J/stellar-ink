<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { groupCitations } from '@/utils/citations'

/**
 * 引用列表：同一文档只显示一张卡，保留每个片段的原始编号。
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
const props = defineProps({
  /** 每项可带 `no`（原始序号）：编号必须与**答案正文里的 `[n]`** 一致，不能按位置重排 */
  citations: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
  /** 评测台展开逐段核对原文，标题按文档归并，编号仍与答案一一对应。 */
  expandable: { type: Boolean, default: false },
})
const groups = computed(() => groupCitations(props.citations))
const numbers = (group) => group.citations.map((cite) => `[${cite.no}]`).join(' ')
const emit = defineEmits(['open'])

const route = useRoute()
const router = useRouter()

function open(citation) {
  if (!citation) return
  const isNote = citation.kind === 'note'
  // 已经在目标页面上就不跳（深读页问的常是当前这篇）
  if (route.name === (isNote ? 'note' : 'read')
      && Number(citation.postId) === Number(route.params.id)) {
    emit('open', citation)
    return
  }
  router.push({ name: isNote ? 'note' : 'read', params: { id: citation.postId } })
  emit('open', citation)
}
</script>

<template>
  <ul v-if="groups.length" class="qa-cites" :class="{ compact }">
    <li v-for="group in groups" :key="group.key">
      <details v-if="expandable" class="qa-cite-group">
        <summary>
          <b>{{ group.title }}</b><i v-if="group.kind === 'note'" class="qa-cite-kind">笔记</i>
          <span class="qa-cite-count">{{ group.citations.length }} 段</span>
          <span class="qa-cite-numbers">{{ numbers(group) }}</span>
        </summary>
        <div class="qa-cite-excerpts">
          <div v-for="cite in group.citations" :key="cite.no" class="qa-cite-excerpt">
            <p class="qa-cite-location">[{{ cite.no }}] 第 {{ cite.chunkIndex + 1 }} 段</p>
            <p class="qa-cite-quote">{{ cite.snippet }}</p>
          </div>
          <button class="qa-cite-open" type="button" @click="open(group.citations[0])">打开{{ group.kind === 'note' ? '笔记' : '文章' }}原文</button>
        </div>
      </details>
      <button v-else class="qa-cite" type="button" @click="open(group.citations[0])">
        <b>{{ numbers(group) }} {{ group.title }}<i
          v-if="group.kind === 'note'" class="qa-cite-kind"
        >笔记</i></b>
        <small v-if="group.citations.length > 1" class="qa-cite-count">{{ group.citations.length }} 段</small>
        <template v-if="!compact">
          <span v-for="cite in group.citations" :key="cite.no">[{{ cite.no }}] {{ cite.snippet }}</span>
        </template>
      </button>
    </li>
  </ul>
</template>

<style scoped>
.qa-cites{list-style:none; margin:16px 0 0; padding:0; display:flex; flex-direction:column; gap:8px}
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
.qa-cite-count{font-size:10px;color:var(--ink-faint);white-space:nowrap}
.qa-cite-group{border:1px solid var(--line);border-radius:var(--r-sm);background:var(--bg-2);padding:12px 14px}
.qa-cite-group summary{cursor:pointer;font-size:12px;line-height:1.9;color:var(--ink);overflow-wrap:anywhere}
.qa-cite-group summary b{font-weight:500}.qa-cite-group .qa-cite-count{margin-left:8px}
.qa-cite-numbers{display:block;font-family:var(--font-mono);font-size:11px;color:var(--primary);margin-top:5px}
.qa-cite-excerpt{padding:14px 0;border-top:1px solid var(--line);margin-top:12px}
.qa-cite-location{font-size:11px;color:var(--ink-faint);margin:0 0 8px}
.qa-cite-quote{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;line-height:1.9;color:var(--ink-dim);margin:0}
.qa-cite-open{border:0;background:transparent;color:var(--primary);font:inherit;font-size:12px;padding:4px 0;cursor:pointer}

/* 紧凑档：一行一条（编号 + 标题 + 栏目），超出省略 —— 只留"点进去"这一个动作 */
.qa-cites.compact{margin-top:0; gap:6px}
.qa-cites.compact .qa-cite{flex-direction:row; align-items:baseline; gap:6px; padding:7px 10px}
.qa-cites.compact .qa-cite b{font-size:11.5px; font-weight:400; min-width:0;
  overflow:hidden; text-overflow:ellipsis; white-space:nowrap}
.qa-cites.compact .qa-cite-count{margin-left:auto;flex-shrink:0}
</style>
