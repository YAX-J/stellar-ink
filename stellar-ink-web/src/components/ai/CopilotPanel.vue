<script setup>
/**
 * 星笺 Copilot 面板（执笔页侧边，仅作者可见）。
 *
 * 红线（`development-workflow.md` §7.4）：**面板自己不写正文**。
 * 它只做三件事：选功能 → 让后端给候选 → 把候选与当前草稿的差异摆出来。
 * 正文的每一次改动都由作者点按钮触发，通过 `emit` 交回执笔页，走既有的草稿保存链路。
 */
import { computed, onMounted, ref } from 'vue'
import { emit, TOAST } from '@/utils/bus'
import { actionFor, resolveText } from '@/utils/copilot-action'
import { COPILOT_TASKS, COPILOT_TONES, useCopilotStore } from '@/stores/copilot'
import DiffView from '@/components/ai/DiffView.vue'

const props = defineProps({
  /** 当前草稿正文（由执笔页传入，面板不持有正文的权威副本） */
  draft: { type: String, default: '' },
})

const emitEvent = defineEmits(['replace', 'insert'])

const copilot = useCopilotStore()
const task = ref('polish')
const tone = ref('keep')
const count = ref(3)
const instruction = ref('')
const status = ref('')

const currentTask = computed(() => COPILOT_TASKS.find((item) => item.key === task.value))
/** 采纳动作由 `utils/copilot-action.js` 决定（纯函数、可自检），面板只负责发事件 */
const action = computed(() => actionFor(task.value))
/** 只有「整段替换」类任务才谈得上逐行差异；标题/标签/摘要是一个值，不是改写正文 */
const showDiff = computed(() => action.value.mode === 'replace' && props.draft.trim().length > 0)

const draftSnapshot = computed(() => copilot.requestedDraft)

function selectTask(key) {
  task.value = key
  status.value = ''
  copilot.error = ''
}

async function run() {
  status.value = ''
  try {
    await copilot.suggest({
      task: task.value,
      draft: props.draft,
      tone: tone.value,
      instruction: instruction.value,
      candidateCount: count.value,
    })
  } catch {
    /* 错误已进 copilot.error（面板内展示 + 全局 toast） */
  }
}

function accept(candidate) {
  const mode = action.value.mode
  const text = resolveText(mode, candidate.text)
  if (!text) return
  if (mode === 'replace') {
    emitEvent('replace', { text, task: task.value })
    status.value = '已采纳：正文已按候选替换（自动保存随后接管）'
  } else if (mode === 'title') {
    emitEvent('replace', { text, task: task.value, target: 'title' })
    status.value = '已采纳：标题已填入，可继续改'
  } else if (mode === 'insert' || mode === 'append') {
    emitEvent('insert', { text })
    status.value = mode === 'append' ? '已采纳：提纲已追加到正文末尾' : '已采纳：候选已插到光标处'
  } else {
    copy(candidate)
    return
  }
  emit(TOAST, { type: 'success', message: '已采纳这条建议' })
}

async function copy(candidate) {
  try {
    await navigator.clipboard.writeText(candidate.text)
    status.value = '已复制到剪贴板，自己挑地方贴'
  } catch {
    status.value = '复制失败：浏览器拒绝了剪贴板权限，请手动选中复制'
  }
}

/* ---- 写作画像（E1）----
 * 只读的统计量：句长、关联词、反复出现的字组。**不含作者原句**（服务端只给 ≥3 次的字组），
 * 所以可以放心显示 —— 它的用途是让作者确认「这确实是我」，也是 Copilot 后续贴近语气的依据。
 * 样本不够时显示服务端给的那句人话（带实际篇数与字数），而不是画一堆 0。
 */
const styleOpen = ref(false)

onMounted(() => {
  // 失败不弹全局 toast（写作页不该因为画像拉不到而打断）：错误进面板内的 styleError
  copilot.loadStyle().catch(() => {})
})

function toggleStyle() {
  styleOpen.value = !styleOpen.value
  if (styleOpen.value && !copilot.style && !copilot.styleLoading) {
    copilot.loadStyle().catch(() => {})
  }
}

const styleMetrics = computed(() => {
  const profile = copilot.style?.profile
  if (!profile) return []
  return [
    { label: '句长中位', value: `${profile.medianSentenceChars} 字` },
    { label: '短句占比', value: `${Math.round((profile.shortSentenceRatio || 0) * 100)}%` },
    { label: '逗号密度', value: `${profile.clausesPer100Chars} /百字` },
    { label: '样本', value: `${profile.sampleCount} 篇` },
  ]
})
</script>

