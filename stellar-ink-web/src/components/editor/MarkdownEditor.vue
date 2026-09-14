<script setup>
/* ================= Obsidian 式 Markdown 编辑器 =================
 * 技术笔记专用。基于 CodeMirror 6（本项目唯一的前端 UI 依赖，原因见 AGENTS.md：
 * 原生的行内渲染 Live Preview 无法用 textarea 实现），只被 /note/edit 懒加载。
 *
 * Live Preview 规则（对标 Obsidian）：光标所在行显示原始源码，其余行渲染成结果。
 * 实现方式是遍历语法树做装饰：把标记符号折叠成零宽、给内容加语义类。
 */
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { EditorState } from '@codemirror/state'
import {
  EditorView, Decoration, ViewPlugin, keymap, drawSelection, highlightActiveLine, lineNumbers, placeholder,
} from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { markdown, markdownLanguage } from '@codemirror/lang-markdown'
import { syntaxTree, syntaxHighlighting, HighlightStyle } from '@codemirror/language'
import { tags as t } from '@lezer/highlight'

const props = defineProps({
  modelValue: { type: String, default: '' },
  /* 父组件每次「切换/载入」一篇笔记时自增，用于把新内容灌进编辑器而不打断正在输入的内容 */
  syncKey: { type: [String, Number], default: 0 },
  placeholderText: { type: String, default: '从这里开始…' },
})
const emit = defineEmits(['update:modelValue', 'save'])

const host = ref(null)
const view = shallowRef(null)
/* 正在把编辑器内容同步给父组件时置位，避免 watch 回灌造成光标跳动 */
let syncing = false

/* ---------- 主题：只用项目语义变量，保证夜/暮/破晓三主题都可读 ---------- */
const editorTheme = EditorView.theme({
  '&': { color: 'var(--ink)', backgroundColor: 'transparent', fontSize: '15px' },
  '.cm-scroller': {
    fontFamily: 'var(--font-mono)',
    lineHeight: '1.95',
    overflow: 'auto',
  },
  '.cm-content': { padding: '4px 0', caretColor: 'var(--primary)' },
  '.cm-line': { padding: '0 2px' },
  '&.cm-focused': { outline: 'none' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--primary)', borderLeftWidth: '2px' },
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
    backgroundColor: 'var(--primary-soft)',
  },
  '.cm-placeholder': { color: 'var(--ink-faint)', fontFamily: 'var(--font-body)' },

  /* 已渲染的内容（非光标行） */
  '.cm-h1': { fontSize: '1.5em', fontWeight: '700', fontFamily: 'var(--font-serif)', color: 'var(--ink)' },
  '.cm-h2': { fontSize: '1.28em', fontWeight: '700', fontFamily: 'var(--font-serif)', color: 'var(--ink)' },
  '.cm-h3': { fontSize: '1.12em', fontWeight: '700', fontFamily: 'var(--font-serif)', color: 'var(--ink)' },
  '.cm-strong': { fontWeight: '700', color: 'var(--ink)' },
  '.cm-em': { fontStyle: 'italic' },
  '.cm-strike': { textDecoration: 'line-through', opacity: '.7' },
  '.cm-inline-code': {
    backgroundColor: 'var(--surface-2)', color: 'var(--primary)',
    padding: '1px 5px', borderRadius: '4px', fontSize: '.92em',
  },
  '.cm-url': { color: 'var(--teal)', textDecoration: 'underline', textDecorationColor: 'var(--line)' },
  '.cm-quote': {
    color: 'var(--ink-dim)', fontStyle: 'italic',
    borderLeft: '3px solid var(--amber)', paddingLeft: '10px',
  },
  '.cm-code-line': { backgroundColor: 'var(--bg-2)', color: 'var(--teal)' },
  '.cm-list-mark': { color: 'var(--primary)', fontWeight: '600' },
  /* 用真圆点替换源码里的 `- `/`* `/`+ `；有序列表的 `1.` 不套这个类 */
  '.cm-list-mark::before': { content: '"• "', color: 'var(--primary)' },
  '.cm-list-mark::after': { content: '""', display: 'inline-block', width: '1ch' },
  '.cm-bullet-line': { color: 'var(--ink-dim)' },
})

/* 语法树 → 语义类：给「内容」上色加粗（标记由下面的 Live Preview 折叠） */
const highlight = HighlightStyle.define([
  { tag: t.heading1, class: 'cm-h1' },
  { tag: t.heading2, class: 'cm-h2' },
  { tag: t.heading3, class: 'cm-h3' },
  { tag: t.heading4, class: 'cm-h3' },
  { tag: t.heading5, class: 'cm-h3' },
  { tag: t.heading6, class: 'cm-h3' },
  { tag: t.strong, class: 'cm-strong' },
  { tag: t.emphasis, class: 'cm-em' },
  { tag: t.strikethrough, class: 'cm-strike' },
  { tag: t.link, class: 'cm-url' },
  { tag: t.url, class: 'cm-url' },
  { tag: t.monospace, class: 'cm-inline-code' },
  { tag: t.quote, class: 'cm-quote' },
])

