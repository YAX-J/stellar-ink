<script setup>
import { computed, watch, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import Starfield from '@/components/canvas/Starfield.vue'
import RailNav from '@/components/common/RailNav.vue'

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
  <Starfield />
  <RailNav v-if="!isAuthPage" />
  <main class="main" :class="{ 'auth-main': isAuthPage }">
    <RouterView />
  </main>
</template>

<style scoped>
.main.auth-main{margin-left:0; margin-bottom:0}
</style>
