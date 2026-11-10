<script setup>
import { computed, watch, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import Starfield from '@/components/canvas/Starfield.vue'
import TopNav from '@/components/common/TopNav.vue'
import ToastCenter from '@/components/common/ToastCenter.vue'
import AssistantEntry from '@/components/assistant/AssistantEntry.vue'
import { navigation } from '@/stores/navigation'
import router from '@/router'
import { finishPublicPage } from '@/utils/public-page'

const route = useRoute()
const settings = useSettingsStore()
function retryNavigation() {
  router.replace(navigation.path).catch(() => {})
}
function reloadNavigation() {
  window.location.assign(navigation.path)
}
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
      <section v-if="navigation.error" class="page" role="alert">
        <div class="title-row"><h1>页面暂未加载</h1><span class="kicker">星轨暂时中断</span></div>
        <p class="navigation-message">连接可能暂时中断，或页面版本已经更新。请重试；仍无法打开时可刷新页面。</p>
        <div class="navigation-actions"><button class="btn btn-primary" @click="retryNavigation">重新加载页面</button><button class="btn btn-ghost" @click="reloadNavigation">刷新打开</button></div>
      </section>
      <div v-show="!navigation.error"><RouterView v-slot="{ Component }"><component :is="Component" :onVnodeMounted="finishPublicPage" /></RouterView></div>
    </main>
    <ToastCenter />
    <!-- 星笺助手（右下角浮层）：不占一级导航，登录/注册页不出现 -->
    <AssistantEntry v-if="route.name && !isAuthPage" />
  </div>
</template>

<style scoped>
/* 包裹层只做分组，不设置会影响 fixed 后代定位的属性。 */
.app-root{display:block}
.navigation-message{margin:24px 0; color:var(--ink-dim); line-height:1.9}
.navigation-actions{display:flex; flex-wrap:wrap; gap:12px}
</style>
