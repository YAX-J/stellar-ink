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
  <!-- 项目组件保持单根节点；包裹层不参与布局。 -->
  <div class="app-root">
    <Starfield />
    <!-- 初始路由解析前先不渲染导航，避免登录/注册页短暂闪现导航栏。 -->
    <RailNav v-if="route.name && !isAuthPage" />
    <main class="main" :class="{ 'auth-main': isAuthPage }">
      <RouterView />
    </main>
    <ToastCenter />
  </div>
</template>

<style scoped>
.main.auth-main{margin-left:0; margin-bottom:0}
/* 包裹层只做分组，不设置会影响 fixed 后代定位的属性。 */
.app-root{display:block}
</style>
