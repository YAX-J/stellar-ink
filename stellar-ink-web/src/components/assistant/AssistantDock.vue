<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useQaStream } from '@/composables/useQaStream'
import QaAnswerBlock from '@/components/ai/QaAnswerBlock.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 星笺助手（右下角浮层）：就全站已发布内容（文章 + 公开技术笔记）多轮提问。
 *
 * 三条设计口径：
 *
 * ① **它用独立的 `useQaStream()` 实例**，不碰 `stores/qa.js` —— 深读页的面板与浮层会
 *    同时存在，共用一个 store 时一边的 `delta` 会写进另一边的界面。
 * ② **历史只是语境的参考**：带上去的最近几轮只用来理解「那它呢」指的是什么，
 *    服务端提示词明确要求不得当事实陈述、不得据它编号引用（与「记忆」同一口径）。
 *    轮数上限与服务端一致（6 轮）—— 堆多了只会挤掉真正要引用的摘录。
 * ③ **会话存 sessionStorage**：关掉标签页就没了，符合「助手不是账号资产」的定位；
 *    这也避免了「换个设备发现对话历史同步过去了」这种没人要求过的承诺。
 */
const HISTORY_LIMIT = 6
const STORAGE_KEY = 'stellar-ink:assistant:session'

const auth = useAuthStore()

const {
  asking,
  streaming,
  answer,
  question,
  error,
  completed,
  refused,
  offline,
  interrupted,
  answerDone,
  askStream,
  abort,
  reset,
} = useQaStream()

const open = ref(false)
const draft = ref('')
/** 已归档的轮次（**不含正在进行的那一轮**）：`{ question, answer }` */
const turns = ref([])

onMounted(() => {
  try {
    const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '[]')
    if (Array.isArray(saved)) turns.value = saved.slice(-HISTORY_LIMIT)
  } catch {
    // 存的东西坏了就当没有：助手会话不是重要数据，不值得为它报错
    turns.value = []
  }
})

watch(
  turns,
  (value) => {
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(value.slice(-HISTORY_LIMIT)))
    } catch {
      // 隐私模式 / 配额满：不落盘也要能用（当前会话内的多轮不受影响）
    }
  },
  { deep: true },
)

/** 带进提示词的历史：只取**已经完成并且有正文**的那些轮 */
const history = computed(() =>
  turns.value
    .filter((turn) => turn.answer?.answer)
    .map((turn) => ({ question: turn.question, answer: turn.answer.answer })),
)

function archiveFinishedTurn() {
  if (completed.value && answer.value?.answer && question.value) {
    turns.value = [...turns.value, { question: question.value, answer: answer.value }].slice(
      -HISTORY_LIMIT,
    )
  }
}

async function send() {
  const text = draft.value.trim()
  if (!text || streaming.value || !auth.isLoggedIn) return
  // 先把上一轮归档，它的正文才会进这一轮的上下文（`history` 依赖 turns）
  archiveFinishedTurn()
  draft.value = ''
  reset()
  try {
    await askStream(text, { history: history.value })
  } catch {
    // 失败已经在 `error` 里如实显示（含 401/429 的专门处理）
  }
}

function clearAll() {
  turns.value = []
  reset()
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // 同上：清不掉也不影响当前会话
  }
}
</script>