<template>
  <div class="copilot side-card">
    <div class="cp-head">
      <h5>Copilot · 写作助手</h5>
      <button v-if="copilot.result" class="cp-clear" type="button" title="清空结果" @click="copilot.clear()">×</button>
    </div>
    <p class="cp-note">它只给候选，<b>不会自己改正文</b>。采纳与否由你点。</p>

    <!-- 写作画像：只读统计量，用来确认「这确实是我」 -->
    <div class="cp-style">
      <button type="button" class="cp-style-head" :aria-expanded="styleOpen" @click="toggleStyle">
        <span>我的写作画像</span>
        <span class="cp-style-hint">{{ copilot.hasStyle ? '已量出' : '查看' }} {{ styleOpen ? '▾' : '▸' }}</span>
      </button>
      <div v-if="styleOpen" class="cp-style-body">
        <p v-if="copilot.styleLoading" class="cp-style-note">正在量…</p>
        <p v-else-if="copilot.styleError" class="cp-error">{{ copilot.styleError }}</p>
        <template v-else-if="copilot.hasStyle">
          <div class="cp-metrics">
            <div v-for="metric in styleMetrics" :key="metric.label" class="cp-metric">
              <b>{{ metric.value }}</b><span>{{ metric.label }}</span>
            </div>
          </div>
          <p v-if="copilot.style.profile.transitions?.length" class="cp-style-note">
            常用关联词：{{ copilot.style.profile.transitions.join('、') }}
          </p>
          <p v-if="copilot.style.profile.commonPhrases?.length" class="cp-style-note">
            反复出现的字组：{{ copilot.style.profile.commonPhrases.join('、') }}
          </p>
          <p v-if="copilot.style.profile.topTags?.length" class="cp-style-note">
            常写主题：{{ copilot.style.profile.topTags.join('、') }}
          </p>
          <p class="cp-style-foot">{{ copilot.style.notes }}</p>
        </template>
        <!-- 样本不够：显示服务端给的那句人话（带实际篇数与字数），而不是一堆 0 -->
        <p v-else class="cp-style-note">{{ copilot.style?.notes || '还没有可用的画像。' }}</p>
      </div>
    </div>

    <div class="cp-tasks">
      <button
        v-for="item in COPILOT_TASKS" :key="item.key" type="button"
        class="cp-task" :class="{ on: task === item.key }" :title="item.hint"
        @click="selectTask(item.key)"
      >{{ item.label }}</button>
    </div>

    <div class="cp-row">
      <select v-model="tone" class="cp-select" aria-label="风格目标">
        <option v-for="item in COPILOT_TONES" :key="item.key" :value="item.key">{{ item.label }}</option>
      </select>
      <select v-model.number="count" class="cp-select narrow" aria-label="候选数量">
        <option v-for="n in 5" :key="n" :value="n">{{ n }} 条</option>
      </select>
    </div>

    <input
      v-model="instruction" class="cp-input" maxlength="500"
      placeholder="补充要求（可空，填了就按它来）" @keyup.enter="run"
    >

    <button class="btn btn-primary cp-run" type="button" :disabled="copilot.running" @click="run">
      {{ copilot.running ? '正在想…' : `让 Copilot ${currentTask?.label}` }}
    </button>

    <p v-if="copilot.error" class="cp-error">{{ copilot.error }}</p>
    <p v-else-if="status" class="cp-status">{{ status }}</p>

    <div v-if="copilot.candidates.length" class="cp-list">
      <div class="cp-meta">
        <span>{{ copilot.candidates.length }} 条候选</span>
        <span v-if="copilot.offline" class="cp-fake">离线自测 · 未接真模型</span>
        <span v-else-if="copilot.result?.usage?.model" class="cp-model">{{ copilot.result.usage.model }}</span>
      </div>

      <article v-for="(candidate, index) in copilot.candidates" :key="index" class="cp-cand">
        <div class="cp-cand-head">
          <span class="cp-idx">候选 {{ index + 1 }}</span>
          <div class="cp-actions">
            <button type="button" class="cp-apply" @click="accept(candidate)">{{ action.verb }}</button>
            <button v-if="action.mode !== 'copy'" type="button" class="cp-copy" @click="copy(candidate)">复制</button>
          </div>
        </div>

        <DiffView v-if="showDiff" :before="draftSnapshot" :after="candidate.text" />

        <template v-else-if="action.mode === 'replace'">
          <!-- 草稿为空时没有「原文」可比，直接看候选本身 -->
          <pre class="cp-plain">{{ candidate.text }}</pre>
        </template>
        <div v-else-if="action.mode === 'title'" class="cp-value">{{ candidate.text }}</div>
        <pre v-else class="cp-plain">{{ candidate.text }}</pre>

        <p v-if="candidate.rationale" class="cp-why">{{ candidate.rationale }}</p>
      </article>
    </div>
  </div>
</template>

<style scoped>
.copilot{display:flex; flex-direction:column; gap:12px}
.cp-head{display:flex; align-items:center; justify-content:space-between}
.cp-head h5{margin:0}
.cp-clear{width:26px; height:26px; border:0; background:transparent; color:var(--ink-faint);
  font-size:20px; cursor:pointer; transition:color .2s}
