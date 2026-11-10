<script setup>
import { ref, shallowRef } from 'vue'

const panel = shallowRef(null)
const loading = ref(false)
const failed = ref(false)

async function open() {
  if (loading.value) return
  loading.value = true
  failed.value = false
  try {
    panel.value = (await import('./AssistantDock.vue')).default
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}
function reload() { window.location.reload() }
</script>

<template>
  <div>
    <component :is="panel" v-if="panel" :initial-open="true" />
    <div v-else class="assistant-entry">
      <div v-if="failed" class="assistant-error" role="alert">助手暂未加载，点击重试；仍失败可<button type="button" class="state-action" @click="reload">刷新页面</button></div>
      <button class="assistant-bubble" type="button" :disabled="loading" :aria-busy="loading"
        :aria-label="loading ? '正在加载星笺助手' : failed ? '重试加载星笺助手' : '打开星笺助手'"
        title="星笺助手" @click="open"><span aria-hidden="true">{{ loading ? '…' : '✦' }}</span></button>
    </div>
  </div>
</template>

<style scoped>
.assistant-entry{position:fixed; right:22px; bottom:22px; z-index:70; display:flex; flex-direction:column; align-items:flex-end; gap:10px}
.assistant-error{color:var(--rose); background:var(--bg-2); border:1px solid var(--line); border-radius:var(--r-sm); padding:10px; font-size:12px}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
@media(max-width:560px){.assistant-entry{left:10px; right:10px; bottom:10px}}
</style>