<template>
  <div class="dock">
    <!-- 入口：右下角气泡。不占一级导航（那 6 项是内容维度） -->
    <button
      v-if="!open"
      class="dock-bubble" type="button" aria-label="打开星笺助手"
      @click="open = true"
    >
      <span class="dock-glyph" aria-hidden="true">✦</span>
      <span class="dock-label">问星笺</span>
    </button>

    <section v-else class="dock-panel" aria-label="星笺助手">
      <header class="dock-head">
        <div class="title-row">
          <h3>星笺助手</h3>
          <span class="kicker">ASK · 就全站文章与笔记提问</span>
        </div>
        <div class="dock-actions">
          <button
            v-if="turns.length || answer"
            class="dock-mini" type="button" @click="clearAll"
          >清空</button>
          <button class="dock-mini" type="button" aria-label="收起助手" @click="open = false">收起</button>
        </div>
      </header>

      <div class="dock-body">
        <p v-if="!auth.isLoggedIn" class="dock-hint">
          提问需要登录（答案要花算力，也要能按人计费）。
          <RouterLink class="dock-link" :to="{ name: 'login', query: { redirect: $route.fullPath } }">
            去登录
          </RouterLink>
        </p>

        <template v-else>
          <!-- 历史轮：只读展示，引用仍可点回原文 -->
          <article v-for="(turn, index) in turns" :key="index" class="dock-turn">
            <QaAnswerBlock
              :question="turn.question"
              :answer="turn.answer"
              :offline="turn.answer?.usage?.model === 'fake'"
            />
          </article>

          <!-- 当前轮：三态提示与流式光标都在这里 -->
          <article v-if="answer || streaming" class="dock-turn">
            <QaAnswerBlock
              :question="question"
              :answer="answer"
              :streaming="streaming"
              :refused="refused"
              :offline="offline"
              :interrupted="interrupted"
              :answer-done="answerDone"
              empty-hint="这次没有引用可给：站内确实没有相关段落。"
            />
          </article>

          <p v-if="!turns.length && !answer && !streaming" class="dock-hint">
            问点什么，比如「上次那个 Nacos 乱码是怎么修的？」。
            答案只依据站内文章与技术笔记，并给出引用；找不到依据时会直说，不会编。
          </p>
          <p v-if="error" class="dock-err">{{ error }}</p>
        </template>
      </div>

      <footer v-if="auth.isLoggedIn" class="dock-foot">
        <input
          v-model="draft" maxlength="500" type="text"
          placeholder="接着问，或者换个问题…"
          @keydown.enter="send"
        >
        <button
          class="btn btn-primary dock-send"
          :disabled="asking || streaming"
          @click="send"
        >{{ streaming ? '生成中…' : '提问' }}</button>
        <button
          v-if="streaming"
          class="btn btn-ghost dock-stop" type="button" @click="abort"
        >停止</button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
/* 浮层挂件：避开 64px 顶栏（在底部，天然不冲突），z-index 高于顶栏（50）与进度条 */
.dock{position:fixed; right:22px; bottom:22px; z-index:60; display:flex; flex-direction:column;
  align-items:flex-end; gap:10px}
.dock-bubble{display:flex; align-items:center; gap:8px; cursor:pointer; font:inherit;
  padding:11px 16px; border:1px solid var(--line); border-radius:999px; background:var(--bg-2);
  color:var(--ink); box-shadow:0 10px 30px rgba(0,0,0,.28); transition:border-color .25s var(--ease-soft)}
.dock-bubble:hover{border-color:var(--primary)}
.dock-glyph{color:var(--primary)}
.dock-label{font-size:13px}
.dock-panel{width:390px; max-width:calc(100vw - 44px); display:flex; flex-direction:column;
  border:1px solid var(--line); border-radius:var(--r-lg); background:var(--bg);
  box-shadow:0 18px 48px rgba(0,0,0,.34); overflow:hidden}
.dock-head{display:flex; align-items:center; justify-content:space-between; gap:10px;
  padding:12px 14px; border-bottom:1px solid var(--line)}
.dock-head h3{font-size:14px; margin:0}
.dock-actions{display:flex; gap:6px}
.dock-mini{border:1px solid var(--line); background:transparent; color:var(--ink-faint);
  border-radius:var(--r-sm); font:inherit; font-size:11px; padding:4px 8px; cursor:pointer}
.dock-mini:hover{color:var(--ink); border-color:var(--primary)}
.dock-body{padding:12px 14px; overflow-y:auto; max-height:52vh}
.dock-turn + .dock-turn{margin-top:14px; padding-top:12px; border-top:1px solid var(--line)}
.dock-hint{font-size:12px; color:var(--ink-faint); line-height:1.9; margin:0}
.dock-link{color:var(--primary)}
.dock-err{margin:10px 0 0; font-size:12px; color:var(--rose)}
.dock-foot{display:flex; gap:8px; padding:10px 12px; border-top:1px solid var(--line)}
.dock-foot input{flex:1; min-width:0; background:var(--bg-2); border:1px solid var(--line);
  border-radius:var(--r-sm); color:var(--ink); font:inherit; font-size:13px; padding:8px 10px}
.dock-send,.dock-stop{white-space:nowrap}
@media (max-width: 560px) {
  .dock{right:12px; left:12px; bottom:12px}
  .dock-panel{width:auto}
}
</style>