/* ---------- Live Preview：折叠非光标行的标记符号 ---------- */
const zeroWidth = Decoration.replace({})
const lineTheme = {
  quote: Decoration.line({ class: 'cm-quote' }),
  code: Decoration.line({ class: 'cm-code-line' }),
  bullet: Decoration.line({ class: 'cm-bullet-line' }),
}

/** 光标/选区落在哪些行上；这些行保持源码原样，便于编辑 */
function activeLines(state) {
  const lines = new Set()
  for (const range of state.selection.ranges) {
    const from = state.doc.lineAt(range.from).number
    const to = state.doc.lineAt(range.to).number
    for (let n = from; n <= to; n += 1) lines.add(n)
  }
  return lines
}

/**
 * Live Preview 插件。
 *
 * 用 ViewPlugin 而不是「StateField + EditorView.decorations.from(field)」：
 * 后者在应用里出现过「装饰数量正确却不生效」的情况；ViewPlugin 直接从语法树
 * 计算当前视口的装饰，是这类插件的规范做法，也天然带增量重算。
 */
const livePreviewPlugin = ViewPlugin.fromClass(class {
  constructor(view) {
    this.decorations = buildDecorations(view)
  }

  update(update) {
    /* 文档变了、换了行、或视口滚动到新区域，都要重算 */
    if (update.docChanged || update.selectionSet || update.viewportChanged) {
      this.decorations = buildDecorations(update.view)
    }
  }
}, {
  decorations: (instance) => instance.decorations,
})

function buildDecorations(view) {
  const state = view.state
  const items = []
  const active = activeLines(state)
  const isActive = (pos) => active.has(state.doc.lineAt(pos).number)

  const tree = syntaxTree(state)
  tree.iterate({
    from: 0,
    to: state.doc.length,
    enter: (node) => {
      const { name, from: nFrom, to: nTo } = node
      const line = state.doc.lineAt(nFrom)

      switch (name) {
        case 'HeaderMark':
          /* 折叠 `#`，内容由 cm-h1..h3 负责放大加粗；光标行保留以便改层级 */
          if (!isActive(nFrom)) items.push({ from: nFrom, to: nTo, deco: zeroWidth })
          break
        case 'EmphasisMark':
          /* `**` / `*` 折叠；文字加粗倾斜由 HighlightStyle 的 strong/emphasis 负责 */
          if (!isActive(nFrom)) items.push({ from: nFrom, to: nTo, deco: zeroWidth })
          break
        case 'CodeMark':
          /* 行内代码的反引号 / 围栏代码的 ``` 标记 */
          if (!isActive(nFrom)) items.push({ from: nFrom, to: nTo, deco: zeroWidth })
          break
        case 'QuoteMark':
          if (!isActive(nFrom)) items.push({ from: nFrom, to: nTo, deco: zeroWidth })
          break
        case 'ListMark':
          /* 列表符号保留（与 Obsidian 一致），但把 `- ` 渲染成真正的圆点 `• `。
           * 只折叠文档里的 `-`，以标记后紧跟的那个空格为「可点区域」保住光标落点；
           * `• ` 用 ::before 补上，CSS content 里的空格保留，视觉宽度与原来一致。 */
          items.push({ from: nFrom, to: nFrom + 1, deco: zeroWidth })
          items.push({ from: nFrom, to: nFrom + 1, deco: Decoration.mark({ class: 'cm-list-mark' }) })
          break
        case 'FencedCode':
        case 'CodeBlock': {
          /* 整块代码：每行加底色，视觉上连成一片 */
          const last = state.doc.lineAt(nTo).number
          for (let n = line.number; n <= last; n += 1) {
            const l = state.doc.line(n)
            if (l.from <= nTo) items.push({ from: l.from, to: l.from, deco: lineTheme.code })
          }
          break
        }
        case 'Blockquote': {
          const last = state.doc.lineAt(nTo).number
          for (let n = line.number; n <= last; n += 1) {
            const l = state.doc.line(n)
            items.push({ from: l.from, to: l.from, deco: lineTheme.quote })
          }
          break
        }
        case 'BulletList':
        case 'OrderedList': {
          const last = state.doc.lineAt(nTo).number
          for (let n = line.number; n <= last; n += 1) {
            const l = state.doc.line(n)
            items.push({ from: l.from, to: l.from, deco: lineTheme.bullet })
          }
          break
        }
        default:
          break
      }
    },
  })

  /* Decoration.set 而不是 RangeSetBuilder：后者要求调用方严格按内部
   * startSide 排序，顺序稍差就抛错，容易被 try/catch 静默吞掉。 */
  return Decoration.set(items.map((i) => i.deco.range(i.from, i.to)), true)
}

