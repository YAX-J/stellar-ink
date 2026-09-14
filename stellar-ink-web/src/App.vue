<script setup>
import { computed, watch, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import Starfield from '@/components/canvas/Starfield.vue'
import RailNav from '@/components/common/RailNav.vue'
import ToastCenter from '@/components/common/ToastCenter.vue'

const route = useRoute()
const settings = useSettingsStore()
const isAuthPage = computed(() => route.meta.layout === 'auth')

watchEffect(() => {
  document.body.dataset.theme = settings.theme
})

/* 记录最后一个非深读页，供深读页「返回星域」使用 */
watch(
  () => route.fullPath,
  () => {
    if (route.name !== 'read') settings.rememberPage(route.name, route.fullPath)
  },
  { immediate: true },
)
</script>

<template>
  <!-- ⚠️ 必须保持单根节点：main.js 用的是 app.mount('#app')，
       Vue 3 对「多根组件挂载到容器」只在开发模式报错，
       生产构建会把该错误静默吞掉 —— 现象就是整页黑屏、资源全部 200、控制台无异常。 -->
  <div class="app-root">
    <Starfield />
    <RailNav v-if="!isAuthPage" />
    <main class="main" :class="{ 'auth-main': isAuthPage }">
      <RouterView />
    </main>
    <ToastCenter />
  </div>
</template>

<style scoped>
.main.auth-main{margin-left:0; margin-bottom:0}
/* 包裹层只做分组，不参与布局：不设 display 以外的任何盒模型属性，
   避免改变 fixed 定位的 Starfield / RailNav 与 main 的既有布局 */
.app-root{display:block}
</style>
