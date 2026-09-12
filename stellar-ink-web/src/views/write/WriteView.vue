<script setup>
import { computed, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { usePostStore } from '@/stores/posts'
import { PROMPTS } from '@/api/mock'

const router = useRouter()
const postStore = usePostStore()

const moods = [
  { label: '☀️ 专注', color: 'rgba(139,124,255,.22)', tag: '随笔' },
  { label: '🌧️ 忧郁', color: 'rgba(77,201,217,.20)', tag: '沉思' },
  { label: '🌊 流动', color: 'rgba(255,180,84,.20)', tag: '速写' },
  { label: '🔥 燃烧', color: 'rgba(255,107,157,.22)', tag: '生活' },
]
const activeMood = ref(moods[0])
const title = ref('')
const body = ref('')
const savedAt = ref(nowTime())
const prompt = ref(PROMPTS[0])
const promptFading = ref(false)
const focused = ref(false)
const publishing = ref(false)
const drafts = [
  { t: '雨夜出租车观察笔记', d: '2h 前' },
  { t: '论“已读不回”的礼仪', d: '昨天' },
  { t: '写给三年后的自己', d: '3 天前' },
]

const wordCount = computed(() => body.value.replace(/\s/g, '').length)
const readTime = computed(() => Math.ceil(wordCount.value / 400))

function nowTime() {
  return new Date().toTimeString().slice(0, 8)
}
function onInput() {
  savedAt.value = nowTime()
}
function refreshPrompt() {
  promptFading.value = true
  setTimeout(() => {
    prompt.value = PROMPTS[Math.floor(Math.random() * PROMPTS.length)]
    promptFading.value = false
  }, 300)
}
function toggleFocus() {
  focused.value = !focused.value
  document.body.classList.toggle('focus-mode', focused.value)
}
async function launch() {
  if (!body.value.trim()) return
  publishing.value = true
  try {
    await postStore.publish({ title: title.value, body: body.value, tag: activeMood.value.tag })
    router.push('/archive')
  } finally {
    publishing.value = false
  }
}

onUnmounted(() => {
  document.body.classList.remove('focus-mode')
})
</script>

<template>
  <section class="page">
    <div class="kicker reveal">WRITING STUDIO · 留白写作舱</div>
    <div class="studio">
      <div class="studio-desk reveal" style="--d:.1s" :style="{ '--mood': activeMood.color }">
        <div class="mood-row">
          <button
            v-for="m in moods" :key="m.label"
            class="mood" :class="{ on: activeMood === m }" @click="activeMood = m"
          >{{ m.label }}</button>
        </div>
        <input v-model="title" class="title-input" placeholder="给今晚的思绪起个名字…">
        <div class="quill-line"></div>
        <textarea
          v-model="body" class="body-input" @input="onInput"
          placeholder="从这里开始。不追求完美，只追求诚实。&#10;&#10;提示：情绪会改变舱内的光，专注模式会熄灭整个世界。"
        ></textarea>
        <div class="desk-foot">
          <span class="save-dot"><i></i>已自动保存 · {{ savedAt }}</span>
          <span>星尘 <b style="color:var(--amber)">+{{ wordCount }}</b> 字</span>
          <span>约 {{ readTime }} 分钟读完</span>
          <button class="btn btn-primary grow" style="height:40px" :disabled="publishing" @click="launch">
            {{ publishing ? '正在发射…' : '🚀 发射到星图' }}
          </button>
        </div>
        <p v-if="postStore.error" class="publish-error">{{ postStore.error }}</p>
      </div>
      <aside class="studio-side">
        <button class="focus-toggle" @click="toggleFocus">
          {{ focused ? '☀️ 退出专注模式' : '🌑 进入专注模式' }}
        </button>
        <div class="side-card reveal" style="--d:.18s">
          <h5>灵感签</h5>
          <div class="prompt-card" :style="{ opacity: promptFading ? 0 : 1 }">{{ prompt }}</div>
          <button class="prompt-refresh" @click="refreshPrompt">↻ 换一签</button>
        </div>
        <div class="side-card reveal" style="--d:.26s">
          <h5>未完的星 · 草稿</h5>
          <div v-for="d in drafts" :key="d.t" class="draft">
            <span>{{ d.t }}</span><small>{{ d.d }}</small>
          </div>
        </div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.studio{display:grid; grid-template-columns:1fr 300px; gap:28px}
.studio-desk{
  border:1px solid var(--line); border-radius:var(--r-lg);
  background:linear-gradient(180deg,var(--bg-2),var(--bg));
  padding:clamp(26px,3.5vw,48px); position:relative; overflow:hidden; min-height:70vh;
  display:flex; flex-direction:column;
}
.studio-desk::before{
  content:''; position:absolute; inset:0; pointer-events:none; opacity:.6;
  background:radial-gradient(600px 300px at 85% -10%,var(--mood,var(--primary-soft)),transparent 70%);
}
.mood-row{display:flex; gap:10px; margin-bottom:26px; flex-wrap:wrap; position:relative}
.mood{
  border:1px solid var(--line); background:var(--surface); border-radius:99px;
  padding:8px 16px; font-size:13px; color:var(--ink-dim); cursor:pointer;
  transition:all .25s var(--ease-spring); font-family:var(--font-body);
}
.mood:hover{transform:translateY(-2px); color:var(--ink)}
.mood.on{background:var(--primary-soft); border-color:var(--primary); color:var(--primary)}
.title-input{
  width:100%; border:none; background:transparent; outline:none; color:var(--ink);
  font-family:var(--font-serif); font-weight:900; font-size:clamp(28px,3.4vw,44px);
  line-height:1.35; margin-bottom:8px; position:relative;
}
.title-input::placeholder{color:var(--ink-faint)}
.quill-line{width:64px; height:3px; border-radius:2px; margin-bottom:28px;
  background:linear-gradient(90deg,var(--primary),var(--rose))}
.body-input{
  flex:1; width:100%; border:none; background:transparent; outline:none; resize:none;
  color:var(--ink); font-family:var(--font-body); font-weight:300; font-size:17px;
  line-height:2.2; min-height:340px; position:relative;
}
.body-input::placeholder{color:var(--ink-faint)}
.desk-foot{
  position:relative; margin-top:26px; padding-top:18px; border-top:1px dashed var(--line);
  display:flex; align-items:center; gap:22px; flex-wrap:wrap;
  font-family:var(--font-mono); font-size:12px; color:var(--ink-faint);
}
.save-dot{display:inline-flex; align-items:center; gap:8px}
.save-dot i{width:8px; height:8px; border-radius:50%; background:var(--teal);
  animation:pulse 2.2s infinite; box-shadow:0 0 8px var(--teal)}
.desk-foot .grow{margin-left:auto}
.desk-foot .grow:disabled{opacity:.6; cursor:wait}
.publish-error{position:relative; margin-top:12px; color:var(--rose); font-size:12px}
.studio-side{display:flex; flex-direction:column; gap:18px; transition:opacity .4s, filter .4s}
/* 专注模式只压暗侧卡；reveal 动画的 fill 会钉住 opacity，需一并关闭动画 */
body.focus-mode .studio-side{pointer-events:none}
body.focus-mode .studio-side .side-card{opacity:.12; animation:none}
.prompt-card{
  border-radius:var(--r-sm); padding:16px; background:var(--bg-3);
  border-left:3px solid var(--amber); font-family:var(--font-serif); font-size:14px;
  line-height:1.9; color:var(--ink-dim); min-height:76px; transition:all .4s;
}
.prompt-refresh{margin-top:12px; width:100%; height:38px; border-radius:10px; cursor:pointer;
  border:1px solid var(--line); background:transparent; color:var(--ink-dim); font-size:12px;
  letter-spacing:.15em; transition:all .25s}
.prompt-refresh:hover{border-color:var(--amber); color:var(--amber)}
.draft{display:flex; justify-content:space-between; align-items:center; padding:11px 4px;
  border-bottom:1px dashed var(--line); font-size:13px; color:var(--ink-dim); cursor:pointer;
  transition:color .2s}
.draft:last-child{border-bottom:none}
.draft:hover{color:var(--ink)}
.draft small{font-family:var(--font-mono); font-size:10px; color:var(--ink-faint)}
.focus-toggle{
  width:100%; height:52px; border-radius:var(--r-md); cursor:pointer; border:1px solid var(--line);
  background:var(--surface); color:var(--ink-dim); font-size:14px; letter-spacing:.1em;
  display:flex; align-items:center; justify-content:center; gap:10px;
  transition:all .3s var(--ease-spring); font-family:var(--font-body);
}
.focus-toggle:hover{border-color:var(--primary); color:var(--primary)}
body.focus-mode .focus-toggle{background:var(--primary); color:#fff; border-color:var(--primary);
  position:fixed; right:28px; bottom:28px; width:auto; padding:0 24px; z-index:60;
  box-shadow:0 10px 32px var(--primary-soft); pointer-events:auto}

@media (max-width:980px){
  .studio{grid-template-columns:1fr}
}
</style>
