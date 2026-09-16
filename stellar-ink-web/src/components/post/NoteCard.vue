<script setup>
import { computed } from 'vue'
import AuthorBadge from '@/components/common/AuthorBadge.vue'
import { fmt } from '@/utils/format'

const props = defineProps({
  note: { type: Object, required: true },
  showAuthor: { type: Boolean, default: true },
  showState: { type: Boolean, default: false },
})
const emit = defineEmits(['open'])

/** 新鲜度由后端统一判定；天数只用于更具体的展示。 */
const verifyState = computed(() => {
  if (props.note.reviewState === 'UNVERIFIED' || !props.note.verifiedAt) {
    return { text: '未验证', stale: true }
  }
  const days = Math.floor((Date.now() - new Date(props.note.verifiedAt).getTime()) / 86400000)
  if (props.note.reviewState === 'EXPIRED') {
    return { text: `结论待复核 · ${days} 天前`, stale: true }
  }
  return { text: `已验证 · ${Math.max(0, days)} 天前`, stale: false }
})
</script>

<template>
  <article class="note-card" @click="emit('open', props.note)">
    <div class="nc-top">
      <span class="nc-type" :title="props.note.noteTypeLabel">
        {{ props.note.noteTypeGlyph }} {{ props.note.noteTypeLabel }}
      </span>
      <span v-if="props.showState" class="nc-state" :class="{ pub: props.note.visibility === 'PUBLIC' }">
        {{ props.note.visibility === 'PUBLIC' ? '公开' : '私有' }}
        <template v-if="props.note.status === 0"> · 草稿</template>
      </span>
    </div>

    <h4>{{ props.note.title }}</h4>
    <p class="nc-ex">{{ props.note.excerpt || '这条笔记还没有留下结论。' }}</p>

    <div class="nc-tags">
      <span v-for="t in props.note.tags" :key="t" class="tchip">{{ t }}</span>
    </div>

    <div class="nc-foot">
      <AuthorBadge v-if="props.showAuthor" :user-id="props.note.userId" compact />
      <span class="nc-date">{{ props.note.date }}</span>
      <span class="nc-verify" :class="{ stale: verifyState.stale }">{{ verifyState.text }}</span>
      <span v-if="props.note.viewCount > 0" class="nc-view">◉ {{ fmt(props.note.viewCount) }}</span>
    </div>
  </article>
</template>

<style scoped>
.note-card{
  border:1px solid var(--line); border-radius:var(--r-md); background:var(--surface);
  padding:20px 22px; cursor:pointer; transition:all .3s var(--ease-spring);
  display:flex; flex-direction:column; gap:10px; min-width:0;
}
.note-card:hover{transform:translateY(-4px); border-color:var(--teal)}
.nc-top{display:flex; align-items:center; justify-content:space-between; gap:10px}
.nc-type{font-family:var(--font-mono); font-size:10px; letter-spacing:.16em; color:var(--teal)}
.nc-state{font-family:var(--font-mono); font-size:10px; letter-spacing:.1em; color:var(--ink-faint);
  border:1px dashed var(--line); border-radius:99px; padding:2px 9px}
.nc-state.pub{color:var(--primary); border-color:var(--primary); border-style:solid}
.note-card h4{font-family:var(--font-serif); font-weight:600; font-size:17px; line-height:1.55;
  overflow:hidden; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical}
.nc-ex{font-size:13px; line-height:1.9; color:var(--ink-dim);
  display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden}
.nc-tags{display:flex; gap:6px; flex-wrap:wrap}
.tchip{font-family:var(--font-mono); font-size:10px; padding:3px 9px; border-radius:99px;
  background:var(--primary-soft); color:var(--primary); letter-spacing:.04em}
.nc-foot{display:flex; align-items:center; gap:12px; flex-wrap:wrap; margin-top:2px;
  font-family:var(--font-mono); font-size:10px; color:var(--ink-faint)}
.nc-verify{color:var(--ink-faint)}
.nc-verify.stale{color:var(--amber)}
.nc-view{margin-left:auto}
</style>
