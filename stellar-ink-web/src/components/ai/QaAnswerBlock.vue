<script setup>
import { computed } from 'vue'
import QaCitationList from './QaCitationList.vue'

/**
 * 一次问答的结果块：问题 + 三态提示 + 答案 + 引用。
 *
 * 两种布局，样式只有一份：
 * - `plain`（默认）：深读页的「问星笺」面板 —— 问句一行小字、答案整段，嵌在文章旁边；
 * - `bubbles`：助手浮层的对话形态 —— **问题靠右、回答靠左**（问的人在右、答的人在左，
 *   与即时通讯一致），引用跟在回答那一侧。
 *   引用为什么不放进气泡：卡片自己就是个框，套进气泡就是框里框，两层边框只会变成视觉噪音。
 *
 * 深读页与浮层共用它，是因为两处重复的不是代码，而是**三态语义会慢慢分叉**
 * （拒答 / 中断 / 离线自测这三条本来就容易写错，见 `docs/api/README.md` 与 AGENTS §4）。
 *
 * 组件只如实呈现调用方给的状态：「预算用尽」与「用户停止」**不是失败**，
 * 只有请求真的失败才该进错误提示（那是调用方的事）。
 */
const props = defineProps({
  question: { type: String, default: '' },
  /** 形状：`{ answer, citations, doneReason, usage, evidenceSufficient }` */
  answer: { type: Object, default: null },
  streaming: { type: Boolean, default: false },
  refused: { type: Boolean, default: false },
  offline: { type: Boolean, default: false },
  interrupted: { type: Boolean, default: false },
  answerDone: { type: Boolean, default: false },
  /** 顶部要不要画分隔线：深读页的面板里需要，浮层里不需要 */
  separated: { type: Boolean, default: false },
  /** 没有引用时的兜底文案（拒答时用；留空则不显示那一行） */
  emptyHint: { type: String, default: '' },
  /** `plain` | `bubbles` —— 见文件头注释 */
  layout: { type: String, default: 'plain' },
  /**
   * 只列**答案正文真正引用到**的那几条（浮层用）。
   *
   * 为什么需要它：`citations` 是「这次进了上下文的全部块」，契约上可到 8 条；
   * 在 376px 的对话气泡下方，8 条卡片会把整屏吃掉，而其中大部分模型根本没用到。
   * 被正文 `[n]` 点到的那些才是"依据"，其余的只是"检索到过"。
   */
  compactCitations: { type: Boolean, default: false },
})

/**
 * 要显示的引用。两条约定：
 * ① **保留原始序号**：正文里写的是 `[2]`，按位置重排会变成 `[1]`，编号与正文当场对不上；
 * ② 正文一个编号都没标（模型漏标）时**退回前三条**，而不是什么都不显示 ——
 *    那会让人以为这次没有依据，其实检索到过。
 */
const visibleCitations = computed(() => {
  const all = props.answer?.citations || []
  const numbered = all.map((cite, index) => ({ ...cite, no: index + 1 }))
  if (!props.compactCitations) return numbered
  const text = String(props.answer?.answer || '')
  const used = new Set([...text.matchAll(/\[(\d+)\]/g)].map((match) => Number(match[1])))
  const picked = numbered.filter((cite) => used.has(cite.no))
  return picked.length ? picked : numbered.slice(0, 3)
})
</script>

<template>
  <div v-if="answer" class="qa-answer" :class="[`is-${layout}`, { separated }]">
    <p v-if="question" class="qa-question">问：{{ question }}</p>

    <div class="qa-bubble">
      <p v-if="offline" class="qa-offline">离线自测：当前用的是 Fake 模型，回答仅用于验证链路。</p>
      <p v-if="answerDone && interrupted" class="qa-interrupted">
        回答中断了，下面是已经生成的部分。
      </p>
      <p v-if="streaming && !answer.answer" class="qa-waiting">正在检索内容…</p>
      <p class="qa-text" :class="{ refused }">{{ answer.answer }}<span
        v-if="streaming && answer.answer" class="qa-caret" aria-hidden="true"
      >▍</span></p>
    </div>

    <div class="qa-cites">
      <QaCitationList
        :citations="visibleCitations"
        :compact="compactCitations"
      />
    </div>
    <p v-if="!visibleCitations.length && refused && emptyHint" class="qa-hint">
      {{ emptyHint }}
    </p>
  </div>
