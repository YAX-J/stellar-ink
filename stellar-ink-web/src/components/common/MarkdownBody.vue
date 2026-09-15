<script setup>
import { ref } from 'vue'
import { emit, TOAST } from '@/utils/bus'

/* 正文渲染：接收 parseMarkdown 的块级结构，按白名单标签输出。
 * 文章（深读页）与技术笔记详情共用，保证正文排版与代码块行为只有一份实现。
 * 安全前提：块的 html 字段已由 utils/markdown.js 转义 + 白名单，见该文件头部说明。 */
const props = defineProps({
  blocks: { type: Array, default: () => [] },
  emptyText: { type: String, default: '这里还没有留下正文。' },
})

/* 最近复制过的代码块下标，用于给出「已复制」反馈 */
const copiedIndex = ref(-1)

/** 首段下沉：找到第一个真正的段落块 */
function isDropcap(block, index) {
  return index === props.blocks.findIndex((item) => item.type === 'paragraph') && block.type === 'paragraph'
}

async function copyCode(code, index) {
  try {
    await navigator.clipboard.writeText(code)
    copiedIndex.value = index
    setTimeout(() => {
      if (copiedIndex.value === index) copiedIndex.value = -1
    }, 1600)
  } catch {
    emit(TOAST, { type: 'warn', message: '浏览器没有授予剪贴板权限，请手动选中复制' })
  }
}
</script>

<template>
  <div class="md-body">
    <template v-for="(block, index) in props.blocks" :key="index">
      <p v-if="block.type === 'paragraph'" :class="{ dropcap: isDropcap(block, index) }" v-html="block.html"></p>

      <h3 v-else-if="block.type === 'heading'" :id="block.id" v-html="block.html"></h3>

      <blockquote v-else-if="block.type === 'quote'" v-html="block.html"></blockquote>

      <div v-else-if="block.type === 'note'" class="marg-note" v-html="block.html"></div>

      <div v-else-if="block.type === 'code'" class="code-block">
        <div class="code-head">
          <span>{{ block.lang || 'code' }}</span>
          <button
            class="code-copy" :class="{ done: copiedIndex === index }"
            :title="copiedIndex === index ? '已复制' : '复制这段代码'"
            @click="copyCode(block.text, index)"
          >{{ copiedIndex === index ? '✓ 已复制' : '⧉ 复制' }}</button>
        </div>
        <pre><code>{{ block.text }}</code></pre>
      </div>

      <ol v-else-if="block.type === 'list' && block.ordered" class="md-list">
        <li v-for="(item, li) in block.items" :key="li" v-html="item"></li>
      </ol>
      <ul v-else-if="block.type === 'list'" class="md-list">
        <li v-for="(item, li) in block.items" :key="li" v-html="item"></li>
      </ul>

      <hr v-else-if="block.type === 'hr'">
    </template>

    <p v-if="!props.blocks.length" class="md-empty">{{ props.emptyText }}</p>
  </div>
</template>

<style scoped>
/* 整页布局：正文铺满可用宽度，不再设行宽上限（右侧不留空白）。
 * --prose-max 仍保留为统一入口，由详情页/深读页下发为 100%。 */
.md-body{max-width:var(--prose-max,100%)}
.md-body p{font-size:var(--read-fs,17px); line-height:var(--read-lh,2.3); color:var(--ink-dim);
  margin-bottom:30px; text-align:justify; text-justify:inter-ideograph;
  hanging-punctuation:allow-end; line-break:strict}
.md-body p.dropcap::first-letter{font-family:var(--font-serif); font-weight:900; font-size:56px;
  float:left; line-height:1; margin:6px 12px 0 0; color:var(--primary)}
.md-body blockquote{font-family:var(--font-serif); font-size:clamp(20px,2.6vw,27px); font-weight:600;
  line-height:1.8; color:var(--ink); border-left:3px solid var(--amber); padding:6px 0 6px 24px; margin:40px 0}
.md-body h3{font-family:var(--font-serif); font-weight:900;
  font-size:calc(var(--read-fs,17px) * 1.4); margin:44px 0 18px;
  display:flex; align-items:center; gap:14px; scroll-margin-top:80px}
.md-body h3::before{content:'✦'; color:var(--amber); font-size:16px}
.md-body a{color:var(--primary); text-decoration:underline; text-underline-offset:3px;
  text-decoration-color:var(--primary-soft)}
.md-body a:hover{text-decoration-color:var(--primary)}
.md-body strong{color:var(--ink); font-weight:600}
.md-body code{font-family:var(--font-mono); font-size:.86em; padding:2px 6px; border-radius:4px;
  background:var(--surface-2); color:var(--primary)}
.md-body hr{border:0; height:1px; background:var(--line); margin:44px 0}
.md-list{margin:0 0 30px; padding-left:22px; color:var(--ink-dim)}
.md-list li{font-size:var(--read-fs,17px); line-height:var(--read-lh,2.3); margin-bottom:8px}
.md-list li::marker{color:var(--primary)}
.md-empty{color:var(--ink-faint); font-size:14px; line-height:1.9}

.marg-note{background:var(--primary-soft); border:1px dashed var(--primary); border-radius:var(--r-sm);
  padding:14px 18px; font-size:13px; line-height:1.9; color:var(--ink-dim); margin:-8px 0 30px;
  max-width:var(--prose-max,100%)}
.marg-note b{color:var(--primary); margin-right:8px; font-size:12px}

.code-block{margin:0 0 30px; border:1px solid var(--line); border-radius:var(--r-md);
  background:var(--bg-2); overflow:hidden; max-width:var(--prose-max,100%)}
.code-head{padding:7px 8px 7px 14px; border-bottom:1px solid var(--line);
  display:flex; align-items:center; justify-content:space-between; gap:12px;
  font-family:var(--font-mono); font-size:10px; letter-spacing:.2em; color:var(--ink-faint);
  text-transform:uppercase}
.code-copy{border:0; background:transparent; cursor:pointer; font-family:var(--font-mono);
  font-size:10px; letter-spacing:.1em; color:var(--ink-faint); padding:4px 8px; border-radius:6px;
  transition:all .2s var(--ease-standard)}
.code-copy:hover{color:var(--primary); background:var(--surface-2)}
.code-copy.done{color:var(--teal)}
.code-block pre{margin:0; padding:16px; overflow-x:auto}
.code-block code{background:transparent; color:var(--ink-dim); padding:0; font-size:12.5px; line-height:1.9}
</style>