/* ---------- 快捷键 ---------- */
/** 用成对标记包裹选区；未选中时插入空标记并把光标放到标记中间 */
function wrapSelection(view, before, after = before) {
  const ranges = view.state.selection.ranges.map((r) => {
    const text = view.state.sliceDoc(r.from, r.to)
    return {
      change: { from: r.from, to: r.to, insert: `${before}${text}${after}` },
      anchor: r.from + before.length,
      head: r.from + before.length + text.length,
    }
  })
  view.dispatch({
    changes: ranges.map((r) => r.change),
    selection: { anchor: ranges[0].anchor, head: ranges[0].head },
    scrollIntoView: true,
  })
  view.focus()
  return true
}

const keyBindings = [
  { key: 'Mod-b', run: (v) => wrapSelection(v, '**'), preventDefault: true },
  { key: 'Mod-i', run: (v) => wrapSelection(v, '*'), preventDefault: true },
  { key: 'Mod-k', run: (v) => wrapSelection(v, '[', ']()'), preventDefault: true },
  { key: 'Mod-s', run: () => { emit('save'); return true }, preventDefault: true },
]

function createState(doc) {
  return EditorState.create({
    doc,
    extensions: [
      lineNumbers(),
      history(),
      drawSelection(),
      highlightActiveLine(),
      EditorView.lineWrapping,
      markdown({ base: markdownLanguage, codeLanguages: [] }),
      syntaxHighlighting(highlight),
      editorTheme,
      livePreviewPlugin,
      placeholder(props.placeholderText),
      keymap.of([...keyBindings, ...defaultKeymap, ...historyKeymap, indentWithTab]),
      EditorView.updateListener.of((update) => {
        if (!update.docChanged) return
        syncing = true
        emit('update:modelValue', update.state.doc.toString())
        syncing = false
      }),
    ],
  })
}

onMounted(() => {
  view.value = new EditorView({ state: createState(props.modelValue), parent: host.value })
})

onBeforeUnmount(() => {
  view.value?.destroy()
  view.value = null
})

/* 两种内容来源都要处理：
 *  1) 挂载时就带内容（新建笔记 / 已缓存的笔记）；
 *  2) 挂载后才灌入（详情是异步加载的，等它到达时编辑器早已挂载完成）。
 * 只靠 onMounted 读一次会漏掉第 2 种，表现为「编辑器一直是空的、只显示占位提示」。 */
function applyExternalContent(text) {
  const v = view.value
  if (!v || syncing) return
  /* 关键保护：编辑器里已有内容时，拒绝被空字符串覆盖。
   * 父组件的 modelValue 在保存/切换过程中会短暂变成空，若照单全收就会把正文清掉
   * （表现为正文消失、只剩占位提示，且所有 Markdown 标记都露出来）。
   * 真正需要清空的场景（新建、切换笔记）由 key 变化重建实例完成，不走这里。 */
  if (!text && v.state.doc.length > 0) return
  if (v.state.doc.toString() === text) return
  const { anchor } = v.state.selection.main
  v.dispatch({
    changes: { from: 0, to: v.state.doc.length, insert: text },
    selection: { anchor: Math.min(anchor, text.length) },
  })
}

watch(() => props.modelValue, (text) => applyExternalContent(text))
/* syncKey 变化时也重新灌一次：父组件切换笔记时可据此强制对齐 */
watch(() => props.syncKey, () => applyExternalContent(props.modelValue))

defineExpose({
  focus: () => view.value?.focus(),
  /** 在光标处插入一段文本（工具栏按钮用，例如 Callout 模板） */
  insert: (text) => {
    const v = view.value
    if (!v) return
    const { from, to } = v.state.selection.main
    v.dispatch({
      changes: { from, to, insert: text },
      selection: { anchor: from + text.length },
      scrollIntoView: true,
    })
    v.focus()
  },
})
</script>

<template>
  <div ref="host" class="md-editor"></div>
</template>

<style scoped>
/* 高度交给父级 flex 容器；编辑器内部自己滚动 */
.md-editor{flex:1; min-height:340px; display:flex; flex-direction:column; overflow:hidden}
.md-editor :deep(.cm-editor){height:100%; min-height:340px}
.md-editor :deep(.cm-scroller){overflow:auto}
/* 行号弱化，别抢正文 */
.md-editor :deep(.cm-gutters){
  background:transparent; border:none; color:var(--ink-faint);
  font-family:var(--font-mono); font-size:11px; padding-right:10px;
}
.md-editor :deep(.cm-activeLine){background:var(--surface)}
.md-editor :deep(.cm-activeLineGutter){background:transparent; color:var(--ink-dim)}
</style>
