<template>
  <section class="page">
    <p v-if="store.error && !series" class="state-text error-text">
      {{ store.error }}
      <button class="state-action" @click="load(route.params.id)">重试</button>
    </p>
    <p v-else-if="loading && !series" class="state-text">正在读取系列…</p>

    <template v-else-if="series">
      <div class="title-row">
        <h1>{{ series.title }}</h1>
        <span class="kicker">SERIES · 共 {{ series.total }} 篇</span>
      </div>
      <p v-if="series.description" class="series-intro">{{ series.description }}</p>

      <div class="series-head">
        <span class="mono">{{ postTotal }} 篇文章 · {{ noteTotal }} 条笔记</span>
        <button v-if="isOwner" class="btn btn-ghost danger" :disabled="removing" @click="removeSeries">
          {{ confirmRemove ? '再点一次确认删除' : '🗑 删除系列' }}
        </button>
      </div>

      <ol class="item-list">
        <li v-for="(item, index) in series.items" :key="`${item.kind}-${item.id}`">
          <button class="item" @click="open(item)">
            <span class="item-no mono">{{ String(index + 1).padStart(2, '0') }}</span>
            <span class="item-main">
              <span class="item-title">{{ item.title }}</span>
              <span class="item-meta mono">
                {{ item.kind === 'note' ? '技术笔记' : '文章' }}
                · {{ item.date }}
                <template v-if="item.order"> · 序号 {{ item.order }}</template>
              </span>
            </span>
            <span class="item-words mono">{{ item.wordCount }} 字</span>
          </button>
        </li>
      </ol>
      <p v-if="!series.items.length" class="empty-state">这个系列还没有公开篇目。</p>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSeriesStore } from '@/stores/series'
import { useAuthStore } from '@/stores/auth'
import { emit, TOAST } from '@/utils/bus'
import { usePageMeta } from '@/composables/usePageMeta'

const route = useRoute()
const router = useRouter()
const store = useSeriesStore()
const auth = useAuthStore()
const loading = ref(false)
const removing = ref(false)
const confirmRemove = ref(false)

const series = computed(() => store.byId(route.params.id))
const isOwner = computed(() => auth.isLoggedIn && Number(auth.user?.id) === Number(series.value?.userId))
/* 详情接口只回篇目，不回分类计数：这里直接从 items 数，避免两处口径不一致 */
const postTotal = computed(() => (series.value?.items || []).filter((item) => item.kind === 'post').length)
const noteTotal = computed(() => (series.value?.items || []).filter((item) => item.kind === 'note').length)

usePageMeta(() => ({
  title: series.value?.title || '系列',
  description: series.value?.description || '按顺序读完一条线的文章与笔记。',
  type: 'website',
  url: `${location.origin}/series/${route.params.id}`,
}))

async function load(id) {
  loading.value = true
  try {
    await store.fetchDetail(id)
  } catch {
    /* 错误在页面上显示 */
  } finally {
    loading.value = false
  }
}

function open(item) {
  router.push(item.kind === 'note' ? `/note/${item.id}` : `/read/${item.id}`)
}

async function removeSeries() {
  if (!confirmRemove.value) {
    confirmRemove.value = true
    return
  }
  removing.value = true
  try {
    await store.remove(series.value.id)
    emit(TOAST, { type: 'success', message: '系列已删除，篇目保留（只是不再归入系列）' })
    router.push('/series')
  } catch {
    /* 全局 toast 已提示 */
  } finally {
    removing.value = false
  }
}

onMounted(() => load(route.params.id))
</script>

<style scoped>
.series-intro{color:var(--ink-dim); font-size:14px; line-height:1.9; margin:10px 0 0; max-width:60ch}
.series-head{display:flex; align-items:center; justify-content:space-between; gap:16px;
  flex-wrap:wrap; margin:22px 0 10px; color:var(--ink-faint); font-size:11px; letter-spacing:.08em}
.btn.danger{color:var(--rose); border-color:var(--line)}
.item-list{list-style:none; margin:0; padding:0; border-top:1px solid var(--line)}
.item{width:100%; display:flex; align-items:center; gap:16px; padding:16px 6px; border:0;
  border-bottom:1px solid var(--line); background:transparent; color:var(--ink); cursor:pointer;
  text-align:left; transition:background .2s var(--ease-standard)}
.item:hover{background:var(--surface)}
.item-no{color:var(--ink-faint); font-size:12px; width:2.2em; flex-shrink:0}
.item-main{display:flex; flex-direction:column; gap:4px; flex:1; min-width:0}
.item-title{font-family:var(--font-serif); font-weight:700; font-size:16px; line-height:1.5}
.item-meta{color:var(--ink-faint); font-size:10px; letter-spacing:.08em}
.item-words{color:var(--ink-faint); font-size:11px; flex-shrink:0}
.empty-state{margin-top:26px; padding:40px 20px; text-align:center; color:var(--ink-faint);
  font-size:13px; border-bottom:1px dashed var(--line)}
.state-text{margin-top:26px; color:var(--ink-faint); font-size:13px; line-height:1.9}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.error-text{color:var(--rose)}
</style>
