<script setup>
/**
 * 行级差异预览：候选与当前草稿到底差在哪几行。
 * 只负责渲染 `utils/diff.js` 算好的行，不自己算 —— 差异算法要能被单测直接调用。
 */
import { computed } from 'vue'
import { diffLines, DIFF_TOO_LONG } from '@/utils/diff'

const props = defineProps({
  before: { type: String, default: '' },
  after: { type: String, default: '' },
  /** 折叠掉的上下文行数（两侧各留这么多），0 表示全部展示 */
  context: { type: Number, default: 2 },
})

const diff = computed(() => diffLines(props.before, props.after, { context: props.context }))

const summary = computed(() => {
  if (diff.value.unchanged) return '与当前草稿一模一样'
  if (diff.value.why === DIFF_TOO_LONG) return '草稿过长，已按整段替换展示'
  return `－${diff.value.removed} 行 · ＋${diff.value.added} 行`
})
</script>

<template>
  <div class="diff">
    <div class="diff-head">
      <span class="diff-sum">{{ summary }}</span>
      <span class="diff-legend"><i class="del"></i>删<i class="add"></i>加</span>
    </div>
    <ol class="diff-body">
      <li v-for="(row, index) in diff.rows" :key="index" class="row" :class="row.type">
        <span class="gutter">{{ row.type === 'fold' ? '⋯' : (row.after || row.before || '') }}</span>
        <span class="mark">{{ row.type === 'add' ? '＋' : (row.type === 'del' ? '－' : ' ') }}</span>
        <span v-if="row.type === 'fold'" class="fold">… 中间 {{ row.count }} 行未改动 …</span>
        <span v-else class="text">{{ row.text === '' ? ' ' : row.text }}</span>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.diff{border:1px solid var(--line); border-radius:var(--r-sm); background:var(--bg-3); overflow:hidden}
.diff-head{
  display:flex; justify-content:space-between; gap:12px; padding:8px 12px;
  border-bottom:1px dashed var(--line); font-family:var(--font-mono); font-size:10px;
  letter-spacing:.1em; color:var(--ink-faint);
}
.diff-legend{display:inline-flex; align-items:center; gap:6px}
.diff-legend i{width:8px; height:8px; border-radius:2px; display:inline-block}
/* 底色一律从语义变量混出来：写死 rgba 会在破晓（浅色）主题下继续用夜色配方 */
.diff-legend i.del{background:color-mix(in srgb, var(--rose) 40%, transparent)}
.diff-legend i.add{background:color-mix(in srgb, var(--teal) 40%, transparent)}
.diff-body{margin:0; padding:6px 0; list-style:none; max-height:340px; overflow:auto}
.row{
  display:grid; grid-template-columns:32px 14px 1fr; align-items:baseline; gap:6px;
  padding:2px 10px; font-family:var(--font-mono); font-size:12px; line-height:1.7;
  color:var(--ink-dim); white-space:pre-wrap; word-break:break-word;
}
.row .gutter{color:var(--ink-faint); text-align:right; font-size:10px; user-select:none}
.row .mark{color:var(--ink-faint); user-select:none}
.row.add{background:color-mix(in srgb, var(--teal) 14%, transparent); color:var(--ink)}
.row.add .mark{color:var(--teal)}
.row.del{background:color-mix(in srgb, var(--rose) 14%, transparent); color:var(--ink-dim)}
.row.del .mark{color:var(--rose)}
.row.del .text{text-decoration:line-through; text-decoration-color:var(--line)}
.row.fold{color:var(--ink-faint); font-size:10px; letter-spacing:.1em; padding:4px 10px}
</style>
