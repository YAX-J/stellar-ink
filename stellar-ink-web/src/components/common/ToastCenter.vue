<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { on, TOAST } from '@/utils/bus'

/* 全局提示中心：订阅 bus 的 TOAST 事件，按类型展示并可停留久一点复制 traceId */
const items = ref([])
let seq = 0
let off = null

const DURATION = { success: 2600, info: 3200, warn: 4600, error: 5200 }

function push(detail) {
  const id = ++seq
  items.value.push({
    id,
    type: detail.type || 'error',
    message: detail.message || '操作失败',
    traceId: detail.traceId || '',
  })
  /* 错误带 traceId 时多留一会儿，方便用户抄给站长排障 */
  const ttl = detail.traceId ? DURATION[detail.type] + 2400 : (DURATION[detail.type] ?? 5000)
  setTimeout(() => dismiss(id), ttl)
}

function dismiss(id) {
  const i = items.value.findIndex((item) => item.id === id)
  if (i >= 0) items.value.splice(i, 1)
}

async function copyTrace(text) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    /* 剪贴板不可用时静默，不阻断提示 */
  }
}

onMounted(() => { off = on(TOAST, push) })
onUnmounted(() => off && off())
</script>

<template>
  <div class="toast-center" role="status" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="t in items" :key="t.id" class="toast" :class="t.type" @click="dismiss(t.id)">
        <span class="t-glyph" aria-hidden="true">{{ t.type === 'success' ? '✦' : '!' }}</span>
        <div class="t-body">
          <p class="t-msg">{{ t.message }}</p>
          <p
            v-if="t.traceId" class="t-trace"
            :title="`点击复制 traceId：${t.traceId}`" @click.stop="copyTrace(t.traceId)"
          >trace · {{ t.traceId }}</p>
        </div>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
/* top 让开 sticky 顶栏（64px），否则提示会压在导航上 */
.toast-center{
  position:fixed; top:78px; right:22px; z-index:200; display:flex; flex-direction:column;
  gap:10px; width:min(360px,calc(100vw - 44px)); pointer-events:none;
}
.toast{
  pointer-events:auto; display:flex; gap:12px; align-items:flex-start; cursor:pointer;
  border:1px solid var(--line); border-left-width:3px; border-radius:var(--r-md);
  background:color-mix(in srgb, var(--bg-3) 92%, transparent);
  backdrop-filter:blur(14px); padding:14px 16px;
  box-shadow:0 12px 34px rgba(0,0,0,.28);
}
.toast.error{border-left-color:var(--rose)}
.toast.warn{border-left-color:var(--amber)}
.toast.success{border-left-color:var(--teal)}
.toast.info{border-left-color:var(--primary)}
.t-glyph{
  font-family:var(--font-mono); font-size:13px; line-height:1.6; flex:0 0 auto;
  color:var(--ink-faint);
}
.toast.error .t-glyph{color:var(--rose)}
.toast.warn .t-glyph{color:var(--amber)}
.toast.success .t-glyph{color:var(--teal)}
.t-body{min-width:0}
.t-msg{font-size:13px; line-height:1.7; color:var(--ink); word-break:break-word}
.t-trace{
  margin-top:6px; font-family:var(--font-mono); font-size:10px; letter-spacing:.06em;
  color:var(--ink-faint); cursor:copy; word-break:break-all;
}
.t-trace:hover{color:var(--primary)}

/* 入场：从右侧轻轻浮入，呼应「信号抵达」的调性 */
.toast-enter-active,.toast-leave-active{transition:opacity .28s var(--ease-standard), transform .28s var(--ease-standard)}
.toast-enter-from{opacity:0; transform:translateX(18px)}
.toast-leave-to{opacity:0; transform:translateX(18px)}

@media (max-width:720px){
  .toast-center{top:12px; right:12px; left:12px; width:auto}
}
</style>