</template>

<style scoped>
/* 样式从深读页搬来（视觉不变）：同一块东西出现在两个地方，样式只能有一份 */
.qa-answer{margin-top:16px}
.qa-answer.separated{padding-top:16px; border-top:1px solid var(--line)}
.qa-question{font-size:12px; color:var(--ink-faint); margin-bottom:8px}
.qa-offline{font-size:11px; color:var(--amber); margin-bottom:8px}
/* 中断提示用暖色：它是「内容可能不完整」的提醒，不是错误（错误走调用方的提示） */
.qa-interrupted{font-size:11px; color:var(--amber); line-height:1.9; margin-bottom:8px}
.qa-waiting{font-size:13px; color:var(--ink-faint); animation:qa-pulse 1.6s ease-in-out infinite}
/* 光标：用一个字宽的下划线块，比动画省略号更能表达「还在写」 */
.qa-caret{display:inline-block; margin-left:2px; color:var(--primary);
  animation:qa-pulse 1.1s step-end infinite}
@keyframes qa-pulse{0%,100%{opacity:1}50%{opacity:.25}}
.qa-text{font-size:14px; line-height:1.95; color:var(--ink-dim); white-space:pre-wrap}
.qa-text.refused{border-left:2px solid var(--amber); padding-left:12px; color:var(--ink-faint)}
.qa-hint{font-size:11px; color:var(--ink-faint); line-height:1.9; margin-top:10px}

/* ===== 对话气泡（助手浮层用） =====
   问题通常一两行、答案往往几百字，所以**刻意不是对称双栏**：对称会让左边
   拖成很长一条、右边大片空着。做法是问题窄气泡靠右、回答宽气泡靠左。
   圆角方向：问题右下角收小、回答左下角收小，做出"谁在说"的方向感。 */
.is-bubbles{display:flex; flex-direction:column; gap:10px}
.is-bubbles .qa-question{
  align-self:flex-end; max-width:78%; margin:0 0 0 auto;
  background:var(--surface); border:1px solid var(--line);
  border-radius:var(--r-md) var(--r-md) var(--r-sm) var(--r-md);
  padding:8px 12px; font-size:12.5px; line-height:1.8; color:var(--ink-dim);
}
.is-bubbles .qa-bubble{
  align-self:flex-start; max-width:96%; margin-right:auto;
  background:color-mix(in srgb, var(--bg-3) 92%, transparent);
  border:1px solid var(--line);
  border-radius:var(--r-md) var(--r-md) var(--r-md) var(--r-sm);
  padding:11px 13px;
}
.is-bubbles .qa-text{font-size:13px}
/* 拒答在气泡里不再画左边框：气泡本身已经有边框，两层边会打架，语气由文案承担 */
.is-bubbles .qa-text.refused{border-left:none; padding-left:0}
.is-bubbles .qa-cites{align-self:flex-start; margin-right:auto; margin-left:0; width:100%; max-width:96%}
.is-bubbles .qa-hint{align-self:flex-start; margin-right:auto; margin-left:0; margin-top:0}
/* 引用卡在浮层里要比毛玻璃面板亮一点才看得出是卡片（深读页的面板自己就是 --surface，
   那里沿用 --bg-2 更清楚），所以只在气泡布局下提亮 */
.is-bubbles :deep(.qa-cite){background:var(--surface)}
@media (prefers-reduced-motion: reduce) { .qa-waiting,.qa-caret{animation:none} }
</style>
