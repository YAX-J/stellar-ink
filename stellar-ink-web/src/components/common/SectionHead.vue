<script setup>
defineProps({
  title: { type: String, required: true },
  /* 页面/区块标签（如 TECH NOTES · 程序员的标本册）：跟在标题后面，不再另起一行压在标题上方 */
  kicker: { type: String, default: '' },
  more: { type: String, default: '' },
})
const emit = defineEmits(['more'])
</script>

<template>
  <div class="section-head reveal">
    <div class="title-row">
      <h2>{{ title }}</h2>
      <span v-if="kicker" class="kicker">{{ kicker }}</span>
    </div>
    <span class="more" @click="emit('more')">
      <slot>{{ more }}</slot>
    </span>
  </div>
</template>
