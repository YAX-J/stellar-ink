<script setup>
import { watch, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import Starfield from '@/components/canvas/Starfield.vue'
import RailNav from '@/components/common/RailNav.vue'

const route = useRoute()
const settings = useSettingsStore()

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
  <Starfield />
  <RailNav />
  <main class="main">
    <RouterView />
  </main>
</template>
