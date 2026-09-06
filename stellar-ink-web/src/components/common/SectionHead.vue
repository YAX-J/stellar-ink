<script setup>
defineProps({
  title: { type: String, required: true },
  more: { type: String, default: '' },
})
const emit = defineEmits(['more'])
</script>

<template>
  <div class="section-head reveal">
    <h2>{{ title }}</h2>
    <span class="more" @click="emit('more')">
      <slot>{{ more }}</slot>
    </span>
  </div>
</template>
