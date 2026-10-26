<template>
  <section class="page">
    <SectionHead
      title="系列" kicker="SERIES · 按顺序读完一条线"
      :more="canWrite ? '把零散的篇目串成一条阅读线' : '文章与笔记，按系列读'"
    />

    <!-- 新建：只有作者能看到。系列本身很小（标题 + 一句话），不值得单开管理页 -->
    <form v-if="canWrite" class="series-create reveal" style="--d:.06s" @submit.prevent="create">
      <input v-model="title" class="field" maxlength="100" placeholder="系列标题，例如「Redis 超时排查」">
      <input v-model="description" class="field grow" maxlength="300" placeholder="一句话说清读它能得到什么（可选）">
      <button class="btn btn-primary" type="submit" :disabled="creating">
        {{ creating ? '正在创建…' : '＋ 新建系列' }}
      </button>
    </form>

    <p v-if="store.loading && !store.list.length" class="state-text">正在读取系列…</p>
    <p v-else-if="store.error && !store.list.length" class="state-text error-text">
      {{ store.error }}
      <button class="state-action" @click="store.fetchList({ force: true })">重试</button>
    </p>
    <p v-else-if="!store.visibleList.length" class="empty-state">
      还没有公开的系列。把几篇文章或技术笔记挂到同一个系列里，它们就会出现在这里。
    </p>

    <div v-else class="series-grid">
      <RouterLink
        v-for="(item, index) in store.visibleList" :key="item.id"
        class="series-card reveal" :style="{ '--d': `${Math.min(index * .04, .3)}s` }"
        :to="`/series/${item.id}`"
      >
        <h3>{{ item.title }}</h3>
        <p v-if="item.description" class="series-desc">{{ item.description }}</p>
        <p class="series-meta">
          <span class="mono">{{ item.itemCount }} 篇</span>
          <span v-if="item.postCount" class="mono">文章 {{ item.postCount }}</span>
          <span v-if="item.noteCount" class="mono">笔记 {{ item.noteCount }}</span>
        </p>
      </RouterLink>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSeriesStore } from '@/stores/series'
import { useAuthStore } from '@/stores/auth'
import { emit, TOAST } from '@/utils/bus'
import { usePageMeta } from '@/composables/usePageMeta'
import SectionHead from '@/components/common/SectionHead.vue'

const router = useRouter()
const store = useSeriesStore()
const auth = useAuthStore()
const title = ref('')
const description = ref('')
const creating = ref(false)
const canWrite = computed(() => auth.isLoggedIn && auth.isAuthorOrAbove)

usePageMeta({
  title: '系列',
  description: '按顺序读完一条线：文章与技术笔记组成的系列目录。',
  url: `${location.origin}/series`,
})

onMounted(() => { store.fetchList().catch(() => {}) })

async function create() {
  if (!title.value.trim()) {
    emit(TOAST, { type: 'warn', message: '系列得有个名字' })
    return
  }
  creating.value = true
  try {
    const id = await store.create({ title: title.value, description: description.value })
    title.value = ''
    description.value = ''
    emit(TOAST, { type: 'success', message: '系列建好了：去编辑文章或笔记时选择它' })
    if (id) router.push(`/series/${id}`)
  } catch {
    /* 失败提示由全局 toast 承担 */
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.series-create{display:flex; gap:10px; align-items:center; flex-wrap:wrap; margin-bottom:26px}
.series-create .field{height:44px; flex:1 1 240px; min-width:200px}
.series-create .grow{flex:2 1 320px}
.series-create .btn{height:44px; flex-shrink:0}
.series-grid{display:grid; grid-template-columns:repeat(auto-fill,minmax(280px,1fr)); gap:16px}
.series-card{border:1px solid var(--line); border-radius:var(--r-md); background:var(--surface);
  padding:20px 22px; display:flex; flex-direction:column; gap:10px; color:var(--ink);
  transition:border-color .2s var(--ease-standard), transform .2s var(--ease-standard)}
.series-card:hover{border-color:var(--primary); transform:translateY(-2px)}
.series-card h3{font-family:var(--font-serif); font-weight:900; font-size:19px; line-height:1.4}
.series-desc{color:var(--ink-dim); font-size:13px; line-height:1.8; flex:1}
.series-meta{display:flex; gap:14px; color:var(--ink-faint); font-size:11px; letter-spacing:.08em}
.state-text,.empty-state{margin-top:26px; color:var(--ink-faint); font-size:13px; line-height:1.9}
.empty-state{padding:44px 20px; text-align:center; border-top:1px dashed var(--line); border-bottom:1px dashed var(--line)}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.error-text{color:var(--rose)}
</style>