.cp-clear:hover{color:var(--rose)}
.cp-note{font-size:11px; line-height:1.8; color:var(--ink-faint)}
.cp-note b{color:var(--amber); font-weight:500}
/* 写作画像：折叠面板。默认收起 —— 它是参考信息，不该挤占 Copilot 的操作区 */
.cp-style{border:1px solid var(--line); border-radius:var(--r-sm); background:var(--bg-3); overflow:hidden}
.cp-style-head{
  display:flex; align-items:center; justify-content:space-between; gap:8px; width:100%;
  padding:8px 12px; border:0; background:transparent; cursor:pointer;
  font-family:var(--font-body); font-size:12px; color:var(--ink-dim); transition:color .2s;
}
.cp-style-head:hover{color:var(--ink)}
.cp-style-hint{font-family:var(--font-mono); font-size:10px; color:var(--ink-faint)}
.cp-style-body{padding:0 12px 12px; display:flex; flex-direction:column; gap:8px}
.cp-metrics{display:grid; grid-template-columns:repeat(2,1fr); gap:8px}
.cp-metric{
  display:flex; flex-direction:column; gap:2px; padding:8px 10px;
  border-radius:var(--r-sm); background:var(--surface); border:1px solid var(--line);
}
.cp-metric b{font-family:var(--font-mono); font-size:13px; color:var(--primary); font-weight:500}
.cp-metric span{font-size:10px; color:var(--ink-faint); letter-spacing:.05em}
.cp-style-note{font-size:11px; line-height:1.8; color:var(--ink-dim)}
.cp-style-foot{font-size:10px; line-height:1.7; color:var(--ink-faint)}
.cp-tasks{display:flex; flex-wrap:wrap; gap:6px}
.cp-task{
  border:1px solid var(--line); background:var(--bg-3); border-radius:99px;
  padding:5px 12px; font-size:12px; color:var(--ink-dim); cursor:pointer;
  transition:all .2s var(--ease-spring); font-family:var(--font-body);
}
.cp-task:hover{color:var(--ink)}
.cp-task.on{background:var(--primary-soft); border-color:var(--primary); color:var(--primary)}
.cp-row{display:flex; gap:8px}
.cp-select{
  flex:1; min-width:0; height:34px; border-radius:10px; border:1px solid var(--line);
  background:var(--bg-3); color:var(--ink-dim); font-size:12px; padding:0 8px;
  font-family:var(--font-body);
}
.cp-select.narrow{flex:0 0 82px}
.cp-input{
  width:100%; height:34px; border-radius:10px; border:1px solid var(--line);
  background:var(--bg-3); color:var(--ink); font-size:12px; padding:0 10px;
  font-family:var(--font-body); outline:none;
}
.cp-input:focus{border-color:var(--primary)}
.cp-run{height:36px; font-size:13px}
.cp-run:disabled{opacity:.6; cursor:wait}
.cp-error{font-size:11px; line-height:1.7; color:var(--rose)}
.cp-status{font-size:11px; line-height:1.7; color:var(--teal)}
.cp-list{display:flex; flex-direction:column; gap:12px; margin-top:2px}
.cp-meta{display:flex; align-items:center; justify-content:space-between; gap:8px;
  font-family:var(--font-mono); font-size:10px; letter-spacing:.1em; color:var(--ink-faint)}
.cp-fake{color:var(--amber)}
.cp-model{max-width:120px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap}
.cp-cand{border-top:1px dashed var(--line); padding-top:10px; display:flex; flex-direction:column; gap:8px}
.cp-cand-head{display:flex; align-items:center; justify-content:space-between; gap:8px}
.cp-idx{font-family:var(--font-mono); font-size:10px; letter-spacing:.15em; color:var(--ink-faint)}
.cp-actions{display:flex; gap:6px}
.cp-apply{
  border:1px solid var(--primary); background:var(--primary-soft); color:var(--primary);
  border-radius:8px; padding:4px 10px; font-size:11px; cursor:pointer; transition:all .2s;
}
.cp-apply:hover{background:var(--primary); color:var(--on-primary)}
.cp-copy{
  border:1px solid var(--line); background:transparent; color:var(--ink-faint);
  border-radius:8px; padding:4px 10px; font-size:11px; cursor:pointer; transition:color .2s;
}
.cp-copy:hover{color:var(--ink)}
.cp-plain{
  margin:0; padding:10px 12px; border-radius:var(--r-sm); background:var(--bg-3);
  border:1px solid var(--line); font-family:var(--font-body); font-size:13px; line-height:1.9;
  color:var(--ink); white-space:pre-wrap; word-break:break-word; max-height:260px; overflow:auto;
}
.cp-value{
  padding:10px 12px; border-radius:var(--r-sm); background:var(--bg-3); border:1px solid var(--line);
  font-family:var(--font-serif); font-weight:900; font-size:18px; color:var(--ink);
}
.cp-why{font-size:11px; line-height:1.8; color:var(--ink-faint)}
</style>
