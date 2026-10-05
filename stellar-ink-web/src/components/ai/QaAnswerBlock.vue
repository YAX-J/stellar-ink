<script setup>
import QaCitationList from './QaCitationList.vue'

/**
 * 一次问答的结果块：问题 + 三态提示 + 答案 + 引用。
 *
 * 深读页的「问星笺」与助手浮层共用它 —— 两处各写一遍的结果不是代码重复，
 * 而是**三态语义会慢慢分叉**（拒答 / 中断 / 离线自测这三条本来就容易写错，
 * 见 `docs/api/README.md` 与 AGENTS §4）。
 *
 * 组件只如实呈现调用方给的状态：「预算用尽」与「用户停止」**不是失败**，
 * 只有请求真的失败才该进错误提示（那是调用方的事）。
 */
defineProps({
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
})
</script>

<template>
  <div v-if="answer" class="qa-answer" :class="{ separated }">
    <p v-if="question" class="qa-question">问：{{ question }}</p>
    <p v-if="offline" class="qa-offline">离线自测：当前用的是 Fake 模型，回答仅用于验证链路。</p>
    <p v-if="answerDone && interrupted" class="qa-interrupted">
      回答中断了，下面是已经生成的部分。
    </p>
    <p v-if="streaming && !answer.answer" class="qa-waiting">正在检索内容…</p>
    <p class="qa-text" :class="{ refused }">{{ answer.answer }}<span
      v-if="streaming && answer.answer" class="qa-caret" aria-hidden="true"
    >▍</span></p>

    <QaCitationList :citations="answer.citations || []" />
    <p v-if="!(answer.citations || []).length && refused && emptyHint" class="qa-hint">
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
@media (prefers-reduced-motion: reduce) { .qa-waiting,.qa-caret{animation:none} }
</style>
