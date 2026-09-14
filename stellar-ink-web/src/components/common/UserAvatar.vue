<script setup>
/* 统一头像元件：有图片用图片，没有就回落成「底字」文字头像。
 *
 * 三处使用场景（导航 / 署名 / 账号页 / 星籍页）尺寸与形状不同，但取字与降级规则必须一致，
 * 所以收在一个组件里 —— 否则「未上传头像时显示什么」会在四个地方各写一遍并逐渐跑偏。
 *
 * 降级链路：avatarUrl 图片 → avatarText 底字 → 昵称首字 → 星。
 * 图片加载失败（文件被清理/路径失效）也走同一降级，不会留一个破图。
 */
import { computed, ref, watch } from 'vue'

const props = defineProps({
  /** 头像图片相对路径（如 /uploads/avatars/u1_ab12cd34.png），为空则用底字 */
  url: { type: String, default: '' },
  /** 头像底字 */
  text: { type: String, default: '' },
  /** 昵称（底字与底字都缺时取首字） */
  nickname: { type: String, default: '' },
  /** 直径（px） */
  size: { type: Number, default: 44 },
  /** circle 圆形 / square 圆角方形（账号页「我的星籍」用方形） */
  shape: { type: String, default: 'circle' },
})

const failed = ref(false)
/* 换头像后 url 变化要重新给图片一次机会，否则一次失败会永久卡在底字上 */
watch(() => props.url, () => { failed.value = false })

const showImage = computed(() => !!props.url && !failed.value)

const fallbackChar = computed(() => {
  const candidates = [props.text, props.nickname]
  for (const item of candidates) {
    const value = (item || '').trim()
    if (value) return [...value][0]
  }
  return '星'
})

const style = computed(() => {
  const size = Math.max(12, props.size)
  return {
    width: `${size}px`,
    height: `${size}px`,
    flex: `0 0 ${size}px`,
    fontSize: `${Math.max(9, Math.round(size * 0.45))}px`,
    borderRadius: props.shape === 'square' ? `${Math.round(size * 0.32)}px` : '50%',
  }
})
</script>

<template>
  <span class="user-avatar" :class="{ img: showImage }" :style="style">
    <img v-if="showImage" :src="url" :alt="nickname || '头像'" @error="failed = true" />
    <template v-else>{{ fallbackChar }}</template>
  </span>
</template>

<style scoped>
.user-avatar{display:grid; place-items:center; overflow:hidden;
  border:1px solid var(--line); background:var(--primary-soft); color:var(--primary);
  font-family:var(--font-serif); font-weight:600; line-height:1; user-select:none}
.user-avatar.img{border-color:transparent; background:var(--surface-2)}
.user-avatar img{width:100%; height:100%; object-fit:cover; display:block}
</style>
