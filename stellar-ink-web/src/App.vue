<script setup>
import { computed, watch, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import Starfield from '@/components/canvas/Starfield.vue'
import TopNav from '@/components/common/TopNav.vue'
import ToastCenter from '@/components/common/ToastCenter.vue'

const route = useRoute()
const settings = useSettingsStore()
const isAuthPage = computed(() => route.meta.layout === 'auth')

/* 主题落在 <html> 与 <body> 两处：index.html 的内联脚本为了消除首屏闪烁先写 <html>，
 * 这里保持一致，CSS 变量选择器同时匹配两者。 */
watchEffect(() => {
  document.documentElement.dataset.theme = settings.theme
  document.body.dataset.theme = settings.theme
})

/* 记录最后一个列表/功能页；文章深读与笔记详情都不覆盖导航来源。 */
watch(
  () => route.fullPath,
  () => {
    if (route.name !== 'read' && route.name !== 'note') {
      settings.rememberPage(route.name, route.fullPath)
    }
  },
  { immediate: true },
)
</script>

<template>
  <!-- 项目组件保持单根节点；包裹层不参与布局。 -->
  <div class="app-root">
    <Starfield />
    <!-- 初始路由解析前先不渲染导航，避免登录/注册页短暂闪现导航栏。 -->
    <TopNav v-if="route.name && !isAuthPage" />
    <main class="main">
      <RouterView />
    </main>
    <ToastCenter />
  </div>
</template>

<style scoped>
/* 包裹层只做分组，不设置会影响 fixed 后代定位的属性。 */
.app-root{display:block}
</style>
